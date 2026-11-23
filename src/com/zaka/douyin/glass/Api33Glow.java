package com.zaka.douyin.glass;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.os.Build;

/** API 33 implementation of the Tieba press-glow spot shader. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class Api33Glow {
    private static RuntimeShader shader;

    private Api33Glow() {}

    static void draw(Canvas canvas, RectF bounds, float progress, float touchX, Paint paint) {
        if (Build.VERSION.SDK_INT < 33 || progress <= 0f) return;
        RuntimeShader current = shader;
        if (current == null) {
            current = new RuntimeShader(GlassShader.GLOW);
            shader = current;
        }
        float width = bounds.width();
        float height = bounds.height();
        current.setColorUniform("color", android.graphics.Color.argb(
                Math.max(0, Math.min(255, Math.round(255f * 0.12f * progress))),
                255, 255, 255));
        current.setFloatUniform("radius", Math.min(width, height) * 1.2f);
        current.setFloatUniform("position",
                Math.max(0f, Math.min(width, touchX)), height / 2f);
        int save = canvas.save();
        try {
            canvas.translate(bounds.left, bounds.top);
            paint.setShader(current);
            paint.setAlpha(255);
            canvas.drawRect(0f, 0f, width, height, paint);
        } finally {
            paint.setShader(null);
            canvas.restoreToCount(save);
        }
    }
}
