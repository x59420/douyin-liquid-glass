package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.Checkable;
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

    /** 每个 Activity 识别出的官方 Dock。 */
    private static final WeakHashMap<Activity, View> DOCKS = new WeakHashMap<Activity, View>();
    /** 我们盖上去的那层玻璃，Activity 走的时候要拆掉、把官方底栏还回去 */
    private static final WeakHashMap<Activity, DockOverlay> OVERLAYS =
            new WeakHashMap<Activity, DockOverlay>();
    /** Keep the per-Activity poller cancellable so destroyed pages do not retain work. */
    private static final WeakHashMap<Activity, Runnable> POLLERS =
            new WeakHashMap<Activity, Runnable>();
    private static final WeakHashMap<Activity, boolean[]> POLL_STATES =
            new WeakHashMap<Activity, boolean[]>();

    private DockController() {
    }

    static View dockOf(Activity act) {
        if (act == null) return null;
        synchronized (DOCKS) {
            return DOCKS.get(act);
        }
    }

    /**
     * Activity 销毁时收尾。
     *
     * 少这一步就是残影：底栏被我们设成 alpha 0，Activity 一换、这套 View 被复用，
     * 用户看到的就是「底栏没了」或者「上一页的玻璃浮在新界面上」。
     */
    static void release(Activity act) {
        if (act == null) return;
        View dock;
        DockOverlay overlay;
        synchronized (DOCKS) {
            dock = DOCKS.remove(act);
        }
        synchronized (OVERLAYS) {
            overlay = OVERLAYS.remove(act);
        }
        Runnable poller;
        boolean[] pollState;
        synchronized (POLLERS) {
            poller = POLLERS.remove(act);
        }
        synchronized (POLL_STATES) {
            pollState = POLL_STATES.remove(act);
        }
        if (pollState != null) pollState[0] = false;
        if (poller != null) MAIN.removeCallbacks(poller);
        try {
            if (overlay != null) {
                overlay.dispose();
                ViewParent p = overlay.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(overlay);
            }
        } catch (Throwable ignored) {
        }
        // 原生 Dock 永不恢复显示；Activity 销毁时只拆除覆盖层。
        synchronized (DONE) {
            DONE.remove(act);
        }
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
        // Match KernelSU FloatingBottomBar's 4dp backdrop blur.
        backdrop.setBlurDp(4f, density);

        final DockOverlay overlay = DockOverlay.create(act, backdrop, density);
        overlay.setTabs(left, plus);
        // Refresh the sampled background as feed content and video frames change.
        backdrop.setOnFrame(new Runnable() {
            @Override public void run() {
                if (overlay.isAttachedToWindow()) overlay.invalidate();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ((ViewGroup) decor).addView(overlay, lp);
        overlay.attachDock(dock);
        overlay.setBand(rectInWindow(dock));
        // 首次识别只用于确定底部位置；后续玻璃不再追随原生 Dock。
        overlay.pinPosition();

        // Capture only after the Dock band is known. Temporarily hide the native
        // artwork while sampling so its icons/text are not baked into the glass.
        // 原生 Dock 从接管开始就永久隐藏；玻璃层是唯一视觉导航。
        try {
            dock.setAlpha(0f);
            backdrop.capture();
        } finally {
            dock.setAlpha(0f);
        }

        final View[] dockRef = new View[]{dock};
        final boolean[] live = new boolean[]{true};
        synchronized (DOCKS) {
            DOCKS.put(act, dock);
        }
        synchronized (OVERLAYS) {
            OVERLAYS.put(act, overlay);
        }

        // 固定位置的玻璃 Dock 只检测菜单/个人页状态，不再用原生 Dock 的可见性控制显隐。
        // 节点被抖音临时隐藏时保留玻璃；只有确实换节点时才低频重新绑定点击目标。
        final int[] expandedTicks = new int[]{0};
        final boolean[] overlayTargetVisible = new boolean[]{true};
        final long[] lastFindAt = new long[]{0L};
        final Runnable backdropRefresh = new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    if (overlay.isAttachedToWindow() && overlay.getVisibility() == View.VISIBLE) {
                        backdrop.capture();
                        overlay.invalidate();
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("backdrop-refresh", "背景刷新失败: " + t);
                }
                MAIN.postDelayed(this, 300L);
            }
        };
        MAIN.postDelayed(backdropRefresh, 300L);

        final Runnable visibilityPoll = new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    if (act.isFinishing() || act.isDestroyed()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        MAIN.removeCallbacks(backdropRefresh);
                        synchronized (POLLERS) { POLLERS.remove(act); }
                        synchronized (POLL_STATES) { POLL_STATES.remove(act); }
                        return;
                    }
                    // A transition can leave the old overlay parented but detached.
                    // Remove/re-add it when the Activity decor is live; checking only
                    // getParent()==null misses this case and leaves the Dock gone forever.
                    if (!overlay.isAttachedToWindow() && decor.isAttachedToWindow()) {
                        ViewParent parent = overlay.getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(overlay);
                        }
                        if (overlay.getParent() == null) {
                            ((ViewGroup) decor).addView(overlay, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            backdrop.capture();
                            Dydock.log("底栏:玻璃层已重新挂回主窗口并刷新背景");
                        }
                    }
                    View d = dockRef[0];
                    long now = SystemClock.uptimeMillis();
                    if (d == null || !d.isAttachedToWindow() || !d.isShown()) {
                        // 原生节点不可用时才重新搜索，并限制为每 800ms 一次。
                        if (now - lastFindAt[0] >= 800L) {
                            lastFindAt[0] = now;
                            ViewGroup fresh = Finder.findDock(content);
                            if (fresh != null && fresh != d) {
                                List<View> its = Finder.tabItems(fresh);
                                if (its.size() >= 4) {
                                    View p = Finder.pickPlus(its);
                                    List<View> lf = new ArrayList<View>(its);
                                    if (p != null) lf.remove(p);
                                    fresh.setAlpha(0f);
                                    dockRef[0] = fresh;
                                    overlay.setTabs(lf, p);
                                    overlay.attachDock(fresh);
                                    overlay.pinPosition();
                                    synchronized (DOCKS) {
                                        DOCKS.put(act, fresh);
                                    }
                                    d = fresh;
                                    Dydock.log("底栏:已更新原生点击目标，玻璃位置保持固定");
                                }
                            }
                        }
                    }

                    boolean expanded = d != null
                            && Shield.hasExpandedOverlay(d, content, decor);
                    expandedTicks[0] = expanded ? Math.min(2, expandedTicks[0] + 1) : 0;
                    // Two 80ms samples keep transitions responsive while filtering one-frame layout noise.
                    // Window focus is not a reliable menu/profile signal during transitions.
                    boolean show = expandedTicks[0] < 2;

                    // 原生 Dock 永远透明；玻璃层用短淡入淡出，不再直接 GONE/VISIBLE 跳变。
                    if (d != null && d.getAlpha() != 0f) d.setAlpha(0f);
                    if (show != overlayTargetVisible[0]) {
                        overlayTargetVisible[0] = show;
                        overlay.animate().cancel();
                        if (show) {
                            if (overlay.getVisibility() != View.VISIBLE) {
                                overlay.setVisibility(View.VISIBLE);
                            }
                            // 从当前透明度继续，避免反向切换时闪一下。
                            overlay.animate().alpha(1f).setDuration(75L).start();
                        } else {
                            overlay.animate().alpha(0f).setDuration(65L)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (!overlayTargetVisible[0]) {
                                                overlay.setVisibility(View.GONE);
                                            }
                                        }
                                    }).start();
                        }
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("visibility-poll", "Dock 显隐检查失败: " + t);
                }
                MAIN.postDelayed(this, 80L);
            }
        };
        synchronized (POLLERS) { POLLERS.put(act, visibilityPoll); }
        synchronized (POLL_STATES) { POLL_STATES.put(act, live); }
        MAIN.postDelayed(visibilityPoll, 80L);


        //
        // 0.0.2/0.0.3 这里多了一步「把附近容器的背景设成 null 让它透出来」——
        // 那些容器切页之后还是同一个对象，背景没了再没人还回来，
        // 用户看到的就是「打开别的界面还有黑块」「好多残影」。
        // 这一版一个背景都不碰，玻璃自己就是半透明的，不需要底下挖洞。
        // 原生 Dock 从接管开始就保持透明。
        dock.setAlpha(0f);

        // 看门狗：玻璃**可见却迟迟没画出来**才把官方底栏还回去，绝不留一条空白。
        // 只守最初这两秒半；画出来过一次之后就永久放手，不再干预。
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!live[0] || overlay.hasDrawn()) return;
                View d0 = dockRef[0];
                try {
                    if (d0 != null) d0.setAlpha(0f);
                    if (overlay.getVisibility() == View.VISIBLE) {
                        overlay.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
                Dydock.warn("底栏:玻璃未能绘制；按设置保持原生 Dock 隐藏");
            }
        }, 2500L);

        Dydock.log("底栏已接管：" + left.size() + " 个图标"
                + (plus != null ? " ＋ 独立一块" : "")
                + "，底栏 " + dock.getWidth() + "x" + dock.getHeight()
                + "（" + dock.getClass().getSimpleName() + "）");
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

                // Restrict matching to the actual bottom navigation strip. Older broad bounds
                // could mistake a search/result row near the bottom for the Dock.
                boolean inBottom = bottom >= rh - 48f * density
                        && relTop >= rh - 150f * density;
                boolean sizeOk = hDp >= 34f && hDp <= 96f && w >= rw * 0.70f;
                if (inBottom && sizeOk && !hasInput(g)) {
                    List<View> items = tabItems(g);
                    // Fail closed: geometry and clickability alone must never identify a Dock.
                    if (isExpectedDouyinDock(items)) {
                        float score = bottom + w * 0.2f;
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

        /**
         * Positive identity check for the native Douyin bottom navigation:
         * exactly four named destinations (Home/Friends/Messages/Profile) plus
         * the center publish slot. Unknown rows are left untouched.
         */
        private static boolean isExpectedDouyinDock(List<View> items) {
            if (items == null || items.size() != 5) return false;
            boolean home = false;
            boolean friends = false;
            boolean messages = false;
            boolean profile = false;
            for (int i = 0; i < items.size(); i++) {
                if (i == 2) continue; // Douyin's independent center publish button
                String label = navigationLabel(items.get(i), 0);
                if ("首页".equals(label)) home = true;
                else if ("朋友".equals(label) || "好友".equals(label)) friends = true;
                else if ("消息".equals(label)) messages = true;
                else if ("我".equals(label) || "个人主页".equals(label)) profile = true;
                else return false;
            }
            return home && friends && messages && profile;
        }

        private static String navigationLabel(View v, int depth) {
            if (v == null || depth > 7) return "";
            String label = normalizeLabel(v.getContentDescription());
            if (isNavigationLabel(label)) return label;
            if (v instanceof android.widget.TextView) {
                label = normalizeLabel(((android.widget.TextView) v).getText());
                if (isNavigationLabel(label)) return label;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    String found = navigationLabel(g.getChildAt(i), depth + 1);
                    if (!found.isEmpty()) return found;
                }
            }
            return "";
        }

        private static String normalizeLabel(CharSequence value) {
            if (value == null) return "";
            return value.toString().trim().replaceAll("[0-9]+$", "").trim();
        }

        private static boolean isNavigationLabel(String value) {
            return "首页".equals(value) || "朋友".equals(value) || "好友".equals(value)
                    || "消息".equals(value) || "我".equals(value)
                    || "个人主页".equals(value);
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

        /** 只在明确确认首页处于选中状态时显示；无法确认就恢复原生底栏。 */
        static boolean isHomeSelected(List<View> items) {
            boolean homeSelected = false;
            boolean otherDestinationSelected = false;
            for (View item : items) {
                if (!selectedDeep(item, 0)) continue;
                String label = navigationLabel(item, 0);
                if ("首页".equals(label)) {
                    homeSelected = true;
                } else if ("朋友".equals(label) || "好友".equals(label)
                        || "消息".equals(label) || "我".equals(label)
                        || "个人主页".equals(label)) {
                    otherDestinationSelected = true;
                }
            }
            // 安全优先：其他页明确选中，或当前选中状态无法识别时，都不显示自定义 Dock。
            return homeSelected && !otherDestinationSelected;
        }

        private static boolean selectedDeep(View v, int depth) {
            if (v == null || depth > 8) return false;
            if (v.isSelected() || v.isActivated()
                    || (v instanceof Checkable && ((Checkable) v).isChecked())) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (selectedDeep(g.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }

        private Finder() {
        }
    }
}
