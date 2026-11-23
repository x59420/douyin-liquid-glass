package com.zaka.douyin.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 * API 33+ GPU implementation of KernelSU's Miuix backdrop chain:
 * vibrancy -> 4dp Gaussian blur -> rounded-rectangle SDF lens.
 *
 * The sampled backdrop is recorded into a RenderNode; RenderEffect performs
 * the blur on the GPU instead of pre-blurring a bitmap on the CPU.
 */
final class Api33Glass {
    private static RuntimeShader refract;
    private static RuntimeShader disperse;
    private static RenderEffect vibrancy;

    private static final RenderNode panelNode = new RenderNode("dydock-panel-backdrop");
    private static final RenderNode lensBackgroundNode = new RenderNode("dydock-selected-lens-background");
    private static final RenderNode lensNode = new RenderNode("dydock-selected-lens-backdrop");
    private static final Paint capturePaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Rect dst = new Rect();

    private Api33Glass() {
    }

    static boolean draw(Canvas canvas, RectF bounds, float radius, Bitmap bitmap,
                        Rect region, Backdrop backdrop, View owner, float press,
                        boolean dispersion, float density, boolean selectedLens,
                        Bitmap foregroundLayer) {
        if (canvas == null || !canvas.isHardwareAccelerated()
                || bitmap == null || bitmap.isRecycled()
                || region == null || region.isEmpty() || backdrop == null) {
            return false;
        }
        try {
            int[] location = GlassPainter.LOC;
            owner.getLocationInWindow(location);

            // KernelSU explicitly keeps at least 40dp of backdrop around the bar;
            // Lens.kt raises padding to at least its 24dp refraction amount.
            int pad = Math.round(40f * density);
            int nodeW = Math.max(1, Math.round(bounds.width()) + pad * 2);
            int nodeH = Math.max(1, Math.round(bounds.height()) + pad * 2);
            RenderNode node = selectedLens ? lensNode : panelNode;
            node.setPosition(0, 0, nodeW, nodeH);

            float sx = bitmap.getWidth() / (float) region.width();
            float sy = bitmap.getHeight() / (float) region.height();
            int sl = clamp((int) Math.floor((location[0] + bounds.left - pad - region.left) * sx),
                    0, bitmap.getWidth() - 1);
            int st = clamp((int) Math.floor((location[1] + bounds.top - pad - region.top) * sy),
                    0, bitmap.getHeight() - 1);
            int sr = clamp((int) Math.ceil((location[0] + bounds.right + pad - region.left) * sx),
                    sl + 1, bitmap.getWidth());
            int sb = clamp((int) Math.ceil((location[1] + bounds.bottom + pad - region.top) * sy),
                    st + 1, bitmap.getHeight());
            Rect src = new Rect(sl, st, sr, sb);
            dst.set(0, 0, nodeW, nodeH);

            RuntimeShader shader = shader(dispersion && selectedLens);
            RecordingCanvas recording;
            if (selectedLens) {
                // KernelSU's combinedBackdrop is the already-blurred main backdrop
                // plus a separate tab layer. Blur the background first, then place
                // the tab layer above it, and apply only the selected lens afterward.
                lensBackgroundNode.setPosition(0, 0, nodeW, nodeH);
                recording = lensBackgroundNode.beginRecording(nodeW, nodeH);
                try {
                    recording.drawBitmap(bitmap, src, dst, capturePaint);
                } finally {
                    lensBackgroundNode.endRecording();
                }
                if (vibrancy == null) {
                    vibrancy = RenderEffect.createColorFilterEffect(GlassPainter.vibrant());
                }
                float baseBlurSigma = 4f * density * 0.45f;
                RenderEffect baseBlur = RenderEffect.createBlurEffect(
                        baseBlurSigma, baseBlurSigma, vibrancy, Shader.TileMode.CLAMP);
                lensBackgroundNode.setRenderEffect(baseBlur);

                recording = node.beginRecording(nodeW, nodeH);
                try {
                    recording.drawRenderNode(lensBackgroundNode);
                    if (foregroundLayer != null && !foregroundLayer.isRecycled()) {
                        recording.drawBitmap(foregroundLayer, src, dst, capturePaint);
                    }
                } finally {
                    node.endRecording();
                }
            } else {
                recording = node.beginRecording(nodeW, nodeH);
                try {
                    recording.drawBitmap(bitmap, src, dst, capturePaint);
                } finally {
                    node.endRecording();
                }
            }
            float progress = GlassPainter.clamp01(press);
            shader.setFloatUniform("size", bounds.width(), bounds.height());
            shader.setFloatUniform("offset", -pad, -pad);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);

            float refractionHeight = selectedLens
                    ? 10f * density * progress
                    : 24f * density;
            float refractionAmount = selectedLens
                    ? 14f * density * progress
                    : 24f * density;
            shader.setFloatUniform("refractionHeight", Math.max(0.001f, refractionHeight));
            // KernelSU's Lens.kt negates the refraction amount before setting the uniform.
            shader.setFloatUniform("refractionAmount", -Math.max(0.001f, refractionAmount));
            shader.setFloatUniform("depthEffect", selectedLens ? progress : 0f);
            if (dispersion && selectedLens) {
                shader.setFloatUniform("chromaticAberration", 0.5f);
            }

            RenderEffect lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
            if (selectedLens) {
                node.setRenderEffect(lens);
            } else {
                if (vibrancy == null) {
                    vibrancy = RenderEffect.createColorFilterEffect(GlassPainter.vibrant());
                }
                // Miuix blur(radius=4dp) converts radius to sigma with 0.45f.
                float blurSigma = 4f * density * 0.45f;
                RenderEffect blur = RenderEffect.createBlurEffect(
                        blurSigma, blurSigma, vibrancy, Shader.TileMode.CLAMP);
                node.setRenderEffect(RenderEffect.createChainEffect(lens, blur));
            }

            int save = canvas.save();
            try {
                canvas.translate(bounds.left - pad, bounds.top - pad);
                canvas.drawRenderNode(node);
            } finally {
                canvas.restoreToCount(save);
            }
            return true;
        } catch (Throwable t) {
            Dydock.warnOnce("shader", "Miuix GPU 背景链失败，退回普通裁切: " + t);
            return false;
        }
    }

    private static RuntimeShader shader(boolean dispersion) {
        if (dispersion) {
            if (disperse == null) {
                disperse = new RuntimeShader(GlassShader.REFRACTION_DISPERSION);
            }
            return disperse;
        }
        if (refract == null) {
            refract = new RuntimeShader(GlassShader.REFRACTION);
        }
        return refract;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
