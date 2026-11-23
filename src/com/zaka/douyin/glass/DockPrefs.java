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

    static final String NAME = "dydock_settings";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HEIGHT = "dock_height_dp";
    private static final String KEY_TAB_WIDTH = "tab_width_dp";
    private static final String KEY_SIDE_MARGIN = "side_margin_dp";
    private static final String KEY_BOTTOM_GAP = "bottom_gap_dp";
    private static final String KEY_SHOW_PLUS = "show_plus";
    private static final String KEY_SHOW_LABELS = "show_labels";
    private static final String KEY_ENTRY_OFFSET = "entry_top_offset_dp";
    /** 配置版本号：模块里的设置页写盘时打一个时间戳，宿主这边比它新才覆盖。 */
    private static final String KEY_REV = "settings_rev";

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
            prefs(context).edit().clear()
                    .putLong(KEY_REV, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 模块设置页

    private static long lastSyncAt;

    /**
     * 把模块自己那个设置页（桌面图标的 dydock 设置）里改的东西同步进来。
     *
     * 设置页跑在模块进程里，写的是模块包名的 SharedPreferences；
     * 这边（抖音进程）用 XSharedPreferences 直接读那份文件，谁的版本号新用谁的。
     * 读不到（旧式框架/文件不可读）就什么也不做，Dock 继续用本地配置，不会崩。
     *
     * @return 有变化返回 true
     */
    static boolean syncFromModule(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSyncAt < 1000L) return false;
        lastSyncAt = now;
        try {
            de.robv.android.xposed.XSharedPreferences remote =
                    new de.robv.android.xposed.XSharedPreferences(Dydock.PKG, NAME);
            remote.reload();
            long remoteRev = remote.getLong(KEY_REV, 0L);
            long localRev = prefs(context).getLong(KEY_REV, 0L);
            if (remoteRev <= localRev) return false;

            SharedPreferences.Editor editor = prefs(context).edit();
            editor.putBoolean(KEY_ENABLED, remote.getBoolean(KEY_ENABLED, true));
            editor.putBoolean(KEY_SHOW_PLUS, remote.getBoolean(KEY_SHOW_PLUS, true));
            editor.putBoolean(KEY_SHOW_LABELS, remote.getBoolean(KEY_SHOW_LABELS, true));
            editor.putInt(KEY_HEIGHT, remote.getInt(KEY_HEIGHT, DEF_HEIGHT));
            editor.putInt(KEY_TAB_WIDTH, remote.getInt(KEY_TAB_WIDTH, DEF_TAB_WIDTH));
            editor.putInt(KEY_SIDE_MARGIN, remote.getInt(KEY_SIDE_MARGIN, DEF_SIDE_MARGIN));
            editor.putInt(KEY_BOTTOM_GAP, remote.getInt(KEY_BOTTOM_GAP, DEF_BOTTOM_GAP));
            editor.putInt(KEY_ENTRY_OFFSET, remote.getInt(KEY_ENTRY_OFFSET, 0));
            editor.putLong(KEY_REV, remoteRev);
            editor.apply();
            return true;
        } catch (Throwable t) {
            return false;
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
            prefs(context).edit().putBoolean(key, value)
                    .putLong(KEY_REV, System.currentTimeMillis()).apply();
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
            prefs(context).edit().putInt(key, value)
                    .putLong(KEY_REV, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
