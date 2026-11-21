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

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏：左边一整块玻璃装 4 个图标、右边一块圆形玻璃装「＋」，整组居中。
 *
 * 官方底栏那一条只把「画」藏起来（alpha = 0，布局和点击都还在），
 * 然后按它的位置自己画两块悬浮玻璃，点击转发回官方那几个子项。
 *
 * 动效照 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs 和 lengroo/pdd-LiquidGlass：
 *  - 出现：整组从下往上浮一点、由小到大淡入，图标依次亮起来
 *  - 按压：选中块的长按钮被压扁一点，并按下去的那一格浮起
 *  - 拖动：选中块跟手、越界有阻尼；拖的时候那块「透镜」被拉长，松手弹回最近一格
 *  - 切页：选中块在格子之间带过冲地滑过去，图标跟着放大缩小
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
    private float pillH;
    private int count;

    private final Spring pos = new Spring(0f);
    private final Spring press = new Spring(0f);
    private final Spring plusPress = new Spring(0f);
    /** 0 → 1：整组出现 */
    private final Spring appear = new Spring(0f);
    /** 0 → 1：按下时那块长条被压下去的形变 */
    private final Spring squish = new Spring(0f);

    private int selected;
    private boolean downInLeft;
    private int pendingIndex;
    private boolean downInPlus;
    private boolean dragging;
    private float downX;
    private float downY;
    private int touchSlop;
    private boolean looping;
    private long lastFrame;
    private boolean drewOnce;

    /** 未读角标：从宿主原来那几个子项里读，自己在玻璃上重画 */
    private final int[] badgeNum = new int[MAX_SLOTS];
    private final boolean[] badgeDot = new boolean[MAX_SLOTS];

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        appear.to(1f, 260f, 34f);
        startLoop();
    }

    /** @param r 官方底栏在窗口里的矩形 */
    void setBand(Rect r) {
        if (r == null || r.isEmpty()) return;
        if (r.equals(band) && !leftPill.isEmpty()) return;
        band.set(r);
        layoutPills();
        backdrop.setRegion(new Rect(
                Math.round(leftPill.left + winLoc[0]) - Math.round(30 * density),
                Math.round(leftPill.top + winLoc[1]) - Math.round(26 * density),
                Math.round(plusPill.right + winLoc[0]) + Math.round(30 * density),
                Math.round(leftPill.bottom + winLoc[1]) + Math.round(22 * density)));
        invalidate();
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
        float plusSize = Math.min(pillH, 56f * density);
        slotW = Math.min(56f * density, Math.max(50f * density, pillH * 0.88f));

        float pad = 8f * density;
        float gap = 10f * density;
        float leftW = slotW * count + pad * 2f;
        float total = leftW + gap + plusSize;

        // 整组居中，＋ 紧挨着右边
        float startX = bandLeft + (bandW - total) / 2f;
        // 抬高一点，别贴着系统手势条
        float bottom = bandTop + bandH - 6f * density;
        float top = bottom - pillH;

        leftPill.set(startX, top, startX + leftW, bottom);
        plusPill.set(startX + leftW + gap, top + (pillH - plusSize) / 2f,
                startX + leftW + gap + plusSize, top + (pillH + plusSize) / 2f);

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
        drewOnce = true;

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

        scaled(leftPill, k * (1f - 0.03f * sq), tmp);
        // 按下时整条往下压一点点，像被指头压住
        tmp.offset(0f, 1.5f * density * sq);
        drawPane(canvas, tmp, tmp.height() / 2f, framed, luma, p * 0.55f,
                dragging && p > 0.3f, alpha);

        scaled(plusPill, k * (1f - 0.04f * pp), rect);
        drawPane(canvas, rect, rect.width() / 2f, framed, luma, pp, false, alpha);

        drawLens(canvas, a, p, framed, luma, light, ink);
        drawIcons(canvas, a, p, ink);
        drawPlus(canvas, a, pp, ink);
        drawBadges(canvas, a);
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
                GlassPainter.draw(canvas, b, rad, backdrop, this, press, dispersion, luma, density);
            } else {
                GlassPainter.drawFrosted(canvas, b, rad, density, luma);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** 选中块：平时是一块淡色胶囊，按住/拖动时变成会拉长的透镜 */
    private void drawLens(Canvas canvas, float a, float p, boolean framed,
                          int luma, boolean light, int ink) {
        float x = pos.value;
        float base = slotW * 0.86f;
        // 越界拉扯时横向拉长，松手回弹
        float v = Math.min(1f, Math.abs(pos.velocity) / 20f);
        float stretch = 1f + 0.18f * p + 0.22f * v;
        float w = base * stretch;
        float h = leftPill.height() * (0.78f + 0.04f * p);

        // 越界时中心跟着手指多跑一点，但不出整条玻璃
        float cx = slotCenterX(x);
        lens.set(cx - w / 2f, leftPill.centerY() - h / 2f, cx + w / 2f, leftPill.centerY() + h / 2f);
        lens.intersect(leftPill);

        if (framed && p > 0.02f) {
            drawPane(canvas, lens, h / 2f, true, luma, p, dragging, (int) (255 * a));
        } else {
            int save = canvas.save();
            try {
                if (a < 1f) canvas.saveLayerAlpha(lens, (int) (255 * a));
                selPaint.setShader(null);
                selPaint.setColor(light ? 0x24000000 : 0x26FFFFFF);
                canvas.drawRoundRect(lens, h / 2f, h / 2f, selPaint);
            } finally {
                canvas.restoreToCount(save);
            }
        }
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
        float size = 24f * density;
        iconPaint.setStrokeWidth(1.8f * density);

        for (int i = 0; i < count; i++) {
            float d = Math.abs(pos.value - i);
            boolean sel = d < 0.5f;
            float near = Math.max(0f, 1f - Math.min(1f, d));
            // 出现时依次亮起
            float seq = Math.max(0f, Math.min(1f, (a - i * 0.08f) * 2.4f));
            int alpha = (int) ((sel ? 255 : 190 + 30 * near) * seq);
            if (alpha <= 2) continue;
            float s = 1f + 0.08f * near + 0.03f * p;
            float cx = slots[i].centerX();
            float cy = slots[i].centerY() + (1f - seq) * 4f * density;
            drawIcon(canvas, i, cx, cy, size * s, alpha, ink);
        }
    }

    private void drawPlus(Canvas canvas, float a, float pp, int ink) {
        iconPaint.setColor((((int) (255 * a) & 0xFF) << 24) | (ink & 0xFFFFFF));
        iconPaint.setStrokeWidth(2.4f * density);
        iconPaint.setStyle(Paint.Style.STROKE);
        float r = 10f * density * (1f + 0.06f * pp);
        float px = plusPill.centerX();
        float py = plusPill.centerY();
        canvas.drawLine(px - r, py, px + r, py, iconPaint);
        canvas.drawLine(px, py - r, px, py + r, iconPaint);
    }

    private void drawIcon(Canvas canvas, int index, float cx, float cy, float size, int alpha, int ink) {
        float k = size / 24f;
        canvas.save();
        try {
            canvas.translate(cx - size / 2f, cy - size / 2f);
            canvas.scale(k, k);
            iconPaint.setColor(((alpha & 0xFF) << 24) | (ink & 0xFFFFFF));
            iconPaint.setStyle(Paint.Style.STROKE);
            path.reset();
            rect.setEmpty();

            switch (index) {
                case 0: // 首页：房子
                    path.moveTo(3.6f, 10.4f);
                    path.lineTo(12f, 3.6f);
                    path.lineTo(20.4f, 10.4f);
                    path.lineTo(20.4f, 19.4f);
                    path.lineTo(14.6f, 19.4f);
                    path.lineTo(14.6f, 14.2f);
                    path.lineTo(9.4f, 14.2f);
                    path.lineTo(9.4f, 19.4f);
                    path.lineTo(3.6f, 19.4f);
                    path.close();
                    break;
                case 1: // 朋友：两个人
                    canvas.drawCircle(9.2f, 8.4f, 3.2f, iconPaint);
                    path.moveTo(3.2f, 19.6f);
                    path.cubicTo(3.4f, 15.6f, 6f, 13.4f, 9.2f, 13.4f);
                    path.cubicTo(12.4f, 13.4f, 15f, 15.6f, 15.2f, 19.6f);
                    canvas.drawPath(path, iconPaint);
                    path.reset();
                    canvas.drawCircle(17f, 9.4f, 2.4f, iconPaint);
                    path.moveTo(16.6f, 14.1f);
                    path.cubicTo(19.2f, 14.4f, 20.6f, 16.4f, 20.8f, 19.6f);
                    break;
                case 2: // 消息：气泡
                    rect.set(3.6f, 5.2f, 20.4f, 16.4f);
                    canvas.drawRoundRect(rect, 4.2f, 4.2f, iconPaint);
                    path.moveTo(8.6f, 16.4f);
                    path.lineTo(8.6f, 20f);
                    path.lineTo(12.6f, 16.4f);
                    break;
                default: // 我：单人
                    canvas.drawCircle(12f, 8.2f, 3.6f, iconPaint);
                    path.moveTo(4.8f, 20f);
                    path.cubicTo(5.2f, 15.4f, 8.2f, 13.6f, 12f, 13.6f);
                    path.cubicTo(15.8f, 13.6f, 18.8f, 15.4f, 19.2f, 20f);
                    break;
            }
            canvas.drawPath(path, iconPaint);
        } finally {
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
        int i = s.endsWith("+") ? 0 : 0;
        if (s.isEmpty()) return false;
        for (int k = i; k < s.length(); k++) {
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
                    downX = x;
                    downY = y;
                    dragging = false;
                    int i = slotAt(x);
                    pendingIndex = i;
                    pos.snapTo(clampIndex(pos.value));
                    pos.to(i, 900f, 45f);
                    selected = i;
                    press.to(1f, 900f, 48f);
                    squish.to(1f, 520f, 34f);
                    startLoop();
                    return true;
                }
                if (plusPill.contains(x, y)) {
                    downInPlus = true;
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
                    boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    // 拖动过就落到手指停下的那一格；只是点一下就是按下时那一格
                    int target = dragging ? clampIndex(pos.value) : pendingIndex;
                    downInLeft = false;
                    press.to(0f, 600f, 42f);
                    squish.to(0f, 420f, 30f);
                    dragging = false;
                    pos.to(target, 700f, 34f);
                    selected = target;
                    if (!cancelled) clickTab(target);
                    startLoop();
                    return true;
                }
                if (downInPlus) {
                    downInPlus = false;
                    squish.to(0f, 420f, 30f);
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
        int i = (int) Math.floor((x - leftPill.left - 8f * density) / slotW);
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
        long t = nanos / 1000000L;
        long dt = lastFrame == 0L ? 16L : Math.min(50L, t - lastFrame);
        if (dt <= 0L) dt = 16L;
        lastFrame = t;

        boolean a = pos.step(dt);
        boolean b = press.step(dt);
        boolean c = plusPress.step(dt);
        boolean d = appear.step(dt);
        boolean e = squish.step(dt);
        invalidate();

        if (a || b || c || d || e) {
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
    }

    @Override
    protected void onDetachedFromWindow() {
        timer.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
