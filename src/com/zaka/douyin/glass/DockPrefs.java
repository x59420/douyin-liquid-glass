package com.zaka.douyin.glass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * dydock 自己的配置。
 *
 * 存在抖音进程里的私有 SharedPreferences 中：模块的代码本来就跑在宿主进程里，
 * 设置页（注入在抖音设置页里的那个入口）和 Dock 用的是进程里的同一份，
 * 不需要跨进程、也不需要读写模块 APK 所在目录。
 */
final class DockPrefs {

    private static final String NAME = "dydock_settings";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HEIGHT = "dock_height_dp";
    private static final String KEY_TAB_WIDTH = "tab_width_dp";
    private static final String KEY_SIDE_MARGIN = "side_margin_dp";
    private static final String KEY_BOTTOM_GAP = "bottom_gap_dp";
    private static final String KEY_SHOW_PLUS = "show_plus";
    private static final String KEY_SHOW_LABELS = "show_labels";
    private static final String KEY_ENTRY_OFFSET = "entry_top_offset_dp";

    /** 默认值就是 0.4.x 一直在用的那套参数：64dp 高、单格 76dp、左右各 24dp、离底 8dp。 */
    static final int DEF_HEIGHT = 64;
    static final int DEF_TAB_WIDTH = 76;
    static final int DEF_SIDE_MARGIN = 24;
    static final int DEF_BOTTOM_GAP = 8;

    static final int MIN_HEIGHT = 52;
    static final int MAX_HEIGHT = 88;
    static final int MIN_TAB_WIDTH = 60;
    static final int MAX_TAB_WIDTH = 100;
    static final int MIN_SIDE_MARGIN = 12;
    static final int MAX_SIDE_MARGIN = 40;
    static final int MIN_BOTTOM_GAP = 0;
    static final int MAX_BOTTOM_GAP = 28;

    private DockPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return readBool(context, KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean value) {
        writeBool(context, KEY_ENABLED, value);
    }

    static boolean showPlusButton(Context context) {
        return readBool(context, KEY_SHOW_PLUS, true);
    }

    static void setShowPlusButton(Context context, boolean value) {
        writeBool(context, KEY_SHOW_PLUS, value);
    }

    static boolean showLabels(Context context) {
        return readBool(context, KEY_SHOW_LABELS, true);
    }

    static void setShowLabels(Context context, boolean value) {
        writeBool(context, KEY_SHOW_LABELS, value);
    }

    static int heightDp(Context context) {
        return clamp(readInt(context, KEY_HEIGHT, DEF_HEIGHT), MIN_HEIGHT, MAX_HEIGHT);
    }

    static void setHeightDp(Context context, int value) {
        writeInt(context, KEY_HEIGHT, clamp(value, MIN_HEIGHT, MAX_HEIGHT));
    }

    static int tabWidthDp(Context context) {
        return clamp(readInt(context, KEY_TAB_WIDTH, DEF_TAB_WIDTH), MIN_TAB_WIDTH, MAX_TAB_WIDTH);
    }

    static void setTabWidthDp(Context context, int value) {
        writeInt(context, KEY_TAB_WIDTH, clamp(value, MIN_TAB_WIDTH, MAX_TAB_WIDTH));
    }

    static int sideMarginDp(Context context) {
        return clamp(readInt(context, KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                MIN_SIDE_MARGIN, MAX_SIDE_MARGIN);
    }

    static void setSideMarginDp(Context context, int value) {
        writeInt(context, KEY_SIDE_MARGIN, clamp(value, MIN_SIDE_MARGIN, MAX_SIDE_MARGIN));
    }

    static int bottomGapDp(Context context) {
        return clamp(readInt(context, KEY_BOTTOM_GAP, DEF_BOTTOM_GAP),
                MIN_BOTTOM_GAP, MAX_BOTTOM_GAP);
    }

    static void setBottomGapDp(Context context, int value) {
        writeInt(context, KEY_BOTTOM_GAP, clamp(value, MIN_BOTTOM_GAP, MAX_BOTTOM_GAP));
    }

    /** 设置入口在抖音设置页里往下挪多少（避开顶部标题栏）。 */
    static int entryOffsetDp(Context context) {
        return clamp(readInt(context, KEY_ENTRY_OFFSET, 0), 0, 240);
    }

    static void setEntryOffsetDp(Context context, int value) {
        writeInt(context, KEY_ENTRY_OFFSET, clamp(value, 0, 240));
    }

    static void resetAll(Context context) {
        try {
            prefs(context).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    private static boolean readBool(Context context, String key, boolean def) {
        try {
            return prefs(context).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void writeBool(Context context, String key, boolean value) {
        try {
            prefs(context).edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    private static int readInt(Context context, String key, int def) {
        try {
            return prefs(context).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void writeInt(Context context, String key, int value) {
        try {
            prefs(context).edit().putInt(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
