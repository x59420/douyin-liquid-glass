package com.zaka.douyin.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏：左边一整块玻璃装 4 个图标、右边一块圆形玻璃装「＋」。
 *
 * 官方底栏那一条只把「画」藏起来（alpha = 0，布局和点击都还在），
 * 然后按它的位置自己画两块悬浮玻璃，点击转发回官方那几个子项。
 *
 * 0.0.5 的三处改动：
 *  1. 左边那块**水平居中**，＋ 紧挨在它右边（只留 3dp 的缝）
 *  2. 位置改成**每帧跟一次官方底栏**。以前只在全局布局回调里更新，
 *     而抖音展开个人页侧栏时底栏是靠 translation 滑过去的 —— 不触发布局，
 *     玻璃就一直停在原地，等下一次布局才「啪」地跳过去，看着像卡了半秒。
 *  3. 补上长按拖动那一套（照 KernelSU 的 DampedDragAnimation / Kyant 的
 *     LiquidBottomTabs）：按住 → 选中块放大成透镜、整条鼓一下；
 *     横着拖 → 透镜跟手、按速度往运动方向拉长；松手 → 弹到最近一格，
 *     等它快到位了才把放大收回去，读起来是一个连贯动作而不是「滑一下再缩一下」。
 */
final class DockOverlay extends View implements Choreographer.FrameCallback {

    private static final int MAX_SLOTS = 6;

    /** 按住时透镜放大到的倍数（KernelSU: 78 / 56） */
    private static final float PRESSED_SCALE = 1.16f;
    /** 松手后位置进到总行程的这个比例以内，才收回放大 */
    private static final float SETTLE_FRACTION = 0.025f;
    /** 兜底：位置一直没停稳也别把放大卡住 */
    private static final long RELEASE_WATCHDOG_MS = 800L;

    private final Backdrop backdrop;
    private final float density;

    private final List<View> tabs = new ArrayList<View>();
    private View plusTab;

    /** 官方底栏那一条（窗口坐标） */
    private final Rect band = new Rect();
    private final int[] winLoc = new int[2];

    private final RectF leftPill = new RectF();
    private final RectF plusPill = new RectF();
    private final RectF lens = new RectF();
    private final RectF tmp = new RectF();
    private final Rect[] slots = new Rect[MAX_SLOTS];

    private float slotW;
    private float pillH;
    private int count;

    private final Spring pos = new Spring(0f);
    private final Spring press = new Spring(0f);
    private final Spring plusPress = new Spring(0f);
    /** 0 → 1：整组出现 */
    private final Spring appear = new Spring(0f);
    /** 0 → 1：按下时那块长条被压下去的形变 */
    private final Spring squish = new Spring(0f);
    /** Multi-channel droplet deformation ported from the TieBa reference. */
    private final DropletMotion droplet = new DropletMotion();

    private int selected;
    private boolean downInLeft;
    private int pendingIndex;
    private boolean downInPlus;
    private boolean dragging;
    /** 正在接收原生触摸序列的底栏子项；未开始拖动前由它处理点击和长按。 */
    private View nativeTarget;
    private float downX;
    private float downY;
    private float dragStart;
    private float lastDragX;
    private int touchSlop;
    private boolean looping;
    private long lastFrame;
    private boolean drewOnce;

    /** 松手后等位置快到位再收放大 */
    private boolean releasePending;
    private long releaseSince;

    /** 每帧跟着走的官方底栏 */
    private View dockView;
    private boolean following;
    private boolean followScheduled;
    private final int[] dockLoc = new int[2];

    /** 未读角标：从宿主原来那几个子项里读，自己在玻璃上重画 */
    private final int[] badgeNum = new int[MAX_SLOTS];
    private final boolean[] badgeDot = new boolean[MAX_SLOTS];

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() == VISIBLE && isAttachedToWindow()) {
                backdrop.pumpVideo();
            }
            timer.postDelayed(this, 90L);
        }
    };
    private final Runnable recapture = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() == VISIBLE && isAttachedToWindow()) {
                backdrop.capture();
                invalidate();
            }
            timer.postDelayed(this, 1200L);
        }
    };

    private final Runnable badgeTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (getVisibility() == VISIBLE && isAttachedToWindow()) {
                    scanBadges();
                    invalidate();
                }
            } catch (Throwable t) {
                Dydock.warnOnce("badge", "读角标失败: " + t);
            }
            timer.postDelayed(this, 700L);
        }
    };

    /** 每帧对齐官方底栏：位置一变就重排玻璃（动画里也跟得上） */
    private final Choreographer.FrameCallback follow = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            followScheduled = false;
            if (!following) return;
            if (getVisibility() != VISIBLE || !isAttachedToWindow()) {
                following = false;
                return;
            }
            try {
                View d = dockView;
                if (d != null && d.isAttachedToWindow() && d.getWidth() > 0) {
                    d.getLocationInWindow(dockLoc);
                    int l = dockLoc[0];
                    int t = dockLoc[1];
                    int r = l + d.getWidth();
                    int b = t + d.getHeight();
                    if (l != band.left || t != band.top || r != band.right || b != band.bottom) {
                        band.set(l, t, r, b);
                        layoutPills();
                        syncRegion();
                        invalidate();
                    }
                }
            } catch (Throwable t) {
                Dydock.warnOnce("follow", "跟随底栏失败: " + t);
            }
            scheduleFollow();
        }
    };

    DockOverlay(Context ctx, Backdrop backdrop, float density) {
        super(ctx);
        this.backdrop = backdrop;
        this.density = density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setWillNotDraw(false);
        // This full-screen overlay must receive DOWN/MOVE/UP inside its two Dock surfaces.
        // Outside those surfaces onTouchEvent returns false, so underlying content still handles input.
        setClickable(true);
        setFocusable(false);
        for (int i = 0; i < MAX_SLOTS; i++) slots[i] = new Rect();
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        shadowPaint.setColor(0x33000000);
        backdrop.setOnFrame(new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        });
    }

    static DockOverlay create(Context ctx, Backdrop bd, float density) {
        return new DockOverlay(ctx, bd, density);
    }

    void setTabs(List<View> list, View plus) {
        tabs.clear();
        tabs.addAll(list);
        plusTab = plus;
        count = Math.min(tabs.size(), MAX_SLOTS);
        selected = 0;
        pos.set(0f);
        droplet.snapToRest();
        appear.to(1f, 260f, 34f);
        startLoop();
    }

    /** 认出来的官方底栏，之后每帧都跟它对齐 */
    void attachDock(View dock) {
        dockView = dock;
        following = dock != null;
        scheduleFollow();
    }

    private void scheduleFollow() {
        if (followScheduled || !following) return;
        followScheduled = true;
        Choreographer.getInstance().postFrameCallback(follow);
    }

    /** @param r 官方底栏在窗口里的矩形 */
    void setBand(Rect r) {
        if (r == null || r.isEmpty()) return;
        if (r.equals(band) && !leftPill.isEmpty()) return;
        band.set(r);
        layoutPills();
        syncRegion();
        invalidate();
    }

    /**
     * 挂上来的头一帧，View 还没测量完，getLocationInWindow 拿到的是 (0,0)；
     * 等真正布局好之后重量一次，免得玻璃整体偏一段。
     */
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (band.isEmpty()) return;
        layoutPills();
        syncRegion();
        invalidate();
    }

    private void syncRegion() {
        if (leftPill.isEmpty()) return;
        backdrop.setRegion(new Rect(
                Math.round(leftPill.left + winLoc[0]) - Math.round(30 * density),
                Math.round(leftPill.top + winLoc[1]) - Math.round(26 * density),
                Math.round(plusPill.right + winLoc[0]) + Math.round(30 * density),
                Math.round(leftPill.bottom + winLoc[1]) + Math.round(22 * density)));
    }

    /** 玻璃有没有真的画出来过（没画出来就得把官方底栏还回去） */
    boolean hasDrawn() {
        return drewOnce;
    }

    // ---------------------------------------------------------------- 几何

    private void layoutPills() {
        getLocationInWindow(winLoc);
        float bandLeft = band.left - winLoc[0];
        float bandTop = band.top - winLoc[1];
        float bandW = band.width();
        float bandH = band.height();

        // 比官方那一条大一圈：太小了托不住 4 个图标
        pillH = Math.min(64f * density, Math.max(52f * density, bandH + 10f * density));
        float plusSize = pillH;
        float plusGap = 3f * density;
        slotW = Math.min(56f * density, Math.max(50f * density, pillH * 0.88f));

        float pad = 8f * density;
        float leftW = slotW * count + pad * 2f;

        // 左边那块**水平居中**，＋ 紧贴着它右边
        float leftX = bandLeft + (bandW - leftW) / 2f;
        // 抬高一点，别贴着系统手势条
        float bottom = bandTop + bandH - 6f * density;
        float top = bottom - pillH;

        // 抬高 + 放大之后别顶出屏幕（顶出去会被裁掉，看着就像没显示）
        int vh = getHeight();
        if (vh > 0) {
            float lo = 2f * density;
            float hi = vh - 2f * density;
            if (bottom > hi) {
                bottom = hi;
                top = bottom - pillH;
            }
            if (top < lo) {
                top = lo;
                bottom = top + pillH;
            }
        }
        // 也别顶出左右边缘
        int vw = getWidth();
        if (vw > 0) {
            float total = leftW + plusGap + plusSize;
            if (leftX < 2f * density) leftX = 2f * density;
            if (leftX + total > vw - 2f * density) leftX = vw - 2f * density - total;
            if (leftX < 2f * density) leftX = 2f * density;
        }

        leftPill.set(leftX, top, leftX + leftW, bottom);
        plusPill.set(leftPill.right + plusGap, top,
                leftPill.right + plusGap + plusSize, bottom);

        for (int i = 0; i < count; i++) {
            float l = leftPill.left + pad + i * slotW;
            slots[i].set(Math.round(l), Math.round(leftPill.top),
                    Math.round(l + slotW), Math.round(leftPill.bottom));
        }
    }

    /** 出现动画：以底边为锚点，把一块矩形按 k 缩小 */
    private void scaled(RectF src, float k, RectF out) {
        float cx = src.centerX();
        float cy = src.bottom - src.height() * k / 2f;
        float hw = src.width() * k / 2f;
        float hh = src.height() * k / 2f;
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count <= 0 || leftPill.isEmpty()) return;

        float a = Math.max(0f, Math.min(1f, appear.value));
        float k = 0.90f + 0.10f * a;
        int alpha = (int) (255 * a);
        if (alpha <= 0) return;

        float p = Math.max(0f, Math.min(1f, press.value));
        float pp = Math.max(0f, Math.min(1f, plusPress.value));
        float sq = Math.max(0f, Math.min(1f, squish.value));

        boolean framed = backdrop.hasFrame();
        int luma = framed ? backdrop.luma() : 128;
        boolean light = luma >= 140;
        int ink = light ? 0xFF1A1A1A : 0xFFFFFFFF;

        // 按住时整条鼓一下：高度随按压微涨，像被指头压起来
        float grow = 1f + 0.035f * p;
        scaled(leftPill, k * (1f - 0.03f * sq) * grow, tmp);
        tmp.offset(0f, 1.5f * density * sq);
        drawPane(canvas, tmp, tmp.height() / 2f, framed, luma, p * 0.55f,
                dragging && p > 0.3f, alpha);

        scaled(plusPill, k * (1f - 0.04f * pp) * (1f + 0.02f * p), rect);
        drawPane(canvas, rect, rect.width() / 2f, framed, luma, pp, false, alpha);

        drawLens(canvas, a, p, framed, luma, light, ink);
        drawIcons(canvas, a, p, ink);
        drawPlus(canvas, a, pp, ink);
        drawBadges(canvas, a);
        // 真的把玻璃画到画布上了才算数 —— 出现动画还没启动（整块全透明）时不算
        drewOnce = true;
    }

    private void drawPane(Canvas canvas, RectF b, float rad, boolean framed,
                          int luma, float press, boolean dispersion, int alpha) {
        int save = canvas.save();
        try {
            if (alpha < 255) {
                // 出现动画：整体淡入
                canvas.saveLayerAlpha(b.left - 2, b.top - 2, b.right + 2, b.bottom + 2, alpha);
            }
            if (framed) {
                try {
                    GlassPainter.draw(canvas, b, rad, backdrop, this, press, dispersion, luma, density);
                } catch (Throwable t) {
                    // 着色器在一些机型/系统版本上会直接抛（AGSL 编不过、RuntimeShader 不支持）。
                    // 以前这一下会把整块玻璃带走 —— 退回磨砂，至少看得见。
                    Dydock.warnOnce("pane-gl", "折射绘制失败，退回磨砂: " + t);
                    GlassPainter.drawFrosted(canvas, b, rad, density, luma);
                }
            } else {
                GlassPainter.drawFrosted(canvas, b, rad, density, luma);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * 选中块：平时是一块淡色胶囊，按住/拖动时放大成一块透镜，
     * 拖得越快沿运动方向拉得越长（KernelSU 的 scaleX /= 1 - velocity * 0.75）。
     */
    private void drawLens(Canvas canvas, float a, float p, boolean framed,
                          int luma, boolean light, int ink) {
        float x = pos.value;

        // 按压缩放：宽度、高度一起长
        float dropletPress = droplet.pressProgress();
        float w = slotW * 0.84f * droplet.scaleX();
        float h = leftPill.height() * 0.76f * droplet.scaleY();
        // Preserve the restrained resting lens, but use independent width/height spring channels while touched.
        w = Math.max(slotW * 0.72f, Math.min(slotW * 1.55f, w));
        h = Math.max(leftPill.height() * 0.62f, Math.min(leftPill.height() * 1.12f, h));
        h = Math.min(h, leftPill.height() * 1.10f);

        float dragFraction = leftPill.width() > 0f
                ? clampF(droplet.accumulatedDrag() / leftPill.width(), -1f, 1f) : 0f;
        float cx = slotCenterX(x) + 4f * density * dragFraction;
        // 允许透镜在按住时上下略微鼓出整条玻璃 —— 放大镜浮起来才有实感
        lens.set(cx - w / 2f, leftPill.centerY() - h / 2f,
                cx + w / 2f, leftPill.centerY() + h / 2f);
        // 只在没被按住时限制在玻璃里；按住时鼓出来一点更有立体感
        if (p < 0.5f) lens.intersect(leftPill);

        int save = canvas.save();
        try {
            if (a < 1f) canvas.saveLayerAlpha(lens, (int) (255 * a));

            if (dropletPress > 0.02f && framed) {
                // 按住/拖动：透镜本身是一块玻璃，带一层柔和投影，像浮在底栏上
                float sh = (2.5f + 2f * droplet.glowProgress()) * density * dropletPress;
                shadowPaint.setColor(light ? 0x22000000 : 0x40000000);
                shadowPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(lens.left - sh * 0.6f, lens.top + sh * 0.8f,
                        lens.right + sh * 0.6f, lens.bottom + sh, h / 2f, h / 2f, shadowPaint);
                drawPane(canvas, lens, h / 2f, true, luma, dropletPress, dragging, 255);
                drawDropletRim(canvas, lens, h / 2f, droplet.glowProgress(), light);
            } else if (dropletPress > 0.02f) {
                drawPane(canvas, lens, h / 2f, false, luma, dropletPress, dragging, 255);
                drawDropletRim(canvas, lens, h / 2f, droplet.glowProgress(), light);
            } else {
                selPaint.setShader(null);
                selPaint.setColor(light ? 0x24000000 : 0x26FFFFFF);
                canvas.drawRoundRect(lens, h / 2f, h / 2f, selPaint);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private final Paint dropletRim = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Soft moving rim/glow, kept inside the lens silhouette. */
    private void drawDropletRim(Canvas canvas, RectF b, float radius, float glow, boolean light) {
        if (glow <= 0.01f) return;
        dropletRim.setStyle(Paint.Style.STROKE);
        dropletRim.setStrokeWidth(Math.max(1f, density * (0.7f + glow * 0.65f)));
        dropletRim.setShader(null);
        int alpha = (int)(30 + 58 * Math.min(1f, glow));
        dropletRim.setColor((alpha << 24) | (light ? 0x000000 : 0xFFFFFF));
        RectF edge = new RectF(b);
        edge.inset(density * 0.8f, density * 0.8f);
        canvas.drawRoundRect(edge, radius, radius, dropletRim);
        dropletRim.setStyle(Paint.Style.FILL);
    }

    private float slotCenterX(float index) {
        if (count <= 1) return slots[0].centerX();
        if (index <= 0f) return slots[0].centerX() + index * slotW;
        if (index >= count - 1) return slots[count - 1].centerX() + (index - (count - 1)) * slotW;
        int i = (int) Math.floor(index);
        float f = index - i;
        return slots[i].centerX() + (slots[i + 1].centerX() - slots[i].centerX()) * f;
    }

    // ---------------------------------------------------------------- 图标

    private void drawIcons(Canvas canvas, float a, float p, int ink) {
        float size = 23f * density;

        for (int i = 0; i < count; i++) {
            float d = Math.abs(pos.value - i);
            boolean sel = d < 0.5f;
            float near = Math.max(0f, 1f - Math.min(1f, d));
            // 出现时依次亮起
            float seq = Math.max(0f, Math.min(1f, (a - i * 0.08f) * 2.4f));
            int alpha = (int) ((sel ? 255 : 176 + 44 * near) * seq);
            if (alpha <= 2) continue;
            // 按住时选中那个再放大一圈，像是被透镜托起来
            float s = 1f + 0.10f * near + 0.02f * p;
            if (sel) s += 0.18f * p;
            float cx = slots[i].centerX();
            float cy = slots[i].centerY() + (1f - seq) * 4f * density;
            drawIcon(canvas, i, cx, cy, size * s, alpha, ink);
        }
    }

    private void drawPlus(Canvas canvas, float a, float pp, int ink) {
        iconPaint.setColor((((int) (255 * a) & 0xFF) << 24) | (ink & 0xFFFFFF));
        iconPaint.setStyle(Paint.Style.FILL);
        float cx = plusPill.centerX();
        float cy = plusPill.centerY();
        float len = 19f * density * (1f + 0.07f * pp);
        float th = 4.6f * density * (1f + 0.04f * pp);
        float r = th / 2f;
        canvas.save();
        try {
            canvas.translate(cx, cy);
            rect.set(-th / 2f, -len / 2f, th / 2f, len / 2f);
            canvas.drawRoundRect(rect, r, r, iconPaint);
            rect.set(-len / 2f, -th / 2f, len / 2f, th / 2f);
            canvas.drawRoundRect(rect, r, r, iconPaint);
        } finally {
            canvas.restore();
        }
    }

    /**
     * 统一的简洁线性图标：仅替换视觉路径，不改变四个原生导航项的顺序、
     * 点击转发和角标逻辑。所有图标使用相同线宽、圆角端点与连接。
     */
    private void drawIcon(Canvas canvas, int index, float cx, float cy, float size, int alpha, int ink) {
        float k = size / 24f;
        canvas.save();
        try {
            canvas.translate(cx - size / 2f, cy - size / 2f);
            canvas.scale(k, k);
            iconPaint.setColor(((alpha & 0xFF) << 24) | (ink & 0xFFFFFF));
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(1.85f);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            path.setFillType(Path.FillType.WINDING);
            rect.setEmpty();

            switch (index) {
                case 0: { // 首页：房屋轮廓
                    path.moveTo(3.2f, 10.7f);
                    path.lineTo(12f, 3.4f);
                    path.lineTo(20.8f, 10.7f);
                    path.moveTo(5.4f, 9.4f);
                    path.lineTo(5.4f, 20.5f);
                    path.quadTo(5.4f, 21.2f, 6.2f, 21.2f);
                    path.lineTo(17.8f, 21.2f);
                    path.quadTo(18.6f, 21.2f, 18.6f, 20.5f);
                    path.lineTo(18.6f, 9.4f);
                    path.moveTo(9.5f, 21f);
                    path.lineTo(9.5f, 15.3f);
                    path.quadTo(9.5f, 14.6f, 10.2f, 14.6f);
                    path.lineTo(13.8f, 14.6f);
                    path.quadTo(14.5f, 14.6f, 14.5f, 15.3f);
                    path.lineTo(14.5f, 21f);
                    break;
                }
                case 1: { // 朋友：双人头像
                    canvas.drawCircle(9f, 8f, 3.1f, iconPaint);
                    canvas.drawCircle(17.1f, 9.2f, 2.45f, iconPaint);
                    path.moveTo(2.8f, 20.8f);
                    path.quadTo(3.2f, 14.2f, 9f, 14.2f);
                    path.quadTo(14.8f, 14.2f, 15.2f, 20.8f);
                    path.moveTo(14.2f, 14.8f);
                    path.quadTo(20.6f, 13.7f, 21.2f, 20.8f);
                    break;
                }
                case 2: { // 消息：圆角对话框与省略号
                    rect.set(3f, 3.8f, 21f, 17.1f);
                    canvas.drawRoundRect(rect, 5f, 5f, iconPaint);
                    path.moveTo(7.2f, 16.8f);
                    path.lineTo(6.1f, 21f);
                    path.lineTo(12.1f, 17.1f);
                    canvas.drawCircle(8f, 10.4f, 0.85f, iconPaint);
                    canvas.drawCircle(12f, 10.4f, 0.85f, iconPaint);
                    canvas.drawCircle(16f, 10.4f, 0.85f, iconPaint);
                    break;
                }
                default: { // 我：单人头像
                    canvas.drawCircle(12f, 8f, 3.7f, iconPaint);
                    path.moveTo(3.2f, 21f);
                    path.quadTo(3.8f, 13.7f, 12f, 13.7f);
                    path.quadTo(20.2f, 13.7f, 20.8f, 21f);
                    break;
                }
            }
            canvas.drawPath(path, iconPaint);
        } finally {
            iconPaint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------- 角标

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 从宿主那几个子项里读未读：有数字读数字，只有红点就画红点 */
    private void scanBadges() {
        for (int i = 0; i < count && i < tabs.size(); i++) {
            int[] dot = new int[1];
            int n = scanBadge(tabs.get(i), dot, 0);
            badgeNum[i] = n;
            badgeDot[i] = n <= 0 && dot[0] == 1;
        }
    }

    private int scanBadge(View v, int[] dot, int depth) {
        if (v == null || depth > 8 || v.getVisibility() != View.VISIBLE) return 0;
        float density = getResources().getDisplayMetrics().density;

        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (!s.isEmpty() && s.length() <= 4 && isNumeric(s)) {
                    if (v.getWidth() <= 40 * density) {
                        String digits = s.replace("+", "");
                        try {
                            return Math.min(999, Integer.parseInt(digits));
                        } catch (Throwable ignored) {
                            return 1;
                        }
                    }
                }
            }
        }

        // 没有数字的那种小红点
        if (dot[0] == 0 && v.getWidth() > 2 && v.getWidth() <= 16 * density
                && v.getHeight() > 2 && v.getHeight() <= 16 * density && isReddish(v)) {
            dot[0] = 1;
        }

        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                int n = scanBadge(g.getChildAt(i), dot, depth + 1);
                if (n > 0) return n;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '+' && k == s.length() - 1) continue;
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isReddish(View v) {
        try {
            String n = v.getClass().getName().toLowerCase();
            if (n.contains("redpoint") || n.contains("reddot") || n.contains("badge")) return true;
            CharSequence d = v.getContentDescription();
            if (d != null && (d.toString().contains("未读") || d.toString().contains("条"))) return true;
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                return r > 140 && g < 110 && b < 130;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void drawBadges(Canvas canvas, float a) {
        if (a < 0.9f) return;
        float r = 8f * density;
        badgePaint.setColor(0xFFFE2C55);
        badgePaint.setShader(null);
        badgeText.setColor(0xFFFFFFFF);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badgeText.setTextSize(10f * density);

        for (int i = 0; i < count; i++) {
            int n = badgeNum[i];
            boolean dot = badgeDot[i];
            if (n <= 0 && !dot) continue;

            float cx = slots[i].centerX() + 11f * density;
            float cy = slots[i].centerY() - 11f * density;

            if (n <= 0) {
                canvas.drawCircle(cx, cy, 4f * density, badgePaint);
                continue;
            }
            String txt = n > 99 ? "99+" : String.valueOf(n);
            float rad = Math.max(r, badgeText.measureText(txt) / 2f + 4f * density);
            rect.set(cx - rad, cy - r, cx + rad, cy + r);
            canvas.drawRoundRect(rect, r, r, badgePaint);
            float ty = cy - (badgeText.descent() + badgeText.ascent()) / 2f;
            canvas.drawText(txt, cx, ty, badgeText);
        }
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count <= 0) return false;
        float x = e.getX();
        float y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (leftPill.contains(x, y)) {
                    downInLeft = true;
                    downInPlus = false;
                    downX = x;
                    downY = y;
                    lastDragX = x;
                    dragging = false;
                    releasePending = false;
                    pendingIndex = slotAt(x);
                    nativeTarget = pendingIndex >= 0 && pendingIndex < tabs.size()
                            ? tabs.get(pendingIndex) : null;
                    // 普通按下、点击和长按先交给抖音原生子项。
                    dispatchToNative(nativeTarget, e);
                    // 按下就把透镜挪到这一格；形变由独立弹簧通道驱动。
                    pos.to(pendingIndex, 1400f, 58f);
                    droplet.press();
                    droplet.touchDown();
                    selected = pendingIndex;
                    press.to(1f, 1000f, 62f);
                    squish.to(1f, 520f, 34f);
                    startLoop();
                    return true;
                }
                if (plusPill.contains(x, y)) {
                    downInPlus = true;
                    downInLeft = false;
                    nativeTarget = plusTab;
                    dispatchToNative(nativeTarget, e);
                    squish.to(1f, 520f, 34f);
                    plusPress.to(1f, 700f, 40f);
                    startLoop();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (downInLeft) {
                    float dx = x - downX;
                    float dy = y - downY;
                    if (!dragging && Math.abs(dx) > touchSlop
                            && Math.abs(dx) > Math.abs(dy) * 0.75f) {
                        dragging = true;
                        // 拖动正式接管时，给原生子项发送真实 CANCEL，
                        // 这样系统会撤销点击/长按，而不是只清除 pressed 状态。
                        dispatchNativeCancel(nativeTarget, e);
                        dragStart = pendingIndex;
                        if (Math.abs(pos.value - pendingIndex) < 0.6f) dragStart = pos.value;
                    }
                    if (dragging) {
                        float v = clampF(dragStart + dx / slotW, 0f, count - 1f);
                        pos.to(v, 2000f, 68f);
                        droplet.dragBy(x - lastDragX);
                        lastDragX = x;
                        droplet.touchDown();
                        startLoop();
                    } else {
                        dispatchToNative(nativeTarget, e);
                    }
                    return true;
                }
                if (downInPlus) {
                    dispatchToNative(nativeTarget, e);
                    if (!plusPill.contains(x, y)) plusPress.to(0f, 500f, 40f);
                    else plusPress.to(1f, 700f, 40f);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (downInLeft) {
                    boolean wasDragging = dragging;
                    int target = wasDragging ? clampIndex(pos.target) : pendingIndex;
                    if (!wasDragging) {
                        // 没有拖动：原样把 UP/CANCEL 交给原生控件，保留点击与长按。
                        dispatchToNative(nativeTarget, e);
                    } else if (!cancelled) {
                        // 已拖动：原生序列此前已 CANCEL，松手时只触发最终落点一次。
                        clickTab(target);
                    }
                    downInLeft = false;
                    dragging = false;
                    nativeTarget = null;
                    selected = target;
                    pos.to(target, 900f, 46f);
                    droplet.touchUp();
                    droplet.release();
                    squish.to(0f, 420f, 30f);
                    releasePending = true;
                    releaseSince = SystemClock.uptimeMillis();
                    startLoop();
                    return true;
                }
                if (downInPlus) {
                    dispatchToNative(nativeTarget, e);
                    downInPlus = false;
                    nativeTarget = null;
                    squish.to(0f, 420f, 30f);
                    plusPress.to(0f, 600f, 42f);
                    startLoop();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * 把覆盖层收到的事件换算到原生 tab 的本地坐标。
     * 使用 raw 坐标定位，避免覆盖层与宿主子项处在不同父容器时产生偏移。
     */
    private void dispatchToNative(View target, MotionEvent source) {
        if (target == null || source == null || !target.isAttachedToWindow()) return;
        MotionEvent copy = MotionEvent.obtain(source);
        try {
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            float dx = source.getRawX() - loc[0] - source.getX();
            float dy = source.getRawY() - loc[1] - source.getY();
            copy.offsetLocation(dx, dy);
            target.dispatchTouchEvent(copy);
        } catch (Throwable t) {
            Dydock.warnOnce("native-touch", "转发原生底栏触摸失败: " + t);
        } finally {
            copy.recycle();
        }
    }

    private void dispatchNativeCancel(View target, MotionEvent source) {
        if (target == null || source == null) return;
        MotionEvent cancel = MotionEvent.obtain(source);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            dispatchToNative(target, cancel);
        } finally {
            cancel.recycle();
        }
    }

    private int slotAt(float x) {
        int i = (int) Math.floor((x - leftPill.left - 8f * density) / slotW);
        return Math.max(0, Math.min(count - 1, i));
    }

    private int clampIndex(float v) {
        if (v < 0f) return 0;
        if (v > count - 1) return count - 1;
        return Math.round(v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void clickTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        try {
            View v = tabs.get(i);
            if (v == null) return;
            v.performClick();
        } catch (Throwable t) {
            Dydock.warnOnce("tab-click", "转发底栏点击失败: " + t);
        }
    }

    private void clickPlus() {
        try {
            if (plusTab != null) plusTab.performClick();
        } catch (Throwable t) {
            Dydock.warnOnce("plus-click", "转发＋点击失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 动画

    private void startLoop() {
        if (looping) return;
        looping = true;
        lastFrame = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long nanos) {
        long t = nanos / 1000000L;
        long dt = lastFrame == 0L ? 16L : Math.min(50L, t - lastFrame);
        if (dt <= 0L) dt = 16L;
        lastFrame = t;

        boolean a = pos.step(dt);
        boolean b = press.step(dt);
        boolean c = plusPress.step(dt);
        boolean d = appear.step(dt);
        boolean e = squish.step(dt);

        boolean g = droplet.advance(dt / 1000f, pos.value, pos.target, pos.velocity, count);
        boolean f = false;
        long now = SystemClock.uptimeMillis();

        // 松手后：位置进到 SETTLE 区间（或弹簧停了 / 超时）才收回放大
        if (releasePending && !dragging && !downInLeft) {
            float span = Math.max(1f, count - 1);
            boolean settled = Math.abs(pos.value - pos.target) <= Math.max(span * SETTLE_FRACTION, 0.001f)
                    || (Math.abs(pos.velocity) < 6f && Math.abs(pos.value - pos.target) < 0.02f);
            boolean timeout = now - releaseSince > RELEASE_WATCHDOG_MS;
            if (settled || timeout) {
                releasePending = false;
                press.to(0f, 620f, 44f);
            } else {
                f = true;
            }
        }

        invalidate();

        if (a || b || c || d || e || g) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            looping = false;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        timer.post(pump);
        timer.post(recapture);
        timer.post(badgeTick);
        if (dockView != null) {
            following = true;
            scheduleFollow();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && dockView != null) {
            following = true;
            scheduleFollow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        timer.removeCallbacksAndMessages(null);
        following = false;
        super.onDetachedFromWindow();
    }
}