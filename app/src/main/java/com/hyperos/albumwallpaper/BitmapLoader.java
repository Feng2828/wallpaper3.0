package com.hyperos.albumwallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;

import java.io.InputStream;

/**
 * 图片加载器 —— 严格按需求 4 实现：
 *
 *   scale = max(屏幕宽 / 图宽, 屏幕高 / 图高)
 *   等比缩放后居中绘制：小图放大到刚好铺满，大图只保留屏幕大小的居中区域，
 *   Canvas 边界自动裁掉超出部分，绝不拉伸变形。
 *
 * 同时处理 EXIF 旋转、大图采样（防止 OOM），返回的 Bitmap 尺寸恒为 [sw, sh]。
 */
public final class BitmapLoader {
    private BitmapLoader() { }

    /** 把一张图片解码成刚好一张屏幕大小的位图；失败返回 null（调用方会换下一张） */
    public static Bitmap decodeCover(Context ctx, String uriString, int sw, int sh) {
        if (uriString == null || sw <= 0 || sh <= 0) return null;
        Bitmap src = null;
        try {
            Uri uri = Uri.parse(uriString);

            // ---------- 1. 只读边界，拿到原始尺寸 ----------
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int rawW = bounds.outWidth;
            int rawH = bounds.outHeight;
            if (rawW <= 0 || rawH <= 0) return null;

            // ---------- 2. EXIF 方向（手机竖拍照片常见） ----------
            int rotation = readRotation(ctx, uri);
            if (rotation == 90 || rotation == 270) {
                int t = rawW; rawW = rawH; rawH = t;
            }

            // ---------- 3. 采样：控制峰值内存，同时尽量保留细节 ----------
            long budget = Math.min((long) sw * sh * 4L, 24_000_000L);
            int sample = 1;
            while (sample < 32 && (long) (rawW / sample) * (rawH / sample) > budget) {
                sample *= 2;
            }

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opt.inJustDecodeBounds = false;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                src = BitmapFactory.decodeStream(in, null, opt);
            }
            if (src == null || src.isRecycled()) return null;

            // ---------- 4. 应用 EXIF 旋转 ----------
            if (rotation != 0) {
                Matrix rot = new Matrix();
                rot.postRotate(rotation);
                Bitmap turned = Bitmap.createBitmap(
                        src, 0, 0, src.getWidth(), src.getHeight(), rot, true);
                if (turned != src) { src.recycle(); src = turned; }
            }

            // ---------- 5. max() 等比缩放 + 居中 + 自动裁切 ----------
            Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.BLACK);

            float scale = Math.max((float) sw / src.getWidth(),
                                   (float) sh / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((sw - src.getWidth() * scale) / 2f,
                            (sh - src.getHeight() * scale) / 2f);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(src, m, paint);
            return out;
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    /** 读取 EXIF 旋转角度，返回 0 / 90 / 180 / 270 */
    @SuppressWarnings("deprecation")
    private static int readRotation(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            android.media.ExifInterface exif = new android.media.ExifInterface(in);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable e) {
            return 0;
        }
    }
}
