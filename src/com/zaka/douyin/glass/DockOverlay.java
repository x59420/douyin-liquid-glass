package com.zaka.douyin.glass;

import android.content.Context;
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
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏：左边一块玻璃装 4 个图标，右边一块独立的圆形玻璃装「＋」。
 *
 * 位置完全沿用官方底栏那一条（在系统手势条上方），只是把官方那条的
 * 画的东西藏起来（alpha = 0，布局不动、点击照旧），然后自己按同样的位置
 * 画两块悬浮玻璃。点击事件转发给官方那 5 个子项，页面切换还是抖音自己的逻辑。
 *
 * 拖动照 Kyant 的 LiquidBottomTabs：按住选中块跟手、越界带阻力、
 * 松手弹簧回弹到最近的一格并触发那一格。
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
    private final Rect[] slots = new Rect[MAX_SLOTS];

    private float slotW;
    private float pillH;
    private int count;

    private final Spring pos = new Spring(0f);
    private final Spring press = new Spring(0f);
    private final Spring plusPress = new Spring(0f);

    private int selected;
    private boolean downInLeft;
    private boolean downInPlus;
    private boolean dragging;
    private float downX;
    private float downY;
    private int touchSlop;
    private boolean looping;
    private long lastFrame;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            backdrop.pumpVideo();
            timer.postDelayed(this, 90L);
        }
    };
    private final Runnable recapture = new Runnable() {
        @Override
        public void run() {
            backdrop.capture();
            invalidate();
            timer.postDelayed(this, 1200L);
        }
    };

    DockOverlay(Context ctx, Backdrop backdrop, float density) {
        super(ctx);
        this.backdrop = backdrop;
        this.density = density;
        touchSlop = Math.round(6f * density);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        for (int i = 0; i < MAX_SLOTS; i++) slots[i] = new Rect();
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        backdrop.setOnFrame(new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        });
    }

    void setTabs(List<View> list, View plus) {
        tabs.clear();
        tabs.addAll(list);
        plusTab = plus;
        count = Math.min(tabs.size(), MAX_SLOTS);
        selected = 0;
        pos.set(0f);
    }

    /** @param r 官方底栏在窗口里的矩形 */
    void setBand(Rect r) {
        if (r == null || r.isEmpty()) return;
        band.set(r);
        layoutPills();
        backdrop.setRegion(new Rect(
                Math.round(leftPill.left + winLoc[0]) - Math.round(34 * density),
                Math.round(leftPill.top + winLoc[1]) - Math.round(34 * density),
                Math.round(plusPill.right + winLoc[0]) + Math.round(34 * density),
                Math.round(plusPill.bottom + winLoc[1]) + Math.round(34 * density)));
        invalidate();
    }

    // ---------------------------------------------------------------- 几何

    private void layoutPills() {
        getLocationInWindow(winLoc);
        float bandLeft = band.left - winLoc[0];
        float bandTop = band.top - winLoc[1];
        float bandW = band.width();
        float bandH = band.height();

        pillH = Math.min(52f * density, Math.max(40f * density, bandH - 4f * density));
        float top = bandTop + (bandH - pillH) / 2f;

        float plusSize = Math.min(pillH, 50f * density);
        float side = 14f * density;

        slotW = Math.min(46f * density, (bandW - plusSize - side * 3f) / Math.max(1, count));
        float pad = 6f * density;
        float leftW = slotW * count + pad * 2f;

        leftPill.set(bandLeft + side, top, bandLeft + side + leftW, top + pillH);
        plusPill.set(bandLeft + bandW - side - plusSize, top + (pillH - plusSize) / 2f,
                bandLeft + bandW - side, top + (pillH + plusSize) / 2f);

        for (int i = 0; i < count; i++) {
            float l = leftPill.left + pad + i * slotW;
            slots[i].set(Math.round(l), Math.round(leftPill.top),
                    Math.round(l + slotW), Math.round(leftPill.bottom));
        }
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count <= 0 || leftPill.isEmpty()) return;

        // 选中块的玻璃（按压时才是透镜，平时只是一层很淡的白）
        float p = Math.max(0f, Math.min(1f, press.value));
        float scaleX = 1f + 0.06f * p;
        float cx = slotCenterX(pos.value);
        float w = slotW * 0.82f * scaleX;
        float h = pillH * 0.80f;
        rect.set(cx - w / 2f, leftPill.centerY() - h / 2f, cx + w / 2f, leftPill.centerY() + h / 2f);

        float pp = Math.max(0f, Math.min(1f, plusPress.value));

        // 左边 4 个
        GlassPainter.draw(canvas, leftPill, pillH / 2f, backdrop, this,
                p * 0.6f, dragging && p > 0.3f, backdrop.luma(), density);

        // 右边 ＋
        GlassPainter.draw(canvas, plusPill, plusPill.width() / 2f, backdrop, this,
                pp, false, backdrop.luma(), density);

        // 选中块
        if (p > 0.02f) {
            GlassPainter.draw(canvas, rect, h / 2f, backdrop, this, p, dragging, backdrop.luma(), density);
        } else {
            selPaint.setColor(0x1FFFFFFF);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, selPaint);
        }

        drawIcons(canvas, p);
    }

    private float slotCenterX(float index) {
        if (count <= 1) return slots[0].centerX();
        if (index <= 0f) return slots[0].centerX() + index * slotW;
        if (index >= count - 1) return slots[count - 1].centerX() + (index - (count - 1)) * slotW;
        int i = (int) Math.floor(index);
        float f = index - i;
        return slots[i].centerX() + (slots[i + 1].centerX() - slots[i].centerX()) * f;
    }

    private void drawIcons(Canvas canvas, float p) {
        float size = 22f * density;
        float sw = 1.75f * density;
        iconPaint.setStrokeWidth(sw);

        for (int i = 0; i < count; i++) {
            boolean sel = Math.abs(pos.value - i) < 0.5f;
            int alpha = sel ? 255 : 198;
            float s = 1f + (sel ? 0.06f * (1f - Math.min(1f, Math.abs(pos.value - i) * 2f)) : 0f);
            float cx = slots[i].centerX();
            float cy = slots[i].centerY();
            drawIcon(canvas, i, cx, cy, size * s, alpha);
        }

        float px = plusPill.centerX();
        float py = plusPill.centerY();
        iconPaint.setColor(0xFFFFFFFF);
        iconPaint.setStrokeWidth(2.2f * density);
        float r = 9f * density;
        canvas.drawLine(px - r, py, px + r, py, iconPaint);
        canvas.drawLine(px, py - r, px, py + r, iconPaint);
    }

    private void drawIcon(Canvas canvas, int index, float cx, float cy, float size, int alpha) {
        float k = size / 24f;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(k, k);
        iconPaint.setColor((alpha << 24) | 0xFFFFFF);
        path.reset();

        switch (index) {
            case 0: // 首页
                path.moveTo(4.2f, 11.2f);
                path.lineTo(12f, 4.4f);
                path.lineTo(19.8f, 11.2f);
                path.moveTo(6.2f, 10.4f);
                path.lineTo(6.2f, 19.6f);
                path.lineTo(17.8f, 19.6f);
                path.lineTo(17.8f, 10.4f);
                break;
            case 1: // 朋友
                canvas.drawCircle(9f, 8.8f, 2.6f, iconPaint);
                canvas.drawCircle(16.2f, 9.6f, 2.1f, iconPaint);
                path.moveTo(3.4f, 19.4f);
                path.cubicTo(4.2f, 15.2f, 6.6f, 13.4f, 9.2f, 13.4f);
                path.cubicTo(11.8f, 13.4f, 14.2f, 15.2f, 15f, 19.4f);
                path.moveTo(16.4f, 14.2f);
                path.cubicTo(18.8f, 14.6f, 20.2f, 16.4f, 20.6f, 19.4f);
                break;
            case 2: // 消息
                rect.set(4f, 5.2f, 20f, 16.6f);
                canvas.drawRoundRect(rect, 3.4f, 3.4f, iconPaint);
                path.moveTo(8.6f, 16.6f);
                path.lineTo(8.6f, 20.2f);
                path.lineTo(12.4f, 16.6f);
                break;
            default: // 我
                canvas.drawCircle(12f, 8.4f, 3.1f, iconPaint);
                path.moveTo(5.2f, 19.8f);
                path.cubicTo(6f, 15.6f, 8.8f, 14f, 12f, 14f);
                path.cubicTo(15.2f, 14f, 18f, 15.6f, 18.8f, 19.8f);
                break;
        }
        canvas.drawPath(path, iconPaint);
        canvas.restore();
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count <= 0) return false;
        float x = e.getX();
        float y = e.getY();
        getLocationInWindow(winLoc);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (leftPill.contains(x, y)) {
                    downInLeft = true;
                    downX = x;
                    downY = y;
                    dragging = false;
                    int i = slotAt(x);
                    pos.snapTo(clampIndex(pos.value));
                    pos.to(i, 900f, 45f);
                    selected = i;
                    press.to(1f, 900f, 48f);
                    startLoop();
                    return true;
                }
                if (plusPill.contains(x, y)) {
                    downInPlus = true;
                    plusPress.to(1f, 700f, 40f);
                    startLoop();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (downInLeft) {
                    float dx = x - downX;
                    if (!dragging && Math.abs(dx) > touchSlop) dragging = true;
                    if (dragging) {
                        float f = clampIndex(pos.value + dx / slotW);
                        // 越界带阻力
                        if (f < 0f) f *= 0.35f;
                        if (f > count - 1) f = count - 1 + (f - (count - 1)) * 0.35f;
                        pos.snapTo(f);
                        downX = x;
                        invalidate();
                    }
                    return true;
                }
                if (downInPlus) {
                    if (!plusPill.contains(x, y)) plusPress.to(0f, 500f, 40f);
                    else plusPress.to(1f, 700f, 40f);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (downInLeft) {
                    downInLeft = false;
                    press.to(0f, 600f, 42f);
                    int target = clampIndex(pos.value);
                    dragging = false;
                    pos.to(target, 700f, 34f);
                    selected = target;
                    clickTab(target);
                    startLoop();
                    return true;
                }
                if (downInPlus) {
                    downInPlus = false;
                    plusPress.to(0f, 600f, 42f);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && plusPill.contains(x, y)) {
                        clickPlus();
                    }
                    startLoop();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private int slotAt(float x) {
        int i = (int) Math.floor((x - leftPill.left - 6f * density) / slotW);
        return Math.max(0, Math.min(count - 1, i));
    }

    private int clampIndex(float v) {
        if (v < 0f) return 0;
        if (v > count - 1) return count - 1;
        return Math.round(v);
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
        long now = System.nanoTime();
        long dt = lastFrame == 0L ? 16L : Math.max(1L, Math.min(32L, (now - lastFrame) / 1000000L));
        lastFrame = now;

        boolean a = pos.step(dt);
        boolean b = press.step(dt);
        boolean c = plusPress.step(dt);
        invalidate();

        if (a || b || c) {
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
    }

    @Override
    protected void onDetachedFromWindow() {
        timer.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    static DockOverlay create(Context ctx, Backdrop bd, float density) {
        return new DockOverlay(ctx, bd, density);
    }
}
