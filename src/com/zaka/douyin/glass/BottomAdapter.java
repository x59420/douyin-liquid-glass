package com.zaka.douyin.glass;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 底部布局接管（思路来自 PDDLG 的 LiquidGlassInstaller）。
 *
 * 只做三件事，全部「原地改 + 存原值」：
 *   1. stretchToBottom   把页面容器撑到父容器底（bottomMargin 归零、固定高度改 MATCH_PARENT）
 *   2. dropNavReserve    把底栏的 paddingBottom 归零
 *   3. dropBottomFrost   反射把宿主的「底部毛玻璃带」高度设成 0
 *
 * 判据：改动幅度小于 8px 一律不动（避免每帧反复 set），LayoutParams 不换对象类型。
 */
final class BottomAdapter {

    /** 页面容器原值：{height, topMargin, bottomMargin}。 */
    private static final WeakHashMap<View, int[]> STRETCHED = new WeakHashMap<View, int[]>();
    /** paddingBottom 原值。 */
    private static final WeakHashMap<View, Integer> PADDED = new WeakHashMap<View, Integer>();
    /** 反射方法缓存：Class -> [getter, setter]；找不到的记进 NO_FROST。 */
    private static final Map<Class<?>, Method[]> FROST_METHODS = new HashMap<>();
    private static final HashSet<Class<?>> NO_FROST = new HashSet<>();
    /** 已经关掉毛玻璃带的 View（避免重复反射）。 */
    private static final HashSet<View> FROST_DROPPED = new HashSet<>();

    private BottomAdapter() {
    }

    /**
     * 把 view 撑到父容器底部。
     *
     * @param parentHeight 父容器高度；传 0 表示用 view 自己父容器的高度
     */
    static void stretchToBottom(View view, int parentHeight) {
        if (view == null) return;
        try {
            int target = parentHeight;
            if (target <= 0) {
                if (!(view.getParent() instanceof View)) return;
                target = ((View) view.getParent()).getHeight();
            }
            if (target <= 0) return;
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            int bottom = loc[1] + view.getHeight();
            int gap = target - bottom;
            if (gap <= 8) return;   // 8px 容差：够近就不动，避免每帧抖动
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            boolean changed = false;
            if (mlp.bottomMargin != 0) {
                saveStretched(view, mlp);
                mlp.bottomMargin = 0;
                changed = true;
            }
            if (mlp.height >= 0) {      // 只把「固定高度」改成 MATCH_PARENT
                saveStretched(view, mlp);
                mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                changed = true;
            }
            if (changed) {
                view.setLayoutParams(mlp);
                Dydock.log("接管:页面撑到底 gap=" + gap + " " + view.getClass().getSimpleName());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveStretched(View v, ViewGroup.MarginLayoutParams lp) {
        if (!STRETCHED.containsKey(v)) {
            STRETCHED.put(v, new int[]{lp.height, lp.topMargin, lp.bottomMargin});
        }
    }

    /** 底栏的 paddingBottom 归零，返回原值（0 表示没动）。 */
    static int dropNavReserve(View view) {
        if (view == null) return 0;
        try {
            int p = view.getPaddingBottom();
            if (p <= 0) return 0;
            if (!PADDED.containsKey(view)) PADDED.put(view, p);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), 0);
            Dydock.log("接管:去掉底栏 " + p + "px 的底部预留");
            return p;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 关掉宿主的「底部毛玻璃带」：反射找 setBottomBlurAreaHeight(int) 设成 0。
     *
     * 抖音/拼多多这类宿主都有这条带子（底部一段模糊渐变），它就是「底部始终压着一层
     * 东西」的来源。找不到对应方法就跳过，不猜。
     */
    static void dropBottomFrost(View view) {
        if (view == null || FROST_DROPPED.contains(view)) return;
        try {
            Class<?> cls = view.getClass();
            Method[] methods;
            if (NO_FROST.contains(cls)) return;
            synchronized (FROST_METHODS) {
                methods = FROST_METHODS.get(cls);
                if (methods == null) {
                    methods = findFrostMethods(cls);
                    if (methods == null) {
                        NO_FROST.add(cls);
                        return;
                    }
                    FROST_METHODS.put(cls, methods);
                }
            }
            Integer height = (Integer) methods[0].invoke(view);
            if (height != null && height > 0) {
                methods[1].invoke(view, 0);
                FROST_DROPPED.add(view);
                Dydock.log("接管:关掉底部毛玻璃带（原高 " + height + "px）");
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method[] findFrostMethods(Class<?> cls) {
        String[][] names = {
                {"getBottomBlurAreaHeight", "setBottomBlurAreaHeight"},
                {"getBottomBlurHeight", "setBottomBlurHeight"},
                {"getBlurAreaHeight", "setBlurAreaHeight"},
        };
        for (String[] pair : names) {
            try {
                Method g = cls.getMethod(pair[0]);
                Method s = cls.getMethod(pair[1], int.class);
                if (g.getReturnType() == int.class) return new Method[]{g, s};
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 把改过的东西全部还原。 */
    static void restore() {
        for (Map.Entry<View, int[]> e : STRETCHED.entrySet()) {
            try {
                View v = e.getKey();
                if (v == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (!(lp instanceof ViewGroup.MarginLayoutParams)) continue;
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int[] saved = e.getValue();
                mlp.height = saved[0];
                mlp.topMargin = saved[1];
                mlp.bottomMargin = saved[2];
                v.setLayoutParams(mlp);
            } catch (Throwable ignored) {
            }
        }
        STRETCHED.clear();
        for (Map.Entry<View, Integer> e : PADDED.entrySet()) {
            try {
                View v = e.getKey();
                if (v == null) continue;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), e.getValue());
            } catch (Throwable ignored) {
            }
        }
        PADDED.clear();
        FROST_DROPPED.clear();
    }
}
