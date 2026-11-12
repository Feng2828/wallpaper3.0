package com.hyperos.albumwallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 开机、覆盖安装之后把悬浮球前台服务拉起来。
 * 注意：HyperOS 默认拦截第三方应用的开机自启广播，必须按《避坑指南》
 * 手动打开「自启动」；打开后本接收器才会被调用。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        if (!Prefs.ballEnabled(ctx)) return;
        if (!Settings.canDrawOverlays(ctx)) return;

        try {
            Intent s = new Intent(ctx, FloatBallService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
            else ctx.startService(s);
        } catch (Throwable ignored) {
            // 部分 ROM 在后台限制启动前台服务，忽略即可；下次打开 App 会再次启动
        }
    }
}
