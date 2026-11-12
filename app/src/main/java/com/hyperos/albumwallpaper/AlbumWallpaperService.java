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
 * 三个切换触发点，全部走同一条「旧图打底 + 新图渐显」路径：
 *   a) 定时（30 / 60 / 120 秒）
 *   b) 悬浮球点击  → AlbumWallpaperService.requestSwitch()
 *   c) 息屏后重新亮屏解锁（ACTION_USER_PRESENT，SCREEN_ON 兜底）
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

    // ==================================================================
    private final class AlbumEngine extends Engine {

        private HandlerThread renderThread, loadThread;
        private Handler renderHandler, loadHandler;

        /** 已裁剪成 surface 大小的位图，最多同时存在 2 张 */
        private Bitmap current, incoming;
        private String currentUri;
        private float fade;
        private boolean fading;

        private int w = 0, h = 0;
        private volatile boolean destroyed = false;
        private boolean visible = true;
        private boolean screenWasOff = false;
        private boolean receiverRegistered = false;

        private final Random random = new Random();
        private final AtomicBoolean switching = new AtomicBoolean(false);
        private volatile boolean queuedSwitch = false;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable timerTask = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                if (visible) requestSwitch(true);
                scheduleNext();
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
            if (isVisible) {
                renderHandler.post(new Runnable() {
                    @Override public void run() { drawFrame(); }
                });
                loadHandler.post(new Runnable() {
                    @Override public void run() { scheduleNext(); }
                });
            }
            // 不可见（打开其它 App / 息屏）时停止绘制，省电；定时任务自动跳过
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
            if (switching.get()) { queuedSwitch = true; return; }
            loadHandler.post(new Runnable() {
                @Override public void run() { performSwitch(animated); }
            });
        }

        void reloadConfig() {
            if (destroyed || loadHandler == null) return;
            loadHandler.post(new Runnable() {
                @Override public void run() { scheduleNext(); }
            });
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
                    if (!animated || current == null) {
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

            scheduleNext();   // 手动切换后重新计时，避免刚换完又立刻自动换
        }

        /** 600ms 淡化：旧图不透明打底，新图 alpha 0 → 255 渐显 */
        private void startFadeLoop(final String nextUri) {
            final long start = SystemClock.uptimeMillis();
            renderHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) { finishSwitch(); return; }
                    if (incoming == null || !fading) { finishSwitch(); return; }
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

        private void scheduleNext() {
            if (destroyed || loadHandler == null) return;
            loadHandler.removeCallbacks(timerTask);
            loadHandler.postDelayed(timerTask, Prefs.interval(getApplicationContext()));
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
            if (destroyed) return;
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
