package com.hyperos.albumwallpaper;

/** 全局常量。applicationId 与签名一旦发布就不要改，否则无法覆盖安装。 */
public final class C {
    private C() { }

    public static final String APP_ID   = "com.hyperos.albumwallpaper";
    public static final String PREFS    = "album_wallpaper_prefs";
    public static final String POOL_FILE = "wallpaper_pool.txt";

    public static final String KEY_MODE        = "source_mode";      // none / bucket / tree
    public static final String KEY_BUCKET_ID   = "bucket_id";
    public static final String KEY_BUCKET_NAME = "bucket_name";
    public static final String KEY_TREE_URI    = "tree_uri";
    public static final String KEY_TREE_NAME   = "tree_name";
    public static final String KEY_INTERVAL    = "interval_ms";
    public static final String KEY_BALL        = "float_ball_enabled";
    /** 下一次自动切换的挂钟时间戳（System.currentTimeMillis，含深睡），跨进程重启保留 */
    public static final String KEY_NEXT_SWITCH_AT = "next_switch_at";

    public static final String MODE_NONE   = "none";
    public static final String MODE_BUCKET = "bucket";
    public static final String MODE_TREE   = "tree";

    public static final long INTERVAL_30  = 30_000L;
    public static final long INTERVAL_60  = 60_000L;
    public static final long INTERVAL_120 = 120_000L;
    public static final long DEFAULT_INTERVAL = INTERVAL_60;
    public static final long FADE_MS = 600L;       // 淡化切换时长
    public static final int  FRAME_MS = 16;        // 约 60fps
    /**
     * 时钟自检心跳上限。postDelayed 基于 uptimeMillis（深睡不走），
     * 所以不直接睡满整个间隔，而是最多每 15 秒用挂钟时间核对一次；
     * 深睡/进程冻结后只要 CPU 被唤醒，就能立刻发现到点并补切。
     * 心跳不会主动唤醒 CPU，额外耗电可忽略。
     */
    public static final long CLOCK_HEARTBEAT_MS = 15_000L;

    public static final String NOTIF_CHANNEL = "album_wallpaper_keepalive";
    public static final int    NOTIF_ID      = 20261112;

    public static final int REQ_PERMISSIONS   = 1001;
    public static final int REQ_PICK_TREE     = 1002;
    public static final int REQ_OVERLAY       = 1003;
    public static final int REQ_USAGE         = 1004;
    public static final int REQ_BATTERY       = 1005;
    public static final int REQ_SET_WALLPAPER = 1006;
}
