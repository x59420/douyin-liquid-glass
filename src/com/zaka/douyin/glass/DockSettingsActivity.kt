package com.zaka.douyin.glass

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * dydock 设置界面（模块进程里的整屏页面）。
 *
 * 从抖音「我的」菜单里点 dydock 那一项进来（也可以从模块自己的桌面图标 / LSPosed 里进）。
 * 界面是 Compose + 液态玻璃卡片，见 SettingsScreen.kt。
 *
 * 这个 Activity 是普通 Activity，不带 androidx.activity；所以这里自己提供
 * ViewTreeLifecycleOwner / SavedStateRegistryOwner，并且像底栏那一层一样自带一个
 * Recomposer（拿不到就回退到宿主 lifecycle），保证一定能渲染出来。
 */
class DockSettingsActivity : Activity() {

    private lateinit var host: SettingsHost
    private lateinit var state: SettingsState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = SettingsState(this)
        host = SettingsHost(this)
        host.compose { SettingsScreen(state, Dydock.VER, onBack = { finish() }) }
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#070A12")))
        setContentView(host)
        applyEdgeToEdge()
    }

    private fun applyEdgeToEdge() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setStatusBarColor(Color.TRANSPARENT)
                window.setNavigationBarColor(Color.TRANSPARENT)
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.TRANSPARENT
            }
        } catch (_: Throwable) {
        }
        // 顶部让出状态栏高度：不引 androidx 的 insets 兼容层，直接量系统资源。
        val res = resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (id > 0) res.getDimensionPixelSize(id) else 0
        host.setPadding(host.paddingLeft, top, host.paddingRight, host.paddingBottom)
    }

    override fun onDestroy() {
        host.destroyOwner()
        super.onDestroy()
    }
}

/**
 * ComposeView 的宿主：提供 Lifecycle / SavedStateRegistry，并自带 Recomposer。
 *
 * 跟 IsoOverlay 里那套一样：不依赖抖音（这里是模块自己）的 DecorView 有没有挂 owner，
 * 自己给 ComposeView 一个 parentContext；反射失败就退回 ViewTree 上的 owner。
 */
private class SettingsHost(context: Context) : FrameLayout(context),
    LifecycleOwner, SavedStateRegistryOwner {

    val inner = ComposeView(context)

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val recomposer = Recomposer(AndroidUiDispatcher.Main)
    private val recomposerScope = CoroutineScope(AndroidUiDispatcher.Main + Job())

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        setBackgroundColor(Color.TRANSPARENT)
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        inner.setViewTreeLifecycleOwner(this)
        inner.setViewTreeSavedStateRegistryOwner(this)
        inner.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        attachRecomposer()
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun compose(content: @androidx.compose.runtime.Composable () -> Unit) {
        inner.setContent { content() }
    }

    private fun attachRecomposer() {
        try {
            val field = androidx.compose.ui.platform.AbstractComposeView::class.java
                .getDeclaredField("parentContext")
            field.isAccessible = true
            field.set(inner, recomposer)
            recomposerScope.launch { recomposer.runRecomposeAndApplyChanges() }
        } catch (_: Throwable) {
            // 反射不通就走 ViewTree 上的 lifecycle（上面已经挂好了）。
            try {
                recomposerScope.cancel()
                recomposer.cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun destroyOwner() {
        try {
            inner.disposeComposition()
        } catch (_: Throwable) {
        }
        try {
            recomposer.cancel()
        } catch (_: Throwable) {
        }
        try {
            recomposerScope.cancel()
        } catch (_: Throwable) {
        }
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override fun dispatchDraw(canvas: Canvas) {
        try {
            super.dispatchDraw(canvas)
        } catch (_: Throwable) {
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        visibility = View.VISIBLE
    }
}
