package com.zaka.douyin.glass;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * 只用一条钩子：Instrumentation.callActivityOnResume。
 * 因为「底栏出现在哪一屏」是主界面决定的，而主界面什么时候准备好、什么时候
 * 异步把底栏填出来，各版本都不一样 —— 挂在 resume 上，进来先试，
 * 没认出来就隔 300ms 再试，最多 24 次（约 7 秒），够覆盖首屏构建慢的情况。
 */
public final class GlassEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !Dydock.HOST.equals(lp.packageName)) return;

        Dydock.log("dydock " + Dydock.VER + " 装载进宿主");

        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume",
                    Activity.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a = param.args[0];
                                if (a instanceof Activity) {
                                    DockController.onActivity((Activity) a);
                                }
                            } catch (Throwable t) {
                                Dydock.error("resume 钩子", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Dydock.error("挂 callActivityOnResume", t);
        }

        // 兜底：有的机型上 callActivityOnResume 的签名被改过，再挂一条 onResume
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object a = param.thisObject;
                        if (a instanceof Activity) {
                            DockController.onActivity((Activity) a);
                        }
                    } catch (Throwable t) {
                        Dydock.error("onResume 钩子", t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 Activity.onResume 失败: " + t);
        }

        // 应用起来就记一笔，方便看日志
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

    static {
        try {
            XposedBridge.log("dydock: init");
        } catch (Throwable ignored) {
        }
    }
}
