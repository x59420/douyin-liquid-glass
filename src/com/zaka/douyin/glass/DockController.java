package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 只做一件事：把官方底栏那一条换成两块悬浮玻璃。
 *
 * 不去扫别的控件、不碰别的页面 —— 这是上一版满屏黑块的根源：
 * 它在整棵视图树上到处套玻璃，每套一块就多一块深色矩形，
 * 页面一切换，那些矩形还没跟上，看着就是到处叠黑块。
 *
 * 这一版的做法只有四步：
 *   1. 在 content 里认一下官方底栏（只看底边、只看有 4~6 个可点子项的那一层）
 *   2. 把官方底栏 alpha 设 0（布局不动、点击照旧，页面切换还是抖音自己干）
 *   3. 在 DecorView 上盖一层全屏 overlay，只在底栏那一条的两个位置画玻璃
 *   4. 背景（玻璃底下看到的画面）只采底栏那一条，采集根是 content，
 *      跟 overlay 是兄弟节点，不会把玻璃画进自己底下去
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
            Dydock.error("处理 Activity", t);
        }
    }

    private static void attempt(final Activity act, final int n) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing()) return;
                    if (install(act)) return;
                    if (n < MAX_ATTEMPTS) {
                        attempt(act, n + 1);
                    } else if (n == MAX_ATTEMPTS) {
                        Dydock.warn("没认出底栏（这个页面可能不是主界面，或这次没进主界面）");
                    }
                } catch (Throwable t) {
                    Dydock.error("安装底栏", t);
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
            Dydock.warn("底栏认出 " + items.size() + " 个子项，不够 4 个，这次不动");
            return false;
        }

        View plus = Finder.pickPlus(items);
        List<View> left = new ArrayList<View>(items);
        if (plus != null) left.remove(plus);

        float density = act.getResources().getDisplayMetrics().density;

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        backdrop.setBlurDp(9f, density);
        backdrop.capture();

        final DockOverlay overlay = DockOverlay.create(act, backdrop, density);
        overlay.setTabs(left, plus);

        // 官方底栏：藏画面，留布局和点击
        dock.setAlpha(0f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ((ViewGroup) decor).addView(overlay, lp);
        overlay.setBand(rectInWindow(dock));

        final View dockRef = dock;
        content.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            if (!dockRef.isAttachedToWindow()) return;
                            // 抖音自己会在切页时给底栏做透明度动画，压回去
                            if (dockRef.getAlpha() != 0f) dockRef.setAlpha(0f);
                            overlay.setBand(rectInWindow(dockRef));
                        } catch (Throwable t) {
                            Dydock.warnOnce("layout", "跟随底栏位置失败: " + t);
                        }
                    }
                });

        Dydock.log("底栏已接管：" + left.size() + " 个图标"
                + (plus != null ? " ＋ 独立一块" : "")
                + "，底栏 " + dockRef.getWidth() + "x" + dockRef.getHeight()
                + "（" + dockRef.getClass().getSimpleName() + "）");
        return true;
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
            walk(root, root, rw, rh, best, bestScore, 0, new int[]{1200});
            return best[0];
        }

        private static void walk(View root, View v, int rw, int rh,
                                 ViewGroup[] best, float[] bestScore, int depth, int[] budget) {
            if (v == null || depth > MAX_DEPTH || budget[0] <= 0) return;
            budget[0]--;

            if (v instanceof ViewGroup && v != root && v.isShown()) {
                ViewGroup g = (ViewGroup) v;
                float density = g.getResources().getDisplayMetrics().density;
                int w = g.getWidth();
                int h = g.getHeight();
                float hDp = h / density;
                int[] l = new int[2];
                g.getLocationInWindow(l);
                int[] rl = new int[2];
                root.getLocationInWindow(rl);
                int top = l[1] - rl[1];
                int bottom = top + h;

                boolean inBottom = bottom > rh * 0.74f && top > rh * 0.55f;
                boolean sizeOk = hDp >= 34f && hDp <= 110f && w >= rw * 0.55f;
                if (inBottom && sizeOk) {
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

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walk(root, g.getChildAt(i), rw, rh, best, bestScore, depth + 1, budget);
                }
            }
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
