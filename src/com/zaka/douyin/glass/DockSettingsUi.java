package com.zaka.douyin.glass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * 抖音「设置」页里的 dydock 入口。
 *
 * 抖音的设置页是一棵普通 View 树，这里先试着把入口当成一行插到设置列表的最上面
 * （背景、内边距、文字颜色都从旁边那几行抄，看起来跟原生设置项一致）；
 * 插不进去就退化成设置页右上角一个浮层小按钮，保证入口一定点得到。
 *
 * 点开是一个系统风格的小面板，改完立刻作用到当前正在跑的 Dock。
 */
final class DockSettingsUi {

    private static final WeakHashMap<Activity, Boolean> ATTACHED = new WeakHashMap<Activity, Boolean>();

    private DockSettingsUi() {
    }

    /** 每个 Activity 走到 onResume 时调用；只有抖音自己的设置页会被接管。 */
    static void onHostActivity(final Activity act) {
        try {
            if (act == null) return;
            String cls = act.getClass().getName().toLowerCase();
            if (!cls.contains("setting") || cls.contains("permission")) return;
            synchronized (ATTACHED) {
                if (ATTACHED.containsKey(act)) return;
                ATTACHED.put(act, Boolean.TRUE);
            }
            final View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        attach(act);
                    } catch (Throwable t) {
                        Dydock.warnOnce("settings-entry", "设置入口挂载失败: " + t);
                    }
                }
            }, 700L);
        } catch (Throwable t) {
            Dydock.warnOnce("settings-entry-resume", "设置页识别失败: " + t);
        }
    }

    private static void attach(Activity act) {
        if (act.isFinishing() || act.isDestroyed()) return;
        View content = act.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        ViewGroup list = findSettingsList((ViewGroup) content, 0,
                new ViewGroup[]{null}, new int[]{-1});
        if (list != null) {
            try {
                list.addView(buildRow(act, list), 0);
                Dydock.log("设置入口:已插入设置列表顶部（共 " + list.getChildCount() + " 项）");
                return;
            } catch (Throwable t) {
                Dydock.warnOnce("settings-entry-insert", "插入设置列表失败: " + t);
            }
        }

        // 兜底：右上角一个小浮层按钮，位置避开标题栏。
        try {
            View pill = buildPill(act);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            float density = act.getResources().getDisplayMetrics().density;
            lp.topMargin = (int) ((DockPrefs.entryOffsetDp(act) + 96) * density);
            lp.rightMargin = (int) (10f * density);
            ((ViewGroup) content).addView(pill, lp);
            Dydock.log("设置入口:设置列表没认出来，改用浮层按钮");
        } catch (Throwable t) {
            Dydock.warnOnce("settings-entry-pill", "浮层入口失败: " + t);
        }
    }

    /**
     * 找设置页里那一条一条的设置列表。
     *
     * 判据：子项够多（≥4）、大部分子项里带文字、并且整块够高 ——
     * 抖音设置页就是一个纵向列表，这个特征很稳；找不到就返回 null 走浮层兜底。
     */
    private static ViewGroup findSettingsList(ViewGroup parent, int depth,
                                              ViewGroup[] best, int[] bestScore) {
        if (parent == null || depth > 14) return best[0];
        int count = parent.getChildCount();
        if (count >= 4 && looksLikeSettingRows(parent)) {
            int score = count * 10 + depth;
            if (score > bestScore[0]) {
                bestScore[0] = score;
                best[0] = parent;
            }
        }
        for (int i = 0; i < count; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup && child.getVisibility() == View.VISIBLE) {
                findSettingsList((ViewGroup) child, depth + 1, best, bestScore);
            }
        }
        return best[0];
    }

    private static boolean looksLikeSettingRows(ViewGroup group) {
        if (group instanceof LinearLayout
                && ((LinearLayout) group).getOrientation() != LinearLayout.VERTICAL) {
            return false;
        }
        int rows = 0;
        int withText = 0;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            rows++;
            if (hasText(child, 0) && child.getHeight() > 0) withText++;
        }
        return rows >= 4 && withText >= Math.max(3, rows - 2);
    }

    private static boolean hasText(View view, int depth) {
        if (view == null || depth > 6) return false;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().trim().length() > 0) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 按旁边那几行的样式拼一行入口，尽量跟原生设置项长得一样。 */
    private static View buildRow(Activity act, ViewGroup list) {
        float density = act.getResources().getDisplayMetrics().density;
        View template = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && hasText(child, 0)) {
                template = child;
                break;
            }
        }

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        int padH = (int) (16f * density);
        int padV = (int) (14f * density);
        if (template != null) {
            try {
                if (template.getBackground() != null) {
                    row.setBackground(template.getBackground().getConstantState() == null
                            ? template.getBackground()
                            : template.getBackground().getConstantState().newDrawable().mutate());
                }
            } catch (Throwable ignored) {
            }
            padH = Math.max(padH, template.getPaddingLeft());
            padV = Math.max(padV, template.getPaddingTop());
            if (template.getLayoutParams() != null
                    && template.getLayoutParams().height > 0) {
                row.setMinimumHeight(template.getLayoutParams().height);
            }
        }
        row.setPadding(padH, padV, padH, padV);

        int textColor = colorOf(act, template);

        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(act);
        title.setText("dydock 液态玻璃");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTextColor(textColor);

        TextView subtitle = new TextView(act);
        subtitle.setText("底栏样式与显隐设置");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setTextColor(withAlpha(textColor, 0.55f));

        column.addView(title);
        column.addView(subtitle);

        TextView arrow = new TextView(act);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        arrow.setTextColor(withAlpha(textColor, 0.45f));
        arrow.setPadding((int) (10f * density), 0, 0, 0);

        row.addView(column);
        row.addView(arrow);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(v.getContext());
            }
        });
        return row;
    }

    private static View buildPill(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(context);
        pill.setText("dydock");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pill.setTextColor(Color.WHITE);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding((int) (12f * density), (int) (7f * density),
                (int) (12f * density), (int) (7f * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC202124);
        bg.setCornerRadius(14f * density);
        pill.setBackground(bg);
        pill.setClickable(true);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(v.getContext());
            }
        });
        return pill;
    }

    private static int colorOf(Context context, View template) {
        if (template != null) {
            TextView tv = firstText(template, 0);
            if (tv != null) {
                ColorStateList colors = tv.getTextColors();
                if (colors != null) return colors.getDefaultColor();
            }
        }
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
                && value.resourceId != 0) {
            try {
                return context.getResources().getColorStateList(value.resourceId).getDefaultColor();
            } catch (Throwable ignored) {
            }
        }
        return 0xFF111111;
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

    private static int withAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 设置面板。所有改动即时写盘，并马上作用到正在跑的 Dock。 */
    static void showDialog(Context context) {
        try {
            final Context ctx = context;
            float density = ctx.getResources().getDisplayMetrics().density;

            LinearLayout form = new LinearLayout(ctx);
            form.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (18f * density);
            form.setPadding(pad, (int) (8f * density), pad, (int) (4f * density));

            final Switch enabled = new Switch(ctx);
            enabled.setText("启用玻璃 Dock");
            enabled.setChecked(DockPrefs.enabled(ctx));
            enabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton button, boolean value) {
                    DockPrefs.setEnabled(ctx, value);
                    if (value) {
                        DockController.requestReinstall();
                    }
                    DockController.applySettingsToAll();
                }
            });
            form.addView(enabled);

            addSlider(form, ctx, "Dock 高度", DockPrefs.MIN_HEIGHT, DockPrefs.MAX_HEIGHT, 4,
                    DockPrefs.heightDp(ctx), new ValueSink() {
                        @Override
                        public void onValue(Context c, int value) {
                            DockPrefs.setHeightDp(c, value);
                            DockController.applySettingsToAll();
                        }
                    });
            addSlider(form, ctx, "单个图标宽度", DockPrefs.MIN_TAB_WIDTH, DockPrefs.MAX_TAB_WIDTH, 4,
                    DockPrefs.tabWidthDp(ctx), new ValueSink() {
                        @Override
                        public void onValue(Context c, int value) {
                            DockPrefs.setTabWidthDp(c, value);
                            DockController.applySettingsToAll();
                        }
                    });
            addSlider(form, ctx, "左右安全边距", DockPrefs.MIN_SIDE_MARGIN, DockPrefs.MAX_SIDE_MARGIN, 4,
                    DockPrefs.sideMarginDp(ctx), new ValueSink() {
                        @Override
                        public void onValue(Context c, int value) {
                            DockPrefs.setSideMarginDp(c, value);
                            DockController.applySettingsToAll();
                        }
                    });
            addSlider(form, ctx, "离底部间距", DockPrefs.MIN_BOTTOM_GAP, DockPrefs.MAX_BOTTOM_GAP, 4,
                    DockPrefs.bottomGapDp(ctx), new ValueSink() {
                        @Override
                        public void onValue(Context c, int value) {
                            DockPrefs.setBottomGapDp(c, value);
                            DockController.applySettingsToAll();
                        }
                    });

            final Switch showPlus = new Switch(ctx);
            showPlus.setText("显示「＋」按钮");
            showPlus.setChecked(DockPrefs.showPlusButton(ctx));
            showPlus.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton button, boolean value) {
                    DockPrefs.setShowPlusButton(ctx, value);
                    DockController.applySettingsToAll();
                }
            });
            form.addView(showPlus);

            final Switch showLabels = new Switch(ctx);
            showLabels.setText("显示文字标签");
            showLabels.setChecked(DockPrefs.showLabels(ctx));
            showLabels.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton button, boolean value) {
                    DockPrefs.setShowLabels(ctx, value);
                    DockController.applySettingsToAll();
                }
            });
            form.addView(showLabels);

            TextView hint = new TextView(ctx);
            hint.setText("改完回到首页、朋友、消息、我任一页面即可看到；关掉「启用玻璃 Dock」会立刻换回抖音原生底栏，"
                    + "下次进抖音同样生效。");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            hint.setPadding(0, (int) (10f * density), 0, 0);
            hint.setTextColor(colorOf(ctx, null));
            form.addView(hint);

            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(form);

            new AlertDialog.Builder(ctx)
                    .setTitle("dydock " + Dydock.VER)
                    .setView(scroll)
                    .setNegativeButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            DockPrefs.resetAll(ctx);
                            DockController.requestReinstall();
                            DockController.applySettingsToAll();
                            showDialog(ctx);
                        }
                    })
                    .setPositiveButton("完成", null)
                    .show();
        } catch (Throwable t) {
            Dydock.warnOnce("settings-dialog", "设置面板打开失败: " + t);
        }
    }

    private interface ValueSink {
        void onValue(Context context, int value);
    }

    private static void addSlider(LinearLayout form, Context ctx, String title,
                                  final int min, final int max, final int step,
                                  int current, final ValueSink sink) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, (int) (12f * density), 0, 0);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setTextColor(colorOf(ctx, null));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(ctx);
        value.setText(current + " dp");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        value.setTextColor(colorOf(ctx, null));

        header.addView(label);
        header.addView(value);
        form.addView(header);

        final SeekBar bar = new SeekBar(ctx);
        final int steps = Math.max(1, (max - min) / step);
        bar.setMax(steps);
        bar.setProgress(DockPrefs.clamp((current - min) / step, 0, steps));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int computed = min + progress * step;
                value.setText(computed + " dp");
                if (fromUser) sink.onValue(seekBar.getContext(), computed);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        form.addView(bar);
    }
}
