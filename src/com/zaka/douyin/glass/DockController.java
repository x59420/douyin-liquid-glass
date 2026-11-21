package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 把官方底栏那一条换成两块悬浮玻璃。
 *
 * 只认「首页那几个大 tab」所在的底栏：
 *   1. 只看底边、只看高度 34~110dp、宽度够宽、里面 4~6 个可点的等宽子项
 *   2. 里面**有输入框的一律不算**（聊天页底部的输入条以前就被误认成底栏）
 *   3. 当前页被另一整屏内容盖住时（同一个 Activity 里叠上来的聊天页/详情页），玻璃一起收
 *
 * 顺序上有一条死规矩：**先把玻璃挂上、确认它真的画出来了，才去藏官方底栏**。
 * 老版本反过来，中间任何一步出岔子，官方底栏已经透明、玻璃又没上来，
 * 用户看到的就是「dock 整条不见了」。现在另外还带一个看门狗：
 * 两秒内玻璃没画出来，立刻把官方底栏还原。
 */
final class DockController {

    private static final int MAX_ATTEMPTS = 24;
    private static final long RETRY_MS = 300L;

    private static final WeakHashMap<Activity, Boolean> DONE =
            new WeakHashMap<Activity, Boolean>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DockController() {
    }

    static void onActivity(final Activity act) {
        if (act == null) return;
        try {
            if (DONE.containsKey(act)) return;
            DONE.put(act, Boolean.TRUE);
            attempt(act, 0);
        } catch (Throwable t) {
            Dydock.error("底栏:处理 Activity", t);
        }
    }

    private static boolean looksLikeChat(Activity act) {
        String n = act.getClass().getName().toLowerCase();
        return n.contains(".chat") || n.contains("chatroom") || n.contains(".im.")
                || n.endsWith("detailactivity") || n.contains("webviewactivity");
    }

    private static void attempt(final Activity act, final int n) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing()) return;
                    if (looksLikeChat(act)) {
                        Dydock.log("底栏:这不是主界面（" + act.getClass().getSimpleName() + "），跳过");
                        return;
                    }
                    if (install(act)) return;
                    if (n < MAX_ATTEMPTS) {
                        attempt(act, n + 1);
                    } else if (n == MAX_ATTEMPTS) {
                        Dydock.warn("底栏:没认出底栏（这个页面可能不是首页）");
                    }
                } catch (Throwable t) {
                    Dydock.error("底栏:安装", t);
                    if (n < MAX_ATTEMPTS) attempt(act, n + 1);
                }
            }
        }, n == 0 ? 120L : RETRY_MS);
    }

    private static boolean install(Activity act) {
        final View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        final View content = act.findViewById(android.R.id.content);
        if (decor == null || content == null) return false;
        if (content.getWidth() < 50 || content.getHeight() < 50) return false;
        if (!(decor instanceof ViewGroup)) return false;

        ViewGroup dock = Finder.findDock(content);
        if (dock == null) return false;

        List<View> items = Finder.tabItems(dock);
        if (items.size() < 4) {
            Dydock.warn("底栏:认出 " + items.size() + " 个子项，不够 4 个，这次不动");
            return false;
        }

        View plus = Finder.pickPlus(items);
        List<View> left = new ArrayList<View>(items);
        if (plus != null) left.remove(plus);

        float density = act.getResources().getDisplayMetrics().density;

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        backdrop.setBlurDp(9f, density);
        backdrop.setExcludeSelf();
        backdrop.capture();

        final DockOverlay overlay = DockOverlay.create(act, backdrop, density);
        overlay.setTabs(left, plus);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ((ViewGroup) decor).addView(overlay, lp);
        overlay.setBand(rectInWindow(dock));

        final View[] dockRef = new View[]{dock};
        final boolean[] live = new boolean[]{true};
        final List<View> cleared = new ArrayList<View>();
        final List<Drawable> saved = new ArrayList<Drawable>();

        content.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!live[0]) return;
                        try {
                            View d = dockRef[0];
                            if (d == null || !d.isAttachedToWindow()) {
                                // 宿主把底栏整个换掉了：重新认一次，别让点击转发到死视图上
                                ViewGroup fresh = Finder.findDock(content);
                                if (fresh == null) {
                                    overlay.setVisibility(View.GONE);
                                    return;
                                }
                                List<View> its = Finder.tabItems(fresh);
                                if (its.size() < 4) {
                                    overlay.setVisibility(View.GONE);
                                    return;
                                }
                                View p = Finder.pickPlus(its);
                                List<View> lf = new ArrayList<View>(its);
                                if (p != null) lf.remove(p);
                                fresh.setAlpha(0f);
                                dockRef[0] = fresh;
                                overlay.setTabs(lf, p);
                                d = fresh;
                            }
                            // 抖音自己会在切页时给底栏做透明度动画，压回去
                            if (d.getAlpha() != 0f) d.setAlpha(0f);
                            // 底栏被藏起来（全屏播放）、或者整页被聊天/详情页盖住时，玻璃跟着收
                            boolean show = d.isShown() && !coveredByFullscreenPage(d, content);
                            overlay.setVisibility(show ? View.VISIBLE : View.GONE);
                            overlay.setBand(rectInWindow(d));
                        } catch (Throwable t) {
                            Dydock.warnOnce("layout", "跟随底栏位置失败: " + t);
                        }
                    }
                });

        // 官方底栏藏画面，留布局和点击；小白条那一带的底色也一起清掉才像悬浮
        clearBackdropAround(dock, content, cleared, saved);
        dock.setAlpha(0f);

        // 看门狗：两秒内玻璃没画出来，就把官方底栏还回去，绝不留一条空白
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (overlay.hasDrawn()) return;
                live[0] = false;
                Dydock.warn("底栏:玻璃没画出来，把官方底栏还原");
                View d = dockRef[0];
                if (d != null) d.setAlpha(1f);
                for (int i = 0; i < cleared.size(); i++) {
                    try {
                        cleared.get(i).setBackground(saved.get(i));
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    ViewParent p = overlay.getParent();
                    if (p instanceof ViewGroup) ((ViewGroup) p).removeView(overlay);
                } catch (Throwable ignored) {
                }
            }
        }, 2000L);

        Dydock.log("底栏已接管：" + left.size() + " 个图标"
                + (plus != null ? " ＋ 独立一块" : "")
                + "，底栏 " + dock.getWidth() + "x" + dock.getHeight()
                + "（" + dock.getClass().getSimpleName() + "）");
        return true;
    }

    /**
     * 把底栏那一带的底色去掉。
     *
     * 只处理高度跟底栏差不多（≤ 2.4 倍）的容器，绝不碰整页背景，
     * 原背景存起来，看门狗还原时一起还回去。
     */
    private static void clearBackdropAround(View dock, View content,
                                            List<View> cleared, List<Drawable> saved) {
        View v = dock;
        for (int i = 0; i < 3 && v != null && v != content; i++) {
            try {
                if (v.getBackground() != null && v.getHeight() <= dock.getHeight() * 2.4f + 8) {
                    cleared.add(v);
                    saved.add(v.getBackground());
                    v.setBackground(null);
                }
            } catch (Throwable t) {
                Dydock.warnOnce("clear-bg", "清底色失败: " + t);
            }
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
    }

    /**
     * 同一个 Activity 里叠上来一整屏内容（聊天页、详情页）时，
     * 底栏虽然在树里还「可见」，但其实已经被盖住了 —— 这种情况玻璃要收起来。
     */
    private static boolean coveredByFullscreenPage(View v, View content) {
        try {
            int cw = content.getWidth();
            int ch = content.getHeight();
            if (cw <= 0 || ch <= 0) return false;
            View cur = v;
            while (cur != null && cur != content) {
                ViewParent p = cur.getParent();
                if (!(p instanceof ViewGroup)) break;
                ViewGroup g = (ViewGroup) p;
                int idx = g.indexOfChild(cur);
                for (int i = idx + 1; i < g.getChildCount(); i++) {
                    View s = g.getChildAt(i);
                    if (s == null || s.getVisibility() != View.VISIBLE || s.getAlpha() < 0.05f) continue;
                    if (s.getWidth() >= cw * 0.92f && s.getHeight() >= ch * 0.80f) return true;
                }
                cur = g;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Rect rectInWindow(View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    // ------------------------------------------------------------------ 认底栏

    static final class Finder {

        private static final int MAX_DEPTH = 44;

        static ViewGroup findDock(View root) {
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return null;
            ViewGroup[] best = new ViewGroup[1];
            float[] bestScore = new float[]{-1f};
            walk(root, rw, rh, best, bestScore, 0, new int[]{1400});
            return best[0];
        }

        private static void walk(View root, int rw, int rh,
                                 ViewGroup[] best, float[] bestScore, int depth, int[] budget) {
            if (root == null || depth > MAX_DEPTH || budget[0] <= 0) return;
            budget[0]--;

            if (root instanceof ViewGroup && root != null && root.isShown()) {
                ViewGroup g = (ViewGroup) root;
                float density = g.getResources().getDisplayMetrics().density;
                int w = g.getWidth();
                int h = g.getHeight();
                float hDp = h / density;
                int[] l = new int[2];
                g.getLocationInWindow(l);
                int[] rl = new int[2];
                View top = root;
                // 用根视图的窗口位置换算相对坐标
                ViewParent p = top.getParent();
                while (p instanceof View) {
                    top = (View) p;
                    p = top.getParent();
                }
                top.getLocationInWindow(rl);
                int relTop = l[1] - rl[1];
                int bottom = relTop + h;

                boolean inBottom = bottom > rh * 0.74f && relTop > rh * 0.55f;
                boolean sizeOk = hDp >= 34f && hDp <= 110f && w >= rw * 0.55f;
                if (inBottom && sizeOk && !hasInput(g)) {
                    List<View> items = tabItems(g);
                    if (items.size() >= 4 && items.size() <= 6) {
                        // 越靠下、子项越规整，分越高
                        float score = bottom + items.size() * 40f + w * 0.2f;
                        if (score > bestScore[0]) {
                            bestScore[0] = score;
                            best[0] = g;
                        }
                    }
                }
            }

            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walk(g.getChildAt(i), rw, rh, best, bestScore, depth + 1, budget);
                }
            }
        }

        /** 里面带输入框的一律不是底栏（聊天页的输入条就是这么被误认的） */
        private static boolean hasInput(View v) {
            return hasInput(v, 0);
        }

        private static boolean hasInput(View v, int depth) {
            if (depth > 8) return false;
            if (v instanceof EditText) return true;
            String n = v.getClass().getName();
            if (n.contains("EditText") || n.contains("EditTextProxy")) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && hasInput(c, depth + 1)) return true;
                }
            }
            return false;
        }

        /** 底栏里那一排可点的子项，按从左到右排好 */
        static List<View> tabItems(ViewGroup g) {
            List<View> out = new ArrayList<View>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                if (c.getWidth() < 8 || c.getHeight() < 8) continue;
                if (clickableish(c)) out.add(c);
            }
            if (out.size() < 4) return out;

            Collections.sort(out, new Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    return Integer.compare(a.getLeft(), b.getLeft());
                }
            });

            // 必须是一排（垂直范围基本重合），否则认错层
            int top = out.get(0).getTop();
            int bottom = out.get(0).getBottom();
            int hh = Math.max(1, bottom - top);
            for (View v : out) {
                if (Math.abs(v.getTop() - top) > hh * 0.5f) return new ArrayList<View>();
            }

            // 宽度不能差太远（＋ 会窄一些，给点余量）
            int w0 = out.get(0).getWidth();
            for (View v : out) {
                if (v.getWidth() < w0 * 0.35f) return new ArrayList<View>();
            }
            return out;
        }

        private static boolean clickableish(View v) {
            if (v.isClickable() || v.hasOnClickListeners()) return true;
            return hasClickableDescendant(v, 0);
        }

        private static boolean hasClickableDescendant(View v, int depth) {
            if (depth > 6) return false;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c == null) continue;
                    if (c.isClickable() || c.hasOnClickListeners()) return true;
                    if (hasClickableDescendant(c, depth + 1)) return true;
                }
            }
            return false;
        }

        /** 认出「＋ / 发布」那一个 */
        static View pickPlus(List<View> items) {
            for (View v : items) {
                if (looksLikePlus(v)) return v;
            }
            // 认不出来：5 个的话，抖音的 ＋ 在中间
            if (items.size() == 5) return items.get(2);
            return null;
        }

        private static boolean looksLikePlus(View v) {
            try {
                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    String s = desc.toString();
                    if (s.contains("+") || s.contains("＋") || s.contains("发布")
                            || s.toLowerCase().contains("publish")) return true;
                }
                String cn = v.getClass().getName().toLowerCase();
                if (cn.contains("publish") || cn.contains("plus")) return true;
                return textIsPlus(v, 0);
            } catch (Throwable t) {
                return false;
            }
        }

        private static boolean textIsPlus(View v, int depth) {
            if (depth > 5) return false;
            if (v instanceof android.widget.TextView) {
                CharSequence t = ((android.widget.TextView) v).getText();
                if (t != null) {
                    String s = t.toString().trim();
                    if (s.equals("+") || s.equals("＋")) return true;
                }
                return false;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (textIsPlus(g.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }

        private Finder() {
        }
    }
}
