package com.zaka.douyin.glass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 一块「当背景用」的悬浮玻璃。
 *
 * 顶栏那几个控件不动它们的层级、不动布局：只是把原来那块底色换成这个
 * drawable。文字/图标还是官方原样画在它上面，所以字不会糊、点击也不受影响。
 *
 * 采集背景时会把这类 drawable 临时静音 —— 否则它会被画进自己的背景里，
 * 一层层糊成一圈光晕。
 */
final class GlassStripDrawable extends Drawable {

    private static int suppressed;

    static void suppress(boolean on) {
        if (on) {
            suppressed++;
        } else if (suppressed > 0) {
            suppressed--;
        }
    }

    private final View owner;
    private final Backdrop backdrop;
    private final float density;
    private final float radiusDp;
    private final float insetDp;

    private final RectF box = new RectF();
    /** 只画 owner 里的这一块（相对 owner 左上角）——「几个控件摊在一行上」时合成一整块用 */
    private RectF window;

    GlassStripDrawable(View owner, Backdrop backdrop, float density, float radiusDp, float insetDp) {
        this.owner = owner;
        this.backdrop = backdrop;
        this.density = density;
        this.radiusDp = radiusDp;
        this.insetDp = insetDp;
    }

    void setWindow(RectF r) {
        window = r == null ? null : new RectF(r);
    }

    @Override
    public void draw(Canvas canvas) {
        if (suppressed > 0) return;
        View v = owner;
        // 宿主把这个控件藏起来 / 挪出屏幕 / 缩成 0 的时候，玻璃不能还浮在那儿画
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float pad = insetDp * density;
        if (window != null) {
            box.set(window);
        } else {
            box.set(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad);
        }
        if (box.width() <= 0 || box.height() <= 0) return;
        if (v.getAlpha() < 0.05f) return;

        float rad = Math.min(radiusDp * density, box.height() / 2f);
        try {
            if (backdrop != null && backdrop.hasFrame()) {
                GlassPainter.draw(canvas, box, rad, backdrop, owner, 0f, false,
                        backdrop.luma(), density);
            } else {
                GlassPainter.drawFrosted(canvas, box, rad, density, 118);
            }
        } catch (Throwable t) {
            Dydock.warnOnce("strip", "顶栏玻璃绘制失败: " + t);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
