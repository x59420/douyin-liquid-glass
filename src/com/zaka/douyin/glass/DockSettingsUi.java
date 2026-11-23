package com.zaka.douyin.glass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * 「我的」页菜单里的 dydock 入口。
 *
 * 入口位置：抖音「我的」页打开侧边菜单之后，会有一条「设置」。
 * 我们把 dydock 这一项插在它**前面**（同一排就是它左边，竖排列表里就是它上面），
 * 点开直接进模块自己的设置界面。
 *
 * 以前是把入口塞进抖音的设置页顶部（位置不对），而且点开是个系统风格对话框。
 * 现在只在「我的」这一页等菜单出现，认出「设置」那一项再插，别的地方一律不动。
 *
 * 这里刻意不引用任何渲染代码（DockController / IsoDock）：入口只是宿主页里的两个 View，
 * 设置界面跑在模块自己的进程里。
 */
final class DockSettingsUi {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 每个 Activity 一个监听器（只在自己这一页有效）。 */
    private static final WeakHashMap<Activity, Watcher> WATCHERS =
            new WeakHashMap<Activity, Watcher>();
    /** 我们插进去的那一行，用来判断它还在不在（菜单被重建之后要重插）。 */
    private static final WeakHashMap<Activity, View> ENTRIES =
            new WeakHashMap<Activity, View>();

    private DockSettingsUi() {
    }

    /** Dock 的选中项变化时调用；只有「我」这一页需要盯着菜单。 */
    static void onTabSelected(Activity act, int selectedIndex) {
        if (act == null) return;
        try {
            if (selectedIndex != 3) {
                stop(act);
                return;
            }
            Watcher watcher;
            synchronized (WATCHERS) {
                watcher = WATCHERS.get(act);
                if (watcher == null) {
                    watcher = new Watcher(act);
                    WATCHERS.put(act, watcher);
                }
            }
            watcher.start();
        } catch (Throwable t) {
            Dydock.warnOnce("settings-watch", "监听「我的」菜单失败: " + t);
        }
    }

    /** Activity 销毁时收尾。 */
    static void release(Activity act) {
        stop(act);
    }

    private static void stop(Activity act) {
        Watcher watcher;
        synchronized (WATCHERS) { watcher = WATCHERS.remove(act); }
        if (watcher != null) watcher.stop();
        synchronized (ENTRIES) { ENTRIES.remove(act); }
    }

    /** 打开模块自己的设置界面（新的整屏界面，液态玻璃风格）。 */
    static void openSettings(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent();
            intent.setClassName(Dydock.PKG, Dydock.PKG + ".DockSettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            Dydock.log("设置:已打开 dydock 设置界面");
        } catch (Throwable t) {
            Dydock.warnOnce("open-settings", "打开设置界面失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 监听

    /**
     * 每 400ms 看一眼「我的」页里有没有出现「设置」那一项。
     *
     * 只在「我」页、并且我们自己那一行还不在的时候才真的去扫树；扫一次有预算上限，
     * 不会把信息流整棵树翻一遍。菜单一出现（下一次 tick 内）就插进去，插好就不再扫。
     */
    private static final class Watcher implements Runnable {
        private final Activity act;
        private boolean running;
        private int attempts;

        Watcher(Activity act) {
            this.act = act;
        }

        void start() {
            if (running) return;
            running = true;
            MAIN.postDelayed(this, 120L);
        }

        void stop() {
            running = false;
            MAIN.removeCallbacks(this);
        }

        @Override public void run() {
            if (!running) return;
            if (act.isFinishing() || act.isDestroyed()) {
                stop();
                return;
            }
            try {
                View entry;
                synchronized (ENTRIES) { entry = ENTRIES.get(act); }
                boolean attached = entry != null && entry.isAttachedToWindow();
                if (!attached) {
                    attempts++;
                    if (inject(act)) {
                        Dydock.log("设置入口:已插到「我的」菜单的「设置」前面");
                    }
                }
            } catch (Throwable t) {
                Dydock.warnOnce("settings-inject", "插入设置入口失败: " + t);
            }
            if (running) MAIN.postDelayed(this, 600L);
        }
    }

    private static boolean inject(Activity act) {
        View content = act.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return false;
        Hit hit = new Hit();
        scan((ViewGroup) content, 0, new int[]{1400}, hit);
        if (hit.item == null || !(hit.item.getParent() instanceof ViewGroup)) return false;
        ViewGroup parent = (ViewGroup) hit.item.getParent();
        int index = parent.indexOfChild(hit.item);

        View row = buildRow(act, hit);
        ViewGroup.LayoutParams source = hit.item.getLayoutParams();
        ViewGroup.LayoutParams params;
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    source != null && source.width > 0
                            ? source.width : ViewGroup.LayoutParams.MATCH_PARENT,
                    source != null && source.height > 0
                            ? source.height : ViewGroup.LayoutParams.WRAP_CONTENT);
            if (source instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams origin = (LinearLayout.LayoutParams) source;
                lp.setMargins(origin.leftMargin, origin.topMargin,
                        origin.rightMargin, origin.bottomMargin);
                lp.weight = origin.weight;
            }
            params = lp;
        } else {
            params = source != null
                    ? new ViewGroup.LayoutParams(source.width, source.height)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        try {
            parent.addView(row, Math.max(0, index), params);
        } catch (Throwable t) {
            Dydock.warnOnce("settings-add", "插入入口被宿主拒绝: " + t);
            return false;
        }
        synchronized (ENTRIES) { ENTRIES.put(act, row); }
        act.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() {
                Dydock.log("设置入口:已就位");
            }
        }, 400L);
        return true;
    }

    /** 扫到的结果：文字是「设置」的那个控件，以及它所在的那一行。 */
    private static final class Hit {
        View item;
        int textColor = 0xFF111111;
        float textSize = 16f;
        int padLeft;
        int padTop;
        int padRight;
        int padBottom;
    }

    private static void scan(ViewGroup group, int depth, int[] budget, Hit hit) {
        if (hit.item != null || depth > 34 || budget[0]-- <= 0) return;
        for (int i = 0, n = group.getChildCount(); i < n && budget[0] > 0; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (isSettingsText(child) && child.isShown()) {
                View item = rowOf(child, group);
                if (item != null) {
                    hit.item = item;
                    TextView text = firstText(child, 0);
                    if (text != null) {
                        ColorStateList colors = text.getTextColors();
                        if (colors != null) hit.textColor = colors.getDefaultColor();
                        hit.textSize = text.getTextSize() / text.getResources()
                                .getDisplayMetrics().scaledDensity;
                    }
                    hit.padLeft = item.getPaddingLeft();
                    hit.padTop = item.getPaddingTop();
                    hit.padRight = item.getPaddingRight();
                    hit.padBottom = item.getPaddingBottom();
                    return;
                }
            }
            if (child instanceof ViewGroup) scan((ViewGroup) child, depth + 1, budget, hit);
        }
    }

    /** 文字是不是「设置」这一类入口文案（不是正文里随口提到设置）。 */
    private static boolean isSettingsText(View view) {
        if (!(view instanceof TextView)) return false;
        CharSequence raw = ((TextView) view).getText();
        if (raw == null) return false;
        String text = raw.toString().trim();
        return text.equals("设置") || text.equals("抖音设置")
                || text.equals("设置与反馈") || text.equals("设置与隐私");
    }

    /** 由文字往上找「菜单里的一行」：父容器里至少还有别的兄弟项才算一行。 */
    private static View rowOf(View text, ViewGroup owner) {
        View cur = text;
        for (int i = 0; i < 6; i++) {
            ViewGroup parent = cur.getParent() instanceof ViewGroup
                    ? (ViewGroup) cur.getParent() : null;
            if (parent == null) return null;
            if (parent != owner && parent.getChildCount() >= 2
                    && parent.getWidth() > 0 && parent.getHeight() > 0) {
                return cur;
            }
            cur = parent;
        }
        return cur == text ? null : cur;
    }

    private static TextView firstText(View view, int depth) {
        if (view == null || depth > 6) return null;
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstText(group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 照旁边那一行的样式拼我们这一行：图标 + 文案 + 箭头。 */
    private static View buildRow(Context context, Hit hit) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(Math.max(hit.padLeft, (int) (16f * density)),
                Math.max(hit.padTop, (int) (12f * density)),
                Math.max(hit.padRight, (int) (16f * density)),
                Math.max(hit.padBottom, (int) (12f * density)));

        // 小圆角方块当图标：抖音那个菜单里的图标也是这种淡淡的色块。
        View icon = new View(context);
        int size = (int) (20f * density);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
        iconParams.rightMargin = (int) (12f * density);
        icon.setLayoutParams(iconParams);
        GradientDrawable iconBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(hit.textColor, 0.85f), withAlpha(hit.textColor, 0.45f)});
        iconBg.setCornerRadius(6f * density);
        icon.setBackground(iconBg);
        row.addView(icon);

        TextView label = new TextView(context);
        label.setText("dydock 液态玻璃");
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, hit.textSize > 0
                ? hit.textSize * context.getResources().getDisplayMetrics().scaledDensity
                : 16f * context.getResources().getDisplayMetrics().scaledDensity);
        label.setTextColor(hit.textColor);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        row.addView(label);

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        arrow.setTextColor(withAlpha(hit.textColor, 0.45f));
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openSettings(v.getContext());
            }
        });
        return row;
    }

    private static int withAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
