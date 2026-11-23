package com.zaka.douyin.glass;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;

import java.io.File;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dalvik.system.DexClassLoader;

/**
 * 玻璃层的外壳：把 Compose + Miuix 那一整套放进**独立类加载器**里跑。
 *
 * 为什么要隔离：
 * 抖音自己的进程里已经带了 androidx（以及可能的 Compose）。LSPosed 把模块的 dex
 * 挂进宿主进程时是父加载器优先，于是模块里的 androidx.lifecycle / savedstate 会被
 * 宿主那份老版本顶掉，ComposeView 一初始化就抛 NoSuchMethodError / ClassCastException。
 * 异常被绘制护栏吞掉之后，用户看到的就是「模块装了，底栏一点没变」。
 *
 * 这里用 DexClassLoader 以**系统启动类加载器**为父，单独加载模块 APK：
 * 模块自己的 androidx / compose / miuix 全套都是独立的一份，和宿主互不相干，
 * 渲染器仍然是 KernelSU 那套 Compose + Miuix，参数一行没动。
 *
 * 跨加载器只传框架类型（View / Bitmap / Rect / Context）和 java.util.function 回调，
 * 这两边都能看见，不需要共享任何模块类。
 */
final class IsoDock {

    private static final String ISO_CLASS = "com.zaka.douyin.glass.iso.IsoOverlay";
    private static final String OPT_DIR = "dydock-iso";

    /** LSPosed 在本进程里告诉我们的模块 APK 路径（拿不到时再走别的兜底）。 */
    private static volatile String modulePathHint;
    /** 类加载器建一次就够了：每个 Activity 都 new 一个 DexClassLoader 又慢又费内存。 */
    private static volatile Class<?> isoClass;
    /** 隔离加载一旦确实不可用，就别在每次重试里再撞一遍。 */
    private static volatile boolean unavailable;

    final View view;

    private final Method setGeometryMethod;
    private final Method setBackdropMethod;
    private final Method setTabsMethod;
    private final Method isBrokenMethod;
    private final Method hasDrawnMethod;
    private final Method disposeMethod;

    private boolean disposed;

    private IsoDock(View view, Class<?> iso) throws NoSuchMethodException {
        this.view = view;
        this.setGeometryMethod = iso.getMethod("setGeometry", Rect.class, Rect.class, Rect.class);
        this.setBackdropMethod = iso.getMethod("setBackdrop", Bitmap.class);
        this.setTabsMethod = iso.getMethod("setTabs", String[].class, Bitmap[].class, int.class);
        this.isBrokenMethod = iso.getMethod("isBroken");
        this.hasDrawnMethod = iso.getMethod("hasDrawn");
        this.disposeMethod = iso.getMethod("dispose");
    }

    static void setModulePath(String path) {
        if (path != null && !path.isEmpty()) modulePathHint = path;
    }

    /**
     * 建一个在隔离类加载器里跑的玻璃层。任何一步失败都返回 null，
     * 由调用方保留抖音原生底栏，绝不在宿主里硬塞一套可能撞版本的 Compose。
     */
    static IsoDock create(Context context, Consumer<String> log, IntConsumer onTab, Runnable onPlus) {
        if (context == null || unavailable) return null;
        String apk = moduleApk(context);
        if (apk == null) {
            log.accept("找不到模块 APK 路径，无法启动隔离渲染");
            return null;
        }
        try {
            File opt = new File(context.getCodeCacheDir(), OPT_DIR);
            if (!opt.isDirectory() && !opt.mkdirs()) {
                log.accept("隔离加载目录不可写：" + opt);
            }
            // librarySearchPath 用 apk!/lib/<abi> 的形式，Compose 需要的
            // libandroidx.graphics.path.so 才能从模块 APK 里直接找到。
            Class<?> iso = isoClass;
            if (iso == null) {
                String libraryPath = apk + "!/lib/" + primaryAbi();
                DexClassLoader loader = new DexClassLoader(
                        apk, opt.getAbsolutePath(), libraryPath, bootParent());
                iso = Class.forName(ISO_CLASS, true, loader);
                isoClass = iso;
            }
            Method create = iso.getMethod("create", Context.class,
                    Consumer.class, IntConsumer.class, Runnable.class);
            Object overlay = create.invoke(null, context, log, onTab, onPlus);
            if (!(overlay instanceof View)) {
                log.accept("隔离类没有返回 View：" + overlay);
                return null;
            }
            log.accept("已用独立类加载器启动玻璃层（宿主 androidx 不再参与）");
            return new IsoDock((View) overlay, iso);
        } catch (Throwable t) {
            unavailable = true;
            log.accept("隔离加载失败：" + t);
            return null;
        }
    }

    void setGeometry(Rect region, Rect tabs, Rect plus) {
        invoke(setGeometryMethod, "setGeometry", region, tabs, plus);
    }

    void setBackdrop(Bitmap bitmap) {
        invoke(setBackdropMethod, "setBackdrop", bitmap);
    }

    void setTabs(String[] labels, Bitmap[] icons, int selected) {
        invoke(setTabsMethod, "setTabs", labels, icons, selected);
    }

    boolean isBroken() {
        Object value = invoke(isBrokenMethod, "isBroken");
        return value instanceof Boolean && (Boolean) value;
    }

    boolean hasDrawn() {
        Object value = invoke(hasDrawnMethod, "hasDrawn");
        return value instanceof Boolean && (Boolean) value;
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        invoke(disposeMethod, "dispose");
    }

    private Object invoke(Method method, String what, Object... args) {
        if (method == null || disposed && !"dispose".equals(what)) return null;
        try {
            return method.invoke(view, args);
        } catch (Throwable t) {
            Dydock.warnOnce("iso-" + what, "玻璃层调用 " + what + " 失败: " + t);
            return null;
        }
    }

    /** 模块 APK 在哪：先问 LSPosed，再看类来源，最后问包管理器。 */
    private static String moduleApk(Context context) {
        String hint = modulePathHint;
        if (isFile(hint)) return hint;

        try {
            CodeSource source = Dydock.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = Uri.decode(source.getLocation().getPath());
                if (isFile(path)) return path;
            }
        } catch (Throwable ignored) {
        }

        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(Dydock.PKG, 0);
            if (info != null && isFile(info.sourceDir)) return info.sourceDir;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isFile(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            return file.isFile() && file.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 父加载器必须是启动类加载器：中间不能有宿主自己的类。 */
    private static ClassLoader bootParent() {
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (system == null) return null;
        ClassLoader parent = system.getParent();
        return parent != null ? parent : system;
    }

    private static String primaryAbi() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 && abis[0] != null ? abis[0] : "arm64-v8a";
    }
}
