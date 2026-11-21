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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 顶栏三块悬浮玻璃：左边展开、中间整条 tab、右边搜索。
 *
 * 做法跟底栏不一样：**不藏官方控件、不动层级**，只把原来那块底色换成
 * 一块玻璃 drawable。所以文字、图标还是抖音自己画的，字不会糊，
 * 点击、动画、切页全部照旧。
 *
 * 背景照样是采出来的真实画面（顶上那一条），所以玻璃下面就是页面本身的
 * 视频/封面，不是拿颜色糊上去的。
 */
final class TopBarController {

    private static final int MAX_ATTEMPTS = 18;
    private static final long RETRY_MS = 420L;

    private static final WeakHashMap<Activity, Boolean> DONE =
            new WeakHashMap<Activity, Boolean>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TopBarController() {
    }

    static void onActivity(final Activity act) {
        if (act == null) return;
        try {
            if (DONE.containsKey(act)) return;
            DONE.put(act, Boolean.TRUE);
            attempt(act, 0);
        } catch (Throwable t) {
            Dydock.error("顶栏:处理 Activity", t);
        }
    }

    private static void attempt(final Activity act, final int n) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing()) return;
                    boolean ok;
                    try {
                        ok = install(act);
                    } catch (Throwable t) {
                        Dydock.error("顶栏:安装", t);
                        ok = false;
                    }
                    if (ok) return;
                    if (n < MAX_ATTEMPTS) {
                        attempt(act, n + 1);
                    } else {
                        Dydock.warn("顶栏:没认出顶栏（这个页面可能没有顶部 tab）");
                    }
                } catch (Throwable t) {
                    Dydock.error("顶栏:重试", t);
                }
            }
        }, n == 0 ? 150L : RETRY_MS);
    }

    private static boolean install(final Activity act) {
        final View content = act.findViewById(android.R.id.content);
        if (content == null || content.getWidth() < 60 || content.getHeight() < 200) return false;

        final Row row = Finder.findRow(content);
        if (row == null) return false;

        final float density = act.getResources().getDisplayMetrics().density;

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        backdrop.setBlurDp(10f, density);
        backdrop.setExcludeSelf();
        backdrop.setRegion(row.region(content, density));
        backdrop.capture();

        final List<Slot> owned = new ArrayList<Slot>();
        final boolean[] on = new boolean[]{true};

        Slot left = new Slot(row.left);
        Slot middle = new Slot(row.middle);
        Slot right = new Slot(row.right);
        attach(left, backdrop, density, 24f, owned);
        attach(middle, backdrop, density, 18f, owned);
        attach(right, backdrop, density, 24f, owned);
        if (owned.isEmpty()) return false;

        final Handler timer = new Handler(Looper.getMainLooper());
        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                try {
                    boolean alive = on[0] ? stillTopBar(row, content) : topBarBack(row, content);
                    if (on[0] && !alive) {
                        // 进了搜索页 / 详情页 / 聊天页：把原来那块底色还回去，
                        // 不然玻璃框会跟着浮在别的页面上（就是「点搜索后玻璃框还在」）
                        on[0] = false;
                        for (Slot s : owned) detach(s);
                        Dydock.log("顶栏:这一页不是主界面了，玻璃先撤掉");
                    } else if (!on[0] && alive) {
                        // 回到主界面：重新挂上
                        on[0] = true;
                        for (Slot s : owned) reattach(s, backdrop, density);
                        Dydock.log("顶栏:回到主界面，玻璃挂回来");
                    }

                    if (on[0]) {
                        backdrop.setRegion(row.region(content, density));
                        backdrop.capture();
                        for (Slot s : owned) s.invalidate();
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("top-loop", "顶栏刷新失败: " + t);
                }
                timer.postDelayed(this, 1200L);
            }
        };
        timer.postDelayed(loop, 1200L);

        content.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            if (on[0]) {
                                boolean was = on[0];
                                if (was && !stillTopBar(row, content)) {
                                    on[0] = false;
                                    for (Slot s : owned) detach(s);
                                    return;
                                }
                                if (on[0]) backdrop.setRegion(row.region(content, density));
                            }
                            for (Slot s : owned) s.invalidate();
                        } catch (Throwable ignored) {
                        }
                    }
                });

        Dydock.log("顶栏已接管：中 " + describe(row.middle)
                + (row.left != null ? " / 左 " + describe(row.left) : " / 左 无")
                + (row.right != null ? " / 右 " + describe(row.right) : " / 右 无"));
        return true;
    }

    /**
     * 现在这一屏还是不是「主界面的顶栏」。
     *
     * 判据：中间那条 tab 条还在、还是原来的宽度、没有被整屏页面盖住、
     * 里面没有冒出来一个可见的输入框（抖音的搜索页顶上就是搜索框）。
     */
    private static boolean stillTopBar(Row row, View content) {
        if (row == null || row.middle == null || content == null) return false;
        if (!row.middle.isAttachedToWindow() || !row.middle.isShown()) return false;
        if (content.getWidth() <= 0) return false;
        if (row.middle.getWidth() < content.getWidth() * 0.18f) return false;
        if (row.middle.getHeight() <= 0) return false;
        if (Shield.covered(row.middle, content)) return false;
        if (Shield.hasVisibleInput(row.middle)) return false;
        return true;
    }

    /** 已经撤掉玻璃之后，判断该不该再挂回去（比 stillTopBar 松一点） */
    private static boolean topBarBack(Row row, View content) {
        if (row == null || row.middle == null || content == null) return false;
        if (!row.middle.isAttachedToWindow() || !row.middle.isShown()) return false;
        if (Shield.covered(row.middle, content)) return false;
        return !Shield.hasVisibleInput(row.middle);
    }

    private static String describe(View v) {
        return v.getClass().getSimpleName() + " " + v.getWidth() + "x" + v.getHeight();
    }

    /** 一块玻璃和它原本的底色 —— 撤的时候要原样还回去 */
    private static final class Slot {
        final View view;
        Drawable original;
        boolean taken;

        Slot(View v) {
            this.view = v;
        }

        void invalidate() {
            try {
                if (view != null && taken) view.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void attach(Slot s, Backdrop bd, float density, float radiusDp, List<Slot> owned) {
        if (s == null || s.view == null) return;
        View v = s.view;
        if (v.getWidth() < 8 || v.getHeight() < 8) return;
        try {
            s.original = v.getBackground();
            s.taken = true;
            v.setBackground(new GlassStripDrawable(v, bd, density, radiusDp, 0f));
            v.invalidate();
            owned.add(s);
        } catch (Throwable t) {
            s.taken = false;
            Dydock.warnOnce("top-attach", "顶栏挂玻璃失败: " + t);
        }
    }

    private static void reattach(Slot s, Backdrop bd, float density) {
        if (s == null || s.view == null || s.taken) return;
        View v = s.view;
        if (v.getWidth() < 8 || v.getHeight() < 8) return;
        try {
            s.taken = true;
            v.setBackground(new GlassStripDrawable(v, bd, density, 18f, 0f));
            v.invalidate();
        } catch (Throwable t) {
            s.taken = false;
        }
    }

    private static void detach(Slot s) {
        if (s == null || s.view == null || !s.taken) return;
        s.taken = false;
        try {
            if (s.view.getBackground() instanceof GlassStripDrawable) {
                s.view.setBackground(s.original);
            }
            s.view.invalidate();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 认顶栏

    static final class Row {
        ViewGroup middle;
        View left;
        View right;

        Rect region(View content, float density) {
            int[] rl = new int[2];
            content.getLocationInWindow(rl);
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE;
            int r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
            for (View v : new View[]{middle, left, right}) {
                if (v == null) continue;
                int[] wl = new int[2];
                v.getLocationInWindow(wl);
                l = Math.min(l, wl[0]);
                t = Math.min(t, wl[1]);
                r = Math.max(r, wl[0] + v.getWidth());
                b = Math.max(b, wl[1] + v.getHeight());
            }
            int pad = Math.round(10 * density);
            return new Rect(l - pad, t - pad, r + pad, b + pad);
        }
    }

    static final class Finder {

        private static final int MAX_DEPTH = 36;

        static Row findRow(View content) {
            int rw = content.getWidth();
            int rh = content.getHeight();
            if (rw <= 0 || rh <= 0) return null;

            int[] cl = new int[2];
            content.getLocationInWindow(cl);

            View[] best = new View[1];
            float[] score = new float[]{-1e9f};
            walk(content, content, cl, rw, rh, best, score, 0, new int[]{1400});
            if (best[0] == null) return null;

            return split((ViewGroup) best[0], rw);
        }

        private static void walk(View content, View v, int[] cl, int rw, int rh,
                                 View[] best, float[] score, int depth, int[] budget) {
            if (v == null || depth > MAX_DEPTH || budget[0] <= 0) return;
            budget[0]--;

            if (v instanceof ViewGroup && v.isShown()) {
                ViewGroup g = (ViewGroup) v;
                float density = g.getResources().getDisplayMetrics().density;
                int w = g.getWidth();
                int h = g.getHeight();
                float hDp = h / density;
                int[] wl = new int[2];
                g.getLocationInWindow(wl);
                int relTop = wl[1] - cl[1];

                if (hDp >= 28f && hDp <= 84f && w >= rw * 0.55f
                        && relTop >= -Math.round(24 * density) && relTop <= rh * 0.16f) {
                    List<View> kids = rowChildren(g);
                    if (kids.size() >= 3 && hasTabLabel(g, 0)) {
                        float s = kids.size() * 120f - relTop * 0.4f + w * 0.05f;
                        if (s > score[0]) {
                            score[0] = s;
                            best[0] = g;
                        }
                    }
                }
            }

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walk(content, g.getChildAt(i), cl, rw, rh, best, score, depth + 1, budget);
                }
            }
        }

        /** 一排水平排开、且都看得见的子项 */
        private static List<View> rowChildren(ViewGroup g) {
            List<View> out = new ArrayList<View>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                if (c.getWidth() < 8 || c.getHeight() < 8) continue;
                out.add(c);
            }
            if (out.size() < 2) return out;
            Collections.sort(out, new Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    return Integer.compare(a.getLeft(), b.getLeft());
                }
            });
            int top = out.get(0).getTop();
            int hh = Math.max(1, out.get(0).getHeight());
            for (View v : out) {
                if (Math.abs(v.getTop() - top) > hh * 0.6f) return new ArrayList<View>();
            }
            return out;
        }

        /** 里面有没有短标签文字（关注 / 推荐 / 朋友 …） */
        private static boolean hasTabLabel(View v, int depth) {
            if (depth > 6) return false;
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String s = t.toString().trim();
                    if (s.length() >= 1 && s.length() <= 4) return true;
                }
                return false;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (hasTabLabel(g.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }

        /** 一行里拆出：中间那块最宽的（tab 条）、左右两边的窄按钮 */
        private static Row split(ViewGroup g, int screenW) {
            List<View> kids = rowChildren(g);
            if (kids.size() < 3) return null;

            View widest = null;
            for (View v : kids) {
                if (widest == null || v.getWidth() > widest.getWidth()) widest = v;
            }
            if (widest == null || widest.getWidth() < screenW * 0.18f) return null;

            List<View> before = new ArrayList<View>();
            List<View> after = new ArrayList<View>();
            int idx = kids.indexOf(widest);
            for (int i = 0; i < kids.size(); i++) {
                if (i < idx) before.add(kids.get(i));
                else if (i > idx) after.add(kids.get(i));
            }

            Row row = new Row();
            row.middle = widest instanceof ViewGroup ? (ViewGroup) widest : g;
            if (row.middle == g) return null;

            // 左边：紧挨着中间那块的那个窄件（展开/头像那一类）
            for (int i = before.size() - 1; i >= 0; i--) {
                View v = before.get(i);
                if (v.getWidth() <= 96 * v.getResources().getDisplayMetrics().density) {
                    row.left = v;
                    break;
                }
            }
            // 右边：紧挨着中间那块的那个窄件（搜索）
            for (View v : after) {
                if (v.getWidth() <= 96 * v.getResources().getDisplayMetrics().density) {
                    row.right = v;
                    break;
                }
            }
            return row;
        }

        private Finder() {
        }
    }
}
