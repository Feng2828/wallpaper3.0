package com.hyperos.albumwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * 轻量配置。所有组件（Activity / 壁纸服务 / 悬浮球服务）都在同一进程，
 * 使用同一个 SharedPreferences 实例；关键配置用 commit() 同步落盘，保证立刻可见。
 */
public final class Prefs {
    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(C.PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        return sp(c).getString(C.KEY_MODE, C.MODE_NONE);
    }

    public static String bucketId(Context c) {
        return sp(c).getString(C.KEY_BUCKET_ID, "");
    }

    public static String bucketName(Context c) {
        return sp(c).getString(C.KEY_BUCKET_NAME, "");
    }

    public static String treeUriString(Context c) {
        return sp(c).getString(C.KEY_TREE_URI, "");
    }

    public static Uri treeUri(Context c) {
        String s = treeUriString(c);
        return s.isEmpty() ? null : Uri.parse(s);
    }

    public static String treeName(Context c) {
        return sp(c).getString(C.KEY_TREE_NAME, "");
    }

    /** 一次性保存「MediaStore 分类相册」来源 */
    public static void setBucket(Context c, String id, String name) {
        sp(c).edit()
                .putString(C.KEY_MODE, C.MODE_BUCKET)
                .putString(C.KEY_BUCKET_ID, id)
                .putString(C.KEY_BUCKET_NAME, name)
                .commit();
    }

    /** 一次性保存「SAF 目录」来源 */
    public static void setTree(Context c, Uri uri, String name) {
        sp(c).edit()
                .putString(C.KEY_MODE, C.MODE_TREE)
                .putString(C.KEY_TREE_URI, uri.toString())
                .putString(C.KEY_TREE_NAME, name)
                .commit();
    }

    public static long interval(Context c) {
        long v = sp(c).getLong(C.KEY_INTERVAL, C.DEFAULT_INTERVAL);
        if (v != C.INTERVAL_30 && v != C.INTERVAL_60 && v != C.INTERVAL_120) {
            return C.DEFAULT_INTERVAL;
        }
        return v;
    }

    public static void setInterval(Context c, long ms) {
        sp(c).edit().putLong(C.KEY_INTERVAL, ms).commit();
    }

    public static boolean ballEnabled(Context c) {
        return sp(c).getBoolean(C.KEY_BALL, false);
    }

    public static void setBallEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(C.KEY_BALL, on).commit();
    }

    /** 给界面显示用的一行文字 */
    public static String sourceLabel(Context c) {
        switch (mode(c)) {
            case C.MODE_BUCKET:
                String n = bucketName(c);
                return "分类相册：" + (n.isEmpty() ? bucketId(c) : n);
            case C.MODE_TREE:
                String t = treeName(c);
                return "目录：" + (t.isEmpty() ? treeUriString(c) : t);
            default:
                return "尚未选择";
        }
    }
}
