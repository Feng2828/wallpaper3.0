package com.hyperos.albumwallpaper;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 动态壁纸服务。
 *
 * 切换触发点：
 *   a) 定时（30 / 60 / 120 秒）—— 按「挂钟绝对时间」推进，与是否停留在桌面无关
 *   b) 悬浮球点击  → AlbumWallpaperService.requestSwitch()
 *   c) 息屏后重新亮屏解锁（ACTION_USER_PRESENT，SCREEN_ON 兜底）
 *
 * 计时模型（这是「离开桌面也照样换图」的关键）：
 *   - 下一次切换时刻 nextSwitchAt 用 System.currentTimeMillis() 表示，并写进 SharedPreferences；
 *   - 计时器不受 onVisibilityChanged 影响，不可见时只停止绘制，到点照常解码并换掉当前图；
 *   - 用 15 秒心跳核对挂钟，避免 postDelayed（基于 uptimeMillis）在深睡后被拖长；
 *   - 进程被冻结 / 杀掉 / 重启时，回桌面、亮屏解锁、引擎重建、悬浮球轮询都会做一次补切，
 *     以持久化的时间戳为准，不依赖进程一直活着。
 *
 * 后台绝不扫描相册：本服务只读取 AlbumRepository 已经写好的池文件。
 */
public class AlbumWallpaperService extends WallpaperService {

    /** 当前存活的引擎。同一进程内静态引用，避免自定义广播在 Android 14+ 的注册限制 */
    private static volatile AlbumEngine sEngine;

    @Override
    public Engine onCreateEngine() {
        return new AlbumEngine();
    }

    /** 供悬浮球服务调用：换一张 */
    public static void requestSwitch() {
        AlbumEngine e = sEngine;
        if (e != null) e.requestSwitch(true);
        // 引擎未就绪（壁纸未启用）时没有画面可切，直接忽略
    }

    /** 供设置页调用：切换间隔改了，重置计时 */
    public static void reloadConfig() {
        AlbumEngine e = sEngine;
        if (e != null) e.reloadConfig();
    }

    /** 供设置页调用：手动同步后，若当前图已被移除则立即换一张 */
    public static void onPoolChanged() {
        AlbumEngine e = sEngine;
        if (e != null) e.onPoolChanged();
    }

    /**
     * 供悬浮球前台服务轮询调用：用挂钟时间核对一次是否该切换。
     * 前台服务比壁纸线程更难被 HyperOS 冻结，等于给计时多上一道保险。
     */
    public static void tickClock() {
        AlbumEngine e = sEngine;
        if (e != null) e.tickClock();
    }

    // ==================================================================
    private final class AlbumEngine extends Engine {

        private HandlerThread renderThread, loadThread;
        private Handler renderHandler, loadHandler;

        /** 已裁剪成 surface 大小的位图，最多同时存在 2 张（只在 render 线程访问） */
        private Bitmap current, incoming;
        private volatile String currentUri;
        private float fade;
        private boolean fading;

        private int w = 0, h = 0;
        private volatile boolean destroyed = false;
        /** 只影响「画不画」，绝不影响「计不计时」 */
        private volatile boolean visible = true;
        private boolean screenWasOff = false;
        private boolean receiverRegistered = false;

        /** 下一次自动切换的挂钟时刻，进程内缓存；同时持久化在 Prefs */
        private volatile long nextSwitchAt = 0L;

        private final Random random = new Random();
        private final AtomicBoolean switching = new AtomicBoolean(false);
        private volatile boolean queuedSwitch = false;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /**
         * 时钟心跳：只认挂钟时间，不看 visible。
         * 到点就切一张（后台也切，只是不绘制），然后重新排下一次。
         */
        private final Runnable clockTask = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                // 切了图的话 switchByClock 内部已经重排过，没切就按剩余时间排下一次心跳
                if (!catchUp(visible)) scheduleNext();
            }
        };

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i == null ? null : i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    screenWasOff = true;
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    // 解锁完成：息屏过才切，避免普通亮屏误触发
                    if (screenWasOff) {
                        screenWasOff = false;
                        requestSwitch(true);
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    // 某些 ROM 不发 USER_PRESENT，1.8s 后兜底
                    if (loadHandler != null) {
                        loadHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (screenWasOff) {
                                    screenWasOff = false;
                                    requestSwitch(true);
                                }
                            }
                        }, 1800);
                    }
                }
            }
        };

        // --------------------------------------------------------------
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            renderThread = new HandlerThread("aw-render");
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());

            loadThread = new HandlerThread("aw-load");
            loadThread.start();
            loadHandler = new Handler(loadThread.getLooper());

            // 初始尺寸用真实屏幕尺寸；onSurfaceChanged 会再校准
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
            } catch (Throwable e) {
                w = 1080; h = 2400;
            }

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(34f);
            textPaint.setTextAlign(Paint.Align.CENTER);

            registerScreenReceiver();
            restoreClock();   // 先恢复挂钟基准，再把引擎暴露给其它组件
            sEngine = this;

            loadHandler.post(new Runnable() {
                @Override public void run() {
                    performSwitch(false);   // 首张直接显示，不做淡化
                    scheduleNext();
                }
            });
        }

        private void registerScreenReceiver() {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    getApplicationContext().registerReceiver(
                            screenReceiver, f, null, null, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getApplicationContext().registerReceiver(screenReceiver, f);
                }
                receiverRegistered = true;
            } catch (Throwable ignored) { }
        }

        // --------------------------------------------------------------
        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            renderHandler.post(new Runnable() {
                @Override public void run() { drawFrame(); }
            });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (width <= 0 || height <= 0) return;
            final int nw = width, nh = height;
            if (nw == w && nh == h) return;
            w = nw; h = nh;
            // 尺寸变化一律重建画面。若首张正在加载，performSwitch 内部的
            // switching 保护会把这次请求排队，等首张完成后按新尺寸再生成一次。
            if (loadHandler == null) return;
            loadHandler.post(new Runnable() {
                @Override public void run() { rebuildForNewSize(); }
            });
        }

        /** 尺寸变化（旋转、分屏、桌面切换）后，按新尺寸重新生成当前画面 */
        private void rebuildForNewSize() {
            if (destroyed) return;
            if (currentUri == null) { performSwitch(false); return; }
            final Bitmap b = BitmapLoader.decodeCover(
                    getApplicationContext(), currentUri, w, h);
            renderHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) { if (b != null) b.recycle(); return; }
                    if (b != null) {
                        recycle(current);
                        current = b;
                    }
                    recycle(incoming);
                    incoming = null;
                    fading = false;
                    drawFrame();
                }
            });
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            super.onVisibilityChanged(isVisible);
            visible = isVisible;

            // 这里只控制「画不画」，绝不取消或重排计时器：
            // 离开桌面（打开微信等）期间，到点照样解码并换掉当前图。
            if (!isVisible) return;

            renderHandler.post(new Runnable() {
                @Override public void run() { drawFrame(); }
            });
            loadHandler.post(new Runnable() {
                @Override public void run() {
                    // 兜底：万一后台进程被 HyperOS 冻结过，挂钟早就过点，
                    // 这里按真实时间补切一张，回到桌面立刻能看到新图（带渐显）。
                    if (!catchUp(true)) scheduleNext();
                }
            });
        }

        @Override
        public void onDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            super.onDesiredSizeChanged(desiredWidth, desiredHeight);
            // 不使用视差，尺寸以 onSurfaceChanged 为准
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            if (loadHandler != null) loadHandler.removeCallbacksAndMessages(null);
            if (renderHandler != null) renderHandler.removeCallbacksAndMessages(null);
            if (receiverRegistered) {
                try { getApplicationContext().unregisterReceiver(screenReceiver); }
                catch (Throwable ignored) { }
            }
            if (sEngine == this) sEngine = null;
            recycle(current); current = null;
            recycle(incoming); incoming = null;
            if (loadThread != null) loadThread.quitSafely();
            if (renderThread != null) renderThread.quitSafely();
            super.onDestroy();
        }

        // --------------------------------------------------------------
        // 切换调度
        // --------------------------------------------------------------
        void requestSwitch(final boolean animated) {
            if (destroyed || loadHandler == null) return;
            if (switching.get()) {
                // 正好撞上后台自动切换的解码窗口：依然要重置时钟，
                // 只是把换图排到当前那次完成后由 finishSwitch 执行。
                queuedSwitch = true;
                loadHandler.post(new Runnable() {
                    @Override public void run() { resetClock(); scheduleNext(); }
                });
                return;
            }
            loadHandler.post(new Runnable() {
                @Override public void run() {
                    resetClock();            // 手动换图后重新起算，避免刚换完又自动换
                    performSwitch(animated);
                    scheduleNext();
                }
            });
        }

        void reloadConfig() {
            if (destroyed || loadHandler == null) return;
            loadHandler.post(new Runnable() {
                @Override public void run() {
                    resetClock();            // 间隔改了，按新间隔从现在重新起算
                    scheduleNext();
                }
            });
        }

        /** 悬浮球前台服务的轮询入口：额外用挂钟核对一次，不干扰 clockTask 的排期 */
        void tickClock() {
            if (destroyed || loadHandler == null) return;
            loadHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    catchUp(visible);
                }
            });
        }

        // --------------------------------------------------------------
        // 挂钟时钟：与桌面可见性完全解耦
        // --------------------------------------------------------------

        /** 引擎创建时调用：恢复持久化的切换时刻；进程被杀重启后仍按真实时间对齐 */
        private void restoreClock() {
            long now = System.currentTimeMillis();
            long iv = Prefs.interval(getApplicationContext());
            long saved = Prefs.nextSwitchAt(getApplicationContext());
            if (saved > now && saved - now <= iv) {
                nextSwitchAt = saved;      // 剩余时间仍有效，不因重开 App 而重置
            } else {
                // 从未设置 / 已过点（深睡、冻结、被杀）/ 系统时间被回拨
                nextSwitchAt = now + iv;
                Prefs.setNextSwitchAt(getApplicationContext(), nextSwitchAt);
            }
        }

        /** 手动切换或改间隔后，从当前时刻重新起算 */
        private void resetClock() {
            nextSwitchAt = System.currentTimeMillis()
                    + Prefs.interval(getApplicationContext());
            Prefs.setNextSwitchAt(getApplicationContext(), nextSwitchAt);
        }

        /**
         * 到点切换：推进挂钟基准 → 换图 → 重排下一次。
         * 后台也会执行（animated 传 visible，不可见时直接替换、不跑动画、不绘制）。
         * 离开很久只补切一张、不追赶 N 次，视觉上就是「回来已经不是原来那张」。
         */
        private void switchByClock(boolean animated) {
            nextSwitchAt = System.currentTimeMillis()
                    + Prefs.interval(getApplicationContext());
            Prefs.setNextSwitchAt(getApplicationContext(), nextSwitchAt);
            performSwitch(animated);
            scheduleNext();
        }

        /** 按挂钟核对一次；返回是否真的切了图。只在 load 线程调用 */
        private boolean catchUp(boolean animated) {
            if (destroyed) return false;
            if (nextSwitchAt <= 0) { restoreClock(); return false; }
            if (System.currentTimeMillis() < nextSwitchAt) return false;
            switchByClock(animated);
            return true;
        }

        void onPoolChanged() {
            if (destroyed || loadHandler == null) return;
            loadHandler.post(new Runnable() {
                @Override public void run() {
                    List<String> pool = AlbumRepository.readPool(getApplicationContext());
                    if (pool.isEmpty()) {
                        renderHandler.post(new Runnable() {
                            @Override public void run() {
                                recycle(current); current = null; currentUri = null;
                                recycle(incoming); incoming = null;
                                fading = false;
                                drawFrame();
                            }
                        });
                    } else if (currentUri == null) {
                        requestSwitch(false);  // 池从空变有，立刻显示第一张
                    } else if (!pool.contains(currentUri)) {
                        requestSwitch(true);   // 当前这张被从相册删掉了
                    }
                }
            });
        }

        /** 只在 load 线程执行：读池 → 随机挑一张 → 解码 */
        private void performSwitch(final boolean animated) {
            if (destroyed || w <= 0 || h <= 0) return;
            if (!switching.compareAndSet(false, true)) { queuedSwitch = true; return; }

            List<String> pool = AlbumRepository.readPool(getApplicationContext());
            if (pool.isEmpty()) {
                renderHandler.post(new Runnable() {
                    @Override public void run() { drawFrame(); finishSwitch(); }
                });
                return;
            }

            Bitmap bmp = null;
            String picked = null;
            int tries = Math.min(6, pool.size());
            for (int i = 0; i < tries; i++) {
                String candidate = pickRandom(pool, currentUri);
                bmp = BitmapLoader.decodeCover(getApplicationContext(), candidate, w, h);
                if (bmp != null) { picked = candidate; break; }
            }
            if (bmp == null) {
                // 所有候选都解不出来（例如 HEIC 不支持、文件损坏），保留旧图
                renderHandler.post(new Runnable() {
                    @Override public void run() { finishSwitch(); }
                });
                return;
            }

            final Bitmap next = bmp;
            final String nextUri = picked;
            renderHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) { next.recycle(); finishSwitch(); return; }
                    // 后台到点：直接替换当前图，不跑淡化、不绘制；
                    // 回到桌面时 onVisibilityChanged 会把这张最新图画出来。
                    boolean doFade = animated && visible && current != null;
                    if (!doFade) {
                        recycle(current);
                        current = next;
                        currentUri = nextUri;
                        recycle(incoming);
                        incoming = null;
                        fading = false;
                        fade = 0f;
                        drawFrame();
                        finishSwitch();
                    } else {
                        recycle(incoming);      // 极端情况下丢弃上一张未完成的淡入
                        incoming = next;
                        fade = 0f;
                        fading = true;
                        startFadeLoop(nextUri);
                    }
                }
            });

            // 计时统一由调用方（clockTask / requestSwitch / reloadConfig / catchUp）排期，
            // 这里不再重置，避免后台切换把挂钟基准打乱。
        }

        /** 600ms 淡化：旧图不透明打底，新图 alpha 0 → 255 渐显 */
        private void startFadeLoop(final String nextUri) {
            final long start = SystemClock.uptimeMillis();
            renderHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) { finishSwitch(); return; }
                    if (incoming == null || !fading) { finishSwitch(); return; }
                    if (!visible) {
                        // 淡化途中切到后台：立刻收尾，不在后台空转 600ms
                        recycle(current);
                        current = incoming;
                        currentUri = nextUri;
                        incoming = null;
                        fading = false;
                        fade = 0f;
                        finishSwitch();
                        return;
                    }
                    float t = (SystemClock.uptimeMillis() - start) / (float) C.FADE_MS;
                    if (t >= 1f) {
                        recycle(current);
                        current = incoming;
                        currentUri = nextUri;
                        incoming = null;
                        fading = false;
                        fade = 0f;
                        drawFrame();
                        finishSwitch();
                    } else {
                        fade = t;
                        drawFrame();
                        renderHandler.postDelayed(this, C.FRAME_MS);
                    }
                }
            });
        }

        private void finishSwitch() {
            switching.set(false);
            if (queuedSwitch) {
                queuedSwitch = false;
                if (!destroyed && loadHandler != null) {
                    loadHandler.post(new Runnable() {
                        @Override public void run() { performSwitch(true); }
                    });
                }
            }
        }

        /**
         * 按「距离 nextSwitchAt 还剩多久」排下一次心跳。
         * 不直接睡满整个间隔：postDelayed 走的是 uptimeMillis（不含深睡），
         * 深睡后会被拖长；改成最多 15 秒核对一次挂钟，唤醒后能立刻发现到点并补切。
         * 可见性不参与计算 —— 这是「离开桌面也照常计时」的核心。
         */
        private void scheduleNext() {
            if (destroyed || loadHandler == null) return;
            loadHandler.removeCallbacks(clockTask);
            long now = System.currentTimeMillis();
            long iv = Prefs.interval(getApplicationContext());
            // 时间被回拨 / 间隔被改小 / 从未初始化：时间戳不合理就重新对齐
            if (nextSwitchAt <= now || nextSwitchAt - now > iv) {
                nextSwitchAt = now + iv;
                Prefs.setNextSwitchAt(getApplicationContext(), nextSwitchAt);
            }
            long remaining = nextSwitchAt - now;
            long delay = Math.min(remaining, C.CLOCK_HEARTBEAT_MS);
            if (delay < 200L) delay = 200L;   // 防止极端情况下忙循环
            loadHandler.postDelayed(clockTask, delay);
        }

        /** 随机抽取，尽量不连续重复同一张 */
        private String pickRandom(List<String> pool, String exclude) {
            if (pool.isEmpty()) return null;
            if (pool.size() == 1) return pool.get(0);
            String s = pool.get(0);
            for (int guard = 0; guard < 12; guard++) {
                s = pool.get(random.nextInt(pool.size()));
                if (exclude == null || !s.equals(exclude)) break;
            }
            return s;
        }

        // --------------------------------------------------------------
        // 绘制
        // --------------------------------------------------------------
        private void drawFrame() {
            if (destroyed || !visible) return;   // 后台只换数据，不碰 surface，省电
            SurfaceHolder sh = getSurfaceHolder();
            if (sh == null) return;
            Canvas c = null;
            try {
                c = sh.lockCanvas();
                if (c == null) return;
                if (current != null && !current.isRecycled()) {
                    c.drawBitmap(current, 0, 0, paint);
                } else {
                    c.drawColor(Color.rgb(24, 32, 43));
                    c.drawText("请在「相册壁纸」中选择文件夹并点一次手动同步",
                            w / 2f, h / 2f, textPaint);
                }
                if (fading && incoming != null && !incoming.isRecycled()) {
                    fadePaint.setAlpha(Math.max(0, Math.min(255, (int) (fade * 255f))));
                    c.drawBitmap(incoming, 0, 0, fadePaint);
                    fadePaint.setAlpha(255);
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try { sh.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
                }
            }
        }

        private void recycle(Bitmap b) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /** 供 MainActivity 判断当前壁纸是不是本应用（比进程内静态引用更可靠） */
    public static boolean isActiveWallpaper(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            android.app.WallpaperInfo info = wm.getWallpaperInfo();
            return info != null && C.APP_ID.equals(info.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }
}
