package com.zaka.douyin.glass;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 模块自己的设置页。
 *
 * 从桌面图标 / LSPosed 模块列表点进来就是这个页面。写的是**模块包名**下的
 * SharedPreferences，抖音进程里的 Dock 用 XSharedPreferences 读同一份文件，
 * 谁改得晚用谁的（版本号 TimeStamp 决定），所以两边都能改，不会互相盖掉。
 *
 * 这里刻意不引用任何宿主编排代码（DockController / IsoDock）：这个页面跑在模块进程里，
 * 只是写配置，不碰抖音。
 */
public class DockSettingsActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(DockPrefs.NAME, MODE_PRIVATE);
        setTitle("dydock 设置");
        setContentView(buildContent());
    }

    private SharedPreferences hostPrefs() {
        return prefs;
    }

    private int readInt(String key, int def, int min, int max) {
        return DockPrefs.clamp(prefs.getInt(key, def), min, max);
    }

    private boolean readBool(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    private void writeInt(String key, int value) {
        prefs.edit().putInt(key, value).putLong("settings_rev", System.currentTimeMillis()).apply();
        makeReadable();
    }

    private void writeBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).putLong("settings_rev", System.currentTimeMillis()).apply();
        makeReadable();
    }

    /** 让注入进程读得到这份配置。不支持的老框架上静默失败，Dock 用本地配置照常跑。 */
    private void makeReadable() {
        try {
            new de.robv.android.xposed.XSharedPreferences(Dydock.PKG, DockPrefs.NAME)
                    .makeWorldReadable();
        } catch (Throwable ignored) {
        }
    }

    private View buildContent() {
        float density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20f * density);
        form.setPadding(pad, (int) (16f * density), pad, (int) (24f * density));
        scroll.addView(form);

        TextView intro = new TextView(this);
        intro.setText("这些设置存在模块里，抖音里的 Dock 会自己读。改完回到抖音即可生效；"
                + "停着不动的话，切一下页面就会刷新。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        intro.setTextColor(Color.GRAY);
        form.addView(intro);

        addSwitch(form, "启用玻璃 Dock", readBool("enabled", true),
                new ValueSink() {
                    @Override
                    public void onValue(boolean value) {
                        writeBool("enabled", value);
                    }
                });

        addSlider(form, "Dock 高度", DockPrefs.MIN_HEIGHT, DockPrefs.MAX_HEIGHT, 4,
                readInt("dock_height_dp", DockPrefs.DEF_HEIGHT,
                        DockPrefs.MIN_HEIGHT, DockPrefs.MAX_HEIGHT),
                new IntSink() {
                    @Override
                    public void onValue(int value) {
                        writeInt("dock_height_dp", value);
                    }
                });

        addSlider(form, "单个图标宽度", DockPrefs.MIN_TAB_WIDTH, DockPrefs.MAX_TAB_WIDTH, 4,
                readInt("tab_width_dp", DockPrefs.DEF_TAB_WIDTH,
                        DockPrefs.MIN_TAB_WIDTH, DockPrefs.MAX_TAB_WIDTH),
                new IntSink() {
                    @Override
                    public void onValue(int value) {
                        writeInt("tab_width_dp", value);
                    }
                });

        addSlider(form, "左右安全边距", DockPrefs.MIN_SIDE_MARGIN, DockPrefs.MAX_SIDE_MARGIN, 4,
                readInt("side_margin_dp", DockPrefs.DEF_SIDE_MARGIN,
                        DockPrefs.MIN_SIDE_MARGIN, DockPrefs.MAX_SIDE_MARGIN),
                new IntSink() {
                    @Override
                    public void onValue(int value) {
                        writeInt("side_margin_dp", value);
                    }
                });

        addSlider(form, "离底部间距", DockPrefs.MIN_BOTTOM_GAP, DockPrefs.MAX_BOTTOM_GAP, 4,
                readInt("bottom_gap_dp", DockPrefs.DEF_BOTTOM_GAP,
                        DockPrefs.MIN_BOTTOM_GAP, DockPrefs.MAX_BOTTOM_GAP),
                new IntSink() {
                    @Override
                    public void onValue(int value) {
                        writeInt("bottom_gap_dp", value);
                    }
                });

        addSwitch(form, "显示「＋」按钮", readBool("show_plus", true),
                new ValueSink() {
                    @Override
                    public void onValue(boolean value) {
                        writeBool("show_plus", value);
                    }
                });

        addSwitch(form, "显示文字标签", readBool("show_labels", true),
                new ValueSink() {
                    @Override
                    public void onValue(boolean value) {
                        writeBool("show_labels", value);
                    }
                });

        TextView how = new TextView(this);
        how.setText("\n还有两个入口：\n"
                + "· 长按抖音里的玻璃 Dock，直接弹出同一个设置面板\n"
                + "· 抖音「设置」页顶部也会插一行 dydock 入口\n"
                + "\n改完想看日志：Download/Dydock/dydock-diagnostic.log");
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        how.setTextColor(Color.GRAY);
        how.setPadding(0, (int) (18f * density), 0, 0);
        form.addView(how);

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().clear()
                        .putLong("settings_rev", System.currentTimeMillis()).apply();
                makeReadable();
                recreate();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (20f * density);
        form.addView(reset, rp);

        return scroll;
    }

    private void addSwitch(LinearLayout form, String title, boolean checked, final ValueSink sink) {
        float density = getResources().getDisplayMetrics().density;
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        sw.setChecked(checked);
        sw.setPadding(0, (int) (10f * density), 0, (int) (10f * density));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sink.onValue(isChecked);
            }
        });
        form.addView(sw);
    }

    private void addSlider(LinearLayout form, String title, final int min, final int max,
                           final int step, int current, final IntSink sink) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, (int) (14f * density), 0, 0);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(this);
        value.setText(current + " dp");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        value.setTextColor(Color.GRAY);

        header.addView(label);
        header.addView(value);
        form.addView(header);

        final SeekBar bar = new SeekBar(this);
        final int steps = Math.max(1, (max - min) / step);
        bar.setMax(steps);
        bar.setProgress(DockPrefs.clamp((current - min) / step, 0, steps));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int computed = min + progress * step;
                value.setText(computed + " dp");
                if (fromUser) sink.onValue(computed);
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

    private interface IntSink {
        void onValue(int value);
    }

    private interface ValueSink {
        void onValue(boolean value);
    }
}
