package com.hyperos.albumwallpaper;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面悬浮球服务。
 *
 * 为什么用悬浮球：HyperOS/Android 原生框架不向第三方应用开放「桌面空白处双击」事件，
 * 无障碍服务也无法可靠识别桌面双击且极易被系统自动关闭，所以用一个 34dp 的小球替代。
 *
 * 显隐规则：
 *  - 用 UsageStatsManager 判断当前前台应用；只有桌面（Launcher）在前台时才显示；
 *  - 打开任何其它 App（包括本 App 设置页）自动隐藏；
 *  - 没有「使用情况访问权限」时降级为常显，保证功能不丢。
 *
 * 保活：前台服务 + 常驻低优先级通知 + START_STICKY + 开机/覆盖安装自启。
 */
public class FloatBallService extends Service {

    public static final String ACTION_STOP = C.APP_ID + ".STOP_BALL";

    private WindowManager wm;
    private View ballView;
    private WindowManager.LayoutParams lp;
    private Handler mainHandler;
    private final Set<String> launcherPackages = new HashSet<>();
    private int ballSize, screenW, screenH;
    private boolean screenOff = false;
    private boolean receiverRegistered = false;

    private int downX, downY;
    private float downRawX, downRawY;
    private boolean dragged;

    // ------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();

        // 悬浮窗权限被系统回收时，直接退出，避免抛异常刷日志
        if (!Settings.canDrawOverlays(this)) {
            stopForeground(true);
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        measureScreen();
        collectLaunchers();
        addBall();
        registerScreenReceiver();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 被系统杀掉后尽量重建
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------
    // 前台服务
    // ------------------------------------------------------------------
    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    C.NOTIF_CHANNEL, "壁纸保活服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持悬浮球与壁纸定时切换稳定运行，请勿关闭");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, C.NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("相册壁纸运行中")
                .setContentText("悬浮球 / 定时切换保持可用")
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 必须带 type，且与 Manifest 的 specialUse 一致
                startForeground(C.NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(C.NOTIF_ID, n);
            }
        } catch (Throwable e) {
            try { startForeground(C.NOTIF_ID, n); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------
    private void measureScreen() {
        ballSize = dp(34);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    @SuppressLint("InflateParams")
    private void addBall() {
        ballView = LayoutInflater.from(this).inflate(R.layout.float_ball, null);

        lp = new WindowManager.LayoutParams(
                ballSize, ballSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = screenH / 3;
        lp.alpha = 0.85f;

        ballView.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = lp.x; downY = lp.y;
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                        lp.x = clamp(downX + dx, 0, screenW - ballSize);
                        lp.y = clamp(downY + dy, 0, screenH - ballSize);
                        try { wm.updateViewLayout(ballView, lp); }
                        catch (Throwable ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            // 点一下 = 随机换一张（同样走淡化切换）
                            AlbumWallpaperService.requestSwitch();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try { wm.addView(ballView, lp); }
        catch (Throwable e) { ballView = null; }
    }

    private void collectLaunchers() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = getPackageManager();
        try {
            List<ResolveInfo> list = pm.queryIntentActivities(home, PackageManager.MATCH_ALL);
            for (ResolveInfo r : list) {
                if (r.activityInfo != null) launcherPackages.add(r.activityInfo.packageName);
            }
        } catch (Throwable ignored) { }
        // HyperOS 桌面兜底
        launcherPackages.add("com.miui.home");
        launcherPackages.add("com.android.launcher3");
    }

    /** 是否有「使用情况访问权限」 */
    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 最近一次进入前台的包名；取不到返回 null（调用方保持上一次状态） */
    private String foregroundPackage() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 10_000L, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            String last = null;
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(ev);
                int t = ev.getEventType();
                if (t == UsageEvents.Event.ACTIVITY_RESUMED
                        || t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    String p = ev.getPackageName();
                    if (p != null) last = p;
                }
            }
            return last;
        } catch (Throwable e) {
            return null;
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            updateVisibility();
            if (mainHandler != null) mainHandler.postDelayed(this, 1200);
        }
    };

    private void startPolling() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(pollTask);
        mainHandler.post(pollTask);
    }

    private void stopPolling() {
        if (mainHandler != null) mainHandler.removeCallbacks(pollTask);
    }

    private void updateVisibility() {
        if (ballView == null || screenOff) return;
        if (!hasUsageAccess()) {
            // 降级：没有使用情况权限时也保持可用（会在所有界面显示）
            setBallVisible(true);
            return;
        }
        String pkg = foregroundPackage();
        if (pkg == null) return;   // 拿不到就维持现状，避免闪烁
        setBallVisible(launcherPackages.contains(pkg));
    }

    private void setBallVisible(boolean show) {
        if (ballView == null) return;
        int v = show ? View.VISIBLE : View.GONE;
        if (ballView.getVisibility() != v) ballView.setVisibility(v);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOff = true;
                stopPolling();
                setBallVisible(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOff = false;
                startPolling();
            }
        }
    };

    private void registerScreenReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, null, null, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    @Override
    public void onDestroy() {
        stopPolling();
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) { }
        }
        if (ballView != null && wm != null) {
            try { wm.removeViewImmediate(ballView); } catch (Throwable ignored) { }
            ballView = null;
        }
        screenOff = false;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
