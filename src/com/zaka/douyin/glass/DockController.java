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
     * 显隐过渡：所有页面、所有理由（倍速 / 清屏 / 覆盖页 / 浮层 / 宿主隐藏）共用这一套，
     * 不再有的地方硬切、有的地方慢半拍。显示 150ms、隐藏 110ms，先快后缓，手感轻。
     */
    private static final long SHOW_MS = 60L;
    private static final long HIDE_MS = 60L;
    private static final android.view.animation.Interpolator TRANSITION =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    /** 手势类隐藏（倍速/清屏）恢复要快：抬手就该回来。 */
    private static final int FAST_REVEAL_TICKS = 1;
    /** 结构类隐藏（覆盖页/浮层/底栏脱离）恢复要多等几帧，防止转场里闪一下。 */
    private static final int STRUCTURAL_REVEAL_TICKS = 2;
    /** 「整屏页面盖住 Dock」需要连续命中的帧数（60ms 采一次，两帧约 120ms）。 */
    private static final int COVER_CONFIRM_TICKS = 2;

    private static final WeakHashMap<Activity, Boolean> DONE =
            new WeakHashMap<Activity, Boolean>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 只弹一次的提示文案，避免每个页面都提示一遍。 */
    private static final java.util.HashSet<String> TOASTED = new java.util.HashSet<String>();

    /**
     * 原生底栏被我们收掉布局占位时，记下它原本的 layoutParams.height。
     *
     * 收占位是「画面真的延伸下去」的唯一办法：抖音是「内容 + 底栏」上下排列，
     * 底栏只要还占着高度，列表就在它上沿截止，Dock 下面那一大截永远是窗口底色。
     * 把高度收成 0，内容容器长到屏幕底，列表就铺到手势条后面去了。
     * alpha 照旧归零（SelfMask），可见性判据读到的仍是原值。
     */
    private static final java.util.WeakHashMap<View, Integer> DOCK_HEIGHT =
            new java.util.WeakHashMap<View, Integer>();

    /** Dock 相对系统导航条再上抬多少 dp，用来给宿主自己的进度条 / 弹幕栏让位。 */
    private static final float NAV_CLEAR_DP = 14f;

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
    /** 回到前台时重新量一次底栏位置：从聊天框返回后位置偏左就是这一步缺了。 */
    private static final WeakHashMap<Activity, Runnable> REFRESHERS =
            new WeakHashMap<Activity, Runnable>();
    /** 原生底栏那一带的窗口坐标（底栏被设成 GONE 之后就只能靠这份缓存）。 */
    private static final WeakHashMap<Activity, Rect> BANDS =
            new WeakHashMap<Activity, Rect>();
    /** 见过的宿主页面：设置里重新打开开关时，要能把它们重新接管一遍。 */
    private static final WeakHashMap<Activity, Boolean> KNOWN =
            new WeakHashMap<Activity, Boolean>();
    /** 宿主自己那份状态栏颜色：进全屏前记下来，退出来要还回去。 */
    private static final WeakHashMap<Activity, Integer> ORIGINAL_STATUS_BAR =
            new WeakHashMap<Activity, Integer>();
    /** LAYOUT_FULLSCREEN 是不是我们自己加的：退出全屏时要主动收掉，不能赖在宿主头上。 */
    /** applyWindowChrome 的节流表：整段 3 秒一次，避免和宿主来回改 flags。 */
    private static final WeakHashMap<Activity, Long> CHROME_AT =
            new java.util.WeakHashMap<Activity, Long>();

    private static final WeakHashMap<Activity, Boolean> OURS_LAYOUT_FULLSCREEN =
            new WeakHashMap<Activity, Boolean>();
    /**
     * 被我们清掉的原始背景 / 前景。
     *
     * 用途只有一个：玻璃没画出来（看门狗还原）、或者用户在设置里关掉模块时，
     * 把原生底栏那条底色原样放回去。不存的话，还原出来就是一条「没有底的底栏」，
     * 看着像 Dock 又没了。
     */
    private static final java.util.WeakHashMap<View, android.graphics.drawable.Drawable> SAVED_BG =
            new java.util.WeakHashMap<View, android.graphics.drawable.Drawable>();
    private static final java.util.WeakHashMap<View, android.graphics.drawable.Drawable> SAVED_FG =
            new java.util.WeakHashMap<View, android.graphics.drawable.Drawable>();

    /** 已经把 window 背景设成透明过的页面（window 背景没有 getter，只能记标记）。 */
    private static final WeakHashMap<Activity, Boolean> WINDOW_BG_CLEARED =
            new WeakHashMap<Activity, Boolean>();
    /** 手指按住视频的起点，用来判断「长按倍速」——抖音自己不一定走 performLongClick。 */
    private static final WeakHashMap<Activity, long[]> PRESS =
            new WeakHashMap<Activity, long[]>();
    /**
     * 按住期间最后一次看到触摸事件的时间。
     *
     * 以前按住超过 4 秒就直接当成「松手了」，长按倍速按住看十几秒，Dock 就会自己冒回来。
     * 现在只在「手彻底没有消息」之后才放弃，按住多久都算按住。
     */
    private static final WeakHashMap<Activity, long[]> PRESS_ALIVE =
            new WeakHashMap<Activity, long[]>();
    /**
     * 按住状态只剩最后一道保险。
     *
     * 以前是一分钟：宿主吞掉 UP 之后，这条「长按中」会一直挂着一分钟 —— 用户从评论区
     * 返回、或者抬手之后 Dock 迟迟不回来，就是它。实测日志里「底栏隐藏：长按」出现了
     * 十几次，而真正按住的只有那么几下。
     *
     * 注意：按住不动时系统不会再发 MOVE 事件，所以这个时间不能设得太短，否则
     * 「按住倍速看十几秒」会在中途被误判成松手、Dock 自己冒出来。真正的解绑
     * 交给抬手事件、返回、以及「原生底栏重新可见」这三个明确信号，这里只做兜底。
     */
    private static final long PRESS_ABANDON_MS = 30_000L;
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
    /**
     * 长按倍速的锁存。
     *
     * 长按状态有两个来源：宿主的长按回调，和「按住超过 240ms」的触摸判定。这两个源
     * 在真机上会交替成立/不成立（尤其页面里视频层的扫描是 240ms 一次，偶尔某一次扫
     * 不到视频层），一交替 Dock 就被放出来一帧又收回去 —— 用户看到的就是「长按倍速
     * 时闪一下」。
     *
     * 进过一次倍速就锁住，只认三件事来解除：抬手 / 取消、返回、或者 2.5 秒没有任何
     * 触摸事件（手已经离开但宿主吞了 UP）。锁住期间不再看页面扫描结果。
     */
    private static final WeakHashMap<Activity, long[]> LONG_PRESS_LATCH =
            new WeakHashMap<Activity, long[]>();

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
        synchronized (LONG_PRESS_LATCH) {
            LONG_PRESS_LATCH.put(act, new long[]{android.os.SystemClock.uptimeMillis()});
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
        if (action == android.view.MotionEvent.ACTION_DOWN
                || action == android.view.MotionEvent.ACTION_MOVE) {
            // 同一串触摸会流过分发链上的每个 View，这里按 200ms 节流写一次就够了
            // （只是用来判断「手还在不在」，不需要精确到每一帧）。
            long nowAlive = android.os.SystemClock.uptimeMillis();
            long[] last;
            synchronized (PRESS_ALIVE) { last = PRESS_ALIVE.get(act); }
            if (last == null || nowAlive - last[0] >= 200L) {
                synchronized (PRESS_ALIVE) { PRESS_ALIVE.put(act, new long[]{nowAlive}); }
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
                // 已经在长按倍速里了：手指移动（包括按着拖进度）不该结束倍速。
                // 以前这里一刀切，移动超过 18dp 就把按住态清掉 —— 表现就是
                // 「长按倍速时手一动 Dock 就冒回来」。只有抬手 / 取消才结束。
                long held = android.os.SystemClock.uptimeMillis() - start[0];
                boolean longPressActive;
                synchronized (LONG_PRESS_TARGETS) {
                    longPressActive = LONG_PRESS_TARGETS.containsKey(act);
                }
                if (!longPressActive && held < PRESS_SPEED_DELAY_MS) {
                    // 还没到长按判定线就移开了：这是滑动，不是长按。
                    synchronized (PRESS) { PRESS.remove(act); }
                    synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
                }
            }
        } else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            synchronized (PRESS) { PRESS.remove(act); }
            synchronized (PRESS_ALIVE) { PRESS_ALIVE.remove(act); }
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
        releaseLongPress(act);
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
        private final int[] baseW = new int[DEPTH];
        private final int[] baseH = new int[DEPTH];

        ChromeSnapshot(View dock, View content, View decor) {
            this.content = content;
            this.decor = decor;
            refresh(dock);
        }

        /** 重新拍一次基线：宿主换了底栏节点、或者误报排除之后要恢复判据时用。 */
        void refresh(View dock) {
            View v = dock;
            for (int i = 0; i < DEPTH; i++) {
                ViewParent parent = v == null ? null : v.getParent();
                if (!(parent instanceof View)) {
                    baseVis[i] = -1;
                    baseAlpha[i] = 0f;
                    v = null;
                    continue;
                }
                v = (View) parent;
                baseVis[i] = v.getVisibility();
                baseAlpha[i] = v.getAlpha();
                baseW[i] = v.getWidth();
                baseH[i] = v.getHeight();
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
                // 接管那一刻这层还没布局（宽高为 0），也拿来比就没有意义了。
                if (baseW[i] <= 0 || baseH[i] <= 0) continue;
                if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) {
                    // 尺寸塌陷但自己还标着「可见」：外壳跟着我们 GONE 掉的底栏缩了，
                    // 这是我们自己干的事，不是宿主在藏底栏 —— 不能拿它当隐藏理由，
                    // 否则 Dock 刚出来就被自己收掉。
                    if (v.getVisibility() == View.VISIBLE
                            && baseH[i] > 0 && v.getHeight() * 3 < baseH[i]) continue;
                    return true;
                }
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

        // 双指外扩被识别成清屏，但宿主一直没把界面藏起来 —— 说明这是个普通缩放手势。
        // 以前这里会一直等下去，Dock 就再也不回来（只能点屏幕或返回才解）。
        if (!confirmed[0] && android.os.SystemClock.uptimeMillis() - enteredAt[0] > 6000L) {
            exitGestureClearMode(act, "宿主一直没隐藏界面，按误识别处理");
            return false;
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
                removed = true;
            }
        }
        // 抬手就是抬手：锁存、按住态、时间戳一起清，下一帧立刻把 Dock 放回来。
        releaseLongPress(act);
        if (removed) Dydock.log("宿主视频长按结束，检查其他隐藏状态");
    }

    private static boolean isHostLongPressActive(Activity act, boolean pageHasVideo) {
        long now = android.os.SystemClock.uptimeMillis();

        // ① 已经锁存：只认「手彻底没了」这一条来解除，不再看页面扫描结果。
        //    以前每次轮询都重新问一遍 pageHasVideo，某一个 240ms 采样没扫到视频层，
        //    这里就返回 false，Dock 被放出来一帧又收回去 —— 就是长按倍速时的「闪一下」。
        synchronized (LONG_PRESS_LATCH) {
            long[] latched = LONG_PRESS_LATCH.get(act);
            if (latched != null) {
                long[] alive;
                synchronized (PRESS_ALIVE) { alive = PRESS_ALIVE.get(act); }
                boolean abandoned = alive == null || now - alive[0] > PRESS_ABANDON_MS;
                if (abandoned) {
                    LONG_PRESS_LATCH.remove(act);
                } else {
                    return true;
                }
            }
        }

        synchronized (LONG_PRESS_TARGETS) {
            if (LONG_PRESS_TARGETS.containsKey(act)) {
                // 兜底：宿主吞掉那次 UP 的话状态会一直留着。
                long[] alive;
                synchronized (PRESS_ALIVE) { alive = PRESS_ALIVE.get(act); }
                boolean abandoned = alive == null || now - alive[0] > PRESS_ABANDON_MS;
                if (abandoned) {
                    LONG_PRESS_TARGETS.remove(act);
                    synchronized (LONG_PRESS_AT) { LONG_PRESS_AT.remove(act); }
                } else {
                    seize(act, now);
                    return true;
                }
            }
        }
        long[] start;
        synchronized (PRESS) { start = PRESS.get(act); }
        if (start == null) return false;
        // 当前页面没有视频层（消息列表、我的页、设置页）：按住不放不是倍速，不参与隐藏。
        if (!pageHasVideo) return false;
        // 兜底只认「手彻底没消息」：按住期间只要还有触摸事件，就一直算按住。
        long[] alive;
        synchronized (PRESS_ALIVE) { alive = PRESS_ALIVE.get(act); }
        if (alive == null || now - alive[0] > PRESS_ABANDON_MS) {
            synchronized (PRESS) { PRESS.remove(act); }
            synchronized (PRESS_ALIVE) { PRESS_ALIVE.remove(act); }
            synchronized (PRESS_TARGET) { PRESS_TARGET.remove(act); }
            return false;
        }
        long held = now - start[0];
        if (held >= PRESS_SPEED_DELAY_MS) {
            seize(act, now);
            return true;
        }
        return false;
    }

    /** 进入长按倍速：锁存住，后面几帧不管扫描结果如何都保持收起。 */
    private static void seize(Activity act, long now) {
        synchronized (LONG_PRESS_LATCH) {
            if (!LONG_PRESS_LATCH.containsKey(act)) {
                LONG_PRESS_LATCH.put(act, new long[]{now});
            }
        }
    }

    /** 抬手 / 取消 / 返回：解除长按锁存。 */
    private static void releaseLongPress(Activity act) {
        synchronized (LONG_PRESS_LATCH) { LONG_PRESS_LATCH.remove(act); }
        synchronized (LONG_PRESS_TARGETS) { LONG_PRESS_TARGETS.remove(act); }
        synchronized (LONG_PRESS_AT) { LONG_PRESS_AT.remove(act); }
        synchronized (LONG_PRESS_LATCH) { LONG_PRESS_LATCH.remove(act); }
    }

    /** 宿主 Activity 回到前台：抖掉转场的影响，按当前底栏重新贴一次位置。 */
    static void onHostResume(Activity act) {
        if (act == null) return;
        // 从评论区 / 他人主页回来：这些页面里不会还在长按倍速，残留状态要清干净，
        // 否则返回主界面后「长按中」这条隐藏理由一直占着，Dock 就不回来。
        releaseLongPress(act);
        final Runnable refresh;
        synchronized (REFRESHERS) { refresh = REFRESHERS.get(act); }
        if (refresh == null) return;
        MAIN.postDelayed(refresh, 80L);
        MAIN.postDelayed(refresh, 360L);
    }

    /**
     * 统一的显隐过渡。
     *
     * 以前显示是 18ms、隐藏是 24ms，等于硬切；显示还要连续 6 帧确认，
     * 看起来就是「该出来的时候不出来、出来时又啪一下」。这里一套曲线走完：
     * 淡入淡出 + 6dp 的轻微上浮 + 0.985 的轻微缩放，所有隐藏理由共用。
     */
    private static void animateDock(final View view, final boolean show, final float dy) {
        if (view == null) return;
        view.animate().cancel();
        if (show) {
            if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(dy);
            view.setScaleX(0.985f);
            view.setScaleY(0.985f);
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(SHOW_MS).setInterpolator(TRANSITION).start();
        } else {
            view.animate().alpha(0f).translationY(dy * 0.7f)
                    .scaleX(0.99f).scaleY(0.99f)
                    .setDuration(HIDE_MS).setInterpolator(TRANSITION)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (view.getAlpha() < 0.05f) view.setVisibility(View.GONE);
                        }
                    }).start();
        }
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
        synchronized (PRESS_ALIVE) { PRESS_ALIVE.remove(act); }
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
        synchronized (REFRESHERS) { REFRESHERS.remove(act); }
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
        ORIGINAL_STATUS_BAR.remove(act);
        OURS_LAYOUT_FULLSCREEN.remove(act);
        WINDOW_BG_CLEARED.remove(act);
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
                                     boolean commentOpen, int coveredTicks, int expandedTicks,
                                     int dockMissingTicks, int hostHiddenTicks) {
        StringBuilder sb = new StringBuilder();
        if (speedPlayback) sb.append("倍速播放 ");
        if (longPress) sb.append("长按加速 ");
        if (clearScreen) sb.append("清屏 ");
        if (coveredTicks >= COVER_CONFIRM_TICKS) sb.append("整屏覆盖页 ");
        if (commentOpen) sb.append("评论区 ");
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
        // 页面自己在底部留的那条 159px：清掉，内容才真的铺到屏幕底。
        releaseBottomReservation(content, rectInWindow(dock).height());

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        // Miuix 自己在 GPU 上做 4dp 模糊，这里只决定采样分辨率。
        backdrop.setSampleScale();

        // 宿主节点被换掉时，点击回调里取到的永远是最新那批导航项。
        final List<View>[] tabTargets = new List[]{initialTabs};
        final View[] plusTarget = new View[]{plus};

        // 背景：Android 12+ 直接把这一页的内容树画进玻璃自己的图层（KernelSU / wxglass 同款做法）。
        //
        // 和「每隔几十毫秒回读一次位图」的区别就是「真透明」和「一张旧截图」的区别：
        // 全程在 GPU 上录一层纹理，玻璃用 drawBackdrop 采样它，滚动、翻页都是实时的。
        // 有视频 Surface 的页面不走这条路 —— 视频像素不在 View 树里，画出来只有 UI 层，
        // 那种页面仍旧交给 Backdrop 做「视频层 + 内容树」的合成。
        // 不再按「当下有没有视频层」决定：抖音的主 Activity 几乎一直挂着视频 Surface，
        // 一进门就被判成不能用实时图层，结果列表页也永远走位图那条老路。
        // 实时图层对列表页是质变（真画面、跟手），对视频页至少不比现在的黑底更差
        //（视频像素本来就 PixelCopy 抓不到 code=1）。
        final boolean livePaint = android.os.Build.VERSION.SDK_INT >= 31;
        final Consumer<android.graphics.Canvas> hostPainter;
        if (livePaint) {
            hostPainter = new Consumer<android.graphics.Canvas>() {
                @Override
                public void accept(android.graphics.Canvas canvas) {
                    try {
                        if (!content.isAttachedToWindow() || !content.isShown()) return;
                        int[] loc = new int[2];
                        content.getLocationInWindow(loc);
                        int save = canvas.save();
                        canvas.translate(loc[0], loc[1]);
                        content.draw(canvas);
                        canvas.restoreToCount(save);
                    } catch (Throwable t) {
                        Dydock.warnOnce("host-painter", "内容画进玻璃图层失败: " + t);
                    }
                }
            };
            Dydock.log("背景:实时图层绘制已启用（玻璃里是这一页的实时画面）");
        } else {
            hostPainter = null;
        }

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
                // 长按不再作为设置入口：设置只从抖音「我的」菜单里那一项进。
                new Runnable() {
                    @Override public void run() {
                    }
                },
                hostPainter);

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
        dumpLayoutChain(act, dock, content);
        dumpBigViews(content);

        final View[] dockRef = new View[]{dock};
        // 抖音的底栏会在每一次 layout / 切页之后把自己的背景和 alpha 写回来，
        // 轮询（1.5s / 100ms）追不上，肉眼看到的就是「原生底栏还在」。
        // 这里挂一个「每帧绘制前」的把关：底栏、它的外壳、给它让位的几层祖先，
        // 只要背景或 alpha 又被写回来，就在这一帧画之前压掉。
        try {
            decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        View d0 = dockRef[0];
                        if (d0 != null) {
                            if (d0.getAlpha() > 0.01f) SelfMask.mask(d0);
                            View shell0 = ancestorOfClass(d0, "MainBottomTabContainer", 6);
                            if (shell0 != null && shell0.getAlpha() > 0.01f) SelfMask.mask(shell0);
                            // 只维护这两个节点：底栏本体 + 它的外壳容器 MainBottomTabContainer。
                            // 它们自己带的 ColorDrawable（深色/黑）就是 Dock 下面那条黑底，
                            // 宿主会在切页/重建时再写回来，所以这里按「非空才清」的方式兜住 ——
                            // 只碰 2 个 View、且清完就是 null，不会整树扫，也不会反复 relayout。
                            if (d0.getBackground() != null) saveAndClearBackground(d0);
                            if (shell0 != null && shell0.getBackground() != null) {
                                saveAndClearBackground(shell0);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
            Dydock.log("底栏:已挂上每帧把关（背景 + alpha）");
        } catch (Throwable t) {
            Dydock.warnOnce("predraw", "挂每帧把关失败: " + t);
        }

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
        //
        // 用 alpha 归零而不是 GONE：GONE 会触发宿主重新布局（底栏一消失，宿主自己
        // 又会补一层底、位置也可能重算），而且「原生底栏此刻该不该显示」这条判断
        // 就再也读不到了 —— 玻璃的显隐正是要跟着它走。
        // 用 SelfMask 记下原值再归零：判定「原生底栏可不可见」时必须用原值，
        // 否则就是拿自己写的 0 去判宿主藏了底栏，玻璃会一帧收一帧放地抖。
        SelfMask.mask(dock);
        Finder.hiddenByUs = new java.lang.ref.WeakReference<View>(dock);
        try {
            backdrop.capture();
        } catch (Throwable t) {
            Dydock.warnOnce("native-dock-hide", "移除原生底栏占位失败: " + t);
        }

        final int[] expandedTicks = new int[]{0};
        final int[] diagDumps = new int[]{0};
        final long[] lastReserveAt = new long[]{0L};
        final long[] lastRemaskAt = new long[]{0L};
        final long[] lastSweepAt = new long[]{0L};
        final long[] lastSweepLogAt = new long[]{0L};
        final long[] diagAt = new long[]{SystemClock.uptimeMillis()};
        final boolean[] overlayTargetVisible = new boolean[]{true};
        final long[] lastFindAt = new long[]{0L};
        final long[] lastClearScanAt = new long[]{0L};
        final long[] lastTabRefreshAt = new long[]{0L};
        final boolean[] clearScreenState = new boolean[]{false};
        final boolean[] speedPlaybackState = new boolean[]{false};
        final boolean[] expandedState = new boolean[]{false};
        final long[] lastExpandedAt = new long[]{0L};
        final boolean[] otherProfileState = new boolean[]{false};
        final long[] lastProfileScanAt = new long[]{0L};
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
         * 显示的确认帧数：隐藏立即生效，显示要连续几帧干净才露出来。
         * 滚动、转场、浮层收起这些过程里会有一两帧的空档，不去抖就会一闪一闪。
         * 手势类 2 帧（约 30ms，抬手就回来），结构类 10 帧（约 160ms，转场里不闪）。
         */
        final int[] revealTicks = new int[]{FAST_REVEAL_TICKS};
        /** 原生底栏连续几帧「该显示」：显隐镜像它的可见性，0 帧就是不可见。 */
        final int[] nativeVisibleTicks = new int[]{0};
        /** 底栏图层诊断的节流（每 8 秒一次，诊断日志里就能看到到底哪层还在铺底）。 */
        final long[] lastDumpAt = new long[]{SystemClock.uptimeMillis()};
        /** 上一次隐藏是不是「结构类」理由（覆盖页/浮层/底栏脱离），决定恢复要等几帧。 */
        final boolean[] lastHideStructural = new boolean[]{false};
        final long[] lastFailLogAt = new long[]{0L};
        /** 上一次记下的窗口尺寸：转屏 / 分屏才需要重新量底栏位置。 */
        final int[] lastWindowW = new int[]{decor.getWidth()};
        final int[] lastWindowH = new int[]{decor.getHeight()};
        final int[] lastAccountedBandW = new int[]{-1};
        final int[] lastAccountedBandH = new int[]{-1};
        /** 背景采样的泵：只在宿主重绘过/区域变过的时候真的重画。 */
        final long[] lastBackdropPumpAt = new long[]{0L};
        /** 模块设置页（桌面图标）改配置的同步节流。 */
        final long[] lastPrefsSyncAt = new long[]{0L};
        /** 状态栏 / 导航栏透明复查的节流：宿主会把自己的栏色整段写回去。 */
        final long[] lastChromeAt = new long[]{0L};
        final long[] lastChromeLogAt = new long[]{0L};
        /** 底栏那条深色底 / 边缘遮罩的复查节流：抖音重建底栏时会重新铺一层。 */
        final long[] lastStripAt = new long[]{0L};
        /**
         * 原生底栏连续几帧不在窗口里。
         *
         * 会话页、聊天框里点开的视频页这些覆盖页，抖音会把主界面的底栏整个摘掉；
         * 这时候如果我们只看「有没有东西盖住底栏」，答案是「没有」，Dock 就会
         * 浮到会话页上（从聊天框看完视频返回时最常见）。底栏都不在了，本身就说明
         * 当前不在四个主 tab 页面上。
         */
        final int[] dockMissingTicks = new int[]{0};
        /** 底栏节点第一次找不到的时刻：用来做「识别跟不上宿主重建」的兜底复位。 */
        final long[] dockMissingSince = new long[]{0L};
        /** 宿主把底栏容器真正隐藏了：持续约 400ms 才认，滚动动画那一小段不算。 */
        final int[] hostHiddenTicks = new int[]{0};
        /** 评论区是不是开着；开着的时候不参与「结构类」隐藏（抖音原生在评论区里不收底栏）。 */
        final boolean[] commentOpen = new boolean[]{false};
        final long[] lastCommentScanAt = new long[]{0L};
        /** 评论节点在发送后可能被宿主短暂重建，保留最近一次确认，避免结构判据抢先收 Dock。 */
        /** 这条隐藏理由第一次成立的时刻，用来做误报兜底。 */
        final long[] hostHiddenSince = new long[]{0L};
        /** 这条理由被认定成误报之后，本 Activity 内不再参与判断（否则底栏再也回不来）。 */
        final boolean[] chromeUntrusted = new boolean[]{false};
        /** 上一次隐藏是什么原因，只在变化时记一条日志，方便对着日志定位。 */
        final String[] lastHideReason = new String[]{""};
        final float densityPx = act.getResources().getDisplayMetrics().density;

        final Runnable refreshGeometry = new Runnable() {
            @Override public void run() {
                if (!live[0]) return;
                try {
                    View d = dockRef[0];
                    // 位置已经量过就沿用（位置钉死，不跟着宿主底栏漂）。
                    // 只有还没量到、或者窗口尺寸变了才重新量一次。
                    int decorW = decor.getWidth();
                    int decorH = decor.getHeight();
                    boolean windowChanged = decorW != lastWindowW[0] || decorH != lastWindowH[0];
                    lastWindowW[0] = decorW;
                    lastWindowH[0] = decorH;
                    if (d != null && d.isAttachedToWindow() && d.getWidth() > 0
                            && (bandRect[0] == null || windowChanged)) {
                        int nav = navigationBarInsetBottom(d);
                        int pillPx = (int) (DockPrefs.heightDp(act) * densityPx);
                        Rect measured = rectInWindow(d);
                        if (!validBand(measured, decor, nav, densityPx)) {
                            measured = fallbackBand(decor, nav, densityPx,
                                    DockPrefs.bottomGapDp(act), pillPx);
                        }
                        bandRect[0] = measured;
                        synchronized (BANDS) { BANDS.put(act, bandRect[0]); }
                        lastAccountedBandW[0] = d.getWidth();
                        lastAccountedBandH[0] = d.getHeight();
                    }
                    if (bandRect[0] != null) {
                        applyGeometryInBand(iso, backdrop, d, bandRect[0], tabTargets[0].size());
                        backdrop.capture();
                        pushBackdrop(iso, backdrop, lastStamp);
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("refresh-geometry", "回到前台重算 Dock 位置失败: " + t);
                }
            }
        };
        synchronized (REFRESHERS) { REFRESHERS.put(act, refreshGeometry); }

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
                    // 临时诊断：每 15 秒打一次当前页面的大视图，共 6 次。
                    // 目的是抓到「消息」这类列表页里 bottom 停在底栏上沿的那个容器。
                    // 原生底栏必须始终是透明的：宿主会在切页、重建节点之后把它改回 alpha=1，
                    // 露出来就变成「Dock 底下还有一排」——就是用户看到的那一条。
                    if (d != null && d.getAlpha() > 0.01f) {
                        SelfMask.mask(d);
                        if (now - lastRemaskAt[0] >= 3000L) {
                            lastRemaskAt[0] = now;
                            Dydock.log("底栏:原生底栏又变回可见，重新压成透明");
                        }
                    }
                    if (now - lastSweepAt[0] >= 300L) {
                        lastSweepAt[0] = now;
                        int swept = maskAllTabBars(decor);
                        if (swept > 0 && now - lastSweepLogAt[0] >= 3000L) {
                            lastSweepLogAt[0] = now;
                            Dydock.log("底栏:全量压掉 " + swept + " 个底栏容器实例");
                        }
                    }
                    // 还有一种情况：抖音有第二个「底栏外壳」容器（MainBottomTabContainer），
                    // 它自己还带着一层背景和一份图标副本 —— 只压里面那个 LinearLayout 是不够的，
                    // 用户看到的「底下还有一排」就是它。
                    if (d != null) {
                        View shell = ancestorOfClass(d, "MainBottomTabContainer", 6);
                        if (shell != null && shell.getAlpha() > 0.01f) {
                            SelfMask.mask(shell);
                            if (now - lastRemaskAt[0] >= 3000L) {
                                lastRemaskAt[0] = now;
                                Dydock.log("底栏:外壳容器 MainBottomTabContainer 也压成透明");
                            }
                        }
                    }
                    // 「消息」这类页面是切过去之后才创建的，安装那一刻还不在树上，
                    // 所以底部预留要周期性复查：命中就清、被宿主改回去就再清一次。
                    if (now - lastReserveAt[0] >= 1500L) {
                        lastReserveAt[0] = now;
                        // 用**原生底栏自己的高度**（159px）当基准，不能用 bandRect 的高度：
                        // 原生底栏被我们压成 alpha=0 之后，band 会退回按胶囊高度算的兜底值（208px），
                        // 拿 208 去匹配 159 的下边距就永远匹配不上 —— 列表又会被压在底栏上沿。
                        int nativeH = d != null && d.getHeight() > 0
                                ? d.getHeight()
                                : (bandRect[0] == null ? 0 : bandRect[0].height());
                        if (nativeH > 0) releaseBottomReservation(content, nativeH);
                    }
                    if (diagDumps[0] < 24 && now - diagAt[0] >= 10000L) {
                        diagAt[0] = now;
                        diagDumps[0]++;
                        int tabTop = bandRect[0] == null ? 0 : bandRect[0].top;
                        Dydock.log("边界:=== dump " + diagDumps[0] + " tabTop=" + tabTop
                                + " 视频层=" + Shield.hasVideoSurface(content));
                        dumpEdgeViews(content, tabTop);
                    }
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
                                    // 先把位置量下来：设成 GONE 之后自己就量不到了，
                                    // 底栏区域和遮罩匹配都要用这份坐标。
                                    Rect freshBand = rectInWindow(fresh);
                                    SelfMask.mask(fresh);
                                    collapseDockSpace(fresh);
                                    Finder.hiddenByUs =
                                            new java.lang.ref.WeakReference<View>(fresh);
                                    dockRef[0] = fresh;
                                    backdrop.setExcluded(fresh);
                                    // 底栏节点换了，父链的显隐基线也得跟着换，否则
                                    // 「跟随宿主底栏显隐」这条判据会一直拿旧节点比对。
                                    chrome.refresh(fresh);
                                    tabTargets[0] = lf;
                                    plusTarget[0] = p;
                                    synchronized (DOCKS) {
                                        DOCKS.put(act, fresh);
                                    }
                                    stripDockBackground(fresh, freshBand);
                                    refreshTabs(iso, lf, p);
                                    applyGeometry(iso, backdrop, fresh, lf.size());
                                    bandRect[0] = freshBand.isEmpty() ? rectInWindow(fresh) : freshBand;
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
                        boolean nowVideo = Shield.hasVideoSurface(content);
                        if (nowVideo != pageHasVideo[0]) {
                            pageHasVideo[0] = nowVideo;
                            try {
                                applyGeometry(iso, backdrop, dockRef[0], tabTargets[0].size());
                            } catch (Throwable ignored) {
                            }
                        }
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

                    // 显隐只跟一件事：抖音自己那条底栏此刻是不是「该显示」。
                    //
                    // 玻璃底栏就是原生底栏的替身，所以显隐完全镜像原生：原生藏起来
                    // （切页、评论区、他人主页、全屏、会话页），玻璃就收；原生回来，玻璃就出。
                    //
                    // 以前是靠文案/类名去「猜」当前页面（他人主页？评论区还开着？浮层？），
                    // 只要猜错一次 —— 残留的「私信 / 发消息」节点、下滑出屏但还没销毁的
                    // 评论面板 —— 这条隐藏理由就会被一直占着，Dock 再也回不来。
                    // 「从别人主页退出 / 从评论区返回 Dock 不出现」就是这条猜错导致的。
                    boolean nativeVisible = Shield.nativeBarVisible(
                            d != null ? d : dockRef[0], bandRect[0], content);
                    // 正数=连续可见帧，负数=连续不可见帧。
                    // 不可见方向撑过 8 帧（约 130ms）：抖音切页 / 侧栏滑动 / 评论面板进出时，
                    // 底栏会有一两帧被临时测量成不可见，3 帧（50ms）太短，玻璃跟着收一下
                    // 就是用户看到的「闪一下 / 消失」。可见方向仍然 6 帧，保证该回来时跟得上。
                    nativeVisibleTicks[0] = nativeVisible
                            ? Math.min(6, nativeVisibleTicks[0] + 1)
                            : Math.max(-9, nativeVisibleTicks[0] - 1);
                    boolean nativeHidden = nativeVisibleTicks[0] <= -8;

                    // 长按倍速结束的可靠信号：宿主把原生底栏放回来了。
                    // 为什么需要这条：按住不动时系统不再发 MOVE，靠触摸超时判断「松手」会把
                    // 按住看十几秒的场景误判成结束；而抬手事件也可能被宿主吞掉。
                    // 锁存超过 1.2 秒、原生底栏又连续可见 6 帧，才认为倍速真的结束了。
                    if (longPress && nativeVisible && nativeVisibleTicks[0] >= 6) {
                        long[] latched;
                        synchronized (LONG_PRESS_LATCH) { latched = LONG_PRESS_LATCH.get(act); }
                        if (latched != null && SystemClock.uptimeMillis() - latched[0] > 1_200L) {
                            releaseLongPress(act);
                            longPress = false;
                        }
                    }

                    boolean hidden = speedPlayback || longPress || clearScreen || nativeHidden;
                    if (hidden) {
                        revealTicks[0] = 0;
                        String why = "";
                        if (speedPlayback) why += "倍速 ";
                        if (longPress) why += "长按 ";
                        if (clearScreen) why += "清屏 ";
                        if (nativeHidden) why += "原生底栏不可见";
                        if (!why.equals(lastHideReason[0])) {
                            lastHideReason[0] = why;
                            Dydock.log("底栏隐藏：" + why);
                            if (nativeHidden) {
                                Dydock.log("底栏:原生底栏判定 → "
                                        + Shield.explainNativeBar(d, bandRect[0], content));
                            }
                        }
                    } else {
                        if (revealTicks[0] < FAST_REVEAL_TICKS) revealTicks[0]++;
                        if (revealTicks[0] >= FAST_REVEAL_TICKS && !lastHideReason[0].isEmpty()) {
                            lastHideReason[0] = "";
                            Dydock.log("底栏恢复显示");
                        }
                    }
                    boolean show = !hidden && revealTicks[0] >= FAST_REVEAL_TICKS;

                    // 位置只在「尺寸真的变了」的时候重算（旋转、导航模式变化、底栏重建）。
                    // 不跟位置走：抖音底栏滚动时会上下位移，跟着它算玻璃就会一路漂。
                    if (d != null && now - lastGeomAt[0] >= 240L) {
                        lastGeomAt[0] = now;
                        int w = d.getWidth();
                        int h = d.getHeight();
                        boolean sizeChanged = w != lastAccountedBandW[0]
                                || h != lastAccountedBandH[0];
                        // 位置一旦量好就钉死：宿主底栏滚动、全屏进出、重建底栏都不跟着动。
                        // （以前会跟着重新量，结果 Dock 时不时漂到系统导航条上。）
                        // 只有「底栏尺寸变了」或者「窗口尺寸变了（转屏 / 分屏）」才重新量。
                        int decorW = decor.getWidth();
                        int decorH = decor.getHeight();
                        boolean windowChanged = decorW != lastWindowW[0] || decorH != lastWindowH[0];
                        if (windowChanged) {
                            lastWindowW[0] = decorW;
                            lastWindowH[0] = decorH;
                        }
                        // 占位被收成 0 之后量到的高度永远是 0：那是我们自己造成的，
                        // 不能拿它当「底栏尺寸变了」去重新定位。
                        if (!dockSpaceCollapsed(d)
                                && (sizeChanged || windowChanged || bandRect[0] == null)) {
                            lastAccountedBandW[0] = w;
                            lastAccountedBandH[0] = h;
                            lastBandSig[0] = bandSignature(d);
                            int nav = navigationBarInsetBottom(d);
                            int pillPx = (int) (DockPrefs.heightDp(act) * densityPx);
                            Rect measured = rectInWindow(d);
                            if (!validBand(measured, decor, nav, densityPx)) {
                                // 量到的位置不可信（全屏刚退出、底栏还没量好……）：
                                // 用「导航条上方」这个永远成立的兜底，绝不把错的定下来。
                                measured = fallbackBand(decor, nav, densityPx,
                                        DockPrefs.bottomGapDp(act), pillPx);
                                Dydock.log("底栏:量到的位置不可信，改用导航条上方的兜底位置");
                            }
                            bandRect[0] = measured;
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

                    // 状态栏 / 导航栏透明：抖音在切页、进出全屏、重获焦点之后会把自己的
                    // 栏色整段写回去，只设一次必被盖掉。
                    //
                    // 41.3.0 的日志里这一步被触发过 50 次（约 1.6 秒一次），旧的 600ms 复查
                    // 意味着每次被改回去之后，最多有半秒多底部露的是宿主原来的底色 ——
                    // 看起来就是「底部背景透明时好时不好」。这里压到 200ms 跟进。
                    // 只读颜色 + 只在真的不一致时才写，开销可以忽略。
                    if (now - lastChromeAt[0] >= 200L) {
                        lastChromeAt[0] = now;
                        if (applyWindowChrome(act)) {
                            // 宿主会在播放/切页时反复写回栏色，这里可能每分钟几十次。
                            // 日志按 5 秒一条记，别把诊断文件刷爆。
                            if (now - lastChromeLogAt[0] >= 5000L) {
                                lastChromeLogAt[0] = now;
                                Dydock.log("窗口:状态栏 / 导航栏被宿主改回不透明，已重新设为透明");
                            }
                        }
                    }
                    // 底栏带图层诊断：只在日志开关打开时写，8 秒一次。
                    if (now - lastDumpAt[0] >= 8000L) {
                        lastDumpAt[0] = now;
                        if (Dydock.verbose()) dumpBandLayers(act);
                    }
                    // 底栏那条深色底 + 上方的渐变压暗层：宿主重建底栏时会重新铺上，同样要复查。
                    // 400ms：抖音 41 重建底栏很勤，1.2 秒的间隔会让「底部背景透明」肉眼可见地
                    // 慢一拍、甚至一直不生效。这一步只读背景引用，开销很小。
                    if (now - lastStripAt[0] >= 400L) {
                        lastStripAt[0] = now;
                        stripDockBackground(d != null ? d : dockRef[0], bandRect[0]);
                        // 状态栏被宿主隐藏时，顶部那条黑色色带也要一起去掉，否则就是「黑一条」。
                        if (hostHidesStatusBar(act)) {
                            stripStatusBand(d != null ? d : dockRef[0]);
                        }
                    }

                    // 从隐藏回到显示：位置沿用锁定的那一份，只把背景换一帧。
                    // （以前这里会重新量一次底栏坐标 —— 页面一换坐标就变，Dock 就漂了。）
                    if (show && !overlayTargetVisible[0] && d != null) {
                        if (bandRect[0] == null) {
                            bandRect[0] = rectInWindow(d);
                            synchronized (BANDS) { BANDS.put(act, bandRect[0]); }
                            applyGeometryInBand(iso, backdrop, d, bandRect[0],
                                    tabTargets[0].size());
                        }
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

                    // 原生底栏：玻璃显示时把它整条 alpha 归零（保留布局、尺寸和点击），
                    // 玻璃收起时把它还原 —— 任何时刻用户都只看得到一条「底栏」。
                    // 这也让「原生底栏该不该显示」这条判断始终读得到，不会被我们自己破坏。
                    if (d != null) {
                        if (show) {
                            // 每次都要确认它还是 0：宿主重建底栏 / 重设 alpha 之后会把它变回 1，
                            // 只判「登记过没有」就会漏掉，结果是原生底栏和玻璃叠在一起。
                            // mask() 本身幂等：已经登记过就不再覆盖最早记下的原值。
                            if (d.getAlpha() > 0.01f) SelfMask.mask(d);
                        } else {
                            if (SelfMask.masked(d)) SelfMask.restore(d);
                        }
                    }
                    if (show != overlayTargetVisible[0]) {
                        overlayTargetVisible[0] = show;
                        animateDock(iso.view, show, 6f * densityPx);
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
     * 一定要先送**真实触摸**，不能只 performClick：
     * 抖音底栏那套「再点一次当前 tab」的行为（首页回到顶部 / 重新拉推荐流、消息回到未读起点）
     * 是靠触摸序列 + 连击判定做的，挂在这些 View 的 onTouchEvent / 手势识别里。
     * performClick 只跑 listener，连击计数根本不会增加，表现就是「点首页没反应」。
     *
     * 触摸从 tab 自己开始；它不消费就往上找真正处理手势的那一层（抖音的点击有时挂在
     * 底栏容器上）。全都不消费再退回 performClick。
     */
    private static void clickHostView(final View target) {
        if (target == null) return;
        try {
            int[] tabPos = new int[2];
            target.getLocationOnScreen(tabPos);
            View node = target;
            for (int depth = 0; depth < 4 && node != null; depth++) {
                if (dispatchTap(node, target, tabPos)) return;
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable t) {
            Dydock.warnOnce("host-click-touch", "触摸转发失败: " + t);
        }
        try {
            target.performClick();
        } catch (Throwable t) {
            Dydock.warnOnce("host-click", "转发点击失败: " + t);
        }
    }

    /**
     * 往 node 里送一对真实的按下 / 抬起，坐标换成 node 自己的坐标系。
     *
     * @return node 消费了这次按下就返回 true（抬起已经一起送出去了）
     */
    private static boolean dispatchTap(View node, View target, int[] tabPos) {
        int w = node.getWidth();
        int h = node.getHeight();
        if (w <= 0 || h <= 0) return false;
        int[] nodePos = new int[2];
        node.getLocationOnScreen(nodePos);
        float x = tabPos[0] - nodePos[0] + target.getWidth() / 2f;
        float y = tabPos[1] - nodePos[1] + target.getHeight() / 2f;
        if (x < 0 || y < 0 || x > w || y > h) return false;

        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        boolean consumed;
        try {
            consumed = node.dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }
        if (!consumed) return false;

        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                now, now + 48L, android.view.MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            node.dispatchTouchEvent(up);
        } finally {
            up.recycle();
        }
        return true;
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

        // 用户在设置里关掉「底部背景透明」时，把之前清掉的底色立刻放回去，
        // 不用重开抖音才看到效果。
        if (!DockPrefs.bottomTransparent(act)) {
            restoreSavedBackgrounds(dock);
        }

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

    /** 收掉原生底栏的布局占位，让内容容器长到屏幕底。 */
    private static void collapseDockSpace(View dock) {
        if (dock == null) return;
        try {
            ViewGroup.LayoutParams lp = dock.getLayoutParams();
            if (lp == null || lp.height == 0) return;
            synchronized (DOCK_HEIGHT) {
                if (!DOCK_HEIGHT.containsKey(dock)) DOCK_HEIGHT.put(dock, lp.height);
            }
            int original = lp.height;
            lp.height = 0;
            dock.setLayoutParams(lp);
            Dydock.log("底栏:已收掉原生底栏占位 " + original + "px，内容铺到屏幕底");
        } catch (Throwable t) {
            Dydock.warnOnce("dock-collapse", "收掉底栏占位失败: " + t);
        }
    }

    /**
     * 「页面底部预留」登记表：paddBottom 被我们清零的 View 及其原值。
     *
     * 0.6.6 的布局链诊断说明白了那 159px 是谁占的：
     *
     *   第3层 MainBottomTabContainer id=css 1280x159 gravity=bottom   ← 底栏自己只是个悬浮容器
     *   第4层 ProTouchDelegateFrameLayout 1280x2772                  ← 它下面已经是整屏
     *
     * 也就是说**不是**底栏在布局里挤占内容，是每个页面自己在底部留了 159px 的空（paddingBottom），
     * 内容到那里就截止。所以正确做法不是收底栏的高度，而是把页面自己那条底部预留清掉，
     * 退回时再按原值还原。
     */
    private static final java.util.WeakHashMap<View, Integer> SAVED_PAD =
            new java.util.WeakHashMap<View, Integer>();

    /** 第三种「底部预留」：容器高度被算成「屏高 − 底栏高度」，首页/朋友页就是这种。 */
    private static final java.util.WeakHashMap<View, Integer> SAVED_HEIGHT =
            new java.util.WeakHashMap<View, Integer>();

    /** 同样是「页面底部预留」，但挂成 bottomMargin 的那一批（消息页就是这种）。 */
    private static final java.util.WeakHashMap<View, Integer> SAVED_MARGIN =
            new java.util.WeakHashMap<View, Integer>();

    /** 清掉页面上「等于底栏高度」的底部预留，让内容铺到屏幕底。 */
    private static void releaseBottomReservation(View root, int tabHeight) {
        if (root == null || tabHeight <= 0) return;
        try {
            int rootH = root.getHeight();
            if (rootH <= 0) return;
            int changed = clearBottomPad(root, tabHeight, rootH, 0);
            int margins = clearBottomMargin(root, tabHeight, 0);
            int heights = stretchShortContainer(root, tabHeight, rootH, root.getWidth(), 0);
            if (changed > 0 || margins > 0 || heights > 0) {
                Dydock.log("布局:已清掉 " + changed + " 处 paddingBottom / " + margins
                        + " 处 bottomMargin / 补高 " + heights + " 处（各 " + tabHeight
                        + "px），内容铺到屏幕底");
            }
        } catch (Throwable t) {
            Dydock.warnOnce("bottom-reserve", "清理底部预留失败: " + t);
        }
    }

    private static int clearBottomPad(View v, int tabHeight, int rootH, int depth) {
        if (v == null || depth > 18) return 0;
        int count = 0;
        try {
            int pad = v.getPaddingBottom();
            if (pad >= tabHeight - 8 && pad <= tabHeight + 8 && v.getHeight() >= rootH * 0.55f) {
                synchronized (SAVED_PAD) {
                    if (!SAVED_PAD.containsKey(v)) SAVED_PAD.put(v, pad);
                }
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), 0);
                count++;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    count += clearBottomPad(g.getChildAt(i), tabHeight, rootH, depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    /**
     * 清掉「等于底栏高度」的 bottomMargin。
     *
     * 消息页的实测数据（0.6.9 的大视图快照）：
     *   FrameLayout 1280x2461 top=152 bottom=2613 lp=LayoutParams{-1x-1} margin[0,0,0,159]
     * 就是这 159px 的下边距把整页压在底栏上沿，列表因此画不到屏幕底。
     */
    private static int clearBottomMargin(View v, int tabHeight, int depth) {
        if (v == null || depth > 18) return 0;
        int count = 0;
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                if (m.bottomMargin >= tabHeight - 8 && m.bottomMargin <= tabHeight + 8) {
                    synchronized (SAVED_MARGIN) {
                        if (!SAVED_MARGIN.containsKey(v)) SAVED_MARGIN.put(v, m.bottomMargin);
                    }
                    m.bottomMargin = 0;
                    v.setLayoutParams(m);
                    count++;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    count += clearBottomMargin(g.getChildAt(i), tabHeight, depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    /**
     * 第三种：容器高度本身被算成「屏高 − 底栏高度」（首页 / 朋友页就是这么干的）。
     * 只认「高度差正好是一个底栏高度、宽度接近整屏」的容器，补高到满屏；
     * 原值记下来，退出接管时还原。
     */
    private static int stretchShortContainer(View v, int tabHeight, int rootH, int rootW, int depth) {
        if (v == null || depth > 18) return 0;
        int count = 0;
        try {
            int h = v.getHeight();
            if (Math.abs(h - (rootH - tabHeight)) <= 8 && v.getWidth() >= rootW * 0.9f) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    synchronized (SAVED_HEIGHT) {
                        if (!SAVED_HEIGHT.containsKey(v)) SAVED_HEIGHT.put(v, lp.height);
                    }
                    lp.height = h + tabHeight;
                    v.setLayoutParams(lp);
                    count++;
                    // 补高之后子节点会跟着重排，这一支不用再往下钻
                    return count;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    count += stretchShortContainer(g.getChildAt(i), tabHeight, rootH, rootW, depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    /** 还原所有被清零的底部预留。 */
    private static void restoreBottomReservation() {
        try {
            java.util.ArrayList<View> views;
            java.util.ArrayList<Integer> pads;
            synchronized (SAVED_PAD) {
                views = new java.util.ArrayList<View>(SAVED_PAD.keySet());
                pads = new java.util.ArrayList<Integer>(SAVED_PAD.values());
                SAVED_PAD.clear();
            }
            for (int i = 0; i < views.size(); i++) {
                View v = views.get(i);
                Integer pad = pads.get(i);
                if (v == null || pad == null) continue;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), pad);
            }
            java.util.ArrayList<View> marginViews;
            java.util.ArrayList<Integer> margins;
            synchronized (SAVED_MARGIN) {
                marginViews = new java.util.ArrayList<View>(SAVED_MARGIN.keySet());
                margins = new java.util.ArrayList<Integer>(SAVED_MARGIN.values());
                SAVED_MARGIN.clear();
            }
            java.util.ArrayList<View> heightViews;
            java.util.ArrayList<Integer> heights2;
            synchronized (SAVED_HEIGHT) {
                heightViews = new java.util.ArrayList<View>(SAVED_HEIGHT.keySet());
                heights2 = new java.util.ArrayList<Integer>(SAVED_HEIGHT.values());
                SAVED_HEIGHT.clear();
            }
            for (int i = 0; i < heightViews.size(); i++) {
                View v = heightViews.get(i);
                Integer h = heights2.get(i);
                if (v == null || h == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    lp.height = h;
                    v.setLayoutParams(lp);
                }
            }
            for (int i = 0; i < marginViews.size(); i++) {
                View v = marginViews.get(i);
                Integer margin = margins.get(i);
                if (v == null || margin == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).bottomMargin = margin;
                    v.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把布局占位还原回去。 */
    private static void expandDockSpace(View dock) {
        if (dock == null) return;
        try {
            Integer original;
            synchronized (DOCK_HEIGHT) {
                original = DOCK_HEIGHT.remove(dock);
            }
            if (original == null) return;
            ViewGroup.LayoutParams lp = dock.getLayoutParams();
            if (lp != null && lp.height != original) {
                lp.height = original;
                dock.setLayoutParams(lp);
            }
        } catch (Throwable t) {
            Dydock.warnOnce("dock-expand", "还原底栏占位失败: " + t);
        }
    }

    private static boolean dockSpaceCollapsed(View dock) {
        if (dock == null) return false;
        synchronized (DOCK_HEIGHT) {
            return DOCK_HEIGHT.containsKey(dock);
        }
    }

    private static void restoreNative(View dock) {
        try {
            if (dock != null) {
                dock.setVisibility(View.VISIBLE);
                expandDockSpace(dock);
                SelfMask.restore(dock);
                restoreSavedBackgrounds(dock);
            }
            Finder.hiddenByUs = null;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 玻璃层的位置全在宿主这边算：四个 tab 的胶囊 + 右边「＋」两块。
     * 参数沿用 KernelSU FloatingBottomBar：28dp 安全边距、8dp 间距、64dp 高、单格 76dp。
     */
    /** 只打「底边正好停在底栏上沿」的 View：每次都是它把内容截断。 */
    static void dumpEdgeViews(View root, int tabTop) {
        try {
            if (root == null || tabTop <= 0) return;
            walkEdgeViews(root, tabTop, 0, new int[]{0});
        } catch (Throwable t) {
            Dydock.warnOnce("edge-dump", "边界诊断失败: " + t);
        }
    }

    private static void walkEdgeViews(View v, int tabTop, int depth, int[] budget) {
        if (v == null || depth > 20 || budget[0] >= 12) return;
        try {
            if (v.getVisibility() != View.VISIBLE) return;
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int bottom = loc[1] + v.getHeight();
            if (Math.abs(bottom - tabTop) <= 40 && v.getHeight() > tabTop * 0.3f) {
                Dydock.log("边界:" + v.getClass().getName() + " id=" + idName(v)
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " top=" + loc[1] + " bottom=" + bottom
                        + " padB=" + v.getPaddingBottom()
                        + " lp=" + describeLp(v));
                budget[0]++;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walkEdgeViews(g.getChildAt(i), tabTop, depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把「占了半屏以上」的 View 全部打出来：找到底边停在 2613 的那个容器，才知道该改谁。 */
    static void dumpBigViews(View root) {
        try {
            if (root == null) return;
            int rootH = root.getHeight();
            if (rootH <= 0) return;
            walkBigViews(root, rootH, 0, new int[]{0});
        } catch (Throwable t) {
            Dydock.warnOnce("big-view-dump", "大视图诊断失败: " + t);
        }
    }

    private static void walkBigViews(View v, int rootH, int depth, int[] budget) {
        if (v == null || depth > 16 || budget[0] >= 24) return;
        try {
            if (v.getVisibility() != View.VISIBLE) return;
            if (v.getHeight() >= rootH * 0.30f) {
                int[] loc = new int[2];
                v.getLocationInWindow(loc);
                Dydock.log("大视图:" + v.getClass().getName() + " id=" + idName(v)
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " top=" + loc[1] + " bottom=" + (loc[1] + v.getHeight())
                        + " padB=" + v.getPaddingBottom()
                        + " lp=" + describeLp(v));
                budget[0]++;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walkBigViews(g.getChildAt(i), rootH, depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把视频层等比放大到铺满整屏（cover），让它一直画到屏幕最底。
     *
     * 背景：16:9 / 4:3 的视频放在 20:9 的屏幕上，抖音按「完整显示」缩放，
     * 上下就会留黑边；底栏被我们藏起来之后那条黑边正好露在 Dock 底下，
     * 看着就是「Dock 下面还有一条黑」。
     *
     * 做法：按 max(屏宽/视宽, 屏高/视高) 等比放大，再平移到屏幕中心 ——
     * 画面永远铺满、不变形，多出来的部分被父容器裁掉（和「全屏观看」一个道理）。
     * 原值记在 SAVED_VIDEO_SCALE 里，退出接管或页面切走时还原。
     */
    private static final java.util.WeakHashMap<View, float[]> SAVED_VIDEO_SCALE =
            new java.util.WeakHashMap<View, float[]>();

    static void fillVideoToScreen(View root) {
        if (root == null) return;
        try {
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0 || h <= 0) return;
            applyVideoFill(root, w, h, 0, new int[]{0});
        } catch (Throwable t) {
            Dydock.warnOnce("video-fill", "视频铺满失败: " + t);
        }
    }

    private static void applyVideoFill(View v, int screenW, int screenH, int depth, int[] budget) {
        if (v == null || depth > 32 || budget[0] >= 4) return;
        try {
            boolean isVideo = v instanceof android.view.SurfaceView
                    || v instanceof android.view.TextureView;
            if (isVideo && v.getWidth() > 0 && v.getHeight() > 0
                    && (long) v.getWidth() * v.getHeight() >= (long) screenW * screenH * 20L / 100L) {
                float k = Math.max(screenW / (float) v.getWidth(), screenH / (float) v.getHeight());
                if (k > 1.001f) {
                    synchronized (SAVED_VIDEO_SCALE) {
                        if (!SAVED_VIDEO_SCALE.containsKey(v)) {
                            SAVED_VIDEO_SCALE.put(v, new float[]{
                                    v.getScaleX(), v.getScaleY(),
                                    v.getTranslationX(), v.getTranslationY()});
                        }
                    }
                    int[] loc = new int[2];
                    v.getLocationInWindow(loc);
                    float cx = loc[0] + v.getWidth() / 2f;
                    float cy = loc[1] + v.getHeight() / 2f;
                    v.setPivotX(v.getWidth() / 2f);
                    v.setPivotY(v.getHeight() / 2f);
                    v.setScaleX(k);
                    v.setScaleY(k);
                    v.setTranslationX(screenW / 2f - cx);
                    v.setTranslationY(screenH / 2f - cy);
                    budget[0]++;
                    Dydock.log("视频:已等比放大 " + String.format(java.util.Locale.US, "%.2f", k)
                            + " 倍铺满屏幕（" + v.getWidth() + "x" + v.getHeight()
                            + " → " + screenW + "x" + screenH + "）");
                }
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    applyVideoFill(g.getChildAt(i), screenW, screenH, depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 还原被放大的视频层。 */
    static void restoreVideoFill() {
        try {
            java.util.ArrayList<View> views;
            java.util.ArrayList<float[]> vals;
            synchronized (SAVED_VIDEO_SCALE) {
                views = new java.util.ArrayList<View>(SAVED_VIDEO_SCALE.keySet());
                vals = new java.util.ArrayList<float[]>(SAVED_VIDEO_SCALE.values());
                SAVED_VIDEO_SCALE.clear();
            }
            for (int i = 0; i < views.size(); i++) {
                View v = views.get(i);
                float[] s = vals.get(i);
                if (v == null || s == null) continue;
                v.setScaleX(s[0]);
                v.setScaleY(s[1]);
                v.setTranslationX(s[2]);
                v.setTranslationY(s[3]);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一次性把「内容 / 底栏 / 底栏祖先链」的布局参数打进日志，用来判断该动谁。 */
    static void dumpLayoutChain(Activity act, View dock, View content) {
        try {
            StringBuilder sb = new StringBuilder("布局:内容 w=");
            sb.append(content == null ? -1 : content.getWidth())
              .append(" h=").append(content == null ? -1 : content.getHeight())
              .append(" padB=").append(content == null ? -1 : content.getPaddingBottom())
              .append(" lp=").append(describeLp(content));
            Dydock.log(sb.toString());
            View v = dock;
            for (int i = 0; i < 6 && v != null; i++) {
                StringBuilder line = new StringBuilder("布局:第").append(i).append("层 ")
                        .append(v.getClass().getName())
                        .append(" id=").append(idName(v))
                        .append(" ").append(v.getWidth()).append("x").append(v.getHeight())
                        .append(" padB=").append(v.getPaddingBottom())
                        .append(" alpha=").append(v.getAlpha())
                        .append(" lp=").append(describeLp(v));
                Dydock.log(line.toString());
                android.view.ViewParent parent = v.getParent();
                v = parent instanceof View ? (View) parent : null;
            }
            // 列表：找第一个 RecyclerView / ListView，看它的底边和 padding
            View list = findList(content, 0);
            if (list != null) {
                int[] loc = new int[2];
                list.getLocationInWindow(loc);
                Dydock.log("布局:列表 " + list.getClass().getName()
                        + " " + list.getWidth() + "x" + list.getHeight()
                        + " top=" + loc[1] + " bottom=" + (loc[1] + list.getHeight())
                        + " padB=" + list.getPaddingBottom()
                        + " lp=" + describeLp(list));
            } else {
                Dydock.log("布局:没找到 RecyclerView/ListView");
            }
        } catch (Throwable t) {
            Dydock.warnOnce("layout-dump", "布局链诊断失败: " + t);
        }
    }

    /**
     * 把树上**所有**像底栏的容器都压掉。
     *
     * 首页/朋友页之所以还能看到原生底栏：抖音在这两个 tab 上用的是另一份底栏实例
     * （同一个类 MainBottomTabContainer，但不是我们接管的那一个），只压手上的那个节点没用。
     * 这里按类名/资源名把所有实例都找出来，alpha 归零 + 背景清掉。
     */
    static int maskAllTabBars(View root) {
        if (root == null) return 0;
        return maskAllTabBars(root, 0, new int[]{0});
    }

    private static int maskAllTabBars(View v, int depth, int[] count) {
        if (v == null || depth > 16 || count[0] >= 6) return 0;
        int n = 0;
        try {
            String cls = v.getClass().getName();
            String id = idName(v);
            boolean looks = cls.contains("MainBottomTabContainer")
                    || "css".equals(id) || "cta".equals(id);
            if (looks) {
                if (v.getAlpha() > 0.01f) SelfMask.mask(v);
                if (v.getBackground() != null) saveAndClearBackground(v);
                count[0]++;
                n++;
                return n;   // 命中就不再往下钻，避免把里面我们自己的玻璃也压了
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    n += maskAllTabBars(g.getChildAt(i), depth + 1, count);
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * 清掉「底栏那条带子里」所有不透明（或半透明深色）背景。
     *
     * 抖音的原生黑底不一定在底栏子树里 —— 实测它是底栏旁边的一个兄弟 View，
     * 所以清父链、清子树都会漏。这里按「落在带子里 + 有背景」两个条件扫，
     * 命中就把 background 置空（原值记下，退出时还原）。
     */
    private static void clearOpaqueInBand(ViewGroup parent, int bandTop, int depth, int[] budget) {
        int[] bandLogCount = BAND_LOG;

        if (parent == null || depth > 18 || budget[0] <= 0) return;
        try {
            int n = parent.getChildCount();
            for (int i = 0; i < n && budget[0] > 0; i++) {
                View child = parent.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                budget[0]--;
                if (isOursView(child)) continue;
                int[] loc = new int[2];
                child.getLocationInWindow(loc);
                int bottom = loc[1] + child.getHeight();
                boolean inBand = bottom > bandTop && loc[1] < loc[1] + child.getHeight();
                if (inBand && loc[1] + child.getHeight() > bandTop && child.getBackground() != null) {
                    if (bandLogCount[0] < 12) {
                        bandLogCount[0]++;
                        Dydock.log("带子:清掉 " + child.getClass().getSimpleName()
                                + " id=" + idName(child)
                                + " [" + loc[1] + "," + (loc[1] + child.getHeight()) + "]"
                                + " bg=" + child.getBackground().getClass().getSimpleName());
                    }
                    saveAndClearBackground(child);
                }
                if (child instanceof ViewGroup) {
                    clearOpaqueInBand((ViewGroup) child, bandTop, depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清掉底栏子树里各层的 background（深度限制 3，只动背景不动内容）。 */
    private static void clearChildBackgrounds(View v, int depth) {
        if (v == null || depth > 3) return;
        try {
            if (depth > 0 && v.getBackground() != null) saveAndClearBackground(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    clearChildBackgrounds(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 带子清理日志的计数（只打前 12 条，别把日志刷爆）。 */
    private static final int[] BAND_LOG = new int[]{0};


    /** 系统是不是手势导航（Settings.Secure.navigation_mode == 2）。 */
    private static boolean isGestureNav(android.content.Context ctx) {
        try {
            int mode = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(), "navigation_mode", -1);
            return mode == 2;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 沿父链找类名包含某段的祖先（抖音的底栏外壳是 MainBottomTabContainer）。 */
    private static View ancestorOfClass(View v, String needle, int maxDepth) {
        View cur = v;
        for (int i = 0; i < maxDepth && cur != null; i++) {
            try {
                if (cur.getClass().getName().contains(needle)) return cur;
            } catch (Throwable ignored) {
            }
            android.view.ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static View findList(View v, int depth) {
        if (v == null || depth > 14) return null;
        String cn = v.getClass().getName();
        if (cn.contains("RecyclerView") || cn.contains("ListView")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findList(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String describeLp(View v) {
        if (v == null) return "null";
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return "null";
        StringBuilder sb = new StringBuilder(lp.getClass().getSimpleName())
                .append("{").append(lp.width).append("x").append(lp.height).append("}");
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            sb.append(" margin[").append(m.leftMargin).append(",").append(m.topMargin)
              .append(",").append(m.rightMargin).append(",").append(m.bottomMargin).append("]");
        }
        if (lp instanceof FrameLayout.LayoutParams) {
            sb.append(" gravity=").append(((FrameLayout.LayoutParams) lp).gravity);
        }
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            sb.append(" weight=").append(((android.widget.LinearLayout.LayoutParams) lp).weight);
        }
        return sb.toString();
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == -1) return "-";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return String.valueOf(v.getId());
        }
    }

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
            // 外观参数（玻璃不透明度 / 隐藏玻璃背景 / 深浅模式）跟着几何一起推下去：
            // 默认值就等于以前那套，不改外观；改了设置这里也会立刻生效。
            android.content.Context glassCtx = dock.getContext();
            int wantedAlpha = DockPrefs.glassAlpha(glassCtx);
            // 视频页拿不到视频像素（PixelCopy code=1），玻璃里没有可折射的画面。
            // 这时候如果还按 100% 不透明度画材质，就是一块盖在视频上的深色板 ——
            // 用户看到的「黑底」就是它。视频页自动压到 40%，让视频直接透出来。
            iso.setAppearance(wantedAlpha,
                    DockPrefs.hideGlassBackground(glassCtx), DockPrefs.themeMode(glassCtx));
            float density = dock.getResources().getDisplayMetrics().density;
            int count = Math.max(1, tabsCount);
            Rect band = bandRect == null ? rectInWindow(dock) : new Rect(bandRect);
            if (band.width() <= 0 || band.height() <= 0) return;

            android.content.Context ctx = dock.getContext();

            int[] overlayLocation = new int[2];
            iso.view.getLocationInWindow(overlayLocation);

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
            // 水平居中一律按覆盖层自己的宽度算，不再跟着原生底栏的 left 走。
            // 以前从聊天框/会话页返回时，宿主底栏还没重新量好，band.left 是旧的，
            // 整组就跟着偏到左边去；覆盖层永远是整屏宽，拿它当基准就没有这个问题。
            int baseWidth = iso.view.getWidth() > 0 ? iso.view.getWidth() : band.width();
            if (baseWidth <= 0) baseWidth = band.width();
            int available = baseWidth - edge * 2;
            if (total > available) {
                // 窄屏：先压胶囊，保住「＋」，但不低过每格 48dp，再窄就让它居中溢出一点。
                tabsWidth = Math.max((int) (48f * density) * count,
                        Math.min(tabsWidth, available - plusBlock));
                total = tabsWidth + plusBlock;
            }
            if (tabsWidth <= 0) return;

            // 两个 dock 作为一整组水平居中；「＋」紧贴胶囊右边，只留 8dp。
            int groupLeft = Math.max(edge, (baseWidth - total) / 2);
            int top = bandTop + band.height() - (int) (DockPrefs.bottomGapDp(ctx) * density) - pill;

            // 位置兜底：不许压到系统导航条（手势条）上。
            // 全屏页面上底栏会贴到屏幕最底，量到的 band 就是「贴着边」的；
            // 之后回到普通页面如果还沿用那次量到的坐标，Dock 就叠在系统导航条上了。
            int overlayHeight = iso.view.getHeight();
            int navBottom = navigationBarInsetBottom(dock);
            if (overlayHeight > 0 && navBottom > 0) {
                // 手势导航下（导航条只有几十像素）Dock 直接贴到屏幕最底：
                // 只要还按导航条高度让位，Dock 底下就会留出一条 60 多像素的底色 ——
                // 用户看到的就是「下面还有一栏」。三键导航（导航条很高）仍然让开，
                // 否则会把返回键盖住。
                // 恢复原来的离底距离：永远从系统导航条上沿再往上 2dp。
                int limit = overlayHeight - navBottom
                        - (int) (2f * density) - pill;
                if (top > limit) top = limit;
            }

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

            // 采样窗口：视频页就采玻璃背后那一条；**没有视频层的页面**整体上移一个底栏高度。
            //
            // 原因：抖音是「内容 + 底栏」上下排列，底栏虽然被我们 alpha 归零，布局位置还占着，
            // 它那一条背后只有一层被清空的容器 + 窗口底色。直接采就是纯黑，玻璃既不透也折射不出东西。
            // 上移一个底栏高度，采到的是紧贴着玻璃上沿那几行内容 —— 视觉上连续，
            // 而且不用动宿主布局，抖音自己的控件（作者信息、弹幕栏）位置一个都不变。
            Rect sampleRegion = new Rect(regionInWindow);
            if (backdrop != null) {
                boolean hasVideo = Shield.hasVideoSurface(dock.getRootView());
                if (!hasVideo) {
                    int shift = Math.min(band.height(), Math.max(0, regionInWindow.top));
                    sampleRegion.offset(0, -shift);
                }
                backdrop.setRegion(sampleRegion);
            }
            iso.setGeometry(region, tabsRect, plusRect);
            // 底部填充带：从底栏上沿一直到屏幕底，用内容主色铺一层（顶部渐隐）。
            // 画面能铺到底的页面，这层和画面同色几乎看不出来；
            // 画面铺不到底的页面（16:9 视频的黑边），它就替那条黑边「续」成同一色调。
            if (backdrop != null) {
                // 填充带已撤：抖音页面自己有「画面往底栏过渡」的渐变层，
                // 我们再画一条固定在窗口上的色带反而翻页时不跟手。
                // iso.setBand(band.top, backdrop.uiTintColor());
            }
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
        stripDockBackground(dock, null);
    }

    /**
     * @param band 底栏所在的那一条（窗口坐标）。底栏被我们设成 GONE 之后自己量不到位置，
     *             所以复查时要把它传进来 —— 否则遮罩层会因为「盖住底栏」这一条算不出来而全部漏掉。
     */
    private static void stripDockBackground(View dock, Rect band) {
        if (dock == null) return;
        // 设置里的「底部背景透明」关掉就完全不动宿主的底色。
        Activity owner = Shield.activityOf(dock);
        if (owner != null && !DockPrefs.bottomTransparent(owner)) return;
        View root = dock.getRootView();
        int screenW = root == null ? 0 : root.getWidth();
        int screenH = root == null ? 0 : root.getHeight();
        if (screenW <= 0 || screenH <= 0) return;

        Rect dockRect = (band != null && !band.isEmpty()) ? new Rect(band) : rectInWindow(dock);
        if (dockRect.isEmpty()) return;
        int cleared = 0;

        // 1) 底栏本身和它的外壳容器。抖音的底栏壳通常自己铺一层深色 + 顶部渐变，
        //    只把底栏设成 GONE 是拿不掉这层的 —— 玻璃隔着它采样就是一块黑。
        View v = dock;
        // 12 层：抖音 41 把底栏外面又套了几层容器，6 层够不到真正铺底的那一层。
        // 只走 3 层：够拿到底栏壳和里面的胶囊底板；再往上就是页面容器了，
        // 清掉它等于删了抖音自己的页面底色（整屏变黑、翻页也不跟手），不能碰。
        for (int i = 0; i < 3 && v != null; i++) {
            if (clearBandBackground(v, screenW, screenH, dockRect)) cleared++;
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
            if (v != null && v.getId() == android.R.id.content) break;
        }

        // 2) 盖在底栏这一条上的渐变压暗层。它一般不在父链上（是兄弟层或更外层的浮层），
        //    所以按「位置 + 形状 + 纯装饰」来找，宁可漏掉也不误伤列表和评论区。
        //
        //    起点用底栏的祖父容器：遮罩层通常就在这一层里当兄弟节点。从窗口根节点开始扫的话，
        //    有限的节点预算会先被别的分支吃掉，轮不到底栏这一块。
        ViewGroup start = null;
        try {
            ViewParent up1 = dock.getParent();
            ViewParent up2 = up1 instanceof View ? ((View) up1).getParent() : null;
            if (up2 instanceof ViewGroup) start = (ViewGroup) up2;
            else if (up1 instanceof ViewGroup) start = (ViewGroup) up1;
        } catch (Throwable ignored) {
        }
        if (start == null && root instanceof ViewGroup) start = (ViewGroup) root;
        if (start != null) {
            int[] budget = new int[]{260};
            cleared += clearMaskLayers(start, dock, dockRect, screenW, screenH, 0, budget);
            // 3) 设置里的「弹层 / 菜单背景透明」：底部弹出来的菜单、分享面板自己铺的那层纯色底。
            //    只清 background，不动内容、不动透明度、不碰带输入框的评论区。
            if (owner != null && DockPrefs.menuTransparent(owner)) {
                cleared += clearMenuBackgrounds(start, dockRect, screenW, screenH, 0, new int[]{220});
            }
        }

        // 3) 底栏下面压着视频的时候，把窗口 / 内容树自己那层不透明底也去掉。
        //    视频是 SurfaceView，像素在窗口「下面」；窗口或内容树只要有一层不透明底挡着，
        //    视频就透不到底栏这一条来 —— 这就是「底部背景怎么都透明不了」的硬原因。
        // 无条件清：用户要的是「底栏那条带子纯透明」，不管有没有视频，
        // 窗口 / 内容树只要留一层不透明底，那条带子就永远是黑的。
        if (clearWindowBackdrop(dock)) cleared++;

        if (cleared > 0) {
            Dydock.log("底栏:已去掉 " + cleared + " 层原生底栏 / 边缘遮罩的背景");
        }
    }

    /** 去掉窗口和根视图自身的不透明背景，让下层视频透上来。 */
    /**
     * 让系统栏和底栏那一块真正透出画面。
     *
     * 关键点：视频是 SurfaceView，它的像素在应用窗口**下面**。所以只把系统栏颜色设成透明
     * 根本不够 —— 窗口自己那层不透明底（decor / content 的 background、以及 window 背景）
     * 会把视频整个挡在下面，透明区域露出来的仍然是黑。
     * 这三层一起清掉，透明区域才能真的透出视频。这也是「透明设了一直没效果」的最后一环。
     */
    private static boolean clearWindowBackdrop(View dock) {
        boolean changed = false;
        try {
            Activity act = Shield.activityOf(dock);
            if (act == null || act.getWindow() == null) return false;
            android.view.Window window = act.getWindow();

            // 只清 window 自己的背景，root / decor / content 一律不碰。
            //
            // 抖音那层「画面往底栏过渡」的背景就挂在页面这棵树上，清掉之后
            // 底下没有东西可透，露出来必然是纯黑（翻页中途那条黑带也是这么来的）。
            // window 背景仍然设透明 —— 系统导航条那一条要靠它才透得出来。
            // window 背景没有 getter，只能用标记保证只设一次。
            if (!WINDOW_BG_CLEARED.containsKey(act)) {
                window.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                WINDOW_BG_CLEARED.put(act, Boolean.TRUE);
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        return changed;
    }

    /** 只处理「够宽、不高、落在屏幕下半部、不是我们自己画的」那几层。 */
    /**
     * 清掉底部弹层 / 菜单自己铺的那层纯色底（设置里的「弹层 / 菜单背景透明」）。
     *
     * 判据刻意保守，宁可漏也不误伤：
     *   · 贴底、宽度 ≥ 屏宽 90%、高度在屏高 18%~62% 之间（菜单 / 分享面板的典型形状）；
     *   · 背景是纯色（ColorDrawable），图片/渐变不碰；
     *   · 里面没有输入框（评论输入条有 EditText，清掉会很难看）；
     *   · 不碰底栏自己的祖先链，也不碰我们自己的层。
     * 只把 background 置空，别的属性一律不动。
     */
    private static int clearMenuBackgrounds(ViewGroup parent, Rect band, int screenW,
                                            int screenH, int depth, int[] budget) {
        if (parent == null || depth > 6 || budget[0] <= 0) return 0;
        int cleared = 0;
        for (int i = 0; i < parent.getChildCount() && budget[0] > 0; i++) {
            View child = parent.getChildAt(i);
            if (child == null || isOursView(child)) continue;
            budget[0]--;
            try {
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.2f
                        || !child.isShown()) continue;
                Rect r = rectInWindow(child);
                if (r.width() < screenW * 0.9f) continue;
                int hh = r.height();
                if (hh < screenH * 0.18f || hh > screenH * 0.62f) continue;
                // 必须贴底（菜单从底部弹出），并且盖住底栏那一条。
                if (r.bottom < screenH - (int) (24f * child.getResources()
                        .getDisplayMetrics().density)) continue;
                if (!Rect.intersects(r, band)) continue;
                android.graphics.drawable.Drawable bg = child.getBackground();
                if (!(bg instanceof android.graphics.drawable.ColorDrawable)) continue;
                if (hasInputField(child, 0)) continue;
                // 评论区 / 列表页一定有滚动容器。小面板（菜单、分享）本身也可能带列表，
                // 所以只有「大面板」（> 45% 屏高）才要求没有列表 —— 半屏以下的面板就算有
                // RecyclerView 也照清，评论区那种大面板则因为有列表被排除。
                boolean bigPanel = hh > screenH * 0.45f;
                if (bigPanel && hasScrollableList(child, 0)) continue;
                saveAndClearBackground(child);
                cleared++;
                Dydock.log("底栏:清掉一层弹层纯色底（" + child.getClass().getName() + "）");
                if (child instanceof ViewGroup) {
                    cleared += clearMenuBackgrounds((ViewGroup) child, band, screenW, screenH,
                            depth + 1, budget);
                }
            } catch (Throwable ignored) {
            }
        }
        return cleared;
    }

    /** 里面有没有滚动列表：评论区、信息流面板靠这一条排除在「菜单透明」之外。 */
    private static boolean hasScrollableList(View v, int depth) {
        if (v == null || depth > 8) return false;
        // 用类名判断 RecyclerView：模块不直接依赖 recyclerview，写死类型会编译不过。
        String cn = v.getClass().getName();
        if (cn.contains("RecyclerView")
                || v instanceof android.widget.ListView
                || v instanceof android.widget.GridView
                || v instanceof android.widget.ScrollView
                || v instanceof android.widget.HorizontalScrollView) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (hasScrollableList(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    /** 弹层里有没有输入框：评论区就是靠这一条被排除在外的。 */
    private static boolean hasInputField(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v instanceof android.widget.EditText
                || v instanceof android.widget.AutoCompleteTextView) return true;
        // 抖音有些输入条是自研控件，不继承 EditText —— 按类名再兜一层。
        String cn = v.getClass().getName();
        if (cn.contains("EditText") || cn.contains("InputView")
                || cn.contains("InputBar") || cn.contains("CommentInput")) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (hasInputField(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    private static boolean clearBandBackground(View v, int screenW, int screenH, Rect dockRect) {
        try {
            if (v == null || isOursView(v)) return false;
            // 抖音的底栏渐变有时候不挂在 background 上，而是 foreground（叠在子内容上面那一层）。
            // 渐变层是抖音自己画的「画面往底栏过渡」，留着它底部才是一整块，不能清。
            if (v.getBackground() instanceof android.graphics.drawable.GradientDrawable) return false;
            boolean hasBg = v.getBackground() != null;
            boolean hasFg = hasForeground(v);
            if (!hasBg && !hasFg) return false;
            if (v.getWidth() < screenW * 0.6f) return false;
            Rect r = rectInWindow(v);
            // 只认底栏这一带：整块都在屏幕上半部的不碰（那是顶部标题栏的地盘）。
            if (r.bottom < screenH * 0.5f) return false;
            // 底栏那一带的容器有高有矮：矮的是底栏壳，高一点的是「底栏 + 上方渐变」整块。
            // 只要它是从下半屏开始、且没有顶到屏幕上沿，就不是页面自己的底。
            if (r.height() > screenH * 0.62f || r.top < screenH * 0.34f) return false;
            if (hasBg) saveAndClearBackground(v);
            if (hasFg) saveAndClearForeground(v);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasForeground(View v) {
        try {
            return v.getForeground() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void clearForeground(View v) {
        try {
            v.setForeground(null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 找底栏上方的渐变遮罩。
     *
     * 判据卡得比较死：盖住底栏那一块、宽度接近整屏、高度不超过屏高 35%、
     * 里面没有可见文字、也没有可点控件。抖音的评论区、列表、面板都带这些东西，
     * 所以不会被误伤；纯装饰的渐变压暗层正好一条都不占。
     */
    /** 清背景之前先存一份，还原时要用。 */
    private static void saveAndClearBackground(View v) {
        if (v == null) return;
        try {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg == null) return;
            synchronized (SAVED_BG) {
                if (!SAVED_BG.containsKey(v)) SAVED_BG.put(v, bg);
            }
            v.setBackground(null);
        } catch (Throwable ignored) {
        }
    }

    private static void saveAndClearForeground(View v) {
        if (v == null) return;
        try {
            android.graphics.drawable.Drawable fg = v.getForeground();
            if (fg == null) return;
            synchronized (SAVED_FG) {
                if (!SAVED_FG.containsKey(v)) SAVED_FG.put(v, fg);
            }
            v.setForeground(null);
        } catch (Throwable ignored) {
        }
    }

    /** 还原本模块清掉的背景 / 前景（沿底栏父链，最多 12 层，和清理时一致）。 */
    private static void restoreSavedBackgrounds(View dock) {
        View v = dock;
        for (int i = 0; i < 12 && v != null; i++) {
            try {
                android.graphics.drawable.Drawable bg;
                synchronized (SAVED_BG) { bg = SAVED_BG.remove(v); }
                if (bg != null && v.getBackground() == null) v.setBackground(bg);
                android.graphics.drawable.Drawable fg;
                synchronized (SAVED_FG) { fg = SAVED_FG.remove(v); }
                if (fg != null && !hasForeground(v)) v.setForeground(fg);
            } catch (Throwable ignored) {
            }
            android.view.ViewParent up = v.getParent();
            v = up instanceof View ? (View) up : null;
            if (v != null && v.getId() == android.R.id.content) break;
        }
    }

    /** candidate 是不是 dock 自己或者它的某一层祖先（这条链上的东西一律不许动）。 */
    private static boolean isAncestorOf(View candidate, View dock) {
        if (candidate == null || dock == null) return false;
        View cur = dock;
        for (int guard = 0; guard < 24 && cur != null; guard++) {
            if (cur == candidate) return true;
            android.view.ViewParent up = cur.getParent();
            cur = up instanceof View ? (View) up : null;
        }
        return false;
    }

    private static int clearMaskLayers(ViewGroup parent, View dock, Rect dockRect,
                                       int screenW, int screenH, int depth, int[] budget) {        if (parent == null || depth > 8 || budget[0] <= 0) return 0;
        int cleared = 0;
        for (int i = 0; i < parent.getChildCount() && budget[0] > 0; i++) {
            View child = parent.getChildAt(i);
            if (child == null || child == dock || isOursView(child)) continue;
            // 绝不能碰底栏自己的祖先链：把祖先 alpha 归零 = 整条底栏跟着消失，
            // 而且我们自己的可见性判据会把这个 0 读成「宿主藏了底栏」，玻璃马上收。
            // 这就是日志里 ancestorAlpha=0.00 → 底栏隐藏 → 恢复 → 再隐藏 的死循环来源。
            if (isAncestorOf(child, dock)) continue;
            budget[0]--;
            try {
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.05f) continue;
                Rect r = rectInWindow(child);
                boolean hasBg = child.getBackground() != null;
                boolean hasFg = hasForeground(child);
                boolean coversDock = Rect.intersects(r, dockRect);
                // 两种都清：
                //  ① 盖住底栏这一条的整条遮罩（宽、扁、没有内容）；
                //  ② 完全落在底栏区域里的装饰层 —— 底栏设成 GONE 之后，抖音经常还留着一块
                //     纯色/渐变的底垫着，不清它就是「底部背景透明没生效」。
                // 判据里都要求「没有可见文字、没有可点控件」，视频封面（用 src 不是 background）、
                // 图标、按钮都不会被碰到。
                boolean bigMask = coversDock
                        && r.width() >= screenW * 0.8f
                        && r.height() <= screenH * 0.4f;
                boolean insideBand = dockRect.contains(r);
                // 压住底栏这一条的一大半（不必整条包含）：抖音的底部渐变压暗层经常
                // 比底栏高出十几 dp，以前「必须完全落在底栏里」就把它漏掉了。
                boolean coversBand = bandCoverage(r, dockRect) >= 0.6f
                        && r.width() >= screenW * 0.7f
                        && r.height() <= screenH * 0.5f;
                boolean gradientScrim = child.getBackground() instanceof android.graphics.drawable.GradientDrawable;
                if ((hasBg || hasFg) && !gradientScrim && (bigMask || insideBand || coversBand)
                        && !hasVisibleTextIn(child, 0)
                        && !hasClickableIn(child, 0)) {
                    if (hasBg) saveAndClearBackground(child);
                    if (hasFg) saveAndClearForeground(child);
                    cleared++;
                    Dydock.log("底栏:清掉一层原生背景（"
                            + child.getClass().getName() + "）");
                }
                // 兜底：有些版本把这条压暗渐变直接画在 onDraw 里（不是 background /
                // foreground），清背景对它没用。
                //
                // 以前这里按类名认（scrim / gradient / fade），可抖音 41 的类名是混淆的
                // （日志里就是 X.0lkZ、X.12sL），这条兜底在真机上基本命中不了 ——
                // 表现就是「底部背景透明怎么设都不生效」。
                // 现在改成按形状和位置认：压在底栏这一条上、扁而宽、没有文字、没有可点控件、
                // 里面也没有视频层，就把它的 alpha 归零。整屏容器和播放器容器都不满足「扁」，
                // 不会被误伤。
                boolean flatStrip = (coversBand || bigMask || insideBand)
                        && dockRect.height() > 0
                        && r.height() <= (int) (dockRect.height() * 1.8f);
                if (!hasBg && !hasFg && !gradientScrim && flatStrip
                        && !Shield.hasVideoSurface(child)
                        && !hasVisibleTextIn(child, 0)
                        && !hasClickableIn(child, 0)
                        && child.getAlpha() > 0.02f) {
                    SelfMask.mask(child);
                    cleared++;
                    Dydock.log("底栏:已隐藏绘制出来的渐变遮罩（"
                            + child.getClass().getName() + "）");
                }
                if (child instanceof ViewGroup) {
                    cleared += clearMaskLayers((ViewGroup) child, dock, dockRect,
                            screenW, screenH, depth + 1, budget);
                }
            } catch (Throwable ignored) {
            }
        }
        return cleared;
    }

    /**
     * 把底栏那一带的图层信息写进诊断日志（Download/Dydock/dydock-diagnostic.log）。
     *
     * 排「底部背景不透明」这种问题时，光看「清掉了几层」不够 —— 得知道到底哪一层还在铺底。
     * 这里把压在底栏带上的每一层都列出来（类名 / 位置 / alpha / 背景 / 有没有文字和点击），
     * 日志发过来就能直接定位，不用再一轮轮试。
     */
    static void dumpBandLayers(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        try {
            View decor = act.getWindow() == null ? null : act.getWindow().peekDecorView();
            if (decor == null) return;
            View content = act.findViewById(android.R.id.content);
            View dock;
            synchronized (DOCKS) { dock = DOCKS.get(act); }
            Rect band;
            synchronized (BANDS) { band = BANDS.get(act); }
            if (band == null && dock != null) band = rectInWindow(dock);
            Dydock.log("诊断:底栏带=" + (band == null ? "未量到" : band.toShortString())
                    + " 原生底栏[" + Shield.explainNativeBar(dock, band, content) + "]");
            if (band == null || band.isEmpty() || !(decor instanceof ViewGroup)) return;
            StringBuilder sb = new StringBuilder();
            int[] out = new int[]{0};
            collectBandLayers((ViewGroup) decor, band, sb, 0, new int[]{1500}, out);
            if (sb.length() > 0) Dydock.log("诊断:压在底栏带上的层\n" + sb);
        } catch (Throwable t) {
            Dydock.warnOnce("dump-band", "底栏图层诊断失败: " + t);
        }
    }

    private static void collectBandLayers(ViewGroup parent, Rect band, StringBuilder sb,
                                          int depth, int[] budget, int[] out) {
        if (parent == null || depth > 14 || budget[0] <= 0 || out[0] >= 24) return;
        for (int i = 0; i < parent.getChildCount() && budget[0] > 0 && out[0] < 24; i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;
            budget[0]--;
            try {
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.02f) continue;
                Rect r = rectInWindow(child);
                if (!Rect.intersects(r, band)) continue;
                boolean hasBg = child.getBackground() != null;
                boolean hasFg = hasForeground(child);
                boolean ours = isOursView(child);
                sb.append("  ·").append(child.getClass().getName())
                        .append(" [").append(r.toShortString()).append("]")
                        .append(" a=").append(String.format(java.util.Locale.US, "%.2f", child.getAlpha()))
                        .append(hasBg ? " bg=" + child.getBackground().getClass().getSimpleName() : "")
                        .append(hasFg ? " fg=有" : "")
                        .append(hasVisibleTextIn(child, 0) ? " 文字" : "")
                        .append(hasClickableIn(child, 0) ? " 可点" : "")
                        .append(ours ? " (ours)" : "")
                        .append("\n");
                out[0]++;
            } catch (Throwable ignored) {
            }
            if (child instanceof ViewGroup) {
                collectBandLayers((ViewGroup) child, band, sb, depth + 1, budget, out);
            }
        }
    }

    /** 这块区域压住了底栏那一条的多少（0..1）：按宽度、高度各自的重叠比例取小的那个。 */
    private static float bandCoverage(Rect r, Rect band) {
        if (r == null || band == null || r.isEmpty() || band.isEmpty()) return 0f;
        int w = Math.min(r.right, band.right) - Math.max(r.left, band.left);
        int h = Math.min(r.bottom, band.bottom) - Math.max(r.top, band.top);
        if (w <= 0 || h <= 0) return 0f;
        float cw = (float) w / Math.max(1, band.width());
        float ch = (float) h / Math.max(1, band.height());
        return Math.min(cw, ch);
    }

    /** 名字像「压暗 / 渐变 / 遮罩」这类纯装饰层。 */
    private static boolean looksLikeScrim(View v) {
        try {
            String n = v.getClass().getName().toLowerCase();
            return n.contains("scrim") || n.contains("gradient") || n.contains("vignette")
                    || n.contains("shadow") || n.contains("fade");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasVisibleTextIn(View v, int depth) {
        if (v == null || depth > 10) return false;
        try {
            if (!v.isShown()) return false;
            if (v instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) v).getText();
                if (text != null && text.toString().trim().length() > 0) return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (hasVisibleTextIn(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasClickableIn(View v, int depth) {
        if (v == null || depth > 10) return false;
        try {
            if (v.isClickable() && v.isShown()) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (hasClickableIn(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isOursView(View v) {
        try {
            return v.getClass().getName().startsWith("com.zaka.douyin.glass");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 状态栏那一块的黑色色带。
     *
     * 抖音全屏播放时会在状态栏区域铺一层从黑到透明的渐变；状态栏一旦隐藏，
     * 那一层就是「黑一条」。只在宿主自己隐藏了状态栏（FULLSCREEN）时处理 ——
     * 状态栏还在的时候这层是给系统图标垫底的，动了会让时间和电量看不清。
     *
     * 判据和底部那套一致：盖住状态栏那一带、几乎整屏宽、不高、没有可见文字、没有可点控件。
     */
    private static void stripStatusBand(View dock) {
        if (dock == null) return;
        View root = dock.getRootView();
        if (!(root instanceof ViewGroup)) return;
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        int statusH = statusBarHeight(dock);
        int limit = Math.max(statusH * 2, (int) (screenH * 0.08f));
        int[] budget = new int[]{220};
        int cleared = clearTopLayers((ViewGroup) root, screenW, screenH, limit, 0, budget);
        if (cleared > 0) {
            Dydock.log("状态栏:已去掉 " + cleared + " 层顶部黑色色带");
        }
    }

    private static int clearTopLayers(ViewGroup parent, int screenW, int screenH, int limit,
                                      int depth, int[] budget) {
        if (parent == null || depth > 8 || budget[0] <= 0) return 0;
        int cleared = 0;
        for (int i = 0; i < parent.getChildCount() && budget[0] > 0; i++) {
            View child = parent.getChildAt(i);
            if (child == null || isOursView(child)) continue;
            budget[0]--;
            try {
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.05f) continue;
                Rect r = rectInWindow(child);
                boolean hasBg = child.getBackground() != null;
                boolean hasFg = hasForeground(child);
                if (r.top <= limit && r.width() >= screenW * 0.8f
                        && r.height() <= screenH * 0.25f
                        && (hasBg || hasFg)
                        && !hasVisibleTextIn(child, 0)
                        && !hasClickableIn(child, 0)) {
                    if (hasBg) saveAndClearBackground(child);
                    if (hasFg) saveAndClearForeground(child);
                    cleared++;
                }
                if (child instanceof ViewGroup) {
                    cleared += clearTopLayers((ViewGroup) child, screenW, screenH, limit,
                            depth + 1, budget);
                }
            } catch (Throwable ignored) {
            }
        }
        return cleared;
    }

    /** 宿主自己是不是已经把状态栏隐藏了（全屏播放、沉浸式）。 */
    private static boolean hostHidesStatusBar(Activity act) {
        try {
            if (act == null || act.getWindow() == null) return false;
            android.view.Window window = act.getWindow();
            View decor = window.getDecorView();
            if (decor == null) return false;
            int flags = decor.getSystemUiVisibility();
            if ((flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) return true;
            if ((window.getAttributes().flags
                    & android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) return true;
            // 新版抖音是用 WindowInsetsController.hide(statusBars()) 藏的，
            // 上面两个老 flag 都看不见，只能问 insets。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets insets = decor.getRootWindowInsets();
                if (insets != null
                        && !insets.isVisible(android.view.WindowInsets.Type.statusBars())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int statusBarHeight(View v) {
        try {
            android.content.Context ctx = v.getContext();
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return (int) (24 * v.getResources().getDisplayMetrics().density);
    }

    /**
     * 系统导航栏那片区域（红米 / HyperOS 的手势条那一带）。
     *
     * 只做两件事：导航栏背景透明、关掉系统的对比度强制灰底。
     *
     * 这里**不开** LAYOUT_HIDE_NAVIGATION：内容延伸进手势区会把抖音自己的控件
     * （视频页进度条 / 弹幕栏）一起顶到 Dock 底下，点不到。画面连续感改由采样窗口处理。
     * 另外 GlassEntry.makeNavigationAreaTransparent 里也是这么处理的，两处必须同一个口径。
     */
    private static void applyImmersiveNavigation(Activity act) {
        if (applyWindowChrome(act)) {
            Dydock.log("窗口:状态栏 / 导航栏已设为透明");
        }
    }

    /**
     * 状态栏 + 导航栏一起处理，**幂等**：每帧调都安全，没被改回去就什么也不做。
     *
     * 为什么必须是幂等的：抖音在 onResume、切 tab、进出全屏、重建窗口之后，
     * 会把自己的栏色和 ui flags 整段写回去 —— 只设一次必然被它盖掉。
     * 「导航栏透明不生效」「隐藏状态栏以后那条是黑的」都是这么来的。
     *
     * 规矩：
     *  - 不打开 LAYOUT_HIDE_NAVIGATION。0.6.0 试过，抖音的进度条/弹幕栏会跟着下移
     *    钻进 Dock 底下点不到；布局范围保持和宿主一致。
     *  - 不主动隐藏状态栏。只有宿主**自己已经在隐藏**（FULLSCREEN）时才补 LAYOUT_FULLSCREEN，
     *    因为透明的状态栏只有内容真的画到那一块才透得出来；不补的话露出来的是窗口底色，
     *    也就是那条黑边。
     *
     * @return 这次有没有真的改动过窗口（用来只在变化时打日志）
     */
    static boolean applyWindowChrome(Activity act) {
        if (act == null) return false;
        try {
            android.view.Window window = act.getWindow();
            if (window == null) return false;
            View decor = window.getDecorView();
            if (decor == null) return false;
            boolean changed = false;
            // 窗口自身背景设成纯透明：不然那条带子透出来的是「窗口底色」（黑）
            try {
                if (!(window.getDecorView().getBackground() instanceof android.graphics.drawable.ColorDrawable)
                        || ((android.graphics.drawable.ColorDrawable) window.getDecorView().getBackground()).getColor() != android.graphics.Color.TRANSPARENT) {
                    window.setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    changed = true;
                }
            } catch (Throwable ignored) {
            }



            // setStatusBarColor / setNavigationBarColor 在 Android 5+ 有个前提：
            // 窗口必须带 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，否则系统直接忽略这两个调用 ——
            // 这条就是「设了透明但一点效果都没有」的根本原因。
            if ((window.getAttributes().flags
                    & android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
                window.addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                changed = true;
            }

            // 导航栏：透明。手势导航下系统默认还要在背后铺一层对比度灰底，不关掉就还是灰的。
            if (window.getNavigationBarColor() != android.graphics.Color.TRANSPARENT) {
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                changed = true;
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
            }
            if (android.os.Build.VERSION.SDK_INT >= 29
                    && window.isNavigationBarContrastEnforced()) {
                window.setNavigationBarContrastEnforced(false);
                changed = true;
            }

            // 不再动 decor 的 systemUiVisibility。
            //
            // 以前这里会补 LAYOUT_STABLE / LAYOUT_HIDE_NAVIGATION / LAYOUT_FULLSCREEN，
            // 宿主（抖音）在长按倍速、切页、进出全屏时会整段写回自己的 flags，
            // 我们又写回去 —— 每来回一次就是一次窗口 relayout，肉眼就是「画面跳一下」。
            // 现在只保留状态栏 / 导航栏的颜色与对比度，flags 交给宿主自己。
            // 直播/视频画面要铺到手势条后面，必须有 LAYOUT_HIDE_NAVIGATION；
            // 但这段整体只允许 3 秒跑一次，否则宿主一改我们就改回去 = 反复 relayout = 画面跳。
            Long lastAt = CHROME_AT.get(act);
            long nowMs = android.os.SystemClock.uptimeMillis();
            if (lastAt != null && nowMs - lastAt < 3000L) return false;
            CHROME_AT.put(act, nowMs);

            int flags = decor.getSystemUiVisibility();
            int next = flags;
            next |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            next |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;


            boolean hostHidesStatus = hostHidesStatusBar(act);

            // 状态栏：只在宿主自己已经把它隐藏（全屏播放 / 沉浸式）的时候处理。
            //
            // 为什么不一刀切：状态栏还在的时候，decor 并没有铺到那一块，设成透明之后
            // 露出来的是窗口底色（黑）—— 那不是修好，而是凭空多一条黑边。
            // 宿主隐藏了状态栏，那块本来就是空的，透明 + 内容延伸过去才真的透出画面。
            if (hostHidesStatus) {
                Integer original = ORIGINAL_STATUS_BAR.get(act);
                if (original == null) {
                    // 记下抖音自己那份颜色，退出全屏时还回去。
                    original = window.getStatusBarColor();
                    ORIGINAL_STATUS_BAR.put(act, original);
                }
                if (window.getStatusBarColor() != android.graphics.Color.TRANSPARENT) {
                    window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                    changed = true;
                }
                if (android.os.Build.VERSION.SDK_INT >= 29
                        && window.isStatusBarContrastEnforced()) {
                    window.setStatusBarContrastEnforced(false);
                    changed = true;
                }
                next |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                OURS_LAYOUT_FULLSCREEN.put(act, Boolean.TRUE);
            } else {
                // 退出全屏（回到主界面）：把我们加的东西收回去。
                // 不收的话状态栏那块会一直透明 —— 主界面的 decor 没铺到那里，
                // 露出来是窗口底色，看着就是「从视频页退回来以后顶部黑一条」。
                Integer original = ORIGINAL_STATUS_BAR.remove(act);
                if (original != null
                        && window.getStatusBarColor() == android.graphics.Color.TRANSPARENT) {
                    window.setStatusBarColor(original);
                    changed = true;
                }
                if (Boolean.TRUE.equals(OURS_LAYOUT_FULLSCREEN.remove(act))) {
                    next &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                }
            }

            if (next != flags) {
                decor.setSystemUiVisibility(next);
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            Dydock.warnOnce("window-chrome", "状态栏 / 导航栏透明设置失败: " + t);
            return false;
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
            // 「我的」页才需要那个 dydock 入口：只在这一页监听菜单里的设置项。
            DockSettingsUi.onTabSelected(Shield.activityOf(tabs.get(0)), selected < 0 ? 0 : selected);
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

    /**
     * 底部当前选中的是不是「我」这一格（第四格）。
     *
     * 选中态是宿主自己给的，比自己主页上扫文字可靠 —— 抖音改版之后「编辑资料」
     * 未必扫得到，只靠文案判就会把「我的」当成别人主页，点进去 Dock 直接没了。
     */
    private static boolean myTabSelected(List<View> tabs) {
        try {
            if (tabs == null || tabs.size() < 4) return false;
            return isSelectedDeep(tabs.get(3), 0);
        } catch (Throwable t) {
            return false;
        }
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

    /**
     * 系统导航条（手势条）在窗口底部占掉的高度。
     *
     * 抖音全屏播放时这条是 0，回到普通页面又会恢复。玻璃 Dock 一律不许压进这一段，
     * 否则看起来就是「Dock 跑到系统导航上去了」。
     */
    private static int navigationBarInsetBottom(View v) {
        if (v == null) return 0;
        try {
            android.view.WindowInsets insets = v.getRootWindowInsets();
            if (insets == null) return 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            }
            if (android.os.Build.VERSION.SDK_INT >= 20) {
                return insets.getStableInsetBottom();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 量到的底栏那一条合不合理。
     *
     * 不合理就不能拿它当锚点 —— 比如全屏播放时量到的位置贴着屏幕最底，
     * 拿这个当锚点，Dock 就会一直压在系统导航条上。
     */
    private static boolean validBand(Rect band, View decor, int navBottom, float density) {
        if (band == null || band.width() <= 0 || band.height() <= 0) return false;
        int windowHeight = decor == null ? 0 : decor.getHeight();
        if (windowHeight <= 0) return true;
        float tol = 4f * density;
        if (band.bottom > windowHeight - navBottom + tol) return false;   // 压进导航条
        if (band.top < windowHeight * 0.45f) return false;                // 太靠上，不可能是底栏
        if (band.height() > 160f * density) return false;
        return true;
    }

    /**
     * 量不出可信位置时的兜底：就贴在系统导航条上方，按设置里的「离底部间距」留白。
     *
     * 这条永远是对的，不依赖宿主有没有量好、也不依赖底栏在不在窗口里。
     */
    private static Rect fallbackBand(View decor, int navBottom, float density, int bottomGapDp,
                                     int pillPx) {
        int windowWidth = decor == null ? 0 : decor.getWidth();
        int windowHeight = decor == null ? 0 : decor.getHeight();
        int bottom = windowHeight - navBottom;
        int top = bottom - Math.max(pillPx, (int) (32f * density));
        return new Rect(0, top, windowWidth, bottom);
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
        /**
         * 我们自己把 alpha 归零的那条原生底栏。
         *
         * 模块用 alpha=0 藏原生底栏（而不是 GONE，为的是保留布局和「该不该显示」这条信号），
         * 打分里那条 alpha 门槛会把它一起否掉 —— 结果就是「藏起来之后再也认不出来」。
         * 这里单独放行这一条。
         */
        static volatile java.lang.ref.WeakReference<View> hiddenByUs;

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
            if (!g.isShown()) return -1f;
            if (g.getAlpha() < 0.2f) {
                java.lang.ref.WeakReference<View> hu = hiddenByUs;
                if (hu == null || hu.get() != g) return -1f;
            }

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
