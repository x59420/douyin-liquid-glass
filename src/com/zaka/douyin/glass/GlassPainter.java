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
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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

    static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHADOW_FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHADOW_CLEAR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static RenderNode outerShadowNode;
    private static RenderNode innerShadowNode;
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
     * 表面颜色只由系统明暗主题决定，不读取视频画面亮度
     */
    static void draw(Canvas canvas, RectF b, float rad, Backdrop bd, View owner,
                     float press, boolean dispersion, int luma, float density) {
        drawInternal(canvas, b, rad, bd, owner, press, dispersion, luma, density, false);
    }

    static void drawLens(Canvas canvas, RectF b, float rad, Backdrop bd, View owner,
                         float press, int luma, float density) {
        drawInternal(canvas, b, rad, bd, owner, press, press > 0.001f,
                luma, density, true);
    }

    /** Selected lens over the combined background + tab-icon layer. */
    static void drawLens(Canvas canvas, RectF b, float rad, Bitmap combined,
                         Rect region, Backdrop bd, View owner,
                         float press, int luma, float density) {
        if (combined == null || combined.isRecycled() || region == null || region.isEmpty()
                || bd == null || bd.bitmap() == null) {
            drawLens(canvas, b, rad, bd, owner, press, luma, density);
            return;
        }
        if (canvas == null || b == null || b.width() <= 0 || b.height() <= 0) return;
        float r = Math.max(0f, Math.min(rad, Math.min(b.width(), b.height()) / 2f));
        float progress = clamp01(press);
        int save = canvas.save();
        try {
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);
            Bitmap source = bd.bitmap();
            boolean ok = android.os.Build.VERSION.SDK_INT >= 33
                    && Api33Glass.draw(canvas, b, r, source, region, bd, owner,
                            progress, progress > 0.001f, density, true, combined);
            if (!ok) cropBitmap(canvas, b, source, region, owner);
            surface(canvas, b, isDarkTheme(owner), progress, true);
            highlight(canvas, b, r, density, isDarkTheme(owner), progress);
            inner(canvas, b, r, density, progress);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void cropBitmap(Canvas canvas, RectF b, Bitmap bitmap,
                                   Rect region, View owner) {
        try {
            owner.getLocationInWindow(LOC);
            float kx = bitmap.getWidth() / (float) region.width();
            float ky = bitmap.getHeight() / (float) region.height();
            int l = clamp((int) ((LOC[0] + b.left - region.left) * kx), 0, bitmap.getWidth() - 1);
            int t = clamp((int) ((LOC[1] + b.top - region.top) * ky), 0, bitmap.getHeight() - 1);
            int rr = clamp((int) ((LOC[0] + b.right - region.left) * kx), l + 1, bitmap.getWidth());
            int bb = clamp((int) ((LOC[1] + b.bottom - region.top) * ky), t + 1, bitmap.getHeight());
            SRC.set(l, t, rr, bb);
            FILL.setShader(null);
            FILL.setColorFilter(vibrant());
            canvas.drawBitmap(bitmap, SRC, b, FILL);
        } catch (Throwable ignored) {
            FILL.setShader(null);
            FILL.setColorFilter(null);
            FILL.setColor(0x14FFFFFF);
            canvas.drawRect(b, FILL);
        } finally {
            FILL.setColorFilter(null);
        }
    }

    private static void drawInternal(Canvas canvas, RectF b, float rad, Backdrop bd,
                                     View owner, float press, boolean dispersion,
                                     int luma, float density, boolean selectedLens) {
        if (canvas == null || b == null || b.width() <= 0 || b.height() <= 0) return;
        float r = Math.max(0f, Math.min(rad, Math.min(b.width(), b.height()) / 2f));
        float progress = clamp01(press);
        int save = canvas.save();
        try {
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);
            boolean ok = refraction(canvas, b, r, bd, owner, progress,
                    dispersion, density, selectedLens);
            if (!ok) crop(canvas, b, bd, owner);
            surface(canvas, b, isDarkTheme(owner), progress, selectedLens);
            highlight(canvas, b, r, density, isDarkTheme(owner), selectedLens ? progress : 0f);
            inner(canvas, b, r, density, selectedLens ? progress : 0f);
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
    static void drawFrosted(Canvas canvas, RectF b, float rad, float density, View owner) {
        if (canvas == null || b == null || b.width() <= 0 || b.height() <= 0) return;
        float r = Math.max(0f, Math.min(rad, Math.min(b.width(), b.height()) / 2f));
        boolean dark = isDarkTheme(owner);

        int save = canvas.save();
        try {
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);

            FILL.setShader(null);
            FILL.setColorFilter(null);
            // 无法取得实时采样时也不能用近乎不透明的底色盖住抖音画面。
            // 保留可见的磨砂质感，同时让底下视频明显透出。
            FILL.setColor(dark ? 0x702C2C2E : 0x70F2F2F7);
            canvas.drawRect(b, FILL);

            AMBIENT.setShader(new LinearGradient(
                    b.left, b.top, b.right, b.bottom,
                    0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(b, AMBIENT);
            AMBIENT.setShader(null);

            RIM.setStyle(Paint.Style.STROKE);
            RIM.setStrokeWidth(Math.max(1f, 0.75f * density));
            RIM.setShader(null);
            RIM.setColor(0x33FFFFFF);
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
                                      float density, boolean selectedLens) {
        if (!HAS_SHADER || bd == null) return false;
        Bitmap bmp = bd.bitmap();
        if (bmp == null || bmp.isRecycled()) return false;
        Rect region = bd.region();
        if (region.isEmpty()) return false;
        return Api33Glass.draw(canvas, b, r, bmp, region, bd, owner, press, dispersion,
                density, selectedLens, null);
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

    private static void surface(Canvas canvas, RectF b, boolean dark, float press, boolean selectedLens) {
        FILL.setShader(null);
        FILL.setColorFilter(null);
        float p = clamp01(press);
        if (selectedLens) {
            int tint = dark ? Color.WHITE : Color.BLACK;
            FILL.setColor(Color.argb(Math.round(26f * (1f - p)),
                    Color.red(tint), Color.green(tint), Color.blue(tint)));
            canvas.drawRect(b, FILL);
            FILL.setColor(Color.argb(Math.round(7.65f * p), 0, 0, 0));
            canvas.drawRect(b, FILL);
        } else {
            FILL.setColor(dark ? 0x662C2C2E : 0x66F2F2F7);
            canvas.drawRect(b, FILL);
        }
    }

    // ---------------------------------------------------------------- 高光

    private static void highlight(Canvas canvas, RectF b, float r, float density,
                                  boolean dark, float press) {
        AMBIENT.setShader(new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(b, AMBIENT);
        AMBIENT.setShader(null);

        RIM.setStyle(Paint.Style.STROKE);
        RIM.setStrokeWidth(Math.max(1f, 0.5f * density));
        RIM.setShader(new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                0x33FFFFFF,
                0x0DFFFFFF, Shader.TileMode.CLAMP));
        RECT.set(b);
        canvas.drawRoundRect(RECT, r, r, RIM);
        RIM.setShader(null);

        // Subtle opposing specular reflection, activated by the pressed lens.
        if (press > 0.001f) {
            int alpha = Math.round(31f * clamp01(press));
            AMBIENT.setShader(new LinearGradient(
                    b.right, b.bottom, b.left, b.top,
                    Color.argb(alpha, 255, 255, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(b, AMBIENT);
            AMBIENT.setShader(null);
        }
    }

    private static void inner(Canvas canvas, RectF b, float r, float density) {
        inner(canvas, b, r, density, 0f);
    }

    /** KernelSU InnerShadow: blurred black outline with a translated CLEAR cutout. */
    private static void inner(Canvas canvas, RectF b, float r, float density, float press) {
        float p = clamp01(press);
        if (p <= 0.001f || android.os.Build.VERSION.SDK_INT < 31
                || !canvas.isHardwareAccelerated()) return;
        float blur = 8f * density * p;
        if (blur <= 0.1f) return;
        try {
            int w = Math.max(1, (int) Math.ceil(b.width()));
            int h = Math.max(1, (int) Math.ceil(b.height()));
            if (innerShadowNode == null) innerShadowNode = new RenderNode("KernelSUInnerShadow");
            innerShadowNode.setPosition(0, 0, w, h);
            innerShadowNode.setAlpha(p);
            RecordingCanvas recording = innerShadowNode.beginRecording(w, h);
            RectF shape = new RectF(0f, 0f, b.width(), b.height());
            SHADOW_FILL.setStyle(Paint.Style.FILL);
            SHADOW_FILL.setShader(null);
            SHADOW_FILL.setXfermode(null);
            SHADOW_FILL.setColor(0x26000000);
            SHADOW_CLEAR.setStyle(Paint.Style.FILL);
            SHADOW_CLEAR.setShader(null);
            SHADOW_CLEAR.setColor(android.graphics.Color.WHITE);
            SHADOW_CLEAR.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            recording.drawRoundRect(shape, r, r, SHADOW_FILL);
            recording.save();
            recording.translate(0f, blur);
            recording.drawRoundRect(shape, r, r, SHADOW_CLEAR);
            recording.restore();
            innerShadowNode.endRecording();
            innerShadowNode.setRenderEffect(RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.DECAL));
            int save = canvas.save();
            PATH.reset();
            PATH.addRoundRect(b, r, r, Path.Direction.CW);
            canvas.clipPath(PATH);
            canvas.translate(b.left, b.top);
            canvas.drawRenderNode(innerShadowNode);
            canvas.restoreToCount(save);
        } catch (Throwable t) {
            Dydock.warnOnce("inner-shadow", "KernelSU 内阴影绘制失败: " + t);
        } finally {
            SHADOW_CLEAR.setXfermode(null);
        }
    }

    /** KernelSU BottomBarMiuix outer dropShadow(radius=10dp). */
    static void drawOuterShadow(Canvas canvas, RectF b, float r, float density,
                                boolean dark, int entranceAlpha) {
        if (entranceAlpha <= 0 || android.os.Build.VERSION.SDK_INT < 31
                || !canvas.isHardwareAccelerated()) return;
        float blur = 10f * density;
        int pad = Math.max(1, (int) Math.ceil(blur * 2f));
        int w = Math.max(1, (int) Math.ceil(b.width()) + pad * 2);
        int h = Math.max(1, (int) Math.ceil(b.height()) + pad * 2);
        try {
            if (outerShadowNode == null) outerShadowNode = new RenderNode("KernelSUOuterShadow");
            outerShadowNode.setPosition(0, 0, w, h);
            outerShadowNode.setAlpha((dark ? 0.2f : 0.1f) * (entranceAlpha / 255f));
            RecordingCanvas recording = outerShadowNode.beginRecording(w, h);
            SHADOW_FILL.setStyle(Paint.Style.FILL);
            SHADOW_FILL.setShader(null);
            SHADOW_FILL.setXfermode(null);
            SHADOW_FILL.setColor(android.graphics.Color.BLACK);
            RectF shape = new RectF(pad, pad, pad + b.width(), pad + b.height());
            recording.drawRoundRect(shape, r, r, SHADOW_FILL);
            outerShadowNode.endRecording();
            outerShadowNode.setRenderEffect(RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.DECAL));
            int save = canvas.save();
            canvas.translate(b.left - pad, b.top - pad);
            canvas.drawRenderNode(outerShadowNode);
            canvas.restoreToCount(save);
        } catch (Throwable t) {
            Dydock.warnOnce("outer-shadow", "KernelSU 外阴影绘制失败: " + t);
        }
    }

    private static boolean isDarkTheme(View owner) {
        int uiMode = owner == null
                ? android.content.res.Resources.getSystem().getConfiguration().uiMode
                : owner.getResources().getConfiguration().uiMode;
        return (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
