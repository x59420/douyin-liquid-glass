package com.zaka.douyin.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/** API 33+ RuntimeShader adapter. Loaded only after the SDK guard succeeds. */
final class Api33Glass {
    private static RuntimeShader refract;
    private static RuntimeShader disperse;
    private static RuntimeShader blurHorizontal;
    private static RuntimeShader blurVertical;

    private Api33Glass() {
    }

    static boolean draw(Canvas canvas, RectF bounds, float radius, Bitmap bitmap,
                        Rect region, View owner, float press, boolean dispersion,
                        float density, boolean selectedLens) {
        try {
            int[] location = GlassPainter.LOC;
            owner.getLocationInWindow(location);
            float sx = bitmap.getWidth() / (float) region.width();
            float sy = bitmap.getHeight() / (float) region.height();

            BitmapShader content = new BitmapShader(
                    bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            android.graphics.Matrix matrix = GlassPainter.MAT;
            matrix.reset();
            matrix.setScale(sx, sy);
            matrix.postTranslate((location[0] - region.left) * sx,
                    (location[1] - region.top) * sy);
            content.setLocalMatrix(matrix);

            // Miuix order: vibrancy -> 4dp Gaussian blur -> rounded-rect lens.
            // The captured bitmap is already recorded at Miuix's adaptive downscale level.
            Shader blurred = gaussianBlur(content, bitmap, bdSigma(bd), bdScale(bd));
            RuntimeShader shader = shader(dispersion);
            shader.setInputShader("content", blurred);
            shader.setFloatUniform("size", bounds.width(), bounds.height());
            shader.setFloatUniform("offset", -bounds.left, -bounds.top);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);

            float minSide = Math.min(bounds.width(), bounds.height());
            float progress = GlassPainter.clamp01(press);
            float lensHeight = selectedLens
                    ? 10f * density * progress
                    : Math.min(24f * density, minSide * 0.42f);
            float lensAmount = selectedLens
                    ? 14f * density * progress
                    : Math.min(24f * density, minSide * 0.46f);
            shader.setFloatUniform("refractionHeight", Math.max(0.001f, lensHeight));
            shader.setFloatUniform("refractionAmount", -Math.max(0.001f, lensAmount));
            shader.setFloatUniform("depthEffect", selectedLens ? progress : 0f);
            if (dispersion && selectedLens) {
                shader.setFloatUniform("chromaticAberration", 0.5f);
            }

            GlassPainter.FILL.setShader(shader);
            GlassPainter.FILL.setColorFilter(GlassPainter.vibrant());
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    GlassPainter.FILL);
            return true;
        } catch (Throwable t) {
            Dydock.warnOnce("shader", "折射着色器失败，退回普通裁切: " + t);
            return false;
        } finally {
            GlassPainter.FILL.setShader(null);
            GlassPainter.FILL.setColorFilter(null);
        }
    }

    private static float bdSigma(Backdrop backdrop) {
        return backdrop.blurSigmaInTexels();
    }

    private static float bdScale(Backdrop backdrop) {
        return backdrop.downscaleFactor();
    }

    private static Shader gaussianBlur(Shader source, Bitmap bitmap, float sigma, float scale) {
        if (blurHorizontal == null) {
            blurHorizontal = new RuntimeShader(GlassShader.GAUSSIAN_BLUR);
        }
        if (blurVertical == null) {
            blurVertical = new RuntimeShader(GlassShader.GAUSSIAN_BLUR);
        }
        // One texel in the downscaled backdrop corresponds to 'scale' screen pixels.
        blurHorizontal.setInputShader("content", source);
        blurHorizontal.setFloatUniform("direction", scale, 0f);
        blurHorizontal.setFloatUniform("sigma", sigma);
        blurVertical.setInputShader("content", blurHorizontal);
        blurVertical.setFloatUniform("direction", 0f, scale);
        blurVertical.setFloatUniform("sigma", sigma);
        return blurVertical;
    }

    private static RuntimeShader shader(boolean dispersion) {
        if (dispersion) {
            if (disperse == null) {
                disperse = new RuntimeShader(GlassShader.REFRACTION_DISPERSION);
            }
            return disperse;
        }
        if (refract == null) refract = new RuntimeShader(GlassShader.REFRACTION);
        return refract;
    }
}
