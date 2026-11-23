package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_MS = 300L;

    private static final WeakHashMap<Activity, Boolean> DONE =
            new WeakHashMap<Activity, Boolean>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 只弹一次的提示文案，避免每个页面都提示一遍。 */
    private static final java.util.HashSet<String> TOASTED = new java.util.HashSet<String>();

    /** 每个 Activity 识别出的官方 Dock。 */
    private static final WeakHashMap<Activity, View> DOCKS = new WeakHashMap<Activity, View>();
    /** Host video view currently holding a recognized long press. */
    private static final WeakHashMap<Activity, View> LONG_PRESS_TARGETS =
            new WeakHashMap<Activity, View>();
    /** 我们盖上去的那层玻璃，Activity 走的时候要拆掉、把官方底栏还回去 */
    private static final WeakHashMap<Activity, ComposeDockOverlay> OVERLAYS =
            new WeakHashMap<Activity, ComposeDockOverlay>();
    /** Keep the per-Activity poller cancellable so destroyed pages do not retain work. */
    private static final WeakHashMap<Activity, Runnable> POLLERS =
            new WeakHashMap<Activity, Runnable>();
    private static final WeakHashMap<Activity, boolean[]> POLL_STATES =
            new WeakHashMap<Activity, boolean[]>();
    /** 手指按住视频的起点，用来判断「长按倍速」——抖音自己不一定走 performLongClick。 */
    private static final WeakHashMap<Activity, long[]> PRESS =
            new WeakHashMap<Activity, long[]>();
    /** 抖音长按大约 400ms 进入倍速，这里取 420ms 作为判定线。 */
    private static final long PRESS_SPEED_DELAY_MS = 420L;
    /**
     * 清屏是双指各自往外拉的手势，抖音不一定会留下可读的文字标记，
     * 所以这里自己认手势：双指按下记初始间距，拉开到 1.45 倍就进清屏态；
     * 之后单指点一下屏幕（抖音的退出方式）就恢复。
     */
    private static final WeakHashMap<Activity, long[]> SPREAD =
            new WeakHashMap<Activity, long[]>();
    /** 清屏态：0=未进入，其它=进入时间（毫秒）。 */
    private static final WeakHashMap<Activity, long[]> CLEAR_MODE =
            new WeakHashMap<Activity, long[]>();
    /** 进入清屏后是否真的观察到宿主 UI 被隐藏过；没观察到就说明这只是个缩放手势。 */
    private static final WeakHashMap<Activity, boolean[]> CLEAR_CONFIRMED =
            new WeakHashMap<Activity, boolean[]>();
    /** 清屏态下手指落点，用来识别「左上角关闭」和「上/下滑到别的视频」。 */
    private static final WeakHashMap<Activity, float[]> CLEAR_TOUCH =
            new WeakHashMap<Activity, float[]>();

    private DockController() {
    }

    /** Called by the Xposed View hooks when Douyin accepts a long press. */
    static void onHostLongPress(View view) {
        Activity act = Shield.activityOf(view);
        if (act == null || !Shield.isVideoLongPressTarget(view, act)) return;
        synchronized (LONG_PRESS_TARGETS) {
            LONG_PRESS_TARGETS.put(act, view);
        }
        Dydock.log("宿主视频长按已识别，隐藏 Dock");
    }

    /**
     * 统管视频上的触摸：按下记时间，滑动超过阈值就作废，抬手取消。
     *
     * 抖音的长按倍速是它自己的手势识别器触发的，不一定走 View.performLongClick，
     * 所以只挂那条回调在真机上经常「长按了但底栏还在」。这里按「按住不放」来判断：
     * 按下 420ms 没怎么动，就当倍速开始，抬手立刻恢复。
     */
    static void onHostTouch(View view, android.view.MotionEvent event) {
        if (view == null || event == null) return;
        Activity act = Shield.activityOf(view);
        if (act == null) return;
        int action = event.getActionMasked();
        int pointers = event.getPointerCount();
        if (action == android.view.MotionEvent.ACTION_POINTER_DOWN && pointers >= 2) {
            synchronized (SPREAD) {
                SPREAD.put(act, new long[]{android.os.SystemClock.uptimeMillis(),
                        (long) spanOf(event)});
            }
        } else if (action == android.view.MotionEvent.ACTION_MOVE && pointers >= 2) {
            long[] spread;
            synchronized (SPREAD) { spread = SPREAD.get(act); }
            if (spread != null) {
                float density = act.getResources().getDisplayMetrics().density;
                float now = spanOf(event);
                // 反方向拉（两指往回收）= 抖音的退出清屏。
                if (isGestureClearMode(act) && now < spread[1] * 0.72f - 24f * density) {
                    synchronized (SPREAD) { SPREAD.remove(act); }
                    exitGestureClearMode(act, "双指内收");
                    return;
                }
                // 往外拉：两指间距明显变大（缩放一点不算）
                if (now > spread[1] * 1.45f + 48f * density) {
                    synchronized (SPREAD) { SPREAD.remove(act); }
                    boolean wasClear;
                    synchronized (CLEAR_MODE) {
                        wasClear = CLEAR_MODE.remove(act) != null;
                        if (!wasClear) {
                            CLEAR_MODE.put(act, new long[]{android.os.SystemClock.uptimeMillis()});
                        }
                    }
                    synchronized (CLEAR_CONFIRMED) {
                        if (wasClear) CLEAR_CONFIRMED.remove(act);
                        else CLEAR_CONFIRMED.put(act, new boolean[]{false});
                    }
                    Dydock.log(wasClear
                            ? "再次双指外扩，退出清屏态"
                            : "识别到双指外扩，按清屏处理，收起 Dock");
                }
            }
        } else if (action == android.view.MotionEvent.ACTION_POINTER_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            synchronized (SPREAD) { SPREAD.remove(act); }
        }
        if (isGestureClearMode(act)) {
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                synchronized (CLEAR_TOUCH) {
                    CLEAR_TOUCH.put(act, new float[]{event.getRawX(), event.getRawY()});
                }
            } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                float[] start;
                synchronized (CLEAR_TOUCH) { start = CLEAR_TOUCH.get(act); }
                if (start != null) {
                    float density = act.getResources().getDisplayMetrics().density;
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    // 往上/往下划到别的视频 = 退出清屏。
                    if (Math.abs(dy) > 72f * density && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                        synchronized (CLEAR_TOUCH) { CLEAR_TOUCH.remove(act); }
                        exitGestureClearMode(act, "划到别的视频");
                        return;
                    }
                }
            } else if (action == android.view.MotionEvent.ACTION_UP) {
                float[] start;
                synchronized (CLEAR_TOUCH) { start = CLEAR_TOUCH.remove(act); }
                if (start != null) {
                    View decor = act.getWindow() == null ? null : act.getWindow().peekDecorView();
                    int w = decor == null ? 0 : decor.getWidth();
                    int h = decor == null ? 0 : decor.getHeight();
                    // 左上角的关闭按钮。
                    if (w > 0 && h > 0
                            && start[0] <= w * 0.24f && start[1] <= h * 0.22f) {
                        exitGestureClearMode(act, "左上角关闭");
                        return;
                    }
                }
            }
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            if (!Shield.isVideoLongPressTarget(view, act)) return;
            synchronized (PRESS) {
                PRESS.put(act, new long[]{android.os.SystemClock.uptimeMillis(),
                        (long) event.getRawX(), (long) event.getRawY()});
            }
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            long[] start;
            synchronized (PRESS) { start = PRESS.get(act); }
            if (start == null) return;
            float slop = 18f * act.getResources().getDisplayMetrics().density;
            float dx = event.getRawX() - start[1];
            float dy = event.getRawY() - start[2];
            if (dx * dx + dy * dy > slop * slop) {
                synchronized (PRESS) { PRESS.remove(act); }
            }
        } else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            synchronized (PRESS) { PRESS.remove(act); }
            onHostTouchEnd(view, action);
        }
    }

    /** 退出由手势（或关闭按钮 / 返回键）判定出来的清屏态。 */
    private static void exitGestureClearMode(Activity act, String reason) {
        boolean was;
        synchronized (CLEAR_MODE) { was = CLEAR_MODE.remove(act) != null; }
        synchronized (CLEAR_CONFIRMED) { CLEAR_CONFIRMED.remove(act); }
        synchronized (CLEAR_TOUCH) { CLEAR_TOUCH.remove(act); }
        if (was) Dydock.log("退出清屏（" + reason + "），恢复 Dock");
    }

    /** 系统侧边返回：宿主处理返回键时，清屏态一并结束。 */
    static void onHostBack(Activity act) {
        if (act == null) return;
        exitGestureClearMode(act, "返回");
    }

    /** 多指手势时第 0、1 根手指的间距。 */
    private static float spanOf(android.view.MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 双指外扩判定出来的清屏态。 */
    private static boolean isGestureClearMode(Activity act) {
        synchronized (CLEAR_MODE) { return CLEAR_MODE.containsKey(act); }
    }

    /**
     * 宿主自己有没有把界面藏起来。
     *
     * 清屏时抖音会把底栏所在的容器一起隐藏；模块只把原生 Dock 自己设成 GONE，
     * 所以往上找几层祖先仍然是「宿主真实状态」——它们一旦不可见，就是进了清屏。
     * 退出清屏时这些祖先会重新可见，这就是恢复 Dock 的唯一信号。
     */
    private static boolean isHostChromeHidden(View dock) {
        if (dock == null) return false;
        int windowHeight = dock.getRootView().getHeight();
        android.view.ViewParent parent = dock.getParent();
        for (int i = 0; i < 4 && parent instanceof View; i++) {
            View v = (View) parent;
            if (v.getVisibility() != View.VISIBLE || !v.isShown() || v.getAlpha() < 0.05f) {
                return true;
            }
            // 有的版本不是隐藏而是把整条底栏平移到屏幕外。
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            if (windowHeight > 0
                    && (loc[1] >= windowHeight - 1 || loc[1] + v.getHeight() <= 1)) {
                return true;
            }
            parent = v.getParent();
        }
        return false;
    }

    /** 清屏状态推进：确认过一次隐藏之后，看到宿主 UI 回来就算退出。 */
    private static boolean updateGestureClearMode(Activity act, View dock, boolean markerClear) {
        long[] enteredAt;
        synchronized (CLEAR_MODE) { enteredAt = CLEAR_MODE.get(act); }
        if (enteredAt == null) return false;

        boolean hidden = markerClear || isHostChromeHidden(dock);
        boolean[] confirmed;
        synchronized (CLEAR_CONFIRMED) { confirmed = CLEAR_CONFIRMED.get(act); }
        if (confirmed == null) {
            confirmed = new boolean[]{false};
            synchronized (CLEAR_CONFIRMED) { CLEAR_CONFIRMED.put(act, confirmed); }
        }

        synchronized (confirmed) {
            if (hidden) {
                confirmed[0] = true;
                return true;
            }
            if (!confirmed[0]) {
                // 还没看到宿主把界面藏起来：继续等。退出由下面几种明确信号负责
                // （双指内收 / 左上角关闭 / 划到别的视频 / 返回键 / 宿主 UI 重新出现），
                // 不按时间猜，避免清屏还没结束 Dock 就冒出来。
                return true;
            }
        }
        // 确认过隐藏、现在宿主 UI 又出来了 = 退出清屏。
        synchronized (CLEAR_MODE) { CLEAR_MODE.remove(act); }
        synchronized (CLEAR_CONFIRMED) { CLEAR_CONFIRMED.remove(act); }
        Dydock.log("宿主 UI 恢复显示，退出清屏态");
        return false;
    }

    /** Release only the same view's long-press state after its UP/CANCEL is dispatched. */
    static void onHostTouchEnd(View view, int action) {
        if (view == null || (action != android.view.MotionEvent.ACTION_UP
                && action != android.view.MotionEvent.ACTION_CANCEL)) return;
        Activity act = Shield.activityOf(view);
        if (act == null) return;
        boolean removed = false;
        synchronized (LONG_PRESS_TARGETS) {
            if (LONG_PRESS_TARGETS.get(act) == view) {
                LONG_PRESS_TARGETS.remove(act);
                removed = true;
            }
        }
        if (removed) Dydock.log("宿主视频长按结束，检查其他隐藏状态");
    }

    private static boolean isHostLongPressActive(Activity act) {
        synchronized (LONG_PRESS_TARGETS) {
            if (LONG_PRESS_TARGETS.containsKey(act)) return true;
        }
        long[] start;
        synchronized (PRESS) { start = PRESS.get(act); }
        return start != null
                && android.os.SystemClock.uptimeMillis() - start[0] >= PRESS_SPEED_DELAY_MS;
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
        synchronized (LONG_PRESS_TARGETS) { LONG_PRESS_TARGETS.remove(act); }
        synchronized (PRESS) { PRESS.remove(act); }
        synchronized (SPREAD) { SPREAD.remove(act); }
        synchronized (CLEAR_MODE) { CLEAR_MODE.remove(act); }
        synchronized (CLEAR_CONFIRMED) { CLEAR_CONFIRMED.remove(act); }
        synchronized (CLEAR_TOUCH) { CLEAR_TOUCH.remove(act); }
        View dock;
        ComposeDockOverlay overlay;
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
                        toast(act, "Dydock " + Dydock.VER + "：没认出抖音底栏");
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
        // Miuix 自己在 GPU 上做 4dp 模糊，这里只决定采样分辨率。
        backdrop.setSampleScale();

        final ComposeDockOverlay overlay = ComposeDockOverlay.create(act, backdrop, density);
        overlay.setTabs(left, plus);
        // Refresh the sampled background as feed content and video frames change.
        backdrop.setOnFrame(new Runnable() {
            @Override public void run() {
                if (overlay.isAttachedToWindow()) overlay.refreshBackdrop();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ((ViewGroup) decor).addView(overlay, lp);
        overlay.setBand(rectInWindow(dock));
        // 首次识别只用于确定底部位置；后续玻璃不再追随原生 Dock。
        overlay.pinPosition();

        // Capture only after the Dock band is known, then remove the native Dock
        // from layout so it reserves no blank bottom strip. The overlay retains the
        // captured band coordinates independently of the native View's new bounds.
        dock.setVisibility(View.GONE);
        try {
            backdrop.capture();
        } catch (Throwable t) {
            Dydock.warnOnce("native-dock-hide", "移除原生底栏占位失败: " + t);
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
        final long[] lastClearScanAt = new long[]{0L};
        final boolean[] clearScreenState = new boolean[]{false};
        final boolean[] speedPlaybackState = new boolean[]{false};
        final Runnable backdropRefresh = new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    if (overlay.isAttachedToWindow() && overlay.getVisibility() == View.VISIBLE) {
                        backdrop.capture();
                        overlay.refreshBackdrop();
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
                    // Compose/Miuix 在宿主里彻底画不出来（抛过错）：立刻停掉轮询，
                    // 把官方底栏还回去，别留一条空白、也别再画。
                    if (overlay.isBroken()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        MAIN.removeCallbacks(backdropRefresh);
                        synchronized (POLLERS) { POLLERS.remove(act); }
                        synchronized (POLL_STATES) { POLL_STATES.remove(act); }
                        View d0 = dockRef[0];
                        try {
                            if (d0 != null) {
                                d0.setVisibility(View.VISIBLE);
                                d0.setAlpha(1f);
                            }
                            overlay.setVisibility(View.GONE);
                        } catch (Throwable ignored) {
                        }
                        Dydock.warn("底栏:玻璃绘制框架出错，已恢复官方 Dock");
                        return;
                    }
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
                    if (d == null || !d.isAttachedToWindow()) {
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
                                    fresh.setVisibility(View.GONE);
                                    dockRef[0] = fresh;
                                    overlay.setTabs(lf, p);
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

                    boolean longPress = isHostLongPressActive(act);
                    // 每 40ms 走一遍视图树太贵，这两项改成定量节流；长按本身由触摸状态给，
                    // 所以隐藏仍然是实时的。
                    if (now - lastClearScanAt[0] >= 150L) {
                        speedPlaybackState[0] = Shield.hasSpeedPlaybackOverlay(decor);
                        clearScreenState[0] = Shield.hasClearScreenState(decor);
                        lastClearScanAt[0] = now;
                    }
                    boolean speedPlayback = speedPlaybackState[0];
                    boolean clearScreen = clearScreenState[0]
                            || updateGestureClearMode(act, d, clearScreenState[0]);
                    boolean expanded = d != null
                            && Shield.hasExpandedOverlay(d, content, decor);
                    expandedTicks[0] = expanded ? Math.min(2, expandedTicks[0] + 1) : 0;
                    // Every hide reason is independent: leaving one state must not reveal
                    // the Dock while another hide reason is still active.
                    boolean show = !speedPlayback && !longPress && !clearScreen
                            && expandedTicks[0] < 2;

                    // GONE removes the native Dock's reserved layout height; the Compose
                    // overlay keeps its captured window position and the feed can extend below it.
                    if (d != null && d.getVisibility() != View.GONE) d.setVisibility(View.GONE);
                    if (show != overlayTargetVisible[0]) {
                        overlayTargetVisible[0] = show;
                        overlay.animate().cancel();
                        if (show) {
                            if (overlay.getVisibility() != View.VISIBLE) {
                                overlay.setVisibility(View.VISIBLE);
                            }
                            // 从当前透明度继续，避免反向切换时闪一下。
                            overlay.animate().alpha(1f).setDuration(28L).start();
                        } else {
                            overlay.animate().alpha(0f).setDuration(24L)
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
                MAIN.postDelayed(this, 40L);
            }
        };
        synchronized (POLLERS) { POLLERS.put(act, visibilityPoll); }
        synchronized (POLL_STATES) { POLL_STATES.put(act, live); }
        MAIN.postDelayed(visibilityPoll, 40L);


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
                    // Fail safe: if Compose never draws, restore the native navigation
                    // instead of leaving the user with an empty bottom strip.
                    if (d0 != null) {
                        d0.setVisibility(View.VISIBLE);
                        d0.setAlpha(1f);
                    }
                    if (overlay.getVisibility() == View.VISIBLE) {
                        overlay.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
                Dydock.warn("底栏:玻璃未能绘制，已恢复原生 Dock 以避免底部空白");
            }
        }, 2500L);

        toast(act, "Dydock " + Dydock.VER + "：玻璃底栏已接管");
        Dydock.log("底栏已接管：" + left.size() + " 个图标"
                + (plus != null ? " ＋ 独立一块" : "")
                + "，底栏 " + dock.getWidth() + "x" + dock.getHeight()
                + "（" + dock.getClass().getSimpleName() + "）");
        return true;
    }

    /** 一次性提示：只在本次进程里弹一次，用来一眼确认模块到底有没有接管到。 */
    private static void toast(final Activity act, final String text) {
        if (act == null) return;
        synchronized (TOASTED) {
            if (!TOASTED.add(text)) return;
        }
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    android.widget.Toast.makeText(act.getApplicationContext(), text,
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static Rect rectInWindow(View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    // ------------------------------------------------------------------ 认底栏

    /**
     * 抖音底栏识别。
     *
     * 老版本是「必须正好 5 个子项 + 首页/朋友/消息/我 四个文字都在」一票否决。
     * 抖音改版、换皮、文字被画进自绘 Canvas、角标把文字节点替换掉……只要命中一种，
     * 就一辈子认不出来，用户看到的就是「装了模块，底栏一点没变」。
     *
     * 现在改成打分：先找「贴着屏幕底边的一排可点控件」，再按文字、个数、选中态、
     * 中间那个发布按钮这些证据累加分数。分数不够就地不动 —— 宁可不接管，
     * 也不能把正文里的一排按钮当成底栏去替换。
     */
    static final class Finder {

        private static final int MAX_DEPTH = 44;
        /** 已经攒够分数的门槛：≥70 直接认，55~70 要连续两帧都看到同一个才认。 */
        private static final float STRONG = 70f;
        private static final float ACCEPT = 55f;

        private static java.lang.ref.WeakReference<View> pending;
        private static int pendingHits;

        static ViewGroup findDock(View root) {
            if (root == null) return null;
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return null;
            Best best = new Best();
            walk(root, root, best, 0, new int[]{2600});
            if (best.view == null || best.score < ACCEPT) {
                pending = null;
                pendingHits = 0;
                return null;
            }
            View last = pending == null ? null : pending.get();
            if (last == best.view) pendingHits++;
            else {
                pending = new java.lang.ref.WeakReference<View>(best.view);
                pendingHits = 1;
            }
            if (best.score >= STRONG || pendingHits >= 2) return best.view;
            return null;
        }

        private static final class Best {
            ViewGroup view;
            float score = -1f;
        }

        private static void walk(View root, View node, Best best, int depth, int[] budget) {
            if (node == null || depth > MAX_DEPTH || budget[0]-- <= 0) return;
            if (node instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) node;
                float score = score(g, root);
                if (score > best.score) {
                    best.score = score;
                    best.view = g;
                }
                for (int i = 0, n = g.getChildCount(); i < n && budget[0] > 0; i++) {
                    walk(root, g.getChildAt(i), best, depth + 1, budget);
                }
            }
        }

        /** 给一个容器打「它像不像抖音底栏」的分；不像就返回 -1。 */
        private static float score(ViewGroup g, View root) {
            String cn = g.getClass().getName();
            if (cn.startsWith("com.zaka.douyin.glass")) return -1f;
            if (!g.isShown() || g.getAlpha() < 0.2f) return -1f;

            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return -1f;

            int w = g.getWidth();
            int h = g.getHeight();
            float d = g.getResources().getDisplayMetrics().density;
            if (w < rw * 0.55f || h <= 0) return -1f;
            float hDp = h / d;
            if (hDp < 30f || hDp > 150f) return -1f;

            int[] rl = new int[2];
            int[] gl = new int[2];
            try {
                root.getLocationOnScreen(rl);
                g.getLocationOnScreen(gl);
            } catch (Throwable t) {
                return -1f;
            }
            int rootBottom = rl[1] + rh;
            int gTop = gl[1] - rl[1];
            int gBottom = gTop + h;
            // 只认贴底那一条；正文滚动到一半经过底部的一排按钮会被这里刷掉。
            if (gBottom < rh - (int) (120 * d)) return -1f;
            if (gTop < rh - (int) (260 * d)) return -1f;
            if (hasInput(g)) return -1f;   // 聊天页输入条不是底栏

            List<View> items = rowItems(g);
            if (items == null) return -1f;

            int count = items.size();
            boolean home = false, friends = false, messages = false, profile = false, anyLabel = false;
            for (int i = 0; i < count; i++) {
                String label = navigationLabel(items.get(i), 0);
                if (label.isEmpty()) continue;
                anyLabel = true;
                if ("首页".equals(label)) home = true;
                else if ("朋友".equals(label) || "好友".equals(label)) friends = true;
                else if ("消息".equals(label)) messages = true;
                else if ("我".equals(label) || "个人主页".equals(label)) profile = true;
            }

            View plus = pickPlus(items);
            boolean middlePlus = count == 5 && plus == items.get(2);

            // 没有任何文字证据、结构也不像 5 格发布条：直接不算，避免误伤正文列表。
            if (!anyLabel && !middlePlus) return -1f;

            float score = 0f;
            if (home) score += 45f;
            if (friends) score += 18f;
            if (messages) score += 18f;
            if (profile) score += 18f;
            if (count == 5) score += 12f;
            else if (count == 4) score += 6f;

            int gapToBottom = rootBottom - (gl[1] + h);
            if (gapToBottom >= -(int) (6 * d) && gapToBottom <= (int) (14 * d)) score += 14f;
            else if (gapToBottom > (int) (90 * d)) score -= 20f;

            for (int i = 0; i < count; i++) {
                if (selectedDeep(items.get(i), 0)) {
                    score += 10f;
                    break;
                }
            }
            if (plus != null) score += 10f;
            if (middlePlus) score += 12f;
            return score;
        }

        /** 底栏里那一排可点的子项（从左到右）；不像一排就返回 null。 */
        static List<View> tabItems(ViewGroup g) {
            List<View> row = rowItems(g);
            return row == null ? new ArrayList<View>() : row;
        }

        private static List<View> rowItems(ViewGroup g) {
            List<View> direct = collectRow(g);
            if (direct != null) return direct;
            // 有些版本每个 tab 外面还套了一层容器：摊平一层再看。
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View c = g.getChildAt(i);
                if (c instanceof ViewGroup && c.getVisibility() == View.VISIBLE) {
                    List<View> inner = collectRow((ViewGroup) c);
                    if (inner != null && inner.size() >= 4) return inner;
                }
            }
            return null;
        }

        private static List<View> collectRow(ViewGroup g) {
            List<View> out = new ArrayList<View>();
            List<View> all = new ArrayList<View>();
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                if (c.getAlpha() < 0.2f) continue;
                if (c.getWidth() < 8 || c.getHeight() < 8) continue;
                all.add(c);
                if (clickableish(c)) out.add(c);
            }
            // 有的版本把点击挂在里面的图标上、外层容器不可点：可点的凑不够就放宽，
            // 只要求是同一排、横着铺开、高度对得上（后面还有贴底 + 文字证据把关）。
            if (out.size() < 4) out = all;
            if (out.size() < 4 || out.size() > 7) return null;

            Collections.sort(out, new Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    return Integer.compare(a.getLeft(), b.getLeft());
                }
            });

            // 必须是一排（垂直范围基本重合）
            int top = out.get(0).getTop();
            int hh = Math.max(1, out.get(0).getBottom() - top);
            for (View v : out) {
                if (Math.abs(v.getTop() - top) > hh * 0.45f) return null;
            }
            // 宽度不能差太远（中间那个发布按钮会小一些，给足余量）
            int max = 1, min = Integer.MAX_VALUE;
            for (View v : out) {
                max = Math.max(max, v.getWidth());
                min = Math.min(min, v.getWidth());
            }
            if (min < max * 0.22f) return null;
            // 得横着铺开，不能挤在一角
            int left = out.get(0).getLeft();
            int right = out.get(out.size() - 1).getRight();
            int w = g.getWidth();
            if (w <= 0) return null;
            if (left > w * 0.22f || right < w * 0.78f) return null;
            return out;
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
            if (items == null) return null;
            for (View v : items) {
                if (looksLikePlus(v)) return v;
            }
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
