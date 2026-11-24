package com.zaka.douyin.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 设置界面（模块进程）→ 抖音进程 的即时同步通道。
 *
 * 原来只靠 `XSharedPreferences` 去读模块那份 prefs 文件：写入用的是 apply()（异步落盘），
 * 文件又是「临时文件 + rename」换上去的，权限会跟着丢，宿主那边经常读到旧值甚至读不到 ——
 * 表现就是「设置里改了，开关不生效」。
 *
 * 现在两条腿走路：
 *   1. 写入改成同步 commit()，落盘后立刻把文件设成可读（DockPrefs.publish）；
 *   2. 设置界面每改一次，直接给抖音进程发一个广播，宿主收到就写进自己的 prefs 并马上应用。
 *      这条路不依赖文件权限，最快，也最稳。
 */
public final class SettingsSync {

    /**
     * 广播动作。
     *
     * 收端会按类型/范围逐个校验（所有值都走 DockPrefs 的 setter，本身就会 clamp），
     * 所以外来的伪造最多改个 Dock 尺寸，不会碰到任何别的状态。
     */
    public static final String ACTION = "com.zaka.douyin.glass.SETTINGS_CHANGED";

    private static BroadcastReceiver receiver;

    private SettingsSync() {
    }

    // ------------------------------------------------------------ 抖音进程侧

    /** 在宿主进程里挂上接收器（Application.onCreate 之后调用一次）。 */
    static void register(Context context) {
        if (context == null || receiver != null) return;
        try {
            final Context app = context.getApplicationContext();
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    try {
                        if (intent == null) return;
                        apply(app, intent);
                    } catch (Throwable t) {
                        Dydock.warnOnce("settings-broadcast", "接收设置广播失败: " + t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Dydock.log("设置:已挂上即时同步通道");
        } catch (Throwable t) {
            Dydock.warnOnce("settings-register", "注册设置广播失败: " + t);
        }
    }

    /** 把广播里的值写进宿主自己的配置，然后立刻作用到正在跑的 Dock。 */
    private static void apply(Context context, Intent intent) {
        boolean changed = false;
        if (intent.hasExtra("enabled")) {
            DockPrefs.setEnabled(context, intent.getBooleanExtra("enabled", true));
            changed = true;
        }
        if (intent.hasExtra("show_plus")) {
            DockPrefs.setShowPlusButton(context, intent.getBooleanExtra("show_plus", true));
            changed = true;
        }
        if (intent.hasExtra("show_labels")) {
            DockPrefs.setShowLabels(context, intent.getBooleanExtra("show_labels", true));
            changed = true;
        }
        if (intent.hasExtra("verbose_log")) {
            boolean verbose = intent.getBooleanExtra("verbose_log", true);
            DockPrefs.setLogEnabled(context, verbose);
            Dydock.setVerbose(verbose);
            changed = true;
        }
        if (intent.hasExtra("height")) {
            DockPrefs.setHeightDp(context, intent.getIntExtra("height", DockPrefs.DEF_HEIGHT));
            changed = true;
        }
        if (intent.hasExtra("tab_width")) {
            DockPrefs.setTabWidthDp(context, intent.getIntExtra("tab_width", DockPrefs.DEF_TAB_WIDTH));
            changed = true;
        }
        if (intent.hasExtra("side_margin")) {
            DockPrefs.setSideMarginDp(context, intent.getIntExtra("side_margin",
                    DockPrefs.DEF_SIDE_MARGIN));
            changed = true;
        }
        if (intent.hasExtra("bottom_gap")) {
            DockPrefs.setBottomGapDp(context, intent.getIntExtra("bottom_gap",
                    DockPrefs.DEF_BOTTOM_GAP));
            changed = true;
        }
        if (intent.hasExtra("native_top_enabled")) { DockPrefs.setNativeTopEnabled(context, intent.getBooleanExtra("native_top_enabled", false)); changed = true; }
        if (intent.hasExtra("native_top_play_alpha")) { DockPrefs.setNativeTopPlayAlpha(context, intent.getIntExtra("native_top_play_alpha", 100)); changed = true; }
        if (intent.hasExtra("native_top_pause_alpha")) { DockPrefs.setNativeTopPauseAlpha(context, intent.getIntExtra("native_top_pause_alpha", 100)); changed = true; }
        if (intent.hasExtra("native_controls_enabled")) { DockPrefs.setNativeControlsEnabled(context, intent.getBooleanExtra("native_controls_enabled", false)); changed = true; }
        if (intent.hasExtra("native_controls_play_alpha")) { DockPrefs.setNativeControlsPlayAlpha(context, intent.getIntExtra("native_controls_play_alpha", 100)); changed = true; }
        if (intent.hasExtra("native_controls_pause_alpha")) { DockPrefs.setNativeControlsPauseAlpha(context, intent.getIntExtra("native_controls_pause_alpha", 100)); changed = true; }
        if (!changed) return;
        Dydock.log("设置:收到设置界面的改动，立刻应用");
        DockController.applySettingsToAll();
    }

    // ------------------------------------------------------------ 模块进程侧

    /** 设置界面每改一项就调一次：广播出去 + （兜底）把 prefs 文件设成可读。 */
    static void push(Context context, boolean enabled, boolean showPlus, boolean showLabels,
                     boolean verboseLog, int height, int tabWidth, int sideMargin, int bottomGap,
                     boolean nativeTopEnabled, int nativeTopPlayAlpha, int nativeTopPauseAlpha,
                     boolean nativeControlsEnabled, int nativeControlsPlayAlpha, int nativeControlsPauseAlpha) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(Dydock.HOST);
            intent.putExtra("enabled", enabled);
            intent.putExtra("show_plus", showPlus);
            intent.putExtra("show_labels", showLabels);
            intent.putExtra("verbose_log", verboseLog);
            intent.putExtra("height", height);
            intent.putExtra("tab_width", tabWidth);
            intent.putExtra("side_margin", sideMargin);
            intent.putExtra("bottom_gap", bottomGap);
            intent.putExtra("native_top_enabled", nativeTopEnabled);
            intent.putExtra("native_top_play_alpha", nativeTopPlayAlpha);
            intent.putExtra("native_top_pause_alpha", nativeTopPauseAlpha);
            intent.putExtra("native_controls_enabled", nativeControlsEnabled);
            intent.putExtra("native_controls_play_alpha", nativeControlsPlayAlpha);
            intent.putExtra("native_controls_pause_alpha", nativeControlsPauseAlpha);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Dydock.warnOnce("settings-push", "发送设置广播失败: " + t);
        }
        // 兜底：宿主下次启动时还能从文件里读到同一份配置。
        DockPrefs.publish(context);
    }
}
