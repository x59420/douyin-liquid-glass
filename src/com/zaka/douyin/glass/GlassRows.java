package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * 顶栏与聊天页的悬浮玻璃。
 *
 * <b>做法只有一件事：把一堆控件里「原来那块底色」换成一块玻璃 drawable。</b>
 * 不改层级、不改布局、不改透明度、不删任何人的背景 ——
 * 文字和图标还是抖音自己画在玻璃上面，所以字不糊、点击照旧、切页照旧。
 *
 * <b>为什么不这么干会翻车（0.0.2 / 0.0.3 的教训）：</b>
 * 那两版为了「让底下露出来」，把底栏附近容器的背景直接设成 null。
 * 那些容器在切页之后还是同一个对象，背景没了就再也没人还回来 ——
 * 用户看到的就是「打开别的界面还有黑块」「好多残影」。
 * 这一版一个字都不碰别人的背景，只在离开时把原来那块还回去。
 *
 * <b>怎么分组（按用户的要求，但用通用规则算出来，不写死抖音的类名）：</b>
 * <ul>
 *   <li>顶部那一行：第一个控件一块、最后一个控件一块、中间所有控件合成一块。</li>
 *   <li>底部那一行：按控件之间的空隙切 —— 挨得紧的归一块，中间明显断开的另起一块。</li>
 * </ul>
 * 于是：顶栏是 [左][中间各种][右]；聊天顶栏是 [返回][头像名字][右侧]；
 * 聊天底部是 [发消息的输入条][右边三个控件]。
 */
final class GlassRows {

    /** 结构复查间隔（每格 = 这个毫秒数） */
    private static final long TICK_MS = 420L;
    /** 背景重采集间隔：静态顶栏用不着太勤，省电 */
    private static final long CAPTURE_MS = 1800L;
    /** 每几格强制重扫一次视图树 */
    private static final int RESCAN_TICKS = 3;

    private static final WeakHashMap<Activity, Runner> RUNNERS =
            new WeakHashMap<Activity, Runner>();

    private GlassRows() {
    }

    static void onActivity(Activity act) {
        if (act == null) return;
        synchronized (RUNNERS) {
            if (RUNNERS.get(act) != null) return;
            Runner r = new Runner(act);
            RUNNERS.put(act, r);
            r.start();
        }
    }

    static void release(Activity act) {
        if (act == null) return;
        Runner r;
        synchronized (RUNNERS) {
            r = RUNNERS.remove(act);
        }
        if (r != null) r.stop();
    }

    // ================================================================ Runner

    private static final class Runner implements Runnable {

        /** 弱引用：这套刷新一直在跑，强引用会把整个界面钉在内存里不回收 */
        private final WeakReference<Activity> ref;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Bar> bars = new ArrayList<Bar>();

        private boolean running = true;
        private int sinceScan = RESCAN_TICKS;
        private long lastCapture;
        private int captureCursor;
        private String sig = "";

        Runner(Activity act) {
            this.ref = new WeakReference<Activity>(act);
        }

        private Activity act() {
            return ref.get();
        }

        void start() {
            main.postDelayed(this, 420L);
        }

        void stop() {
            running = false;
            main.removeCallbacks(this);
            dropAll();
            sig = "";
        }

        @Override
        public void run() {
            if (!running) return;
            boolean again = true;
            try {
                Activity act = act();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    stop();
                    again = false;
                } else {
                    View content = act.findViewById(android.R.id.content);
                    if (content != null && content.getWidth() > 40 && content.getHeight() > 160) {
                        step(content);
                    }
                }
            } catch (Throwable t) {
                Dydock.warnOnce("rows", "顶栏/聊天页刷新失败: " + t);
            }
            if (again && running) main.postDelayed(this, TICK_MS);
        }

        private void step(View content) {
            if (sinceScan >= RESCAN_TICKS) {
                sinceScan = 0;
                rescan(content);
            }
            sinceScan++;

            long now = SystemClock.uptimeMillis();
            boolean capture = now - lastCapture >= CAPTURE_MS;
            if (capture) lastCapture = now;

            for (int i = bars.size() - 1; i >= 0; i--) {
                Bar b = bars.get(i);
                if (!b.alive()) {
                    // 单帧判死容易误杀（切页动画中间会有几帧尺寸为 0），连着几次才拆
                    if (++b.miss < 3) continue;
                    b.release();
                    bars.remove(i);
                    sig = "";
                } else {
                    b.miss = 0;
                }
            }

            int n = bars.size();
            if (n > 0) {
                // 顶栏和聊天页可能同时有两块玻璃：一轮只重采一块，
                // 不然每隔一两秒就要把整棵界面树画两遍，白白掉帧
                if (capture) {
                    captureCursor = (captureCursor + 1) % n;
                    bars.get(captureCursor).capture();
                }
                for (Bar b : bars) {
                    // 宿主自己会动（tab 滑动、横竖屏）：位置每格都对一次，
                    // 不然玻璃要等下一次重采才跟上，看着就是「慢半拍」
                    b.refreshWindows();
                    b.reassert();
                    b.invalidate();
                }
            }
        }

        private void dropAll() {
            for (Bar b : bars) b.release();
            bars.clear();
        }

        private void rescan(View content) {
            List<Bar> fresh = new ArrayList<Bar>();
            String now = build(content, fresh);
            if (now.equals(sig)) {
                for (Bar b : fresh) b.releaseBackdropOnly();
                return;
            }
            dropAll();
            sig = now;
            for (Bar b : fresh) {
                b.mount();
                b.capture();
                bars.add(b);
            }
            Dydock.log("顶栏/聊天页玻璃：" + bars.size() + " 行");
        }
    }

    // ================================================================ 找行 + 分组

    /**
     * 找当前页面「最上面那一行」和「最下面那一行」，给每行按规则分组，返回结构签名。
     */
    private static String build(View content, List<Bar> out) {
        int cw = content.getWidth();
        int ch = content.getHeight();
        if (cw <= 0 || ch <= 0) return "";

        float density = content.getResources().getDisplayMetrics().density;
        int[] cl = new int[2];
        content.getLocationInWindow(cl);

        Activity act = actOf(content);
        View dock = DockController.dockOf(act);

        Hit top = new Hit();
        Hit bottom = new Hit();
        walk(content, content, cl, cw, ch, density, dock, top, bottom, 0, new int[]{1800});

        StringBuilder sb = new StringBuilder();
        if (top.row != null) {
            appendBar(content, top.row, true, density, sb, out);
        }
        if (bottom.row != null && bottom.row != top.row) {
            appendBar(content, bottom.row, false, density, sb, out);
        }
        return sb.toString();
    }

    private static void appendBar(View content, ViewGroup row, boolean topStyle,
                                  float density, StringBuilder sig, List<Bar> out) {
        // 搜索页顶上那一行整条就是个搜索输入框。那是抖音自己的输入态，
        // 在外面再套一层玻璃，用户点进搜索之后还挂着一块框 —— 就是「玻璃框还在」。
        if (topStyle && Shield.hasVisibleInput(row)) return;

        List<View> kids = rowKids(row);
        if (kids.size() < 2) return;

        for (View k : kids) sig.append(System.identityHashCode(k)).append(',');
        sig.append('@').append(row.getWidth()).append('x').append(row.getHeight())
                .append(topStyle ? "T" : "B").append(';');

        List<List<View>> groups = topStyle ? splitTop(kids) : splitBottom(kids, density);

        Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        backdrop.setBlurDp(10f, density);
        backdrop.setExcludeSelf();

        Bar bar = new Bar(row, backdrop, density);
        bar.zone = topStyle ? GlassStripDrawable.ZONE_TOP : GlassStripDrawable.ZONE_BOTTOM;
        // 顶栏三块之间留出明显的空隙，不然看着就是连成一条
        bar.spaced = topStyle;
        for (List<View> group : groups) {
            addGroup(bar, group, row, density);
        }
        if (!bar.slots.isEmpty()) out.add(bar);
        else backdrop.release();
    }


    /**
     * 一组控件 -> 一块玻璃。
     *
     * 组里只有一个控件：挂它自己的底色。
     * 组里有好几个但外面还包着一层容器：挂那个容器。
     * 组里好几个、而且就是这一行的直系子项（中间那几个 tab 常常这样）：
     *   给整行挂一块玻璃，但只画这一组的范围 —— 它照样是一整块，
     *   不会碎成三个小胶囊。
     */
    private static void addGroup(Bar bar, List<View> group, ViewGroup row, float density) {
        if (group.isEmpty()) return;
        boolean spaced = bar.spaced;

        if (group.size() == 1) {
            addSlot(bar, group.get(0), density, spaced);
            return;
        }

        View host = hostOf(group, row);
        if (host != null && host != row) {
            addSlot(bar, host, density, spaced);
            return;
        }

        RectF win = boundsIn(group, row, density);
        if (win == null || bar.rowTaken) {
            for (View v : group) addSlot(bar, v, density, spaced);
            return;
        }
        bar.rowTaken = true;
        GlassStripDrawable glass = new GlassStripDrawable(
                row, bar.backdrop, density, 1000f, 0f, spaced ? 5f : 0f);
        glass.setWindow(win);
        glass.setZone(bar.zone);
        Slot slot = new Slot(row, glass);
        slot.trackWindow(group, row, density);
        bar.slots.add(slot);
    }

    /** 一组控件在 row 本地坐标里的外接矩形（外扩 2dp） */
    private static RectF boundsIn(List<View> group, ViewGroup row, float density) {
        int[] rl = new int[2];
        row.getLocationInWindow(rl);
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        int[] vl = new int[2];
        for (View v : group) {
            if (v == null || v.getWidth() <= 0) continue;
            v.getLocationInWindow(vl);
            l = Math.min(l, vl[0] - rl[0]);
            t = Math.min(t, vl[1] - rl[1]);
            r = Math.max(r, vl[0] - rl[0] + v.getWidth());
            b = Math.max(b, vl[1] - rl[1] + v.getHeight());
        }
        if (l == Float.MAX_VALUE) return null;
        float pad = 2f * density;
        return new RectF(l - pad, t - pad, r + pad, b + pad);
    }

    private static void addSlot(Bar bar, View v, float density, boolean spaced) {
        if (v == null || v == bar.row) return;
        if (v.getWidth() < 8 || v.getHeight() < 8) return;
        GlassStripDrawable glass = new GlassStripDrawable(
                v, bar.backdrop, density, 1000f, 2f, spaced ? 5f : 0f);
        glass.setZone(bar.zone);
        bar.slots.add(new Slot(v, glass));
    }

    /** 顶部那一行：首 / 中 / 尾 三块 */
    private static List<List<View>> splitTop(List<View> kids) {
        List<List<View>> out = new ArrayList<List<View>>();
        int n = kids.size();
        if (n == 1) {
            out.add(new ArrayList<View>(kids));
            return out;
        }
        out.add(new ArrayList<View>(Collections.singletonList(kids.get(0))));
        if (n > 2) {
            out.add(new ArrayList<View>(kids.subList(1, n - 1)));
        }
        out.add(new ArrayList<View>(Collections.singletonList(kids.get(n - 1))));
        return out;
    }

    /** 底部那一行：按空隙切，挨得紧的归一块 */
    private static List<List<View>> splitBottom(List<View> kids, float density) {
        List<List<View>> out = new ArrayList<List<View>>();
        int n = kids.size();
        if (n == 0) return out;
        if (n == 1) {
            out.add(new ArrayList<View>(kids));
            return out;
        }

        int[] gaps = new int[n - 1];
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = kids.get(i + 1).getLeft() - kids.get(i).getRight();
        }

        if (n == 2) {
            boolean apart = gaps[0] >= Math.round(8f * density);
            List<View> one = new ArrayList<View>();
            one.add(kids.get(0));
            if (apart) {
                out.add(one);
                out.add(new ArrayList<View>(Collections.singletonList(kids.get(1))));
            } else {
                one.add(kids.get(1));
                out.add(one);
            }
            return out;
        }

        int[] sorted = gaps.clone();
        java.util.Arrays.sort(sorted);
        int med = sorted[sorted.length / 2];
        int cut = Math.max(Math.round(2f * density), med) + Math.round(2f * density);

        List<View> cur = new ArrayList<View>();
        cur.add(kids.get(0));
        for (int i = 0; i < n - 1; i++) {
            if (gaps[i] > cut) {
                out.add(cur);
                cur = new ArrayList<View>();
            }
            cur.add(kids.get(i + 1));
        }
        out.add(cur);
        return out;
    }

    /** 一组控件合成一块玻璃时的宿主：组内 1 个就是它本身，多个就取它们的最小公共祖先 */
    private static View hostOf(List<View> group, ViewGroup row) {
        if (group.isEmpty()) return null;
        if (group.size() == 1) return group.get(0);

        View best = null;
        View cur = group.get(0);
        int guard = 0;
        while (cur != null && cur != row && guard++ < 40) {
            boolean all = true;
            for (int i = 1; i < group.size(); i++) {
                if (!isDescendant(cur, group.get(i))) {
                    all = false;
                    break;
                }
            }
            if (all) best = cur;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return best;
    }

    private static boolean isDescendant(View ancestor, View v) {
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 60) {
            if (cur == ancestor) return true;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    // ================================================================ 视图树扫描

    private static final class Hit {
        ViewGroup row;
        int y;
    }

    private static void walk(View content, View v, int[] cl, int cw, int ch, float density,
                             View dock, Hit top, Hit bottom, int depth, int[] budget) {
        if (v == null || depth > 40 || budget[0] <= 0) return;
        budget[0]--;

        if (v instanceof ViewGroup && v != content) {
            ViewGroup g = (ViewGroup) v;
            if (g.isShown() && !nearDock(g, dock)) {
                int w = g.getWidth();
                int h = g.getHeight();
                float hDp = h / density;
                if (w >= cw * 0.5f && hDp >= 26f && hDp <= 104f) {
                    int[] wl = new int[2];
                    g.getLocationInWindow(wl);
                    int relTop = wl[1] - cl[1];
                    int relBottom = relTop + h;
                    if (rowKids(g).size() >= 2) {
                        if (relTop >= -Math.round(8 * density) && relBottom <= ch * 0.24f) {
                            if (relTop < top.y || top.row == null) {
                                top.row = g;
                                top.y = relTop;
                            }
                        }
                        if (relTop >= ch * 0.62f && relBottom <= ch + Math.round(8 * density)) {
                            if (relBottom > bottom.y || bottom.row == null) {
                                bottom.row = g;
                                bottom.y = relBottom;
                            }
                        }
                    }
                }
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(content, g.getChildAt(i), cl, cw, ch, density, dock, top, bottom,
                        depth + 1, budget);
            }
        }
    }

    /** g 是底栏本身、或者是底栏的祖先 —— 都别再往上套一层玻璃（底栏有自己的画法） */
    private static boolean nearDock(View g, View dock) {
        if (dock == null) return false;
        View cur = g;
        int guard = 0;
        while (cur != null && guard++ < 60) {
            if (cur == dock) return true;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** 一行里水平排开的可见子项，从左到右 */
    private static List<View> rowKids(ViewGroup g) {
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

    private static Activity actOf(View v) {
        android.content.Context c = v.getContext();
        int guard = 0;
        while (c instanceof android.content.ContextWrapper && guard++ < 12) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return c instanceof Activity ? (Activity) c : null;
    }

    // ================================================================ Bar / Slot

    private static final class Bar {
        final ViewGroup row;
        final Backdrop backdrop;
        final float density;
        final List<Slot> slots = new ArrayList<Slot>();
        /** 整行那一块玻璃已经用掉了（一行只挂得下一个背景） */
        boolean rowTaken;
        /** 这块行玻璃只准出现在屏幕的哪一段 */
        int zone = GlassStripDrawable.ZONE_ANY;
        /** 顶栏这种要一块一块隔开的行 */
        boolean spaced;
        /** 连续几次判成不在了（单帧不算） */
        int miss;
        private final Rect region = new Rect();

        Bar(ViewGroup row, Backdrop backdrop, float density) {
            this.row = row;
            this.backdrop = backdrop;
            this.density = density;
        }

        boolean alive() {
            try {
                if (!row.isAttachedToWindow() || !row.isShown()) return false;
                if (row.getWidth() <= 0 || row.getHeight() <= 0) return false;
                for (Slot s : slots) {
                    if (!s.view.isAttachedToWindow() || s.view.getWidth() <= 0) return false;
                }
                return !slots.isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        void mount() {
            for (Slot s : slots) s.assertMine(true);
        }

        void reassert() {
            for (Slot s : slots) s.assertMine(false);
        }

        /** 宿主自己动了（tab 滑动、横竖屏）：每块玻璃重新对齐一次 */
        void refreshWindows() {
            for (Slot s : slots) s.refreshWindow();
        }

        void invalidate() {
            for (Slot s : slots) s.invalidate();
        }

        void capture() {
            int pad = Math.round(10f * density);
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE;
            int r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
            int[] wl = new int[2];
            for (Slot s : slots) {
                s.refreshWindow();
                s.view.getLocationInWindow(wl);
                l = Math.min(l, wl[0]);
                t = Math.min(t, wl[1]);
                r = Math.max(r, wl[0] + s.view.getWidth());
                b = Math.max(b, wl[1] + s.view.getHeight());
            }
            if (l == Integer.MAX_VALUE) return;
            region.set(l - pad, t - pad, r + pad, b + pad);
            backdrop.setRegion(region);
            backdrop.capture();
        }

        void release() {
            for (Slot s : slots) s.restore();
            slots.clear();
            backdrop.release();
        }

        /** 结构没变时不要重建，但要丢掉多建出来的那套背景 */
        void releaseBackdropOnly() {
            backdrop.release();
        }
    }

    private static final class Slot {
        final View view;
        final GlassStripDrawable glass;
        private Drawable original;
        private long lastFix;

        /** 挂在整行上的那块玻璃：记住这一组是哪几个控件，位置变了要跟着挪 */
        private List<View> windowGroup;
        private ViewGroup windowRow;
        private float windowDensity;

        Slot(View view, GlassStripDrawable glass) {
            this.view = view;
            this.glass = glass;
        }

        /**
         * 把玻璃安回去。
         *
         * 宿主随时可能把底色重设一遍（切 tab、选中态、主题刷新都会），
         * 不盯着的话玻璃就「用着用着没了」—— 上一版就是这么失效的。
         */
        void assertMine(boolean force) {
            try {
                Drawable cur = view.getBackground();
                if (cur == glass) return;
                long now = SystemClock.uptimeMillis();
                if (!force && now - lastFix < 220L) return;
                lastFix = now;
                original = cur;
                view.setBackground(glass);
                view.invalidate();
            } catch (Throwable ignored) {
            }
        }


        void trackWindow(List<View> group, ViewGroup row, float density) {
            windowGroup = group;
            windowRow = row;
            windowDensity = density;
        }

        /** 行里的位置变了（横竖屏、tab 滑动）：这块玻璃跟着重新对齐 */
        void refreshWindow() {
            if (windowGroup == null || windowRow == null) return;
            RectF r = boundsIn(windowGroup, windowRow, windowDensity);
            if (r != null) glass.setWindow(r);
        }

        void invalidate() {
            try {
                view.invalidate();
            } catch (Throwable ignored) {
            }
        }

        /** 还原宿主原来的底色（只在现在挂的还是我们这块时） */
        void restore() {
            try {
                if (view.getBackground() == glass) {
                    view.setBackground(original);
                    view.invalidate();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
