package com.zaka.douyin.glass;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * 只安装底部 Dock 的液态玻璃。顶栏、聊天栏及其他页面区域全部保持抖音原样，
 * 不再启动 GlassRows，也不对这些区域添加玻璃背景。
 */
public final class GlassEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !Dydock.HOST.equals(lp.packageName)) return;

        Dydock.log("dydock " + Dydock.VER + " 装载进宿主（仅 Dock 液态玻璃）");

        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume",
                    Activity.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a = param.args[0];
                                if (a instanceof Activity) {
                                    installDock((Activity) a);
                                }
                            } catch (Throwable t) {
                                Dydock.error("resume 钩子", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Dydock.error("挂 callActivityOnResume", t);
        }

        // 兜底：部分机型上 callActivityOnResume 的签名可能被改过。
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object a = param.thisObject;
                        if (a instanceof Activity) {
                            installDock((Activity) a);
                        }
                    } catch (Throwable t) {
                        Dydock.error("onResume 钩子", t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 Activity.onResume 失败: " + t);
        }

        // 页面销毁时只清理 Dock，不再管理顶栏或聊天区域的玻璃。
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object a = param.thisObject;
                        if (a instanceof Activity) {
                            DockController.release((Activity) a);
                        }
                    } catch (Throwable t) {
                        Dydock.error("Dock 销毁收尾", t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 Activity.onDestroy 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Dydock.log("宿主 Application 已创建");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 让抖音内容延伸到系统手势导航栏后方。
     * 不隐藏导航栏本身，系统白色手势提示和手势操作继续保留。
     */
    private static void makeNavigationAreaTransparent(Activity act) {
        try {
            if (act == null || act.getWindow() == null) return;
            Window window = act.getWindow();
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            // 只将系统导航栏设为透明，不把应用内容布局扩展进手势区；
            // 否则原生 Dock 的窗口坐标会下移，玻璃 Dock 也跟着偏离 0.1.0。
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            flags &= ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            decor.setSystemUiVisibility(flags);
        } catch (Throwable t) {
            Dydock.warnOnce("transparent-navigation",
                    "透明手势导航栏设置失败，保留系统默认导航栏: " + t);
        }
    }

    /** 只安装底部 Dock，其他区域不再添加液态玻璃。 */
    private static void installDock(Activity act) {
        try {
            makeNavigationAreaTransparent(act);
            DockController.onActivity(act);
        } catch (Throwable t) {
            Dydock.error("底栏入口", t);
        }
    }

    static {
        try {
            XposedBridge.log("dydock: init");
        } catch (Throwable ignored) {
        }
    }
}
