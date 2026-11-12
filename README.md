# 相册随机壁纸（Redmi K60E / HyperOS 2.0 专用）

一个**纯原生 Android 动态壁纸 APK**。支持整夹导入相册、手动增量同步、随机轮播、
`max()` 等比缩放居中裁切、定时/悬浮球/亮屏解锁淡化切换，以及 HyperOS 后台保活。

- 📖 [使用与打包教程.md](使用与打包教程.md) —— 不装 Android Studio，用 GitHub Actions 云端编译 + 安装 + 覆盖更新
- ⚠️ [HyperOS避坑指南.md](HyperOS避坑指南.md) —— 权限、省电限制、红米机型专项坑与自检清单

---

## 需求对照表

| # | 需求 | 实现位置 | 说明 |
|---|---|---|---|
| 1 | 选分类相册文件夹，整夹一次导入，数量无上限 | `MainActivity.showAlbumDialog()` / `AlbumRepository` | MediaStore 分类相册为主；另提供 SAF 目录方式兜底。池只存 URI 文本，不缓存 Bitmap |
| 2 | 只有点【手动同步】才扫描，新增加入、删掉移除，无后台扫描 | `AlbumRepository.sync()` | 全工程**没有** JobScheduler / WorkManager / ContentObserver / 定时扫描；壁纸服务只读池文件 |
| 3 | 随机抽取，非顺序轮播 | `AlbumEngine.pickRandom()` | 随机取，且尽量避开与上一张重复 |
| 4 | `max(屏宽/图宽, 屏高/图高)` 等比缩放居中裁切，不拉伸 | `BitmapLoader.decodeCover()` | Matrix 缩放 + 居中平移，Canvas 边界自动裁掉超出部分；含 EXIF 旋转、大图采样 |
| 5 | 30/60/120 秒可选；淡化切换 | `Prefs` / `AlbumEngine.startFadeLoop()` | 600ms 旧图打底、新图 alpha 渐显；定时、悬浮球、亮屏解锁走同一条路径 |
| 6 | 悬浮球仅桌面显示、打开别的 App 隐藏、球很小 | `FloatBallService` | 34dp、可拖动；`UsageStatsManager` 判断前台 Launcher；权限缺失时降级常显 |
| 7 | 息屏后亮屏解锁自动换一张 | `AlbumEngine.screenReceiver` | `ACTION_USER_PRESENT` 为准，`ACTION_SCREEN_ON` +1.8s 兜底 |
| 8 | 界面只保留四个核心功能 | `activity_main.xml` | 相册 / 手动同步 / 切换间隔 / 悬浮球 + 必需的「设为动态壁纸」与权限引导 |
| 9 | 主动申请权限 + HyperOS 保活 | `MainActivity` / `AndroidManifest.xml` / `FloatBallService` | 相册、通知、悬浮窗、使用情况访问、电池优化；前台服务 + START_STICKY + 开机自启 |
| 10 | 完整代码 + 在线打包教程 | 本工程 + `.github/workflows/build.yml` | GitHub Actions，浏览器操作即可 |
| 11 | 单独列出 HyperOS 坑 | `HyperOS避坑指南.md` | 权限、后台限制、安装拦截、MediaStore、覆盖安装等 |
| 12 | 只做安卓动态壁纸；更新不用卸载 | `applicationId` + `release.p12` + 自动 versionCode | 包名/签名固定，CI 注入递增版本号，直接点新 APK 覆盖安装 |

---

## 关键设计说明

**为什么用悬浮球而不是「桌面双击」**：HyperOS 不再向第三方应用转发桌面空白处的触摸事件，
`WallpaperService` 收不到双击；无障碍服务也拿不到可靠的"桌面空白双击"语义且会被系统自动关闭。
一个只在桌面出现的 34dp 小球，是当前唯一稳定的替代方案。

**为什么不做后台自动扫描**：按需求，扫描是纯用户显式触发的动作。
这样做也顺带避开了 HyperOS 对后台任务 / 相册访问的严格管控，更省电、更不容易被杀。
壁纸服务启动时只读一次本地池文件，之后按 URI 随机取图。

**内存策略**：壁纸池为文本文件（一行一个 `content://` URI）；
任意时刻最多持有 2 张屏幕大小的 Bitmap（`current` + 淡化中的 `incoming`），
解码原图时按像素预算做 `inSampleSize` 采样并立即 `recycle()`，超大文件夹也不会 OOM。

**覆盖安装三要素**：`applicationId` 固定、`release.p12` 签名固定、`versionCode` 由 CI 自动递增。
详见教程第八步与三条红线。

---

## 构建参数

| 项 | 值 |
|---|---|
| 语言 / 依赖 | 纯 Java + 纯 Android 系统 API，**零第三方库** |
| 包名 | `com.hyperos.albumwallpaper` |
| compileSdk / minSdk / targetSdk | 35 / 29 / 34 |
| AGP / Gradle / JDK | 8.6.1 / 8.9 / 17 |
| 签名 | PKCS#12 `release.p12`，alias `albumwallpaper`，默认密码 `albumwp2026` |
| 产物 | `AlbumWallpaper-v<构建号>.apk`（GitHub Actions Artifact） |
