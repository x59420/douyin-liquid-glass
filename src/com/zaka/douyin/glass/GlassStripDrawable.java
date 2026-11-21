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
 *
 * 0.0.5 加了两道锁，专治「玻璃跑到 dock 那儿去了」：
 *  1. **分区**：顶栏的玻璃只允许在屏幕上四分之一以内画，底部行的只允许在下四分之一。
 *     宿主换页时会把同一批控件搬去别处（搜索页、详情页都是这么复用的），
 *     位置一越界就直接不画，绝不会在底栏那儿冒出一块顶栏的玻璃。
 *  2. **裁到自己的范围**：画之前 clip 到 owner 的 bounds。
 *     以前那块「整行玻璃」是按行坐标算的，行一变高变矮就会漏到行外面去。
 */
final class GlassStripDrawable extends Drawable {

    static final int ZONE_ANY = 0;
    static final int ZONE_TOP = 1;
    static final int ZONE_BOTTOM = 2;

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
    /** 左右各再缩这么多，让相邻的两块之间留出空隙 */
    private final float insetXDp;

    private final RectF box = new RectF();
    /** 只画 owner 里的这一块（相对 owner 左上角）——「几个控件摊在一行上」时合成一整块用 */
    private RectF window;

    private int zone = ZONE_ANY;
    private final int[] loc = new int[2];

    GlassStripDrawable(View owner, Backdrop backdrop, float density, float radiusDp, float insetDp) {
        this(owner, backdrop, density, radiusDp, insetDp, 0f);
    }

    GlassStripDrawable(View owner, Backdrop backdrop, float density, float radiusDp,
                       float insetDp, float insetXDp) {
        this.owner = owner;
        this.backdrop = backdrop;
        this.density = density;
        this.radiusDp = radiusDp;
        this.insetDp = insetDp;
        this.insetXDp = insetXDp;
    }

    void setWindow(RectF r) {
        window = r == null ? null : new RectF(r);
    }

    void setZone(int z) {
        zone = z;
    }

    /** owner 现在的窗口位置还落在它该在的那一段屏幕上吗 */
    private boolean inZone(View v) {
        if (zone == ZONE_ANY) return true;
        int vh = v.getRootView() == null ? 0 : v.getRootView().getHeight();
        if (vh <= 0) return true;
        v.getLocationInWindow(loc);
        float top = loc[1];
        float bottom = loc[1] + v.getHeight();
        if (zone == ZONE_TOP) return bottom <= vh * 0.34f;
        return top >= vh * 0.55f;
    }

    @Override
    public void draw(Canvas canvas) {
        if (suppressed > 0) return;
        View v = owner;
        // 宿主把这个控件藏起来 / 挪出屏幕 / 缩成 0 的时候，玻璃不能还浮在那儿画
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        if (!inZone(v)) return;
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        float padY = insetDp * density;
        float padX = (insetDp + insetXDp) * density;
        if (window != null) {
            box.set(window);
        } else {
            box.set(b.left + padX, b.top + padY, b.right - padX, b.bottom - padY);
        }
        if (box.width() <= 1f || box.height() <= 1f) return;
        if (v.getAlpha() < 0.05f) return;

        float rad = Math.min(radiusDp * density, box.height() / 2f);
        int save = canvas.save();
        try {
            // 绝不画到自己这块控件以外 —— 行一改尺寸，玻璃也不能漏到别处去
            canvas.clipRect(b);
            if (backdrop != null && backdrop.hasFrame()) {
                GlassPainter.draw(canvas, box, rad, backdrop, owner, 0f, false,
                        backdrop.luma(), density);
            } else {
                GlassPainter.drawFrosted(canvas, box, rad, density, 118);
            }
        } catch (Throwable t) {
            Dydock.warnOnce("strip", "顶栏玻璃绘制失败: " + t);
        } finally {
            canvas.restoreToCount(save);
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
