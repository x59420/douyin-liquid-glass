package com.zaka.douyin.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
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
 * Dock 视觉与交互参数以 KernelSU Manager FloatingBottomBar 为唯一参考；
 * 抖音特有的独立「＋」按钮、原生点击转发和底栏定位按宿主结构适配。
 * 玻璃位置逐帧跟随抖音原生 Dock。
 */
final class DockOverlay extends View implements Choreographer.FrameCallback {

    private static final int MAX_SLOTS = 6;

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
    private float slotPad;
    private float pillH;
    private int count;

    private final Spring plusPress = new Spring(0f);
    /** 0 → 1：整组出现 */
    private final Spring appear = new Spring(0f);
    /** KernelSU DampedDragAnimation 的独立位置、压力、形变和速度通道。 */
    private final DropletMotion droplet = new DropletMotion();
    private final InteractiveHighlight interactiveHighlight = new InteractiveHighlight();

    private int selected;
    private boolean downInLeft;
    private int pendingIndex;
    private int dragOriginSelected;
    private boolean downInPlus;
    private boolean dragging;
    /** 正在接收原生触摸序列的底栏子项；未开始拖动前由它处理点击和长按。 */
    private View nativeTarget;
    private boolean nativeDownHandled;
    private float downX;
    private float downY;
    private float dragStart;
    private float lastDragX;
    private int touchSlop;
    private boolean looping;
    private long lastFrame;
    private boolean drewOnce;
    /** 一旦绘制出过错，就不再走 GPU 折射那条路，永久退回磨砂。 */
    private boolean forceFrosted;

    /** 每帧跟着走的官方底栏 */
    private View dockView;
    private boolean following;
    private boolean fixedPosition;
    private boolean followScheduled;
    private final int[] dockLoc = new int[2];

    /** 未读角标：从宿主原来那几个子项里读，自己在玻璃上重画 */
    private final int[] badgeNum = new int[MAX_SLOTS];
    private final boolean[] badgeDot = new boolean[MAX_SLOTS];

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // KernelSU combines the live backdrop with a separately captured tab layer
    // before applying the selected lens. Keep a reusable low-resolution buffer.
    private Bitmap lensComposite;
    private final Paint lensCompositePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
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
        // 全屏覆盖层自身必须完全透明；只绘制两块 Dock 玻璃，其余位置透出抖音内容。
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        // This full-screen overlay must receive DOWN/MOVE/UP inside its two Dock surfaces.
        // Outside those surfaces onTouchEvent returns false, so underlying content still handles input.
        setClickable(true);
        setFocusable(false);
        for (int i = 0; i < MAX_SLOTS; i++) slots[i] = new Rect();
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
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
        for (int i = 0; i < count; i++) {
            if (isSelectedDeep(tabs.get(i), 0)) selected = i;
        }
        droplet.setSpan(Math.max(1f, count - 1f), 1f);
        droplet.snapTo(selected);
        appear.to(1f, 260f, 34f);
        startLoop();
    }

    /** 认出来的官方底栏，之后每帧都跟它对齐 */
    void attachDock(View dock) {
        dockView = dock;
        if (!fixedPosition) {
            following = dock != null;
            scheduleFollow();
        }
    }

    /** 固定玻璃的屏幕位置，但保留原生 View 引用用于点击与角标读取。 */
    void pinPosition() {
        fixedPosition = true;
        following = false;
        followScheduled = false;
        Choreographer.getInstance().removeFrameCallback(follow);
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
        // KernelSU FloatingBottomBar: padding = maxOf(padding, 40.dp.toPx()).
        int pad = Math.round(40f * density);
        backdrop.setRegion(new Rect(
                Math.round(leftPill.left + winLoc[0]) - pad,
                Math.round(leftPill.top + winLoc[1]) - pad,
                Math.round(plusPill.right + winLoc[0]) + pad,
                Math.round(leftPill.bottom + winLoc[1]) + pad));
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
        // KernelSU FloatingBottomBar: 64dp outer height, 4dp inner padding.
        pillH = 64f * density;
        float plusSize = Math.min(pillH, 64f * density);
        float plusGap = 12f * density;
        float edge = 16f * density;
        // KernelSU uses a 4dp inset on the tab row and a 56dp inner content height.
        // Keep the inset identical on all four sides of the selected lens.
        slotPad = 4f * density;

        // KernelSU BottomBarMiuix gives each item a 76dp minimum width.
        // Preserve that proportion where the separate Douyin plus dock allows it.
        float desiredSlotW = 76f * density;
        float desiredLeftW = desiredSlotW * count + slotPad * 2f;
        float maxLeftW = Math.max(0f, bandW - edge * 2f - plusSize - plusGap);
        float leftW = Math.min(desiredLeftW, maxLeftW);
        slotW = count > 0 ? Math.max(1f, (leftW - slotPad * 2f) / count) : desiredSlotW;

        // 左胶囊贴左侧安全边距；独立的红色「＋」贴右侧安全边距。
        float leftX = bandLeft + edge;
        float plusX = bandLeft + bandW - edge - plusSize;
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
            leftX = clampF(leftX, 2f * density, Math.max(2f * density, vw - leftW - 2f * density));
            plusX = clampF(plusX, 2f * density, Math.max(2f * density, vw - plusSize - 2f * density));
        }

        leftPill.set(leftX, top, leftX + leftW, bottom);
        plusPill.set(plusX, top, plusX + plusSize, bottom);

        for (int i = 0; i < count; i++) {
            float l = leftPill.left + slotPad + i * slotW;
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

    /**
     * 绘制的最后一道防线。
     *
     * 这里是跑在宿主主线程上的 onDraw：只要抛一次没接住的异常，
     * 抖音整个人就没了（宿主直接闪退）。所以整条绘制链一律兜住，
     * 出了事只降级成磨砂玻璃，绝不把异常往上冒。
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            drawFrame(canvas);
        } catch (Throwable t) {
            forceFrosted = true;
            Dydock.warnOnce("overlay-draw", "玻璃绘制失败，已降级为磨砂: " + t);
            try {
                drawFallback(canvas);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 玻璃画不出来时的兜底：两块实心圆角面板，只用最基本的 Canvas 操作。 */
    private void drawFallback(Canvas canvas) {
        if (leftPill.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1C1C1E);
        p.setAlpha(215);
        float r = leftPill.height() / 2f;
        canvas.drawRoundRect(leftPill, r, r, p);
        if (!plusPill.isEmpty()) {
            float pr = plusPill.height() / 2f;
            canvas.drawRoundRect(plusPill, pr, pr, p);
        }
        drewOnce = true;
    }

    private void drawFrame(Canvas canvas) {
        if (count <= 0 || leftPill.isEmpty()) return;

        float a = Math.max(0f, Math.min(1f, appear.value));
        float k = 0.90f + 0.10f * a;
        int alpha = (int) (255 * a);
        if (alpha <= 0) return;

        float p = droplet.pressProgress();
        float pp = Math.max(0f, Math.min(1f, plusPress.value));
        float panelOffset = dragOffsetPx();

        boolean framed = !forceFrosted && backdrop.hasFrame();
        // KernelSU uses the theme's onSurface color; never infer icon color from video pixels.
        boolean darkTheme = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int ink = darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A;
        int luma = 128; // compatibility argument only; GlassPainter does not consume frame brightness.

        // KernelSU backdrop layer：按压力将整个面板等比放大，最大增加 16dp。
        float panelPress = droplet.pressProgress();
        // 入场动画仍以底边为锚点；按压缩放围绕面板中心，四边均匀扩展。
        scaled(leftPill, k, tmp);
        float panelScale = 1f + (16f * density / Math.max(1f, leftPill.width())) * panelPress;
        float panelCx = tmp.centerX();
        float panelCy = tmp.centerY();
        float panelHalfW = tmp.width() * panelScale * 0.5f;
        float panelHalfH = tmp.height() * panelScale * 0.5f;
        tmp.set(panelCx - panelHalfW, panelCy - panelHalfH,
                panelCx + panelHalfW, panelCy + panelHalfH);
        tmp.offset(panelOffset, 0f);
        GlassPainter.drawOuterShadow(canvas, tmp, tmp.height() / 2f, density,
                darkTheme, alpha);
        drawPane(canvas, tmp, tmp.height() / 2f, framed, luma, panelPress,
                dragging && panelPress > 0.3f, alpha);
        interactiveHighlight.draw(canvas, tmp, slotCenterX(droplet.x()) + panelOffset,
                leftPill.centerY());

        // 右侧「＋」独立，不跟随左侧导航透镜的长按放大。
        scaled(plusPill, k * (1f - 0.04f * pp), rect);
        drawPlusGlass(canvas, rect, pp, alpha);

        Bitmap combinedBackdrop = framed
                ? buildLensBackdrop(a, p, ink, panelOffset) : null;
        drawLens(canvas, a, framed, luma, ink, panelOffset, combinedBackdrop);
        drawIcons(canvas, a, p, ink, panelOffset);
        drawPlus(canvas, a, pp, 0xFFFFFFFF);
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
                    GlassPainter.draw(canvas, b, rad, backdrop, this, press, dispersion,
                            luma, density);
                } catch (Throwable t) {
                    // 着色器在一些机型/系统版本上会直接抛（AGSL 编不过、RuntimeShader 不支持）。
                    // 以前这一下会把整块玻璃带走 —— 退回磨砂，至少看得见。
                    Dydock.warnOnce("pane-gl", "折射绘制失败，退回磨砂: " + t);
                    GlassPainter.drawFrosted(canvas, b, rad, density, this);
                }
            } else {
                GlassPainter.drawFrosted(canvas, b, rad, density, this);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * 选中块：平时是一块淡色胶囊，按住/拖动时放大成一块透镜，
     * 拖得越快沿运动方向拉得越长（KernelSU 的 scaleX /= 1 - velocity * 0.75）。
     */
    private void drawLens(Canvas canvas, float a, boolean framed,
                          int luma, int ink, float panelOffset, Bitmap combinedBackdrop) {
        float x = droplet.x();
        float dropletPress = droplet.pressProgress();

        // Tieba uses independent X/Y spring channels; velocity stretches the lens
        // along its travel direction instead of forcing a uniform scale.
        float baseH = Math.min(56f * density, Math.max(1f, leftPill.height()));
        float baseW = Math.max(1f, slotW);
        float sx = clampF(droplet.scaleX(), 0.80f, 1.75f);
        float sy = clampF(droplet.scaleY(), 0.80f, 1.75f);
        float w = baseW * sx;
        float h = baseH * sy;
        float corner = Math.min(w, h) * 0.5f;

        float cx = slotCenterX(x) + panelOffset;
        float cy = leftPill.centerY();
        lens.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);

        // 透镜是独立绘制层：不裁切到左胶囊，也不额外叠加投影或人工描边。
        // 立体感只由液态玻璃折射、深度和材质高光提供。
        int save = canvas.save();
        try {
            if (a < 1f) canvas.saveLayerAlpha(lens, (int) (255 * a));

            // KernelSU selected lens: pressure drives refraction, depth and dispersion.
            if (framed) {
                try {
                    Rect region = backdrop.region();
                    if (combinedBackdrop != null && !region.isEmpty()) {
                        GlassPainter.drawLens(canvas, lens, corner, combinedBackdrop,
                                region, backdrop, this, dropletPress, luma, density);
                    } else {
                        GlassPainter.drawLens(canvas, lens, corner, backdrop, this,
                                dropletPress, luma, density);
                    }
                } catch (Throwable t) {
                    Dydock.warnOnce("lens-gl", "透镜绘制失败，退回磨砂: " + t);
                    GlassPainter.drawFrosted(canvas, lens, corner, density, this);
                }
            } else {
                GlassPainter.drawFrosted(canvas, lens, corner, density, this);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Mirrors KernelSU's combinedBackdrop(backdrop, tabsBackdrop): render the
     * live background first, then the tab artwork into the same sampled layer.
     */
    private Bitmap buildLensBackdrop(float a, float p, int ink, float panelOffset) {
        Bitmap source = backdrop.bitmap();
        Rect region = backdrop.region();
        if (source == null || source.isRecycled() || region.isEmpty()) return null;
        try {
            if (lensComposite == null || lensComposite.isRecycled()
                    || lensComposite.getWidth() != source.getWidth()
                    || lensComposite.getHeight() != source.getHeight()) {
                if (lensComposite != null && !lensComposite.isRecycled()) {
                    lensComposite.recycle();
                }
                lensComposite = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                        Bitmap.Config.ARGB_8888);
            }
            Canvas layer = new Canvas(lensComposite);
            layer.drawColor(android.graphics.Color.TRANSPARENT,
                    android.graphics.PorterDuff.Mode.CLEAR);
            // Keep this as a transparent tab-only layer. Api33Glass first blurs
            // the live backdrop, composites these tabs above it, then applies the lens.
            getLocationInWindow(winLoc);
            int save = layer.save();
            float sx = source.getWidth() / (float) region.width();
            float sy = source.getHeight() / (float) region.height();
            layer.scale(sx, sy);
            layer.translate(winLoc[0] - region.left, winLoc[1] - region.top);
            drawIcons(layer, a, p, ink, panelOffset);
            layer.restoreToCount(save);
            return lensComposite;
        } catch (Throwable t) {
            Dydock.warnOnce("lens-composite", "透镜图标层合成失败: " + t);
            return null;
        }
    }

    private float dragOffsetPx() {
        // KernelSU: 4dp * sign(fraction) * Compose EaseOut(fraction).
        float fraction = clampF(droplet.accumulatedDrag()
                / Math.max(1f, leftPill.width()), -1f, 1f);
        float magnitude = Math.abs(fraction);
        float eased = composeEaseOut(magnitude);
        return 4f * density * Math.signum(fraction) * eased;
    }

    /** Compose EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f). */
    private static float composeEaseOut(float x) {
        x = clampF(x, 0f, 1f);
        float lo = 0f;
        float hi = 1f;
        float t = x;
        for (int i = 0; i < 12; i++) {
            float omt = 1f - t;
            float bx = 3f * omt * t * t * 0.58f + t * t * t;
            if (bx < x) lo = t; else hi = t;
            t = (lo + hi) * 0.5f;
        }
        float omt = 1f - t;
        return 3f * omt * t * t + t * t * t;
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

    private void drawIcons(Canvas canvas, float a, float p, int ink, float panelOffset) {
        // KernelSU BottomBarMiuix: 24dp icon + 1dp gap + 11sp label (14sp line height).
        final float iconSize = 24f * density;
        final float labelSize = 11f * getResources().getDisplayMetrics().scaledDensity;
        final float labelLine = 14f * getResources().getDisplayMetrics().scaledDensity;
        final float contentHeight = iconSize + 1f * density + labelLine;
        final String[] labels = {"首页", "朋友", "消息", "我"};

        labelPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(labelSize);
        labelPaint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < count; i++) {
            float d = Math.abs(droplet.x() - i);
            boolean sel = d < 0.5f;
            float near = Math.max(0f, 1f - Math.min(1f, d));
            // Keep the existing entrance stagger while the tab's icon and label move together.
            float seq = Math.max(0f, Math.min(1f, (a - i * 0.08f) * 2.4f));
            int alpha = (int) ((sel ? 255 : 176 + 44 * near) * seq);
            if (alpha <= 2) continue;

            float itemScale = 1f + 0.20f * p;
            float cx = slots[i].centerX() + panelOffset;
            float cy = slots[i].centerY() + (1f - seq) * 4f * density;
            float top = cy - contentHeight * 0.5f;
            float iconCy = top + iconSize * 0.5f;
            float labelCy = top + iconSize + density + labelLine * 0.5f;

            // KernelSU scales the complete Column around the tab centre, not the glyphs alone.
            int save = canvas.save();
            try {
                canvas.translate(cx, cy);
                canvas.scale(itemScale, itemScale);
                canvas.translate(-cx, -cy);
                drawIcon(canvas, i, cx, iconCy, iconSize, alpha, ink);
                labelPaint.setColor((alpha << 24) | (ink & 0x00FFFFFF));
                Paint.FontMetrics fm = labelPaint.getFontMetrics();
                float baseline = labelCy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(labels[i], cx, baseline, labelPaint);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    /** Opaque ruby-red glass for the independent publish button. */
    private final Paint plusGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plusGlassEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plusGlassHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plusGlassShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path plusGlassClip = new Path();
    private final RectF plusGlassEdgeBounds = new RectF();

    private void drawPlusGlass(Canvas canvas, RectF b, float pp, int alpha) {
        if (b.isEmpty() || alpha <= 0) return;
        float radius = Math.min(b.width(), b.height()) / 2f;
        int save = canvas.save();
        try {
            // The publish button is intentionally the original solid Douyin red,
            // not part of the refractive navigation glass.
            plusGlassPaint.setStyle(Paint.Style.FILL);
            plusGlassPaint.setShader(null);
            plusGlassPaint.setColor(0xFFE91F49);
            plusGlassPaint.setAlpha(alpha);
            canvas.drawRoundRect(b, radius, radius, plusGlassPaint);

            plusGlassClip.reset();
            plusGlassClip.addRoundRect(b, radius, radius, Path.Direction.CW);
            canvas.clipPath(plusGlassClip);

            plusGlassHighlight.setStyle(Paint.Style.FILL);
            plusGlassHighlight.setShader(null);
            plusGlassHighlight.setColor(0xFFFFFFFF);
            plusGlassHighlight.setAlpha(alpha * 0x38 / 255);
            float glossBottom = b.top + b.height() * (0.44f - 0.04f * clampF(pp, 0f, 1f));
            canvas.drawRoundRect(b.left + density, b.top + density,
                    b.right - density, glossBottom, Math.max(1f, radius - density),
                    Math.max(1f, radius - density), plusGlassHighlight);
            plusGlassHighlight.setAlpha(alpha * 0x16 / 255);
            canvas.drawRoundRect(b.left + 2f * density, b.top + 2f * density,
                    b.right - 2f * density, b.top + 5f * density,
                    radius, radius, plusGlassHighlight);

            plusGlassEdge.setStyle(Paint.Style.STROKE);
            plusGlassEdge.setStrokeWidth(Math.max(1f, density));
            plusGlassEdge.setShader(null);
            plusGlassEdge.setColor(0xFFFFFFFF);
            plusGlassEdge.setAlpha(alpha * 0xBF / 255);
            plusGlassEdgeBounds.set(b);
            plusGlassEdgeBounds.inset(0.7f * density, 0.7f * density);
            canvas.drawRoundRect(plusGlassEdgeBounds, Math.max(1f, radius - density),
                    Math.max(1f, radius - density), plusGlassEdge);
        } finally {
            plusGlassPaint.setAlpha(255);
            plusGlassHighlight.setAlpha(255);
            plusGlassEdge.setAlpha(255);
            plusGlassShadow.setAlpha(255);
            canvas.restoreToCount(save);
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
        float rowScale = 1f + (4f * density / Math.max(1f, leftPill.width())) * droplet.pressProgress();
        float rowCx = leftPill.centerX();
        float rowCy = leftPill.centerY();
        float r = 8f * density * rowScale;
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

            float rawCx = slots[i].centerX() + 11f * density;
            float rawCy = slots[i].centerY() - 11f * density;
            float cx = rowCx + (rawCx - rowCx) * rowScale;
            float cy = rowCy + (rawCy - rowCy) * rowScale;

            if (n <= 0) {
                canvas.drawCircle(cx, cy, 4f * density * rowScale, badgePaint);
                continue;
            }
            String txt = n > 99 ? "99+" : String.valueOf(n);
            float rad = Math.max(r, badgeText.measureText(txt) / 2f + 4f * density * rowScale);
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
                refreshNativeTargets();
                if (leftPill.contains(x, y)) {
                    downInLeft = true;
                    downInPlus = false;
                    downX = x;
                    downY = y;
                    lastDragX = x;
                    interactiveHighlight.press(x, y);
                    dragging = false;
                    pendingIndex = slotAt(x);
                    dragOriginSelected = selected;
                    nativeTarget = pendingIndex >= 0 && pendingIndex < tabs.size()
                            ? tabs.get(pendingIndex) : null;
                    // 普通按下、点击和长按先交给抖音原生子项。
                    nativeDownHandled = dispatchToNative(nativeTarget, e);
                    // KernelSU inspectDragGestures starts on the initial DOWN:
                    // updateValue(indexAt(down.position)) first, then press().
                    droplet.moveTo(pendingIndex, true);
                    droplet.press();
                    startLoop();
                    return true;
                }
                if (plusPill.contains(x, y)) {
                    downInPlus = true;
                    downInLeft = false;
                    nativeTarget = plusTab;
                    nativeDownHandled = dispatchToNative(nativeTarget, e);
                    plusPress.to(1f, 700f, 40f);
                    startLoop();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (downInLeft) {
                    interactiveHighlight.move(x, y);
                    float dx = x - downX;
                    float dy = y - downY;
                    if (!dragging && Math.abs(dx) > touchSlop
                            && Math.abs(dx) > Math.abs(dy) * 0.75f) {
                        dragging = true;
                        // KernelSU's onDragStarted updates the tab value, then starts press/scale.
                        droplet.moveTo(clampF(pendingIndex + dx / Math.max(1f, slotW), 0f, count - 1f), true);
                        droplet.touchDown();
                        // 拖动正式接管时，给原生子项发送真实 CANCEL，
                        // 这样系统会撤销点击/长按，而不是只清除 pressed 状态。
                        dispatchNativeCancel(nativeTarget, e);
                        dragStart = pendingIndex;
                        if (Math.abs(droplet.x() - pendingIndex) < 0.6f) dragStart = droplet.x();
                    }
                    if (dragging) {
                        float v = clampF(dragStart + dx / slotW, 0f, count - 1f);
                        droplet.moveTo(v, true);
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
                    int target = wasDragging ? clampIndex(droplet.targetX()) : pendingIndex;
                    if (!wasDragging) {
                        // 没有拖动：原样把 UP/CANCEL 交给原生控件，保留点击与长按。
                        boolean upHandled = dispatchToNative(nativeTarget, e);
                        if (!cancelled && (!nativeDownHandled || !upHandled)) clickTab(target);
                    } else if (!cancelled) {
                        // 已拖动：原生序列此前已 CANCEL，松手时只触发最终落点一次。
                        clickTab(target);
                    }
                    if (cancelled && wasDragging) target = dragOriginSelected;

                    downInLeft = false;
                    interactiveHighlight.release();
                    dragging = false;
                    nativeTarget = null;
                    nativeDownHandled = false;
                    selected = target;
                    if (!cancelled && !wasDragging
                            && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                        // KernelSU activateTab(): press -> animate value -> delayed release.
                        droplet.animateToValue(target);
                    } else {
                        droplet.moveTo(target, true);
                        droplet.release();
                    }
                    droplet.touchUp();
                    startLoop();
                    return true;
                }
                if (downInPlus) {
                    boolean upHandled = dispatchToNative(nativeTarget, e);
                    if (!cancelled && (!nativeDownHandled || !upHandled)) clickPlus();
                    downInPlus = false;
                    nativeTarget = null;
                    nativeDownHandled = false;
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
    private boolean dispatchToNative(View target, MotionEvent source) {
        if (target == null || source == null || !target.isAttachedToWindow()) return false;
        MotionEvent copy = MotionEvent.obtain(source);
        try {
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            float dx = source.getRawX() - loc[0] - source.getX();
            float dy = source.getRawY() - loc[1] - source.getY();
            copy.offsetLocation(dx, dy);
            return target.dispatchTouchEvent(copy);
        } catch (Throwable t) {
            Dydock.warnOnce("native-touch", "转发原生底栏触摸失败: " + t);
            return false;
        } finally {
            copy.recycle();
        }
    }

    /** Refresh native tab references before each press; Douyin may rebuild its nav views. */
    private void refreshNativeTargets() {
        View dock = dockView;
        if (!(dock instanceof android.view.ViewGroup) || !dock.isAttachedToWindow()) return;
        try {
            List<View> current = DockController.Finder.tabItems((android.view.ViewGroup) dock);
            if (current.size() < 4) return;
            View currentPlus = DockController.Finder.pickPlus(current);
            List<View> currentTabs = new ArrayList<View>(current);
            if (currentPlus != null) currentTabs.remove(currentPlus);
            boolean same = currentTabs.size() == tabs.size() && currentPlus == plusTab;
            if (same) {
                for (int i = 0; i < currentTabs.size(); i++) {
                    if (currentTabs.get(i) != tabs.get(i)) { same = false; break; }
                }
            }
            if (!same) {
                tabs.clear();
                tabs.addAll(currentTabs);
                plusTab = currentPlus;
                count = Math.min(tabs.size(), MAX_SLOTS);
                Dydock.log("底栏:点击目标已在触摸前刷新");
            }
        } catch (Throwable t) {
            Dydock.warnOnce("refresh-tabs", "刷新原生导航项失败: " + t);
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
        int i = (int) Math.floor((x - leftPill.left - slotPad) / slotW);
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

    private boolean isSelectedDeep(View view, int depth) {
        if (view == null || depth > 7) return false;
        if (view.isSelected() || view.isActivated()) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isSelectedDeep(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
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

        boolean a = plusPress.step(dt);
        boolean b = appear.step(dt);
        boolean c = droplet.advance(dt / 1000f);
        boolean d = interactiveHighlight.advance(dt / 1000f);

        invalidate();

        if (a || b || c || d) {
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
        if (dockView != null && !fixedPosition) {
            following = true;
            scheduleFollow();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) drewOnce = false;
        if (visibility == VISIBLE && dockView != null && !fixedPosition) {
            following = true;
            scheduleFollow();
        }
    }

    /** Permanently dispose resources when the owning Activity is destroyed. */
    void dispose() {
        timer.removeCallbacksAndMessages(null);
        following = false;
        followScheduled = false;
        looping = false;
        Choreographer choreographer = Choreographer.getInstance();
        choreographer.removeFrameCallback(this);
        choreographer.removeFrameCallback(follow);
        backdrop.release();
        droplet.dispose();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Detach can be temporary during page transitions. Keep the backdrop source;
        // DockController reattaches this same overlay when the Activity returns.
        timer.removeCallbacksAndMessages(null);
        following = false;
        followScheduled = false;
        looping = false;
        Choreographer choreographer = Choreographer.getInstance();
        choreographer.removeFrameCallback(this);
        choreographer.removeFrameCallback(follow);
        super.onDetachedFromWindow();
    }
}