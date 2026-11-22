package com.zaka.douyin.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 画一块液态玻璃。
 *
 * 配方照 Kyant0/AndroidLiquidGlass 的 drawBackdrop，顺序不自己发明：
 *
 *   1. vibrancy        饱和 1.5，让底下的画面透亮
 *   2. 折射            圆角 SDF + circleMap 沿法线外推采样点（透镜厚边），
 *                      可选 RGB 分离色散（只在按压/拖动时开）
 *   3. 表面膜          暗底上一层很淡的白，亮底上一层极淡的黑，按压再加一点
 *   4. 45° 高光        .5dp 的细边 + 一抹朝光的泛白
 *   5. 内阴影          下沿一圈很淡的暗，给厚度
 *
 * 没有背景可用时**不画黑**：只留一层极淡的白雾。
 * 老版本满屏黑块就是这里拿深色打了底。
 */
final class GlassPainter {

    static final boolean HAS_SHADER = android.os.Build.VERSION.SDK_INT >= 33;

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint INNER = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint AMBIENT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();
    private static final Rect SRC = new Rect();
    static final Matrix MAT = new Matrix();
    static final int[] LOC = new int[2];

    private static ColorMatrixColorFilter vibrant;
    private GlassPainter() {
    }

    static ColorMatrixColorFilter vibrant() {
        if (vibrant == null) {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(1.5f);
            vibrant = new ColorMatrixColorFilter(m);
        }
        return vibrant;
    }

    /**
     * @param b      在 canvas 本地坐标里的位置
     * @param rad    圆角
     * @param press  0..1 按压/拖动进度，决定折射强度和色散
     * @param luma   底下画面的亮度，决定表面膜是加白还是加黑
     */
    static void draw(Canvas canvas, RectF b, float rad, Backdrop bd, View owner,
                     float press, boolean dispersion, int luma, float density) {
        if (canvas == null || b == null || b.width() <= 0 || b.height() <= 0) return;
        float r = Math.max(0f, Math.min(rad, Math.min(b.width(), b.height()) / 2f));

        int save = canvas.save();
        try {
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);

            // 1+2. 底：折射（没有着色器就退回裁切）
            boolean ok = refraction(canvas, b, r, bd, owner, press, dispersion, density);
            if (!ok) crop(canvas, b, bd, owner);

            // 3. 表面膜
            surface(canvas, b, luma, press);

            // 4. 高光（45°）
            highlight(canvas, b, r, density, luma);

            // 5. 内阴影
            inner(canvas, b, r, density);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * 一块「照样能看见」的磨砂玻璃。
     *
     * 没有背景可采的时候用它：宁可厚一点、实一点，也绝不能让这块东西
     * 在画面上变得看不见 —— 老版本在这里只糊了一层 8% 的白，
     * 白底页面上玻璃和图标一起消失，看着就是「dock 不见了」。
     *
     * @param luma 底下大致亮度（拿不到就给 128，按暗底处理）
     */
    static void drawFrosted(Canvas canvas, RectF b, float rad, float density, int luma) {
        if (canvas == null || b == null || b.width() <= 0 || b.height() <= 0) return;
        float r = Math.max(0f, Math.min(rad, Math.min(b.width(), b.height()) / 2f));
        boolean light = luma >= 140;

        int save = canvas.save();
        try {
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);

            FILL.setShader(null);
            FILL.setColorFilter(null);
            FILL.setColor(light ? 0xE8EFEFEF : 0xC8181818);
            canvas.drawRect(b, FILL);

            AMBIENT.setShader(new LinearGradient(
                    b.left, b.top, b.right, b.bottom,
                    light ? 0x33FFFFFF : 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(b, AMBIENT);
            AMBIENT.setShader(null);

            RIM.setStyle(Paint.Style.STROKE);
            RIM.setStrokeWidth(Math.max(1f, 0.75f * density));
            RIM.setShader(null);
            RIM.setColor(light ? 0x33FFFFFF : 0x4DFFFFFF);
            RECT.set(b);
            RECT.inset(0.5f * density, 0.5f * density);
            canvas.drawRoundRect(RECT, r, r, RIM);

            inner(canvas, b, r, density);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    // ---------------------------------------------------------------- 折射

    private static boolean refraction(Canvas canvas, RectF b, float r, Backdrop bd,
                                      View owner, float press, boolean dispersion,
                                      float density) {
        if (!HAS_SHADER || bd == null) return false;
        Bitmap bmp = bd.bitmap();
        if (bmp == null || bmp.isRecycled()) return false;
        Rect region = bd.region();
        if (region.isEmpty()) return false;
        return Api33Glass.draw(canvas, b, r, bmp, region, owner, press, dispersion, density);
    }

    private static void crop(Canvas canvas, RectF b, Backdrop bd, View owner) {
        Bitmap bmp = bd == null ? null : bd.bitmap();
        Rect region = bd == null ? null : bd.region();
        if (bmp == null || bmp.isRecycled() || region == null || region.isEmpty()) {
            FILL.setShader(null);
            FILL.setColor(0x14FFFFFF);
            canvas.drawRect(b, FILL);
            return;
        }
        try {
            owner.getLocationInWindow(LOC);
            float kx = bmp.getWidth() / (float) region.width();
            float ky = bmp.getHeight() / (float) region.height();
            int l = clamp((int) ((LOC[0] + b.left - region.left) * kx), 0, bmp.getWidth() - 1);
            int t = clamp((int) ((LOC[1] + b.top - region.top) * ky), 0, bmp.getHeight() - 1);
            int rr = clamp((int) ((LOC[0] + b.right - region.left) * kx), l + 1, bmp.getWidth());
            int bb = clamp((int) ((LOC[1] + b.bottom - region.top) * ky), t + 1, bmp.getHeight());
            SRC.set(l, t, rr, bb);
            FILL.setShader(null);
            FILL.setColorFilter(vibrant());
            canvas.drawBitmap(bmp, SRC, b, FILL);
        } catch (Throwable t) {
            FILL.setShader(null);
            FILL.setColor(0x14FFFFFF);
            canvas.drawRect(b, FILL);
        } finally {
            FILL.setColorFilter(null);
        }
    }

    // ---------------------------------------------------------------- 表面膜

    private static void surface(Canvas canvas, RectF b, int luma, float press) {
        FILL.setShader(null);
        FILL.setColorFilter(null);
        // 暗底提亮，亮底压一点点，让它始终「贴在画面上」而不是贴一张纸
        int base = luma < 118 ? 0x1AFFFFFF : 0x1C000000;
        FILL.setColor(base);
        canvas.drawRect(b, FILL);
        if (press > 0.01f) {
            FILL.setColor(Color.argb((int) (10 * clamp01(press)), 255, 255, 255));
            canvas.drawRect(b, FILL);
        }
    }

    // ---------------------------------------------------------------- 高光

    private static void highlight(Canvas canvas, RectF b, float r, float density, int luma) {
        // 亮底上白边会糊成一片，压一点亮度、补一条极淡的暗边，轮廓才立得住
        boolean light = luma >= 140;
        // 朝光侧泛白：从左上到右下的一条渐变，越靠边越亮
        AMBIENT.setShader(new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                light ? 0x1FFFFFFF : 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(b, AMBIENT);
        AMBIENT.setShader(null);

        // 0.5dp 的细边，左上明显、右下几乎没有
        RIM.setStyle(Paint.Style.STROKE);
        RIM.setStrokeWidth(Math.max(1f, 0.5f * density));
        RIM.setShader(new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                light ? 0x3D000000 : 0x66FFFFFF,
                light ? 0x0A000000 : 0x0DFFFFFF, Shader.TileMode.CLAMP));
        RECT.set(b);
        canvas.drawRoundRect(RECT, r, r, RIM);
        RIM.setShader(null);
    }

    private static void inner(Canvas canvas, RectF b, float r, float density) {
        INNER.setStyle(Paint.Style.STROKE);
        INNER.setStrokeWidth(Math.max(1f, 1f * density));
        INNER.setShader(new LinearGradient(
                b.left, b.bottom, b.right, b.top,
                0x1F000000, 0x00000000, Shader.TileMode.CLAMP));
        RECT.set(b);
        RECT.inset(0.5f * density, 0.5f * density);
        canvas.drawRoundRect(RECT, r, r, INNER);
        INNER.setShader(null);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
