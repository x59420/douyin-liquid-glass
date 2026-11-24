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
    /** 日志开关：关掉之后只留警告/错误，LSPosed 里不再刷日常流程。 */
    private static final String KEY_LOG = "verbose_log";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_NATIVE_TOP_ENABLED = "native_top_transparency_enabled";
    private static final String KEY_NATIVE_TOP_ALPHA = "native_top_alpha";
    private static final String KEY_NATIVE_TOP_PLAY_ALPHA = "native_top_play_alpha";
    private static final String KEY_NATIVE_TOP_PAUSE_ALPHA = "native_top_pause_alpha";
    private static final String KEY_NATIVE_CONTROLS_ENABLED = "native_controls_transparency_enabled";
    private static final String KEY_NATIVE_CONTROLS_ALPHA = "native_controls_alpha";
    private static final String KEY_NATIVE_CONTROLS_PLAY_ALPHA = "native_controls_play_alpha";
    private static final String KEY_NATIVE_CONTROLS_PAUSE_ALPHA = "native_controls_pause_alpha";
    private static final String KEY_NATIVE_BOTTOM_ENABLED = "native_bottom_transparency_enabled";
    private static final String KEY_NATIVE_BOTTOM_ALPHA = "native_bottom_alpha";
    private static final String KEY_NATIVE_BOTTOM_PLAY_ALPHA = "native_bottom_play_alpha";
    private static final String KEY_NATIVE_BOTTOM_PAUSE_ALPHA = "native_bottom_pause_alpha";
    private static final String KEY_HOME_TOP_TRANSPARENT = "home_top_transparent_enabled";
    private static final String KEY_HOME_BOTTOM_TRANSPARENT = "home_bottom_transparent_enabled";
    private static final String KEY_NAVIGATION_TRANSPARENT = "navigation_bar_transparent_enabled";
    private static final String KEY_HIDE_NAVIGATION = "hide_navigation_bar_enabled";
    private static final String KEY_HIDE_STATUS = "hide_status_bar_enabled";
    private static final String KEY_FOLLOW_NATIVE_DOCK_VISIBILITY = "follow_native_dock_visibility";
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

    /** 日志开关（默认开）：关掉只保留警告/错误。 */
    static boolean logEnabled(Context context) {
        return readBool(context, KEY_LOG, true);
    }

    /** 0=跟随系统，1=浅色，2=深色。 */
    static int themeMode(Context context) {
        return clamp(readInt(context, KEY_THEME_MODE, 0), 0, 2);
    }

    static int nativeTopAlpha(Context c) {
        return clamp(readInt(c, KEY_NATIVE_TOP_ALPHA,
                readInt(c, KEY_NATIVE_TOP_PLAY_ALPHA, 100)), 0, 100);
    }
    static void setNativeTopAlpha(Context c, int v) {
        writeInt(c, KEY_NATIVE_TOP_ALPHA, clamp(v, 0, 100));
    }
    static int nativeControlsAlpha(Context c) {
        return clamp(readInt(c, KEY_NATIVE_CONTROLS_ALPHA,
                readInt(c, KEY_NATIVE_CONTROLS_PLAY_ALPHA, 100)), 0, 100);
    }
    static void setNativeControlsAlpha(Context c, int v) {
        writeInt(c, KEY_NATIVE_CONTROLS_ALPHA, clamp(v, 0, 100));
    }
    static int nativeBottomAlpha(Context c) {
        return clamp(readInt(c, KEY_NATIVE_BOTTOM_ALPHA,
                readInt(c, KEY_NATIVE_BOTTOM_PLAY_ALPHA, 100)), 0, 100);
    }
    static void setNativeBottomAlpha(Context c, int v) {
        writeInt(c, KEY_NATIVE_BOTTOM_ALPHA, clamp(v, 0, 100));
    }

    static boolean nativeTopEnabled(Context c) { return readBool(c, KEY_NATIVE_TOP_ENABLED, false); }
    static void setNativeTopEnabled(Context c, boolean v) { writeBool(c, KEY_NATIVE_TOP_ENABLED, v); }
    static int nativeTopPlayAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_TOP_PLAY_ALPHA, 100), 0, 100); }
    static void setNativeTopPlayAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_TOP_PLAY_ALPHA, clamp(v, 0, 100)); }
    static int nativeTopPauseAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_TOP_PAUSE_ALPHA, 100), 0, 100); }
    static void setNativeTopPauseAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_TOP_PAUSE_ALPHA, clamp(v, 0, 100)); }

    static boolean nativeControlsEnabled(Context c) { return readBool(c, KEY_NATIVE_CONTROLS_ENABLED, false); }
    static void setNativeControlsEnabled(Context c, boolean v) { writeBool(c, KEY_NATIVE_CONTROLS_ENABLED, v); }
    static int nativeControlsPlayAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_CONTROLS_PLAY_ALPHA, 100), 0, 100); }
    static void setNativeControlsPlayAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_CONTROLS_PLAY_ALPHA, clamp(v, 0, 100)); }
    static int nativeControlsPauseAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_CONTROLS_PAUSE_ALPHA, 100), 0, 100); }
    static void setNativeControlsPauseAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_CONTROLS_PAUSE_ALPHA, clamp(v, 0, 100)); }
    static boolean nativeBottomEnabled(Context c) { return readBool(c, KEY_NATIVE_BOTTOM_ENABLED, false); }
    static void setNativeBottomEnabled(Context c, boolean v) { writeBool(c, KEY_NATIVE_BOTTOM_ENABLED, v); }
    static int nativeBottomPlayAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_BOTTOM_PLAY_ALPHA, 100), 0, 100); }
    static void setNativeBottomPlayAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_BOTTOM_PLAY_ALPHA, clamp(v, 0, 100)); }
    static int nativeBottomPauseAlpha(Context c) { return clamp(readInt(c, KEY_NATIVE_BOTTOM_PAUSE_ALPHA, 100), 0, 100); }
    static void setNativeBottomPauseAlpha(Context c, int v) { writeInt(c, KEY_NATIVE_BOTTOM_PAUSE_ALPHA, clamp(v, 0, 100)); }
    static boolean homeTopTransparent(Context c) { return readBool(c, KEY_HOME_TOP_TRANSPARENT, false); }
    static void setHomeTopTransparent(Context c, boolean v) { writeBool(c, KEY_HOME_TOP_TRANSPARENT, v); }
    static boolean homeBottomTransparent(Context c) { return readBool(c, KEY_HOME_BOTTOM_TRANSPARENT, false); }
    static void setHomeBottomTransparent(Context c, boolean v) { writeBool(c, KEY_HOME_BOTTOM_TRANSPARENT, v); }
    static boolean navigationTransparent(Context c) { return readBool(c, KEY_NAVIGATION_TRANSPARENT, true); }
    static void setNavigationTransparent(Context c, boolean v) { writeBool(c, KEY_NAVIGATION_TRANSPARENT, v); }
    static boolean hideNavigationBar(Context c) { return readBool(c, KEY_HIDE_NAVIGATION, false); }
    static void setHideNavigationBar(Context c, boolean v) { writeBool(c, KEY_HIDE_NAVIGATION, v); }
    static boolean hideStatusBar(Context c) { return readBool(c, KEY_HIDE_STATUS, false); }
    static void setHideStatusBar(Context c, boolean v) { writeBool(c, KEY_HIDE_STATUS, v); }
    static boolean followNativeDockVisibility(Context c) {
        return readBool(c, KEY_FOLLOW_NATIVE_DOCK_VISIBILITY, true);
    }
    static void setFollowNativeDockVisibility(Context c, boolean v) {
        writeBool(c, KEY_FOLLOW_NATIVE_DOCK_VISIBILITY, v);
    }

    static void setThemeMode(Context context, int value) {
        writeInt(context, KEY_THEME_MODE, clamp(value, 0, 2));
    }

    static void setLogEnabled(Context context, boolean value) {
        writeBool(context, KEY_LOG, value);
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

    /**
     * 让抖音进程读得到这份配置。
     *
     * 设置界面跑在模块进程里，写的是模块包名下的 SharedPreferences；宿主进程用
     * XSharedPreferences 直接读这个文件。这里把文件和目录设成「其他用户可读/可进入」，
     * 不需要 Xposed API（模块进程里没有那个类），纯 java.io 就够。
     */
    static void publish(Context context) {
        try {
            String dataDir = context.getApplicationInfo().dataDir;
            java.io.File file = new java.io.File(dataDir, "shared_prefs/" + NAME + ".xml");
            java.io.File dir = file.getParentFile();
            if (dir != null && dir.isDirectory()) dir.setExecutable(true, false);
            if (file.isFile()) file.setReadable(true, false);
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
            editor.putBoolean(KEY_LOG, remote.getBoolean(KEY_LOG, true));
            editor.putBoolean(KEY_NATIVE_TOP_ENABLED, remote.getBoolean(KEY_NATIVE_TOP_ENABLED, false));
            editor.putInt(KEY_NATIVE_TOP_ALPHA, remote.getInt(KEY_NATIVE_TOP_ALPHA,
                    remote.getInt(KEY_NATIVE_TOP_PLAY_ALPHA, 100)));
            editor.putInt(KEY_NATIVE_TOP_PLAY_ALPHA, remote.getInt(KEY_NATIVE_TOP_PLAY_ALPHA, 100));
            editor.putInt(KEY_NATIVE_TOP_PAUSE_ALPHA, remote.getInt(KEY_NATIVE_TOP_PAUSE_ALPHA, 100));
            editor.putBoolean(KEY_NATIVE_CONTROLS_ENABLED, remote.getBoolean(KEY_NATIVE_CONTROLS_ENABLED, false));
            editor.putInt(KEY_NATIVE_CONTROLS_ALPHA, remote.getInt(KEY_NATIVE_CONTROLS_ALPHA,
                    remote.getInt(KEY_NATIVE_CONTROLS_PLAY_ALPHA, 100)));
            editor.putInt(KEY_NATIVE_CONTROLS_PLAY_ALPHA, remote.getInt(KEY_NATIVE_CONTROLS_PLAY_ALPHA, 100));
            editor.putInt(KEY_NATIVE_CONTROLS_PAUSE_ALPHA, remote.getInt(KEY_NATIVE_CONTROLS_PAUSE_ALPHA, 100));
            editor.putBoolean(KEY_NATIVE_BOTTOM_ENABLED, remote.getBoolean(KEY_NATIVE_BOTTOM_ENABLED, false));
            editor.putInt(KEY_NATIVE_BOTTOM_ALPHA, remote.getInt(KEY_NATIVE_BOTTOM_ALPHA,
                    remote.getInt(KEY_NATIVE_BOTTOM_PLAY_ALPHA, 100)));
            editor.putInt(KEY_NATIVE_BOTTOM_PLAY_ALPHA, remote.getInt(KEY_NATIVE_BOTTOM_PLAY_ALPHA, 100));
            editor.putInt(KEY_NATIVE_BOTTOM_PAUSE_ALPHA, remote.getInt(KEY_NATIVE_BOTTOM_PAUSE_ALPHA, 100));
            editor.putBoolean(KEY_HOME_TOP_TRANSPARENT, remote.getBoolean(KEY_HOME_TOP_TRANSPARENT, false));
            editor.putBoolean(KEY_HOME_BOTTOM_TRANSPARENT, remote.getBoolean(KEY_HOME_BOTTOM_TRANSPARENT, false));
            editor.putBoolean(KEY_NAVIGATION_TRANSPARENT, remote.getBoolean(KEY_NAVIGATION_TRANSPARENT, true));
            editor.putBoolean(KEY_HIDE_NAVIGATION, remote.getBoolean(KEY_HIDE_NAVIGATION, false));
            editor.putBoolean(KEY_HIDE_STATUS, remote.getBoolean(KEY_HIDE_STATUS, false));
            editor.putLong(KEY_REV, remoteRev);
            editor.apply();
            Dydock.setVerbose(remote.getBoolean(KEY_LOG, true));
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
            // 用 commit()：设置界面（模块进程）写完马上要 publish 让宿主读得到，
            // apply() 是异步落盘，紧接着 publish 会作用在旧文件上，权限也就丢了。
            prefs(context).edit().putBoolean(key, value)
                    .putLong(KEY_REV, System.currentTimeMillis()).commit();
            publishIfModule(context);
        } catch (Throwable ignored) {
        }
    }

    /** 只有模块自己那个进程写盘时才需要把文件开放给宿主读。 */
    private static void publishIfModule(Context context) {
        try {
            if (context != null && Dydock.PKG.equals(context.getPackageName())) {
                publish(context);
            }
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
                    .putLong(KEY_REV, System.currentTimeMillis()).commit();
            publishIfModule(context);
        } catch (Throwable ignored) {
        }
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
