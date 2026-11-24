package com.zaka.douyin.glass;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.view.View;
import android.view.MotionEvent;
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

        // LSPosed 会在 LoadPackageParam 里给出模块自己的 APK 路径。
        // 玻璃层要在独立类加载器里加载，必须知道模块 APK 在哪。
        try {
            java.lang.reflect.Field modulePath = lp.getClass().getField("modulePath");
            Object value = modulePath.get(lp);
            if (value instanceof String) IsoDock.setModulePath((String) value);
        } catch (Throwable ignored) {
        }


        // Observe the host's own accepted long-click callback without replacing its listeners.
        // This keeps Douyin's original gesture handling intact.
        try {
            XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (Boolean.TRUE.equals(param.getResult())
                                && param.thisObject instanceof View) {
                            DockController.onHostLongPress((View) param.thisObject);
                        }
                    } catch (Throwable t) {
                        Dydock.warnOnce("host-long-press-hook", "长按状态回调失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 View.performLongClick 失败: " + t);
        }

        // Clear the transient hold state only after the same host View receives UP/CANCEL.
        try {
            XposedBridge.hookAllMethods(View.class, "dispatchTouchEvent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof View && param.args != null
                                && param.args.length > 0 && param.args[0] instanceof MotionEvent) {
                            MotionEvent event = (MotionEvent) param.args[0];
                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN
                                    || action == MotionEvent.ACTION_MOVE
                                    || action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                DockController.onHostTouch((View) param.thisObject, event);
                            }
                        }
                    } catch (Throwable t) {
                        Dydock.warnOnce("host-touch-hook", "触摸状态回调失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 View.dispatchTouchEvent 失败: " + t);
        }

        // 系统侧边返回（以及返回键）：宿主自己处理返回时，清屏态一并结束。
        try {
            XposedBridge.hookAllMethods(Activity.class, "onBackPressed", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Activity) {
                            DockController.onHostBack((Activity) param.thisObject);
                        }
                    } catch (Throwable t) {
                        Dydock.warnOnce("host-back-hook", "返回状态回调失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 Activity.onBackPressed 失败: " + t);
        }

        try {
            XposedBridge.hookAllMethods(Activity.class, "dispatchKeyEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Activity && param.args != null
                                && param.args.length > 0
                                && param.args[0] instanceof android.view.KeyEvent) {
                            android.view.KeyEvent key = (android.view.KeyEvent) param.args[0];
                            if (key.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                                    && key.getAction() == android.view.KeyEvent.ACTION_UP) {
                                DockController.onHostBack((Activity) param.thisObject);
                            }
                        }
                    } catch (Throwable t) {
                        Dydock.warnOnce("host-back-key-hook", "返回键状态回调失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Dydock.warn("挂 Activity.dispatchKeyEvent 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    Dydock.attach(app);
                    registerLifecycleCallbacks(app);
                    Dydock.installCrashHandler();
                    // 设置界面的改动走广播即时同步进来（不依赖 prefs 文件权限）。
                    SettingsSync.register(app);
                    Dydock.log("宿主 Application 已创建；诊断日志已启用");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean lifecycleCallbacksRegistered;

    /** Use the same Activity lifecycle boundary as DouAi: only the real home Activity owns the Dock. */
    private static void registerLifecycleCallbacks(Application app) {
        if (app == null || lifecycleCallbacksRegistered) return;
        synchronized (GlassEntry.class) {
            if (lifecycleCallbacksRegistered) return;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityResumed(Activity activity) {
                    installDock(activity);
                }
                @Override public void onActivityPaused(Activity activity) {
                    NativeUiTransparency.stop(activity);
                    DockController.onHostPause(activity);
                }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) {
                    NativeUiTransparency.stop(activity);
                    DockController.release(activity);
                    DockSettingsUi.release(activity);
                }
            });
            lifecycleCallbacksRegistered = true;
        }
    }

    /**
     * 让抖音内容延伸到系统手势导航栏后方。
     * 不隐藏导航栏本身，系统白色手势提示和手势操作继续保留。
     */
    private static void makeNavigationAreaTransparent(Activity act) {
        NativeUiTransparency.applySystemBars(act);
    }

    /** 只安装底部 Dock，其他区域不再添加液态玻璃。 */
    private static void installDock(Activity act) {
        try {
            makeNavigationAreaTransparent(act);
            NativeUiTransparency.start(act);
            DockController.onActivity(act);
            // 从聊天框 / 会话页退回来时重新量一次底栏位置，Dock 不会偏到左边。
            DockController.onHostResume(act);
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
