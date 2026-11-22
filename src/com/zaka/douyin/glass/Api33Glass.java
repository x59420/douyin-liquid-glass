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

    private Api33Glass() {
    }

    static boolean draw(Canvas canvas, RectF bounds, float radius, Bitmap bitmap,
                        Rect region, View owner, float press, boolean dispersion,
                        float density) {
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

            RuntimeShader shader = shader(dispersion);
            shader.setInputShader("content", content);
            shader.setFloatUniform("size", bounds.width(), bounds.height());
            shader.setFloatUniform("offset", -bounds.left, -bounds.top);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);

            float minSide = Math.min(bounds.width(), bounds.height());
            float lensHeight = Math.min(24f * density, minSide * 0.42f);
            float lensAmount = Math.min(26f * density, minSide * 0.46f);
            shader.setFloatUniform("refractionHeight", Math.max(1f, lensHeight));
            shader.setFloatUniform("refractionAmount", -Math.max(1f, lensAmount));
            shader.setFloatUniform("depthEffect", 0.6f * GlassPainter.clamp01(press));
            if (dispersion) shader.setFloatUniform("chromaticAberration", 1f);

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
