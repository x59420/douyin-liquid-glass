package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Bitmap;
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
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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
    /**
     * 显示前的确认帧数。
     *
     * 隐藏跟着「是否被盖住 / 是否在倍速」这些明确状态走，成立就立刻收；
     * 显示反过来用同一套判据，但要连续这么多帧都干净才露出来 ——
     * 滚动结束、浮层收起、页面转场收尾的那一两帧会短暂「看起来没被盖住」，
     * 直接跟着它就会闪。16ms 一帧，6 帧约 100ms，感觉不到延迟。
     */
    private static final int SHOW_CONFIRM_FRAMES = 6;
    /** 「整屏页面盖住 Dock」需要连续命中的帧数（60ms 采一次，两帧约 120ms）。 */
    private static final int COVER_CONFIRM_TICKS = 2;

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
    /** 上一次识别到长按的系统时间，用来兜底清理没等到 UP 的残留。 */
    private static final WeakHashMap<Activity, long[]> LONG_PRESS_AT =
            new WeakHashMap<Activity, long[]>();
    /** 我们盖上去的那层玻璃，Activity 走的时候要拆掉、把官方底栏还回去 */
    private static final WeakHashMap<Activity, IsoDock> OVERLAYS =
            new WeakHashMap<Activity, IsoDock>();
    /** Keep the per-Activity poller cancellable so destroyed pages do not retain work. */
    private static final WeakHashMap<Activity, Runnable> POLLERS =
            new WeakHashMap<Activity, Runnable>();
    private static final WeakHashMap<Activity, boolean[]> POLL_STATES =
            new WeakHashMap<Activity, boolean[]>();
    /** 每个 Activity 的背景采样器：设置改了要立刻重算几何，得能拿回它。 */
    private static final WeakHashMap<Activity, Backdrop> BACKDROPS =
            new WeakHashMap<Activity, Backdrop>();
    /** 原生底栏那一带的窗口坐标（底栏被设成 GONE 之后就只能靠这份缓存）。 */
    private static final WeakHashMap<Activity, Rect> BANDS =
            new WeakHashMap<Activity, Rect>();
    /** 见过的宿主页面：设置里重新打开开关时，要能把它们重新接管一遍。 */
    private static final WeakHashMap<Activity, Boolean> KNOWN =
            new WeakHashMap<Activity, Boolean>();
    /** 手指按住视频的起点，用来判断「长按倍速」——抖音自己不一定走 performLongClick。 */
    private static final WeakHashMap<Activity, long[]> PRESS =
            new WeakHashMap<Activity, long[]>();
    /** 记录这条按住状态是由哪个 View 起的头，别的 View 收到的同一串事件不参与判定。 */
    private static final WeakHashMap<Activity, View> PRESS_TARGET =
            new WeakHashMap<Activity, View>();
    /**
     * 长按倍速的判定线。
     *
     * 抖音自己进倍速比这更早，原来的 420ms 加上 40ms 轮询，手感上就是「按住半天
     * Dock 才消失」。这里压到 240ms，再配合 16ms 的轮询，基本是按住就跟着收。
     */
    private static final long PRESS_SPEED_DELAY_MS = 240L;
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
        // 只视频页才认长按倍速。消息列表 / 我的页按住不放不是倍速，
        // 以前这里一把抓，手指停在列表上超过判定线 Dock 就消失，回来又冒出来。
        View content = act.findViewById(android.R.id.content);
        if (content == null || !Shield.hasVideoSurface(content)) return;
        synchronized (LONG_PRESS_TARGETS) {
            LONG_PRESS_TARGETS.put(act, view);
        }
        synchronized (LONG_PRESS_AT) {
            LONG_PRESS_AT.put(act, new long[]{android.os.SystemClock.uptimeMillis()});
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
        if (action == android.view.MotionEvent.ACTION_POINTER_DOWN && pointers == 2) {
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
                // 往外拉：两指间距明显变大。原来要求 1.45 倍 + 48dp，实测真机上
                // 手指拉开的速度和幅度都比这个小，基本触发不到；这里放宽到 1.18 倍 + 36dp。
                if (now > spread[1] * 1.18f + 36f * density) {
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
            synchronized (PRESS_TARGET) { PRESS_TARGET.put(act, view); }
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            long[] start;
            synchronized (PRESS) { start = PRESS.get(act); }
            if (start == null) return;
            // 同一串触摸会流过分发链上的每个 View，只认最开始那个按住目标。
            View pressTarget;
            synchronized (PRESS_TARGET) { pressTarget = PRESS_TARGET.get(act); }
            if (pressTarget != null && pressTarget != view) return;
            float slop = 18f * act.getResources().getDisplayMetrics().density;
            float dx = event.getRawX() - start[1];
            float dy = event.getRawY() - start[2];
            if (dx * dx + dy * dy > slop * slop) {
                synchronized (PRESS) { PRESS.remove(act); }
                synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
            }
        } else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            synchronized (PRESS) { PRESS.remove(act); }
            synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
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

    /**
     * 接管那一刻，宿主底栏上面几层祖先容器的可见性快照。
     *
     * 0.4.0 是直接看「父链上有没有 GONE / alpha≈0 的祖先」，结果抖音的底栏外壳本身就带一层
     * 常驻不可见的容器（我们又把底栏本身设成了 GONE，外层壳跟着收缩也常见），判据一装上就恒成立 ——
     * 接管大约 400ms 后玻璃被自己的判据收掉，用户看到的就是「Dock 出来一下立马消失」。
     *
     * 现在只认**变化**：接管时明明可见、接管之后不可见了，才算「宿主把底栏藏了」。
     * 常驻隐藏的层、我们自己的玻璃层、content / decor 这两个根节点都不参与判断。
     */
    private static final class ChromeSnapshot {
        private static final int DEPTH = 5;

        private final View content;
        private final View decor;
        private final int[] baseVis = new int[DEPTH];
        private final float[] baseAlpha = new float[DEPTH];

        ChromeSnapshot(View dock, View content, View decor) {
            this.content = content;
            this.decor = decor;
            View v = dock;
            for (int i = 0; i < DEPTH; i++) {
                ViewParent parent = v == null ? null : v.getParent();
                if (!(parent instanceof View)) {
                    baseVis[i] = -1;
                    continue;
                }
                v = (View) parent;
                baseVis[i] = v.getVisibility();
                baseAlpha[i] = v.getAlpha();
            }
        }

        /** 接管后有没有哪一层祖先从「可见」变成了「不可见」。 */
        boolean isHiddenNow(View dock) {
            if (dock == null) return false;
            View v = dock;
            for (int i = 0; i < DEPTH; i++) {
                ViewParent parent = v.getParent();
                if (!(parent instanceof View) || baseVis[i] < 0) return false;
                v = (View) parent;
                if (v == content || v == decor || isOursGlass(v)) continue;
                // 接管前它就是隐藏/透明的，这层没有参考价值。
                if (baseVis[i] != View.VISIBLE || baseAlpha[i] < 0.05f) continue;
                if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return true;
            }
            return false;
        }

        private static boolean isOursGlass(View v) {
            try {
                return v.getClass().getName().startsWith("com.zaka.douyin.glass");
            } catch (Throwable t) {
                return false;
            }
        }
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
                synchronized (LONG_PRESS_AT) { LONG_PRESS_AT.remove(act); }
                removed = true;
            }
        }
        if (removed) Dydock.log("宿主视频长按结束，检查其他隐藏状态");
    }

    private static boolean isHostLongPressActive(Activity act, boolean pageHasVideo) {
        synchronized (LONG_PRESS_TARGETS) {
            if (LONG_PRESS_TARGETS.containsKey(act)) {
                // 兜底：宿主吞掉那次 UP 的话，状态会一直留着；过了 5 秒就当它结束了，
                // 否则回到视频页的第一帧 Dock 会莫名消失。
                long[] at;
                synchronized (LONG_PRESS_AT) { at = LONG_PRESS_AT.get(act); }
                if (at != null
                        && android.os.SystemClock.uptimeMillis() - at[0] > 5000L) {
                    LONG_PRESS_TARGETS.remove(act);
                    synchronized (LONG_PRESS_AT) { LONG_PRESS_AT.remove(act); }
                } else {
                    return pageHasVideo;
                }
            }
        }
        long[] start;
        synchronized (PRESS) { start = PRESS.get(act); }
        if (start == null) return false;
        // 当前页面没有视频层（消息列表、我的页、设置页）：按住不放不是倍速，不参与隐藏。
        if (!pageHasVideo) return false;
        long held = android.os.SystemClock.uptimeMillis() - start[0];
        // 兜底：UP 万一没回到我们这里，按住状态最多只认 4 秒，
        // 否则一次滑动就能让 Dock 一直藏着。
        if (held > 4000L) {
            synchronized (PRESS) { PRESS.remove(act); }
            synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
            return false;
        }
        return held >= PRESS_SPEED_DELAY_MS;
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
        synchronized (LONG_PRESS_AT) { LONG_PRESS_AT.remove(act); }
        synchronized (PRESS) { PRESS.remove(act); }
        synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
        synchronized (SPREAD) { SPREAD.remove(act); }
        synchronized (CLEAR_MODE) { CLEAR_MODE.remove(act); }
        synchronized (CLEAR_CONFIRMED) { CLEAR_CONFIRMED.remove(act); }
        synchronized (CLEAR_TOUCH) { CLEAR_TOUCH.remove(act); }
        View dock;
        IsoDock iso;
        synchronized (DOCKS) {
            dock = DOCKS.remove(act);
        }
        synchronized (OVERLAYS) {
            iso = OVERLAYS.remove(act);
        }
        synchronized (BACKDROPS) { BACKDROPS.remove(act); }
        synchronized (BANDS) { BANDS.remove(act); }
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
            if (iso != null) {
                iso.dispose();
                ViewParent p = iso.view.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(iso.view);
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
        synchronized (KNOWN) { KNOWN.put(act, Boolean.TRUE); }
        try {
            if (DONE.containsKey(act)) return;
            DONE.put(act, Boolean.TRUE);
            attempt(act, 0);
        } catch (Throwable t) {
            Dydock.error("底栏:处理 Activity", t);
        }
    }

    /** 设置页这类页面里根本没有 Dock，不必反复去认，免得误伤列表里的一排按钮。 */
    private static boolean looksLikeSettings(Activity act) {
        String n = act.getClass().getName().toLowerCase();
        return n.contains("setting");
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
                    if (looksLikeChat(act) || looksLikeSettings(act)) {
                        Dydock.log("底栏:这不是主界面（" + act.getClass().getSimpleName() + "），跳过");
                        return;
                    }
                    if (install(act)) return;
                    if (n < MAX_ATTEMPTS) {
                        attempt(act, n + 1);
                    } else if (n == MAX_ATTEMPTS) {
                        Dydock.warn("底栏:没认出底栏（最高候选 "
                                + Finder.lastBestClass + " 得分 " + Finder.lastBestScore + "）");
                        toast(act, "Dydock " + Dydock.VER + "：没认出抖音底栏");
                    }
                } catch (Throwable t) {
                    Dydock.error("底栏:安装", t);
                    if (n < MAX_ATTEMPTS) attempt(act, n + 1);
                }
            }
        }, n == 0 ? 120L : RETRY_MS);
    }

    /** 把当前成立的隐藏理由拼成一行，只在状态变化时写日志。 */
    private static String hideReason(boolean speedPlayback, boolean longPress, boolean clearScreen,
                                     int coveredTicks, int expandedTicks, int dockMissingTicks,
                                     int hostHiddenTicks) {
        StringBuilder sb = new StringBuilder();
        if (speedPlayback) sb.append("倍速播放 ");
        if (longPress) sb.append("长按加速 ");
        if (clearScreen) sb.append("清屏 ");
        if (coveredTicks >= COVER_CONFIRM_TICKS) sb.append("整屏覆盖页 ");
        if (expandedTicks >= COVER_CONFIRM_TICKS) sb.append("展开浮层 ");
        if (dockMissingTicks >= 12) sb.append("原生底栏脱离窗口 ");
        if (hostHiddenTicks >= 25) sb.append("宿主隐藏了底栏容器 ");
        if (sb.length() == 0) sb.append("复合判据");
        return sb.toString().trim();
    }

    private static boolean install(Activity act) {
        if (!DockPrefs.enabled(act)) {
            // 用户在设置里关掉了玻璃 Dock：原生底栏我们一概不碰。
            return true;
        }
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
        final List<View> initialTabs = new ArrayList<View>(items);
        if (plus != null) initialTabs.remove(plus);

        // 必须在把原生底栏设成 GONE 之前拍：之后父链的可见性变化才算「宿主把底栏藏了」。
        final ChromeSnapshot chrome = new ChromeSnapshot(dock, content, decor);

        // 原生底栏那一带的深色底/渐变是「玻璃一片黑」的来源：我们的采样是隔着它采的。
        // 把它拿掉之后，视频画面才能真正透到 Dock 后面来。
        stripDockBackground(dock);
        // 系统导航条那片区域：透明底、关掉对比度强制、内容延伸到底部。
        applyImmersiveNavigation(act);

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        // Miuix 自己在 GPU 上做 4dp 模糊，这里只决定采样分辨率。
        backdrop.setSampleScale();

        // 宿主节点被换掉时，点击回调里取到的永远是最新那批导航项。
        final List<View>[] tabTargets = new List[]{initialTabs};
        final View[] plusTarget = new View[]{plus};

        // 关键：玻璃层跑在独立类加载器里，不跟抖音自己的 androidx / compose 抢类。
        final IsoDock iso = IsoDock.create(act,
                new Consumer<String>() {
                    @Override public void accept(String message) {
                        Dydock.log("[玻璃] " + message);
                    }
                },
                new IntConsumer() {
                    @Override public void accept(int index) {
                        try {
                            List<View> targets = tabTargets[0];
                            View target = latestTab(act, targets, plusTarget[0], index);
                            if (target != null) clickHostView(target);
                        } catch (Throwable t) {
                            Dydock.warnOnce("iso-tab-click", "转发导航点击失败: " + t);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        try {
                            View target = latestPlus(act, plusTarget[0]);
                            if (target != null) clickHostView(target);
                        } catch (Throwable t) {
                            Dydock.warnOnce("iso-plus-click", "转发发布点击失败: " + t);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        try {
                            DockSettingsUi.showDialog(act);
                        } catch (Throwable t) {
                            Dydock.warnOnce("iso-longpress-settings", "长按打开设置失败: " + t);
                        }
                    }
                });

        if (iso == null) {
            // 隔离加载都起不来的话，宁可不接管，也不在宿主进程里硬塞一套会和抖音撞版本的 Compose。
            Dydock.warn("底栏:玻璃组件加载失败，保留抖音原生底栏");
            toast(act, "Dydock " + Dydock.VER + "：玻璃组件加载失败");
            return false;
        }

        ((ViewGroup) decor).addView(iso.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyGeometry(iso, backdrop, dock, initialTabs.size());
        refreshTabs(iso, initialTabs, plus);

        final View[] dockRef = new View[]{dock};
        final boolean[] live = new boolean[]{true};
        final long[] lastStamp = new long[]{Long.MIN_VALUE};
        synchronized (DOCKS) {
            DOCKS.put(act, dock);
        }
        synchronized (OVERLAYS) {
            OVERLAYS.put(act, iso);
        }
        synchronized (BACKDROPS) {
            BACKDROPS.put(act, backdrop);
        }

        backdrop.setOnFrame(new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    pushBackdrop(iso, backdrop, lastStamp);
                } catch (Throwable t) {
                    Dydock.warnOnce("iso-backdrop", "背景刷新失败: " + t);
                }
            }
        });

        // 原生底栏先让位：留在原位会被一起采进玻璃里。
        // 玻璃万一脸都没画出来，下面的看门狗会把原生底栏原样还回去。
        dock.setVisibility(View.GONE);
        try {
            backdrop.capture();
        } catch (Throwable t) {
            Dydock.warnOnce("native-dock-hide", "移除原生底栏占位失败: " + t);
        }

        final int[] expandedTicks = new int[]{0};
        final boolean[] overlayTargetVisible = new boolean[]{true};
        final long[] lastFindAt = new long[]{0L};
        final long[] lastClearScanAt = new long[]{0L};
        final long[] lastTabRefreshAt = new long[]{0L};
        final boolean[] clearScreenState = new boolean[]{false};
        final boolean[] speedPlaybackState = new boolean[]{false};
        final boolean[] expandedState = new boolean[]{false};
        final long[] lastExpandedAt = new long[]{0L};
        final int[] lastBandSig = new int[]{0};
        final long[] lastGeomAt = new long[]{0L};
        /** 当前这一页有没有视频层：长按倍速只在这类页面上成立，遍历成本高，240ms 采一次。 */
        final boolean[] pageHasVideo = new boolean[]{false};
        final long[] lastVideoScanAt = new long[]{0L};
        /** 「整屏页面盖住 Dock」的判定结果与连续命中帧数。 */
        final boolean[] coveredState = new boolean[]{false};
        final int[] coveredTicks = new int[]{0};
        final long[] lastCoveredAt = new long[]{0L};
        /** Dock 这一条的窗口坐标，applyGeometry 时存下来（原生 Dock 已被设成 GONE）。 */
        final Rect[] bandRect = new Rect[]{null};
        /**
         * 显示的确认帧数：隐藏立即生效，显示要连续 6 帧（约 100ms）都干净才露出来。
         * 滚动、转场、浮层收起这些过程里会有一两帧的空档，不去抖就会一闪一闪。
         */
        final int[] revealTicks = new int[]{SHOW_CONFIRM_FRAMES};
        final long[] lastFailLogAt = new long[]{0L};
        final int[] lastAccountedBandW = new int[]{-1};
        final int[] lastAccountedBandH = new int[]{-1};
        /** 背景采样的泵：只在宿主重绘过/区域变过的时候真的重画。 */
        final long[] lastBackdropPumpAt = new long[]{0L};
        /** 模块设置页（桌面图标）改配置的同步节流。 */
        final long[] lastPrefsSyncAt = new long[]{0L};
        /**
         * 原生底栏连续几帧不在窗口里。
         *
         * 会话页、聊天框里点开的视频页这些覆盖页，抖音会把主界面的底栏整个摘掉；
         * 这时候如果我们只看「有没有东西盖住底栏」，答案是「没有」，Dock 就会
         * 浮到会话页上（从聊天框看完视频返回时最常见）。底栏都不在了，本身就说明
         * 当前不在四个主 tab 页面上。
         */
        final int[] dockMissingTicks = new int[]{0};
        /** 宿主把底栏容器真正隐藏了：持续约 400ms 才认，滚动动画那一小段不算。 */
        final int[] hostHiddenTicks = new int[]{0};
        /** 这条隐藏理由第一次成立的时刻，用来做误报兜底。 */
        final long[] hostHiddenSince = new long[]{0L};
        /** 这条理由被认定成误报之后，本 Activity 内不再参与判断（否则底栏再也回不来）。 */
        final boolean[] chromeUntrusted = new boolean[]{false};
        /** 上一次隐藏是什么原因，只在变化时记一条日志，方便对着日志定位。 */
        final String[] lastHideReason = new String[]{""};

        final Runnable visibilityPoll = new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    if (iso.isBroken()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        synchronized (POLLERS) { POLLERS.remove(act); }
                        synchronized (POLL_STATES) { POLL_STATES.remove(act); }
                        detachOverlay(iso);
                        restoreNative(dockRef[0]);
                        Dydock.warn("底栏:玻璃渲染出错，已恢复官方 Dock");
                        toast(act, "Dydock " + Dydock.VER + "：玻璃渲染出错，已恢复官方底栏");
                        return;
                    }
                    if (act.isFinishing() || act.isDestroyed()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        synchronized (POLLERS) { POLLERS.remove(act); }
                        synchronized (POLL_STATES) { POLL_STATES.remove(act); }
                        return;
                    }
                    // 转场可能让覆盖层还挂在父容器上但已经脱离窗口。
                    if (!iso.view.isAttachedToWindow() && decor.isAttachedToWindow()) {
                        ViewParent parent = iso.view.getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(iso.view);
                        }
                        if (iso.view.getParent() == null) {
                            ((ViewGroup) decor).addView(iso.view, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            applyGeometry(iso, backdrop, dockRef[0], tabTargets[0].size());
                            backdrop.capture();
                            Dydock.log("底栏:玻璃层已重新挂回主窗口并刷新背景");
                        }
                    }

                    View d = dockRef[0];
                    long now = SystemClock.uptimeMillis();
                    if (d == null || !d.isAttachedToWindow()) {
                        // 原生节点不可用时才重新搜索：底栏刚被重建时要在 150ms 内接上，
                        // 否则「接不上 → 判成不在主页面 → 收起」会变成一次闪烁。
                        if (now - lastFindAt[0] >= 150L) {
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
                                    backdrop.setExcluded(fresh);
                                    tabTargets[0] = lf;
                                    plusTarget[0] = p;
                                    synchronized (DOCKS) {
                                        DOCKS.put(act, fresh);
                                    }
                                    stripDockBackground(fresh);
                                    refreshTabs(iso, lf, p);
                                    applyGeometry(iso, backdrop, fresh, lf.size());
                                    bandRect[0] = rectInWindow(fresh);
                                    synchronized (BANDS) { BANDS.put(act, bandRect[0]); }
                                    lastAccountedBandW[0] = fresh.getWidth();
                                    lastAccountedBandH[0] = fresh.getHeight();
                                    d = fresh;
                                    Dydock.log("底栏:已更新原生点击目标，玻璃位置保持固定");
                                }
                            }
                        }
                    }

                    if (now - lastTabRefreshAt[0] >= 500L) {
                        lastTabRefreshAt[0] = now;
                        // 点击目标跟着当前底栏走：抖音重建底栏后，旧的引用点了不会响。
                        if (d != null) {
                            List<View> fresh = Finder.tabItems(d);
                            View freshPlus = Finder.pickPlus(fresh);
                            List<View> freshTabs = new ArrayList<View>(fresh);
                            if (freshPlus != null) freshTabs.remove(freshPlus);
                            if (freshTabs.size() >= 4) {
                                tabTargets[0] = freshTabs;
                                plusTarget[0] = freshPlus;
                            }
                        }
                        refreshTabs(iso, tabTargets[0], plusTarget[0]);
                    }

                    // 走一遍视图树不便宜，这几项都定量节流；长按本身由触摸状态给，
                    // 所以「按住就收」仍然是实时的。
                    if (now - lastVideoScanAt[0] >= 240L) {
                        lastVideoScanAt[0] = now;
                        pageHasVideo[0] = Shield.hasVideoSurface(content);
                    }
                    boolean longPress = isHostLongPressActive(act, pageHasVideo[0]);
                    if (now - lastClearScanAt[0] >= 80L) {
                        speedPlaybackState[0] = Shield.hasSpeedPlaybackOverlay(decor);
                        clearScreenState[0] = Shield.hasClearScreenState(decor);
                        lastClearScanAt[0] = now;
                    }
                    boolean speedPlayback = speedPlaybackState[0];
                    boolean clearScreen = clearScreenState[0]
                            || updateGestureClearMode(act, d, clearScreenState[0]);

                    // 浮层扫描要遍历几百个节点，跟着 16ms 轮询跑会拖帧；60ms 一次足够。
                    if (now - lastExpandedAt[0] >= 60L) {
                        lastExpandedAt[0] = now;
                        expandedState[0] = d != null
                                && Shield.hasExpandedOverlay(d, content, decor);
                        // 整屏覆盖页（会话页、聊天框里点开的视频、详情页）：它必须真的把
                        // Dock 这一条整个盖住才算，只盖住上面一部分的信息流不参与。
                        coveredState[0] = d != null
                                && Shield.pageCoversDock(d, bandRect[0], content);
                    }
                    expandedTicks[0] = expandedState[0]
                            ? Math.min(4, expandedTicks[0] + 1) : 0;
                    coveredTicks[0] = coveredState[0]
                            ? Math.min(4, coveredTicks[0] + 1) : 0;

                    // 一个隐藏理由成立就收，不等其它理由结束；反过来，所有理由都结束之后
                    // 还要再连续干净几帧才露出来 —— 隐藏和显示用的是同一组判据，只是方向相反。
                    //
                    // 这里刻意不再把「宿主自己把底栏藏起来」当隐藏理由：抖音在消息列表、
                    // 我的页上下滚动时会让原生底栏跟着位移/隐藏，跟着它走就是一路闪。
                    //
                    // 原生底栏整个脱离窗口 = 当前不是主 tab 页（会话页、覆盖页、转场中）。
                    // 要连续十几帧（约 190ms）都找不到才认，转场那一瞬间的游离不算。
                    boolean dockAttached = d != null && d.isAttachedToWindow();
                    dockMissingTicks[0] = dockAttached
                            ? 0 : Math.min(60, dockMissingTicks[0] + 1);
                    boolean hostHidden = !chromeUntrusted[0] && chrome.isHiddenNow(d);
                    if (hostHidden) {
                        if (hostHiddenSince[0] == 0L) hostHiddenSince[0] = now;
                    } else {
                        hostHiddenSince[0] = 0L;
                    }
                    hostHiddenTicks[0] = hostHidden
                            ? Math.min(60, hostHiddenTicks[0] + 1) : 0;
                    // 误报兜底：宿主那条底栏还好端端挂在窗口里、页面也没被整屏盖住、
                    // 既不在清屏也不在倍速，只是某个祖先报了不可见 —— 这不是「进会话页」。
                    // 撑过 3.5 秒还是这样，就把这条理由在本 Activity 里彻底关掉，
                    // 保证玻璃不会因为一条判据错到底而永远出不来。
                    if (hostHidden && dockAttached
                            && !coveredState[0] && !clearScreen && !speedPlayback
                            && now - hostHiddenSince[0] > 3500L) {
                        chromeUntrusted[0] = true;
                        hostHiddenTicks[0] = 0;
                        hostHiddenSince[0] = 0L;
                        Dydock.warn("底栏:祖先层报不可见但底栏仍在窗口内，判定为误报，"
                                + "本次运行不再用这条理由隐藏 Dock");
                    }
                    boolean hidden = speedPlayback || longPress || clearScreen
                            || coveredTicks[0] >= COVER_CONFIRM_TICKS
                            || expandedTicks[0] >= COVER_CONFIRM_TICKS
                            || dockMissingTicks[0] >= 12
                            || hostHiddenTicks[0] >= 25;
                    if (hidden) {
                        revealTicks[0] = 0;
                        String why = hideReason(speedPlayback, longPress, clearScreen,
                                coveredTicks[0], expandedTicks[0], dockMissingTicks[0],
                                hostHiddenTicks[0]);
                        if (!why.equals(lastHideReason[0])) {
                            lastHideReason[0] = why;
                            Dydock.log("底栏隐藏：" + why);
                        }
                    } else if (revealTicks[0] < SHOW_CONFIRM_FRAMES) {
                        revealTicks[0]++;
                    } else if (!lastHideReason[0].isEmpty()) {
                        lastHideReason[0] = "";
                        Dydock.log("底栏恢复显示");
                    }
                    boolean show = !hidden && revealTicks[0] >= SHOW_CONFIRM_FRAMES;

                    // 位置只在「尺寸真的变了」的时候重算（旋转、导航模式变化、底栏重建）。
                    // 不跟位置走：抖音底栏滚动时会上下位移，跟着它算玻璃就会一路漂。
                    if (d != null && now - lastGeomAt[0] >= 240L) {
                        lastGeomAt[0] = now;
                        int w = d.getWidth();
                        int h = d.getHeight();
                        boolean sizeChanged = w != lastAccountedBandW[0]
                                || h != lastAccountedBandH[0];
                        if (sizeChanged || bandRect[0] == null) {
                            lastAccountedBandW[0] = w;
                            lastAccountedBandH[0] = h;
                            lastBandSig[0] = bandSignature(d);
                            bandRect[0] = rectInWindow(d);
                            synchronized (BANDS) { BANDS.put(act, bandRect[0]); }
                            applyGeometry(iso, backdrop, d, tabTargets[0].size());
                            try {
                                backdrop.capture();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    // 模块自己那个设置页改过配置：读进来立刻生效，不用重开抖音。
                    if (now - lastPrefsSyncAt[0] >= 1000L) {
                        lastPrefsSyncAt[0] = now;
                        if (DockPrefs.syncFromModule(act)) {
                            Dydock.log("设置:已同步桌面设置页的改动");
                            applySettingsNow(act);
                        }
                    }

                    // 从隐藏回到显示：页面切回来了，按当前底栏重新贴一次位置，避免用的是旧坐标。
                    if (show && !overlayTargetVisible[0] && d != null) {
                        bandRect[0] = rectInWindow(d);
                        synchronized (BANDS) { BANDS.put(act, bandRect[0]); }
                        applyGeometry(iso, backdrop, d, tabTargets[0].size());
                        try {
                            backdrop.capture();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (show) {
                        // 玻璃后面那块画面：跟宿主的重绘走，不截屏也不拍窗口。
                        // 每帧都调，内部有 10fps 的节流和自我判重。
                        backdrop.pump();
                    }

                    if (d != null && d.getVisibility() != View.GONE) d.setVisibility(View.GONE);
                    if (show != overlayTargetVisible[0]) {
                        overlayTargetVisible[0] = show;
                        iso.view.animate().cancel();
                        if (show) {
                            if (iso.view.getVisibility() != View.VISIBLE) {
                                iso.view.setVisibility(View.VISIBLE);
                            }
                            iso.view.animate().alpha(1f).setDuration(18L).start();
                        } else {
                            iso.view.animate().alpha(0f).setDuration(24L)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (!overlayTargetVisible[0]) {
                                                iso.view.setVisibility(View.GONE);
                                            }
                                        }
                                    }).start();
                        }
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("visibility-poll", "Dock 显隐检查失败: " + t);
                }
                MAIN.postDelayed(this, 16L);
            }
        };
        // 原生底栏绝不能被画进玻璃的背景里。它平时已经被藏起来了，
        // 这里再兜一层：万一宿主把它设回可见，Backdrop 会跳过这一帧的内容树。
        backdrop.setExcluded(dockRef[0]);

        synchronized (POLLERS) { POLLERS.put(act, visibilityPoll); }
        synchronized (POLL_STATES) { POLL_STATES.put(act, live); }
        MAIN.postDelayed(visibilityPoll, 16L);

        // 看门狗：玻璃**可见却迟迟没画出来**才把官方底栏还回去，绝不留一条空白。
        // 5 秒：首次进抖音要现场解 dex、建 Recomposer、跑第一帧，2.5 秒在高负载机型上
        // 会把「正在画的玻璃」误判成「画不出来」，表现就是底栏先黑一下再变回原生。
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (!live[0] || iso.hasDrawn()) return;
                // 先把轮询停掉：否则它下一帧又会把刚还回去的原生底栏设成 GONE。
                live[0] = false;
                MAIN.removeCallbacks(visibilityPoll);
                synchronized (POLLERS) { POLLERS.remove(act); }
                synchronized (POLL_STATES) { POLL_STATES.remove(act); }
                detachOverlay(iso);
                restoreNative(dockRef[0]);
                Dydock.warn("底栏:玻璃 5 秒内没有画出第一帧，已恢复原生 Dock");
                toast(act, "Dydock " + Dydock.VER + "：玻璃没画出来，已恢复原生底栏");
            }
        }, 5000L);

        toast(act, "Dydock " + Dydock.VER + "：玻璃底栏已接管");
        Dydock.log("底栏已接管：" + initialTabs.size() + " 个图标"
                + (plus != null ? " ＋ 独立一块" : "")
                + "，底栏 " + dock.getWidth() + "x" + dock.getHeight()
                + "（" + dock.getClass().getSimpleName() + "）");
        return true;
    }

    /**
     * 取当前底栏里第 index 个 tab。
     *
     * 抖音切页、换皮、重建底栏之后，安装时抓到的那批 View 引用可能已经失效 ——
     * 拿失效引用去 performClick 就是「点了没反应」。所以每次点之前都按当前底栏重新取一次，
     * 取不到才退回缓存的那批。
     */
    private static View latestTab(Activity act, List<View> cached, View cachedPlus, int index) {
        try {
            View host = dockOf(act);
            if (host != null) {
                List<View> fresh = Finder.tabItems(host);
                View p = Finder.pickPlus(fresh);
                List<View> tabs = new ArrayList<View>(fresh);
                if (p != null) tabs.remove(p);
                if (tabs.size() >= 4 && index >= 0 && index < tabs.size()) {
                    return tabs.get(index);
                }
            }
        } catch (Throwable ignored) {
        }
        if (cached != null && index >= 0 && index < cached.size()) return cached.get(index);
        return null;
    }

    /** 取当前的「＋ / 发布」按钮，同理。 */
    private static View latestPlus(Activity act, View cached) {
        try {
            View host = dockOf(act);
            if (host != null) {
                View p = Finder.pickPlus(Finder.tabItems(host));
                if (p != null) return p;
            }
        } catch (Throwable ignored) {
        }
        return cached;
    }

    /**
     * 触发宿主自己的点击。
     *
     * 先走 performClick（抖音的 tab 基本都挂了点击回调）；
     * 有些自定义 View 只在自己的 onTouchEvent 里处理，不挂 listener，performClick 会返回 false，
     * 这时候补一对真实的按下 / 抬起事件，让它的手势识别照常跑一遍。
     * 两条路都不做我们自己的页面跳转 —— 全程都是抖音原生行为。
     */
    private static void clickHostView(final View target) {
        if (target == null) return;
        boolean handled = false;
        try {
            handled = target.performClick();
        } catch (Throwable t) {
            Dydock.warnOnce("host-click", "转发点击失败: " + t);
        }
        if (handled) return;
        try {
            long now = SystemClock.uptimeMillis();
            float x = target.getWidth() / 2f;
            float y = target.getHeight() / 2f;
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                    now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                    now, now + 40L, android.view.MotionEvent.ACTION_UP, x, y, 0);
            target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Throwable t) {
            Dydock.warnOnce("host-click-touch", "触摸转发失败: " + t);
        }
    }

    /** 设置里重新打开「启用玻璃 Dock」：把见过的页面重新接管一遍。 */
    static void requestReinstall() {
        List<Activity> acts;
        synchronized (KNOWN) {
            acts = new ArrayList<Activity>(KNOWN.keySet());
        }
        for (Activity act : acts) {
            if (act == null || act.isFinishing() || act.isDestroyed()) continue;
            synchronized (DONE) {
                DONE.remove(act);
            }
            onActivity(act);
        }
    }

    /**
     * 设置改完立刻作用到所有活着的 Dock，不用重开抖音。
     *
     * 只做两件便宜事：重新同步图标/文字、按当前底栏坐标重算一次位置。
     * 底栏那一带的坐标用缓存——原生底栏已经被设成 GONE，现场量出来可能是 0。
     */
    static void applySettingsToAll() {
        List<Activity> acts;
        synchronized (OVERLAYS) {
            acts = new ArrayList<Activity>(OVERLAYS.keySet());
        }
        for (Activity act : acts) {
            try {
                applySettingsNow(act);
            } catch (Throwable t) {
                Dydock.warnOnce("apply-settings", "应用设置失败: " + t);
            }
        }
    }

    private static void applySettingsNow(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if (!DockPrefs.enabled(act)) {
            teardown(act);
            return;
        }
        IsoDock iso;
        synchronized (OVERLAYS) {
            iso = OVERLAYS.get(act);
        }
        if (iso == null) {
            // 之前被关掉了（或者还没挂上），重新走一遍接管流程。
            synchronized (DONE) {
                DONE.remove(act);
            }
            onActivity(act);
            return;
        }
        View dock = dockOf(act);
        if (dock == null) return;

        List<View> items = Finder.tabItems(dock);
        View plus = Finder.pickPlus(items);
        List<View> tabs = new ArrayList<View>(items);
        if (plus != null) tabs.remove(plus);
        if (tabs.isEmpty()) return;
        refreshTabs(iso, tabs, plus);

        Backdrop backdrop;
        synchronized (BACKDROPS) {
            backdrop = BACKDROPS.get(act);
        }
        Rect band;
        synchronized (BANDS) {
            band = BANDS.get(act);
        }
        applyGeometryInBand(iso, backdrop, dock, band, tabs.size());
        if (backdrop != null) {
            try {
                backdrop.capture();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 关掉玻璃 Dock：停轮询、拆覆盖层、把原生底栏原样还回去。
     *
     * 只还原可见性/透明度，不动抖音自己的布局参数 —— 我们从头到尾没改过那些。
     */
    static void teardown(Activity act) {
        if (act == null) return;
        IsoDock iso;
        synchronized (OVERLAYS) {
            iso = OVERLAYS.remove(act);
        }
        View dock;
        synchronized (DOCKS) {
            dock = DOCKS.remove(act);
        }
        Runnable poller;
        synchronized (POLLERS) {
            poller = POLLERS.remove(act);
        }
        boolean[] state;
        synchronized (POLL_STATES) {
            state = POLL_STATES.remove(act);
        }
        if (state != null) state[0] = false;
        if (poller != null) MAIN.removeCallbacks(poller);
        if (iso != null) {
            detachOverlay(iso);
            try {
                iso.dispose();
            } catch (Throwable ignored) {
            }
        }
        restoreNative(dock);
        synchronized (BACKDROPS) { BACKDROPS.remove(act); }
        synchronized (BANDS) { BANDS.remove(act); }
        synchronized (DONE) { DONE.put(act, Boolean.TRUE); }
        Dydock.log("底栏:已按设置关闭玻璃 Dock，恢复原生底栏");
    }

    /** 把玻璃层从窗口上摘掉（不销毁，dispose 由 release 负责）。 */
    private static void detachOverlay(IsoDock iso) {
        if (iso == null) return;
        try {
            iso.view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
        try {
            ViewParent parent = iso.view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(iso.view);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreNative(View dock) {
        try {
            if (dock != null) {
                dock.setVisibility(View.VISIBLE);
                dock.setAlpha(1f);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 玻璃层的位置全在宿主这边算：四个 tab 的胶囊 + 右边「＋」两块。
     * 参数沿用 KernelSU FloatingBottomBar：28dp 安全边距、8dp 间距、64dp 高、单格 76dp。
     */
    private static void applyGeometry(IsoDock iso, Backdrop backdrop, View dock, int tabsCount) {
        if (iso == null || dock == null) return;
        Rect band = rectInWindow(dock);
        applyGeometryInBand(iso, backdrop, dock, band, tabsCount);
    }

    /**
     * 真正算位置的地方。
     *
     * 参数全部来自设置（高度 / 单格宽度 / 左右安全边距 / 离底部间距），默认值就是
     * KernelSU FloatBottomBar 那一套：64dp 高、每格 76dp、组内 8dp 间隔。
     * 「＋」可以在设置里关掉，关掉时给它一个空矩形，玻璃层自然就不画那个圆球。
     */
    private static void applyGeometryInBand(IsoDock iso, Backdrop backdrop, View dock,
                                            Rect bandRect, int tabsCount) {
        if (iso == null || dock == null) return;
        try {
            float density = dock.getResources().getDisplayMetrics().density;
            int count = Math.max(1, tabsCount);
            Rect band = bandRect == null ? rectInWindow(dock) : new Rect(bandRect);
            if (band.width() <= 0 || band.height() <= 0) return;

            android.content.Context ctx = dock.getContext();

            int[] overlayLocation = new int[2];
            iso.view.getLocationInWindow(overlayLocation);

            int bandLeft = band.left - overlayLocation[0];
            int bandTop = band.top - overlayLocation[1];
            int edge = (int) (DockPrefs.sideMarginDp(ctx) * density);
            int gap = (int) (8f * density);
            int pill = (int) (DockPrefs.heightDp(ctx) * density);
            int plusSize = DockPrefs.showPlusButton(ctx) ? pill : 0;
            int tabWidth = (int) (DockPrefs.tabWidthDp(ctx) * density);
            int innerPad = (int) (8f * density);

            int tabsWidth = tabWidth * count + innerPad;
            int plusBlock = plusSize > 0 ? gap + plusSize : 0;
            int total = tabsWidth + plusBlock;
            int available = band.width() - edge * 2;
            if (total > available) {
                // 窄屏：先压胶囊，保住「＋」，但不低过每格 48dp，再窄就让它居中溢出一点。
                tabsWidth = Math.max((int) (48f * density) * count,
                        Math.min(tabsWidth, available - plusBlock));
                total = tabsWidth + plusBlock;
            }
            if (tabsWidth <= 0) return;

            // 两个 dock 作为一整组水平居中；「＋」紧贴胶囊右边，只留 8dp。
            int groupLeft = bandLeft + (band.width() - total) / 2;
            int top = bandTop + band.height() - (int) (DockPrefs.bottomGapDp(ctx) * density) - pill;

            Rect tabsRect = new Rect(groupLeft, top, groupLeft + tabsWidth, top + pill);
            Rect plusRect = plusSize > 0
                    ? new Rect(tabsRect.right + gap, top, tabsRect.right + gap + plusSize, top + plusSize)
                    : new Rect();
            int pad = (int) (40f * density);
            Rect region = new Rect(
                    tabsRect.left - pad,
                    top - pad,
                    (plusSize > 0 ? plusRect.right : tabsRect.right) + pad,
                    top + pill + pad);
            Rect regionInWindow = new Rect(region);
            regionInWindow.offset(overlayLocation[0], overlayLocation[1]);

            if (backdrop != null) backdrop.setRegion(regionInWindow);
            iso.setGeometry(region, tabsRect, plusRect);
        } catch (Throwable t) {
            Dydock.warnOnce("iso-geometry", "玻璃定位失败: " + t);
        }
    }

    /** 交给玻璃层的背景一律是新副本：采样缓冲随时会回收，直接共用就是原生崩溃。 */
    private static void pushBackdrop(IsoDock iso, Backdrop backdrop, long[] lastStamp) {
        Bitmap sampled = backdrop.bitmap();
        if (sampled == null || sampled.isRecycled() || backdrop.looksBlack()) {
            // 采不到画面，或者采出来整片是底栏那层黑底：把旧图清掉，
            // 玻璃只保留自己的模糊，不会变成一块黑玻璃。
            iso.clearBackdrop();
            return;
        }
        long stamp = backdrop.frameStamp();
        if (stamp != 0L && stamp == lastStamp[0]) return;
        lastStamp[0] = stamp;
        Bitmap copy = null;
        try {
            copy = sampled.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            Dydock.warnOnce("iso-backdrop-copy", "背景位图复制失败: " + t);
        }
        if (copy != null) iso.setBackdrop(copy);
    }

    /**
     * 把原生底栏那一带的不透明背景去掉。
     *
     * 抖音的底栏容器通常自己铺一层深色底 + 顶部渐变，模块只把底栏 View 设成 GONE
     * 是拿不掉这层的 —— 于是玻璃隔着它采样，看上去就是一块黑色。
     * 只处理「够宽、且不高于屏幕 40%」的那几层，避免误伤整页容器。
     */
    private static void stripDockBackground(View dock) {
        if (dock == null) return;
        View root = dock.getRootView();
        int screenW = root == null ? 0 : root.getWidth();
        int screenH = root == null ? 0 : root.getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        View v = dock;
        for (int i = 0; i < 4 && v != null; i++) {
            try {
                if (v.getBackground() != null
                        && v.getWidth() >= screenW * 0.7f
                        && v.getHeight() <= screenH * 0.4f) {
                    v.setBackground(null);
                }
            } catch (Throwable ignored) {
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
            if (v != null && v.getId() == android.R.id.content) break;
        }
        Dydock.log("底栏:已去掉原生底栏区域的背景色");
    }

    /**
     * 系统导航栏那片区域（红米 / HyperOS 的手势条那一带）。
     *
     * 只做两件事：导航栏背景透明、关掉系统的对比度强制灰底。
     *
     * 这里**绝不能**再打开 LAYOUT_HIDE_NAVIGATION —— 0.3.3 就是为了消掉导航栏那条灰底
     * 打开了它，结果应用内容整体被顶进手势区，抖音自己的底栏跟着往下移，
     * 我们算出来的玻璃位置也一起下移，看上去就是「Dock 跑到手势条上去了」。
     * 位置要贴在原生底栏所在的这一条上，所以布局范围保持和宿主一致。
     * 另外 GlassEntry.makeNavigationAreaTransparent 里也是这么处理的，两处必须同一个口径。
     */
    private static void applyImmersiveNavigation(Activity act) {
        try {
            android.view.Window window = act.getWindow();
            if (window == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            try {
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
                }
            } catch (Throwable ignored) {
            }
            View decor = window.getDecorView();
            if (decor != null) {
                int flags = decor.getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                // 主动清掉：0.3.3 打开过，残留会让底栏坐标一直偏低。
                flags &= ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                decor.setSystemUiVisibility(flags);
            }
            Dydock.log("系统导航栏:背景透明，布局范围保持与宿主一致（Dock 贴原生底栏位置）");
        } catch (Throwable t) {
            Dydock.warnOnce("immersive-nav", "设置导航栏透明度失败: " + t);
        }
    }

    /** 把官方导航项的文字 / 图标 / 选中态同步给玻璃层。 */
    private static void refreshTabs(IsoDock iso, List<View> tabs, View plus) {
        if (iso == null || tabs == null || tabs.isEmpty()) return;
        try {
            boolean showLabels = DockPrefs.showLabels(tabs.get(0).getContext());
            int count = tabs.size();
            String[] labels = new String[count];
            Bitmap[] icons = new Bitmap[count];
            int selected = -1;
            for (int i = 0; i < count; i++) {
                View item = tabs.get(i);
                // 只认「首页 / 朋友 / 消息 / 我」这几个导航文案。
                // 抖音的消息 tab 里还挂着一个角标（未读数）节点，它在树里排在文字前面，
                // 以前取「第一个非空文本」就会把「3」当成标签，玻璃上就只剩个数字。
                String label = Finder.navigationLabel(item, 0);
                // 设置里关掉文字标签时给空串，玻璃只留图标。
                labels[i] = showLabels
                        ? (label.length() == 0 ? defaultLabel(i) : label)
                        : "";
                icons[i] = drawableToBitmap(findDrawable(item, 0));
                if (selected < 0 && isSelectedDeep(item, 0)) selected = i;
            }
            iso.setTabs(labels, icons, selected < 0 ? 0 : selected);
        } catch (Throwable t) {
            Dydock.warnOnce("iso-tabs", "同步导航状态失败: " + t);
        }
    }

    private static String defaultLabel(int index) {
        switch (index) {
            case 0:
                return "首页";
            case 1:
                return "朋友";
            case 2:
                return "消息";
            default:
                return "我";
        }
    }

    private static String findText(View view, int depth) {
        if (view == null || depth > 7) return "";
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.toString().trim().length() > 0) {
                return text.toString().trim();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = findText(group.getChildAt(i), depth + 1);
                if (found.length() > 0) return found;
            }
        }
        return "";
    }

    private static android.graphics.drawable.Drawable findDrawable(View view, int depth) {
        if (view == null || depth > 7) return null;
        if (view instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable drawable =
                    ((android.widget.ImageView) view).getDrawable();
            if (drawable != null) return drawable;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.graphics.drawable.Drawable found =
                        findDrawable(group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 自己画一份图标位图：不走 androidx.core 的 toBitmap，避开宿主那份老 androidx 缺方法。 */
    private static Bitmap drawableToBitmap(android.graphics.drawable.Drawable drawable) {
        if (drawable == null) return null;
        try {
            int width = Math.max(1, Math.min(96, drawable.getIntrinsicWidth()));
            int height = Math.max(1, Math.min(96, drawable.getIntrinsicHeight()));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            Rect old = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            drawable.setBounds(old);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isSelectedDeep(View view, int depth) {
        if (view == null || depth > 8) return false;
        if (view.isSelected() || view.isActivated()
                || (view instanceof Checkable && ((Checkable) view).isChecked())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isSelectedDeep(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
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

    /** 底栏位置指纹：位置/尺寸任何一个变了就重新对齐玻璃。 */
    private static int bandSignature(View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        int h = l[0] * 31 + l[1];
        h = h * 31 + v.getWidth();
        h = h * 31 + v.getHeight();
        return h;
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
        /** 上一次扫描里最高分的候选，用来在日志里说明「为什么没认出来」。 */
        static volatile float lastBestScore = -1f;
        static volatile String lastBestClass = "无";

        static ViewGroup findDock(View root) {
            if (root == null) return null;
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return null;
            Best best = new Best();
            walk(root, root, best, 0, new int[]{2600});
            if (best.view != null) {
                lastBestScore = best.score;
                lastBestClass = best.view.getClass().getName();
            }
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

        /** 方便宿主那边直接拿 View 调：不是容器就返回空表。 */
        static List<View> tabItems(View v) {
            if (v instanceof ViewGroup) return tabItems((ViewGroup) v);
            return new ArrayList<View>();
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

        /** 只返回导航文案（首页/朋友/好友/消息/我/个人主页），角标数字、其它文案一律返回空。 */
        static String navigationLabel(View v, int depth) {
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
