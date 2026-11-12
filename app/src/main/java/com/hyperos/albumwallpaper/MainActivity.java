package com.hyperos.albumwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简设置页：只有「相册文件夹选择 / 手动同步 / 切换间隔 / 悬浮球」四项核心功能，
 * 外加必需的「设为动态壁纸」入口与权限引导。
 *
 * 重点：本页绝不自动扫描相册。onResume 只读取已经写好的壁纸池文件。
 */
public class MainActivity extends Activity {

    private TextView tvWallpaperState, tvAlbum, tvPool, tvSyncInfo, tvBallState;
    private Button btnPickAlbum, btnPickTree, btnSync, btnPermissions, btnSetWallpaper;
    private RadioGroup rgInterval;
    private Switch swBall;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean intervalInit = false;
    private boolean ballInit = false;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindListeners();
        requestNeededPermissions();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        // 若用户已开启悬浮球但服务被系统杀掉，回到 App 时顺手拉起（不是后台偷偷扫描）
        if (Prefs.ballEnabled(this) && Settings.canDrawOverlays(this)) {
            startBallService();
        }
    }

    private void bindViews() {
        tvWallpaperState = findViewById(R.id.tvWallpaperState);
        tvAlbum         = findViewById(R.id.tvAlbum);
        tvPool          = findViewById(R.id.tvPool);
        tvSyncInfo      = findViewById(R.id.tvSyncInfo);
        tvBallState     = findViewById(R.id.tvBallState);
        btnPickAlbum    = findViewById(R.id.btnPickAlbum);
        btnPickTree     = findViewById(R.id.btnPickTree);
        btnSync         = findViewById(R.id.btnSync);
        btnPermissions  = findViewById(R.id.btnPermissions);
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper);
        rgInterval      = findViewById(R.id.rgInterval);
        swBall          = findViewById(R.id.swBall);
    }

    private void bindListeners() {
        btnPickAlbum.setOnClickListener(v -> pickAlbum());
        btnPickTree.setOnClickListener(v -> pickTree());
        btnSync.setOnClickListener(v -> doSync(false));
        btnSetWallpaper.setOnClickListener(v -> setLiveWallpaper());
        btnPermissions.setOnClickListener(v -> showPermissionMenu());

        rgInterval.setOnCheckedChangeListener((group, checkedId) -> {
            if (intervalInit) return;
            long ms = C.INTERVAL_60;
            if (checkedId == R.id.rb30) ms = C.INTERVAL_30;
            else if (checkedId == R.id.rb120) ms = C.INTERVAL_120;
            Prefs.setInterval(this, ms);
            AlbumWallpaperService.reloadConfig();   // 重置定时，立即按新间隔走
        });

        swBall.setOnCheckedChangeListener((button, on) -> {
            if (ballInit) return;
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    ballInit = true;
                    swBall.setChecked(false);
                    ballInit = false;
                    toast("请先允许「显示悬浮窗」，然后再打开开关");
                    openOverlaySettings();
                    return;
                }
                Prefs.setBallEnabled(this, true);
                startBallService();
                if (!hasUsageAccess()) {
                    toast("建议再开启「使用情况访问权限」，小球才会在其它 App 自动隐藏");
                }
            } else {
                Prefs.setBallEnabled(this, false);
                stopService(new Intent(this, FloatBallService.class));
            }
            refreshBallState();
        });
    }

    // ==================================================================
    // 权限
    // ==================================================================
    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            // 用户主动选了「仅部分照片」时不要反复弹照片选择器，改为在界面上提示
            if (!isPartialPhotoAccess()
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                        != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), C.REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == C.REQ_PERMISSIONS) refreshAll();
    }

    private boolean hasFullImageAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 14 起用户可能只勾选了「仅允许访问部分照片」 */
    private boolean isPartialPhotoAccess() {
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                    == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            return aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            return false;
        }
    }

    private void showPermissionMenu() {
        final String[] items = {
                "① 显示悬浮窗（悬浮球必需）",
                "② 使用情况访问权限（小球自动隐藏必需）",
                "③ 忽略电池优化（防后台被杀）",
                "④ 应用详情页：自启动 / 省电策略",
                "⑤ 相册权限：改为允许所有照片"
        };
        new AlertDialog.Builder(this)
                .setTitle("HyperOS 需要手动开启的设置")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openOverlaySettings(); break;
                        case 1: openUsageSettings(); break;
                        case 2: openBatterySettings(); break;
                        case 3: openAppDetails(); break;
                        default: openAppDetails(); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) { openAppDetails(); }
    }

    private void openUsageSettings() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        catch (Exception e) { openAppDetails(); }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) { openAppDetails(); }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    // ==================================================================
    // 1. 选择相册文件夹 → 一次性导入全部图片
    // ==================================================================
    private void pickAlbum() {
        if (!hasFullImageAccess()) {
            toast("请先允许「访问所有照片」");
            openAppDetails();
            return;
        }
        if (syncing) return;
        syncing = true;
        btnPickAlbum.setEnabled(false);
        tvSyncInfo.setText("正在读取相册列表…");
        new Thread(() -> {
            final List<AlbumRepository.Album> albums = AlbumRepository.listAlbums(MainActivity.this);
            ui.post(() -> {
                syncing = false;
                btnPickAlbum.setEnabled(true);
                tvSyncInfo.setText("只有点击「手动同步」才会重新扫描相册。");
                showAlbumDialog(albums);
            });
        }).start();
    }

    private void showAlbumDialog(List<AlbumRepository.Album> albums) {
        if (albums.isEmpty()) {
            toast("没读到分类相册，请点下方「目录方式」选择，或检查相册权限");
            return;
        }
        String[] items = new String[albums.size()];
        for (int i = 0; i < albums.size(); i++) items[i] = albums.get(i).toString();
        new AlertDialog.Builder(this)
                .setTitle("选择相册文件夹（整夹导入）")
                .setItems(items, (d, which) -> {
                    AlbumRepository.Album a = albums.get(which);
                    Prefs.setBucket(this, a.id, a.name);
                    doSync(true);   // 选中即一次性导入
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 目录方式（SAF）：应对 MediaStore 扫不到、或想选任意文件夹的情况 */
    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { startActivityForResult(i, C.REQ_PICK_TREE); }
        catch (Exception e) { toast("系统文件选择器不可用"); }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == C.REQ_PICK_TREE && result == RESULT_OK && data != null) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                toast("目录授权失败，请重新选择");
                return;
            }
            String name = tree.getLastPathSegment();
            if (name != null) {
                name = Uri.decode(name);
                int idx = name.lastIndexOf(':');
                if (idx >= 0 && idx + 1 < name.length()) name = name.substring(idx + 1);
            }
            if (name == null || name.isEmpty()) name = "已选目录";
            Prefs.setTree(this, tree, name);
            doSync(true);
        } else if (req == C.REQ_SET_WALLPAPER) {
            refreshAll();
        }
    }

    // ==================================================================
    // 2. 手动同步 / 首次全量导入
    // ==================================================================
    private void doSync(boolean firstImport) {
        if (syncing) return;
        if (C.MODE_NONE.equals(Prefs.mode(this))) {
            toast("请先选择相册文件夹");
            return;
        }
        if (C.MODE_BUCKET.equals(Prefs.mode(this)) && !hasFullImageAccess()) {
            toast("相册权限不足，请在系统设置里改为允许所有照片");
            openAppDetails();
            return;
        }
        syncing = true;
        btnSync.setEnabled(false);
        btnPickAlbum.setEnabled(false);
        tvSyncInfo.setText(firstImport ? "正在一次性导入该文件夹全部图片…"
                                       : "正在扫描选中文件夹（本次为唯一一次扫描）…");

        new Thread(() -> {
            String msg;
            boolean ok;
            if (firstImport) {
                try {
                    int n = AlbumRepository.importAll(MainActivity.this);
                    msg = "已一次性导入 " + n + " 张图片进壁纸池。之后只有点「手动同步」才会重新扫描。";
                    ok = true;
                } catch (Exception e) {
                    msg = "导入失败：" + safeMsg(e);
                    ok = false;
                }
            } else {
                AlbumRepository.SyncResult r = AlbumRepository.sync(MainActivity.this);
                if (r.error != null) {
                    msg = "同步失败（壁纸池保持原样）：" + r.error;
                    ok = false;
                } else {
                    msg = "同步完成：新增 " + r.added + " 张，移除 " + r.removed
                            + " 张，当前共 " + r.total + " 张。";
                    ok = true;
                }
            }
            final String text = msg;
            final boolean success = ok;
            ui.post(() -> {
                syncing = false;
                btnSync.setEnabled(true);
                btnPickAlbum.setEnabled(true);
                tvSyncInfo.setText(text);
                if (success) {
                    AlbumWallpaperService.onPoolChanged();
                    toast("同步完成");
                } else {
                    toast("同步未完成，请检查权限");
                }
                refreshAll();
            });
        }).start();
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ==================================================================
    // 3. 设为动态壁纸
    // ==================================================================
    private void setLiveWallpaper() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, AlbumWallpaperService.class));
            startActivityForResult(i, C.REQ_SET_WALLPAPER);
        } catch (Exception e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception e2) {
                toast("请在系统「壁纸」里手动选择「相册随机壁纸」");
            }
        }
    }

    // ==================================================================
    // 悬浮球服务
    // ==================================================================
    private void startBallService() {
        try {
            Intent i = new Intent(this, FloatBallService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) { }
    }

    // ==================================================================
    // 界面刷新（只读本地池文件，不扫描相册）
    // ==================================================================
    private void refreshAll() {
        tvWallpaperState.setText(AlbumWallpaperService.isActiveWallpaper(this)
                ? "✓ 动态壁纸已启用"
                : "○ 尚未设为动态壁纸 —— 点最下方按钮启用");

        String label = Prefs.sourceLabel(this);
        if (isPartialPhotoAccess()) {
            label += "\n⚠ 目前只授权了「部分照片」，请到系统权限里改为「始终允许所有照片」";
        }
        tvAlbum.setText(label);
        tvPool.setText("壁纸池：" + AlbumRepository.poolSize(this) + " 张");

        intervalInit = true;
        long interval = Prefs.interval(this);
        if (interval == C.INTERVAL_30) rgInterval.check(R.id.rb30);
        else if (interval == C.INTERVAL_120) rgInterval.check(R.id.rb120);
        else rgInterval.check(R.id.rb60);
        intervalInit = false;

        ballInit = true;
        swBall.setChecked(Prefs.ballEnabled(this));
        ballInit = false;

        refreshBallState();
    }

    private void refreshBallState() {
        StringBuilder sb = new StringBuilder();
        sb.append(Settings.canDrawOverlays(this) ? "悬浮窗 ✓" : "悬浮窗 ✗（需开启）");
        sb.append(hasUsageAccess() ? " · 使用情况 ✓" : " · 使用情况 ✗（小球会常显）");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            sb.append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())
                    ? " · 电池白名单 ✓" : " · 电池白名单 ✗");
        } catch (Throwable ignored) { }
        tvBallState.setText("权限状态：" + sb);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
