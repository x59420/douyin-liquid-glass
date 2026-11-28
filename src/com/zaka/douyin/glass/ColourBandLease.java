package com.zaka.douyin.glass;

import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.WeakHashMap;

/**
 * 接管抖音自己的「模糊色带」层（思路移植自 DouAi 的 FeedColourBandLease）。
 *
 * 抖音给每条视频生成一张模糊底图，装在一个 FrameLayout 里、用一个无 id 的 ImageView
 * 以 CENTER_CROP 铺开——这就是「画面到不了的地方」本来应该有的颜色。
 *
 * 我们做的事只有两件：
 *   1. 找到这个色带容器，把它自身的绘制关掉（只关这一层，动的是 transitionAlpha，可还原）；
 *   2. 把那张模糊图纵向拉长，铺到屏幕底，让它填满 Dock 上下的空当。
 *
 * 判据全部照抄 DouAi 那套「宁可不做也不误伤」：id 必须没有、不可点、不可聚焦、
 * 无背景、无前景、ScaleType 必须是 CENTER_CROP、drawable 必须是未回收的 BitmapDrawable、
 * 父容器必须是 FrameLayout。任何一条不满足就放弃并还原。
 */
final class ColourBandLease {

    /** 被我们拉长过的色带图：原始 scaleY / pivotY / 容器 alpha。 */
    private static final class Saved {
        float scaleY;
        float pivotY;
        float containerAlpha;
        boolean containerTouched;
    }

    private static final WeakHashMap<View, Saved> TOUCHED = new WeakHashMap<View, Saved>();
    private static View lastContainer = null;
    private static ImageView lastBand = null;
    private static long lastLogAt = 0L;
    private static boolean gaveUp = false;

    private ColourBandLease() {
    }

    /**
     * @param root 窗口根视图
     * @param band 需要被填满的那一条（Dock 所在带子，窗口坐标）
     */
    static void sync(View root, Rect band) {
        if (Build.VERSION.SDK_INT < 29 || root == null || band == null || band.isEmpty()) return;
        if (gaveUp) return;
        try {
            int screenH = root.getHeight();
            if (screenH <= 0) return;
            ImageView found = findBand((ViewGroup) root, screenH, 0);
            if (found == null) {
                restore();
                return;
            }
            View container = found.getParent() instanceof View ? (View) found.getParent() : null;
            apply(found, container, screenH, band);
        } catch (Throwable t) {
            gaveUp = true;
            Dydock.warnOnce("colour-band", "色带接管放弃: " + t);
            restore();
        }
    }

    private static void apply(ImageView bandView, View container, int screenH, Rect band) {
        int[] loc = new int[2];
        bandView.getLocationInWindow(loc);
        int top = loc[1];
        int bottom = top + bandView.getHeight();
        if (bandView.getHeight() <= 0) return;
        // 已经盖到底了就什么都不做（也顺便还原之前的改动）
        if (bottom >= screenH - 1) {
            restore();
            return;
        }
        Saved saved;
        synchronized (TOUCHED) {
            saved = TOUCHED.get(bandView);
            if (saved == null) {
                saved = new Saved();
                saved.scaleY = bandView.getScaleY();
                saved.pivotY = bandView.getPivotY();
                saved.containerAlpha = container == null ? 1f : container.getTransitionAlpha();
                TOUCHED.put(bandView, saved);
            }
        }
        float scale = (screenH - top) / (float) bandView.getHeight();
        if (scale <= 1.0f || scale > 4.0f) {
            restore();
            return;
        }
        if (Math.abs(bandView.getScaleY() - saved.scaleY * scale) > 0.002f) {
            bandView.setPivotY(0f);
            bandView.setScaleY(saved.scaleY * scale);
        }
        // 容器本身那张纯色底会盖住模糊图，关掉它（只动 alpha，可还原）
        if (container != null && container.getTransitionAlpha() > 0f) {
            container.setTransitionAlpha(0f);
            saved.containerTouched = true;
        }
        lastContainer = container;
        lastBand = bandView;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLogAt > 3000L) {
            lastLogAt = now;
            Dydock.log("色带:模糊底图纵向铺满 top=" + top + " bottom=" + bottom
                    + " 屏高=" + screenH + " scale=" + String.format(java.util.Locale.US, "%.2f", scale));
        }
    }

    /** 把改过的东西全部还原（页面切走、判据失败、退出时调用）。 */
    static void restore() {
        synchronized (TOUCHED) {
            for (java.util.Map.Entry<View, Saved> e : TOUCHED.entrySet()) {
                View v = e.getKey();
                Saved s = e.getValue();
                try {
                    if (v != null) {
                        v.setPivotY(s.pivotY);
                        v.setScaleY(s.scaleY);
                    }
                } catch (Throwable ignored) {
                }
            }
            TOUCHED.clear();
        }
        try {
            if (lastContainer != null) lastContainer.setTransitionAlpha(1f);
        } catch (Throwable ignored) {
        }
        lastContainer = null;
        lastBand = null;
    }

    /**
     * 找色带图。判据照抄 DouAi：无 id、可见、不可点/长按/聚焦、无背景与前景、
     * ScaleType=CENTER_CROP、drawable 是未回收的 BitmapDrawable，
     * 且它所在的 FrameLayout 至少盖住屏幕下半部。
     */
    private static ImageView findBand(ViewGroup parent, int screenH, int depth) {
        if (parent == null || depth > 24) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (isOurs(child)) continue;
            if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                if (looksLikeBand(iv) && parent instanceof FrameLayout) {
                    int[] loc = new int[2];
                    parent.getLocationInWindow(loc);
                    int h = parent.getHeight();
                    if (h > screenH * 0.45f) return iv;
                }
            }
            if (child instanceof ViewGroup) {
                ImageView hit = findBand((ViewGroup) child, screenH, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static boolean looksLikeBand(ImageView iv) {
        try {
            if (iv.getId() != View.NO_ID) return false;
            if (iv.isClickable() || iv.isLongClickable() || iv.isFocusable()) return false;
            if (iv.getBackground() != null) return false;
            try {
                if (iv.getForeground() != null) return false;
            } catch (Throwable ignored) {
            }
            if (iv.getScaleType() != ImageView.ScaleType.CENTER_CROP) return false;
            if (iv.getWidth() <= 0 || iv.getHeight() <= 0) return false;
            if (!(iv.getDrawable() instanceof BitmapDrawable)) return false;
            BitmapDrawable d = (BitmapDrawable) iv.getDrawable();
            return d.getBitmap() != null && !d.getBitmap().isRecycled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isOurs(View v) {
        try {
            String n = v.getClass().getName();
            return n.startsWith("com.zaka.") || n.contains("ComposeView") || n.contains("IsoDock");
        } catch (Throwable t) {
            return false;
        }
    }
}
