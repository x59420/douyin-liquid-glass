package com.zaka.douyin.glass.iso

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources

/**
 * 给玻璃层换一份「属于模块自己」的 Resources。
 *
 * 背景（0.5.6 真机闪退，堆栈在 Download/Dydock/dydock-diagnostic.log）：
 *
 *   Resources$NotFoundException: String resource ID #0x7f09001c
 *     at android.content.res.Resources.getText
 *     at ...AccessibilityNodeProviderCompat.createAccessibilityNodeInfo
 *     at AccessibilityInteractionController...prefetchDescendantsOfVirtualNode
 *
 * 原因不是 Compose 或 Miuix 写错了，是**资源表认错了包**：
 * 玻璃层的 ComposeView 建的时候拿的是抖音的 Context，`context.resources` 就是抖音的
 * Resources。模块和抖音的 resource package id 都是 0x7f，模块里某个字符串资源
 * （0x7f09001c）在抖音的表里同样「存在」，只是那一项不是字符串 —— 于是只要有一次
 * 真的去取它（这里是无障碍服务在预取虚拟节点的 contentDescription），
 * 就是 NotFoundException 抛在主线程，抖音直接闪退。
 *
 * 修法：用模块 APK 单独建一份 AssetManager / Resources，包在 ContextWrapper 里交给
 * 玻璃层的 ComposeView。之后 0x7f09001c 在模块自己的表里查到的是模块自己的字符串，
 * 框架资源（0x01xxxxxx）仍然由系统表解析，两边不再打架。
 *
 * 拿不到模块 APK 路径、或者建 Resources 失败时，原样返回宿主的 Context：
 * 宁可维持旧行为（可能仍然闪退），也不让玻璃层因为这一步直接起不来。
 */
internal object GlassEnv {

    /** 模块 APK 的绝对路径，由宿主侧的 IsoDock 反射塞进来。 */
    @Volatile
    var apkPath: String? = null

    @Volatile
    private var moduleResources: Resources? = null

    @Volatile
    private var failed = false

    fun wrap(base: Context): Context {
        val res = resources(base.resources) ?: return base
        return GlassContextWrapper(base, res)
    }

    private fun resources(base: Resources): Resources? {
        moduleResources?.let { return it }
        if (failed) return null
        synchronized(this) {
            moduleResources?.let { return it }
            if (failed) return null
            val apk = apkPath
            if (apk.isNullOrEmpty()) {
                failed = true
                return null
            }
            val built = try {
                build(apk, base)
            } catch (t: Throwable) {
                null
            }
            if (built == null) {
                failed = true
                return null
            }
            moduleResources = built
            return built
        }
    }

    private fun build(apk: String, base: Resources): Resources? {
        val manager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(manager, apk) as? Int ?: 0
        if (cookie == 0) return null
        return Resources(manager, base.displayMetrics, base.configuration)
    }
}

/**
 * 只换 resources / assets，其余（theme、window service、配置）都还走宿主。
 *
 * 主题故意不重做：Compose 和 Miuix 这一层用的颜色全部来自代码里的 ThemeController，
 * 不读宿主主题属性；硬造一份 Theme 反而会让框架属性的解析多一层不确定。
 */
private class GlassContextWrapper(
    base: Context,
    private val res: Resources
) : ContextWrapper(base) {

    override fun getResources(): Resources = res

    override fun getAssets(): AssetManager = res.assets
}
