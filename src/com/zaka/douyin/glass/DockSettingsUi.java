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
import android.view.ViewParent;
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
        scan((ViewGroup) content, 0, new int[]{1600}, hit);
        if (hit.item == null || hit.parent == null) return false;
        ViewGroup parent = hit.parent;
        int index = Math.max(0, Math.min(hit.index, parent.getChildCount()));

        View row = buildRow(act, hit);
        ViewGroup.LayoutParams source = hit.item.getLayoutParams();
        boolean horizontal = parent instanceof LinearLayout
                && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL;
        if (!horizontal) {
            // 竖排列表里「设置」独占一行：把这一行包进一个横排容器，
            // 我们这一项放在它**左边**，原生那一行保持原样在右边。
            // （只是包一层，原生控件还是原来那个实例，点击/绑定都不受影响。）
            if (wrapBeside(act, parent, index, hit.item, row)) {
                synchronized (ENTRIES) { ENTRIES.put(act, row); }
                Dydock.log("设置入口:已放在「设置」左边");
                return true;
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                horizontal
                        ? (source != null && source.width > 0
                            ? source.width : ViewGroup.LayoutParams.WRAP_CONTENT)
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                source != null && source.height > 0
                        ? Math.min(source.height, (int) (72f * act.getResources()
                            .getDisplayMetrics().density))
                        : ViewGroup.LayoutParams.WRAP_CONTENT);
        if (source instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams origin = (LinearLayout.LayoutParams) source;
            // 权重一定不要抄：抄过来我们这一行会跟原生项抢高度，把人家挤掉/盖住。
            params.setMargins(origin.leftMargin, origin.topMargin,
                    origin.rightMargin, origin.bottomMargin);
        }
        try {
            parent.addView(row, index, params);
        } catch (Throwable t) {
            Dydock.warnOnce("settings-add", "插入入口被宿主拒绝: " + t);
            return false;
        }
        synchronized (ENTRIES) { ENTRIES.put(act, row); }
        Dydock.log("设置入口:已插到「设置」前面（第 " + index + " 项，共 "
                + parent.getChildCount() + " 项）");
        return true;
    }

    /** 扫到的结果：设置那一行、它所在的列表容器、以及它的位置。 */
    private static final class Hit {
        View item;
        ViewGroup parent;
        int index;
        int textColor = 0xFF111111;
        float textSize;
        int padLeft;
        int padTop;
        int padRight;
        int padBottom;
    }

    /**
     * 找「设置」那一行。
     *
     * 只在**列表容器**（LinearLayout）里插，并且插在「设置」那个直接子节点的位置上：
     * 横排就落在它左边，竖排就落在它上面。以前会往上找好几层、插到外层容器里，
     * 结果那一行被铺成整屏大小，把原生菜单项全糊住了 —— 宁可这次不插，也不能盖住宿主。
     */
    private static void scan(ViewGroup group, int depth, int[] budget, Hit hit) {
        if (hit.item != null || depth > 34 || budget[0]-- <= 0) return;
        boolean isList = canInsert(group);
        for (int i = 0, n = group.getChildCount(); i < n && budget[0] > 0; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (isList && child.isShown()) {
                TextView label = settingsLabel(child, 0);
                if (label != null) {
                    hit.item = child;
                    hit.parent = group;
                    hit.index = i;
                    ColorStateList colors = label.getTextColors();
                    if (colors != null) hit.textColor = colors.getDefaultColor();
                    hit.textSize = label.getTextSize();
                    hit.padLeft = child.getPaddingLeft();
                    hit.padTop = child.getPaddingTop();
                    hit.padRight = child.getPaddingRight();
                    hit.padBottom = child.getPaddingBottom();
                    return;
                }
            }
            if (child instanceof ViewGroup) scan((ViewGroup) child, depth + 1, budget, hit);
        }
    }

    /**
     * 这个容器能不能安全地多插一行。
     *
     * 只有「普通线性列表」才可以：必须是 LinearLayout；兄弟姐妹里只要有一个是带权重的，
     * 说明这一列是按权重分高度的，再插一行会把原生项挤掉（那正是「挡到原生设置和别的入口」
     * 的来源）——这种情况宁可不插，也不动宿主布局。
     */
    private static boolean canInsert(ViewGroup parent) {
        if (!(parent instanceof LinearLayout) || parent.getWidth() <= 0) return false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = parent.getChildAt(i).getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams
                    && ((LinearLayout.LayoutParams) lp).weight > 0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这一块里是不是「设置」这一行。
     *
     * 只认两种：自己就是写着「设置」的文字控件，或者是一个小容器（高度像一行）里包着这段文字。
     * 大容器一律不算 —— 否则整棵抽屉都可能被当成「设置那一行」。
     */
    private static TextView settingsLabel(View view, int depth) {
        if (view == null || depth > 2) return null;
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            if (raw == null) return null;
            String text = raw.toString().trim();
            if (text.equals("设置") || text.equals("抖音设置")
                    || text.equals("设置与反馈") || text.equals("设置与隐私")) {
                return (TextView) view;
            }
            return null;
        }
        if (depth == 0) {
            float density = view.getResources().getDisplayMetrics().density;
            if (view.getHeight() > 140f * density || view.getWidth() <= 0) return null;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = settingsLabel(group.getChildAt(i), depth + 1);
            if (found != null) return found;
        }
        return null;
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

    /**
     * 我们这一行：只写一个「dydock」，样式全部跟着旁边那一行走
     * （字色、字号、左右内边距、高度都抄原生的），不做图标、不做箭头，看起来就是一项目录。
     */
    private static View buildRow(Context context, Hit hit) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        ViewGroup.LayoutParams source = hit.item == null ? null : hit.item.getLayoutParams();
        boolean fixedHeight = source != null && source.height > 0;

        TextView label = new TextView(context);
        label.setText("dydock");
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, hit.textSize > 0
                ? hit.textSize
                : 16f * context.getResources().getDisplayMetrics().scaledDensity);
        label.setTextColor(hit.textColor);
        label.setSingleLine(true);
        row.addView(label);

        row.setPadding(Math.max(hit.padLeft, (int) (16f * density)),
                fixedHeight ? 0 : (int) (10f * density),
                Math.max(hit.padRight, (int) (16f * density)),
                fixedHeight ? 0 : (int) (10f * density));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openSettings(v.getContext());
            }
        });
        return row;
    }

    /**
     * 把一个竖排列表里的某一行包成「[我们的入口][原生那一行]」的横排。
     *
     * 只换容器，不动原生 View 本身：它还是原来那个实例、原来的点击监听，
     * 只是宽度交给 weight 撑满剩下的位置。任何一步出错都回滚，绝不让菜单少一项。
     */
    private static boolean wrapBeside(Activity act, ViewGroup parent, int index,
                                      View item, View entry) {
        ViewGroup.LayoutParams itemLp = item.getLayoutParams();
        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(
                itemLp != null && itemLp.width > 0
                        ? itemLp.width : ViewGroup.LayoutParams.MATCH_PARENT,
                itemLp != null && itemLp.height > 0
                        ? itemLp.height : ViewGroup.LayoutParams.WRAP_CONTENT);
        if (itemLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams src = (LinearLayout.LayoutParams) itemLp;
            wrapperLp.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
        }
        try {
            parent.removeViewAt(index);
            LinearLayout wrapper = new LinearLayout(act);
            wrapper.setOrientation(LinearLayout.HORIZONTAL);
            wrapper.setGravity(Gravity.CENTER_VERTICAL);
            parent.addView(wrapper, index, wrapperLp);
            int height = itemLp != null && itemLp.height > 0
                    ? itemLp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
            wrapper.addView(entry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, height));
            wrapper.addView(item, new LinearLayout.LayoutParams(0, height, 1f));
            return true;
        } catch (Throwable t) {
            // 回滚：把原生那一行放回原处。
            try {
                ViewParent holder = item.getParent();
                if (holder instanceof ViewGroup) ((ViewGroup) holder).removeView(item);
                if (item.getParent() == null) {
                    parent.addView(item, Math.min(index, parent.getChildCount()), itemLp);
                }
            } catch (Throwable ignored) {
            }
            Dydock.warnOnce("settings-wrap", "把入口放到设置左边失败，这一行不插: " + t);
            return false;
        }
    }

    private static int withAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
