package com.zaka.douyin.glass;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.graphics.RectF;

/** Java rendering port of KernelSU's InteractiveHighlight. */
final class InteractiveHighlight {
    private static final String SHADER_SOURCE =
            "uniform float2 size;\n" +
            "layout(color) uniform half4 color;\n" +
            "uniform float radius;\n" +
            "uniform float2 position;\n" +
            "half4 main(float2 coord) {\n" +
            "    float dist = distance(coord, position);\n" +
            "    float intensity = smoothstep(radius, radius * 0.5, dist);\n" +
            "    return color * intensity;\n" +
            "}";

    private final Channel progress = new Channel(0f, Math.sqrt(300.0), 0.5, 0.001f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeShader shader;
    private float downX;
    private float downY;
    private float pointerX;
    private float pointerY;

    void press(float x, float y) {
        downX = pointerX = x;
        downY = pointerY = y;
        progress.target = 1f;
    }

    void move(float x, float y) {
        pointerX = x;
        pointerY = y;
    }

    void release() {
        progress.target = 0f;
    }

    boolean advance(float seconds) {
        progress.advance(seconds);
        return progress.moving();
    }

    void draw(Canvas canvas, RectF bounds, float focusX, float focusY) {
        float p = Math.max(0f, Math.min(1f, progress.value));
        if (p <= 0.001f || bounds == null || bounds.isEmpty()) return;

        int save = canvas.save();
        try {
            canvas.translate(bounds.left, bounds.top);
            float w = bounds.width();
            float h = bounds.height();

            setPlusBlend();
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(255f * 0.06f * p));
            canvas.drawRect(0f, 0f, w, h, paint);

            int color = android.graphics.Color.argb(
                    Math.round(255f * 0.12f * p), 255, 255, 255);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    if (shader == null) shader = new RuntimeShader(SHADER_SOURCE);
                    shader.setFloatUniform("size", w, h);
                    shader.setColorUniform("color", color);
                    shader.setFloatUniform("radius", Math.min(w, h) * 1.2f);
                    shader.setFloatUniform("position", focusX - bounds.left, focusY - bounds.top);
                    paint.setAlpha(255);
                    paint.setShader(shader);
                } catch (Throwable ignored) {
                    paint.setShader(fallback(w, h, focusX - bounds.left, focusY - bounds.top,
                            Math.min(w, h) * 1.2f, color));
                }
            } else {
                paint.setAlpha(255);
                paint.setShader(fallback(w, h, focusX - bounds.left, focusY - bounds.top,
                        Math.min(w, h) * 1.2f, color));
            }
            canvas.drawRect(0f, 0f, w, h, paint);
        } finally {
            paint.setShader(null);
            paint.setAlpha(255);
            clearBlend();
            canvas.restoreToCount(save);
        }
    }

    private Shader fallback(float w, float h, float x, float y, float radius, int color) {
        return new RadialGradient(x, y, Math.max(1f, radius),
                new int[]{color, android.graphics.Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    private void setPlusBlend() {
        if (Build.VERSION.SDK_INT >= 29) {
            paint.setBlendMode(BlendMode.PLUS);
        } else {
            paint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.ADD));
        }
    }

    private void clearBlend() {
        if (Build.VERSION.SDK_INT >= 29) paint.setBlendMode(null);
        else paint.setXfermode(null);
    }

    private static final class Channel {
        float value;
        float target;
        float velocity;
        final double frequency;
        final double damping;
        final float threshold;

        Channel(float initial, double frequency, double damping, float threshold) {
            value = target = initial;
            this.frequency = frequency;
            this.damping = damping;
            this.threshold = threshold;
        }

        boolean moving() {
            return Math.abs(value - target) > threshold
                    || Math.abs(velocity) > threshold * 62.5f;
        }

        void advance(float seconds) {
            if (!moving()) {
                value = target;
                velocity = 0f;
                return;
            }
            double offset = value - target;
            double speed = velocity;
            double decay = Math.exp(-damping * frequency * seconds);
            if (damping == 1.0) {
                double slope = speed + frequency * offset;
                value = (float) (target + decay * (offset + slope * seconds));
                velocity = (float) (decay * (speed - frequency * slope * seconds));
            } else {
                double angular = frequency * Math.sqrt(Math.max(0.0, 1.0 - damping * damping));
                double sine = Math.sin(angular * seconds);
                double cosine = Math.cos(angular * seconds);
                double slope = (speed + damping * frequency * offset) / angular;
                double wave = offset * cosine + slope * sine;
                value = (float) (target + decay * wave);
                velocity = (float) (decay * (angular * (slope * cosine - offset * sine)
                        - damping * frequency * wave));
            }
            if (!moving()) {
                value = target;
                velocity = 0f;
            }
        }
    }
}
