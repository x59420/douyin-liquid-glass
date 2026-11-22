package com.zaka.douyin.glass;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;

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

    /** 只安装底部 Dock，其他区域不再添加液态玻璃。 */
    private static void installDock(Activity act) {
        try {
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
