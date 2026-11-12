package com.hyperos.albumwallpaper;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相册 / 壁纸池数据层。
 *
 * 设计要点（对应需求 1、2）：
 *  - 两种来源：MediaStore 分类相册（如「风景」「人物」），或 SAF 目录树。
 *  - 选择来源时一次性把该文件夹内全部图片 URI 写入池文件（数量无上限，
 *    池里只存 URI 字符串，不缓存 Bitmap，所以几千张也不会吃内存）。
 *  - 只有 sync() 被调用时才会重新扫描相册。没有任何 Service / JobScheduler /
 *    WorkManager / ContentObserver 在后台扫描，壁纸服务只读池文件。
 *  - sync() 做集合差：新增的加入、删掉的移除。
 */
public final class AlbumRepository {
    private AlbumRepository() { }

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    /** 相册分类列表项 */
    public static final class Album {
        public final String id;
        public final String name;
        public int count;
        Album(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + "（" + count + " 张）"; }
    }

    /** 同步结果 */
    public static final class SyncResult {
        public int added;
        public int removed;
        public int total;
        public String error;   // 非 null 表示扫描失败，此时壁纸池保持原样
    }

    // ------------------------------------------------------------------
    // 1. 列出所有分类相册
    // ------------------------------------------------------------------
    public static List<Album> listAlbums(Context ctx) {
        Map<String, Album> map = new LinkedHashMap<>();
        String[] projection = {
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(IMAGES, projection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c == null) return new ArrayList<>(map.values());
            int iId   = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            while (c.moveToNext()) {
                String id = c.getString(iId);
                if (TextUtils.isEmpty(id)) continue;
                Album a = map.get(id);
                if (a == null) {
                    String name = c.getString(iName);
                    if (TextUtils.isEmpty(name)) name = "相册 " + id;
                    a = new Album(id, name);
                    map.put(id, a);
                }
                a.count++;
            }
        } catch (Exception e) {
            // 权限不足时返回空列表，由界面提示
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        }
        return new ArrayList<>(map.values());
    }

    // ------------------------------------------------------------------
    // 2. 扫描某个来源，返回当前所有图片的 URI 字符串
    // ------------------------------------------------------------------
    public static List<String> scanCurrent(Context ctx) throws Exception {
        switch (Prefs.mode(ctx)) {
            case C.MODE_BUCKET:
                return scanBucket(ctx, Prefs.bucketId(ctx), Prefs.bucketName(ctx));
            case C.MODE_TREE:
                Uri tree = Prefs.treeUri(ctx);
                if (tree == null) throw new IllegalStateException("尚未选择目录");
                return scanTree(ctx, tree);
            default:
                throw new IllegalStateException("尚未选择相册文件夹");
        }
    }

    /** MediaStore 分类相册：一条 SQL 拿到该文件夹全部图片，速度快 */
    private static List<String> scanBucket(Context ctx, String bucketId, String bucketName)
            throws Exception {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(bucketId)) throw new IllegalStateException("相册标识为空");
        String[] projection = { MediaStore.Images.Media._ID };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    IMAGES, projection,
                    MediaStore.Images.Media.BUCKET_ID + "=?",
                    new String[]{ bucketId },
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c == null) throw new IllegalStateException("无法读取相册（权限可能已被回收）");
            while (c.moveToNext()) {
                out.add(ContentUris.withAppendedId(IMAGES, c.getLong(0)).toString());
            }
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        }

        // 少数 ROM 在文件夹重命名后 BUCKET_ID 会变化，用名字兜底再查一次
        if (out.isEmpty() && !TextUtils.isEmpty(bucketName)) {
            Cursor c2 = null;
            try {
                c2 = ctx.getContentResolver().query(
                        IMAGES, projection,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME + "=?",
                        new String[]{ bucketName },
                        MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (c2 != null) {
                    while (c2.moveToNext()) {
                        out.add(ContentUris.withAppendedId(IMAGES, c2.getLong(0)).toString());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c2 != null) { try { c2.close(); } catch (Exception ignored) { } }
            }
        }
        return out;
    }

    /** SAF 目录树：递归收集目录内所有 image/* 文件 */
    private static List<String> scanTree(Context ctx, Uri tree) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        if (rootId == null) throw new IllegalStateException("目录授权已失效，请重新选择文件夹");
        List<String> out = new ArrayList<>();
        walk(ctx.getContentResolver(), tree, rootId, out, 0);
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String docId,
                             List<String> out, int depth) {
        if (depth > 6) return;   // 防止异常目录结构导致无限递归
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        List<String> subDirs = new ArrayList<>();
        Cursor c = null;
        try {
            c = cr.query(children, projection, null, null, null);
            if (c == null) return;
            int iId   = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (c.moveToNext()) {
                String id = iId >= 0 ? c.getString(iId) : null;
                String mime = iMime >= 0 ? c.getString(iMime) : null;
                if (TextUtils.isEmpty(id)) continue;
                if (mime != null && mime.startsWith("image/")) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                } else if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDirs.add(id);
                }
            }
        } catch (Exception ignored) {
            // 单个子目录读不到就跳过，不影响整体
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        }
        for (String d : subDirs) walk(cr, tree, d, out, depth + 1);
    }

    // ------------------------------------------------------------------
    // 3. 壁纸池读写（纯文本文件，一行一个 content:// URI，不设数量上限）
    // ------------------------------------------------------------------
    public static List<String> readPool(Context ctx) {
        List<String> out = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), C.POOL_FILE);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("content://")) out.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) { try { r.close(); } catch (Exception ignored) { } }
        }
        return out;
    }

    public static void writePool(Context ctx, List<String> uris) {
        File dir = ctx.getFilesDir();
        File target = new File(dir, C.POOL_FILE);
        File tmp = new File(dir, C.POOL_FILE + ".tmp");
        BufferedWriter w = null;
        boolean ok = false;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            for (String u : uris) {
                if (u != null) { w.write(u); w.newLine(); }
            }
            w.flush();
            ok = true;
        } catch (Exception ignored) {
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) { } }
        }
        if (ok) {
            if (target.exists() && !target.delete()) { /* 继续尝试覆盖 */ }
            if (!tmp.renameTo(target)) { copyFallback(tmp, target); }
        }
    }

    private static void copyFallback(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream outFile = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) outFile.write(buf, 0, n);
        } catch (Exception ignored) { }
    }

    public static int poolSize(Context ctx) {
        return readPool(ctx).size();
    }

    // ------------------------------------------------------------------
    // 4. 选择来源后立即全量导入（用户主动触发，不属于"后台扫描"）
    // ------------------------------------------------------------------
    public static int importAll(Context ctx) throws Exception {
        List<String> all = scanCurrent(ctx);
        writePool(ctx, all);
        return all.size();
    }

    // ------------------------------------------------------------------
    // 5. 手动同步：新增的加入、删掉的移除
    //    扫描失败时保留原池，绝不清空
    // ------------------------------------------------------------------
    public static SyncResult sync(Context ctx) {
        SyncResult r = new SyncResult();
        List<String> old = readPool(ctx);
        List<String> fresh;
        try {
            fresh = scanCurrent(ctx);
        } catch (Exception e) {
            r.error = e.getMessage() == null ? "扫描失败" : e.getMessage();
            r.total = old.size();
            return r;
        }
        Set<String> oldSet = new HashSet<>(old);
        Set<String> newSet = new HashSet<>(fresh);
        for (String s : newSet) if (!oldSet.contains(s)) r.added++;
        for (String s : oldSet) if (!newSet.contains(s)) r.removed++;
        writePool(ctx, fresh);
        r.total = fresh.size();
        return r;
    }
}
