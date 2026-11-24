package com.zaka.douyin.glass.iso

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
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
import com.zaka.douyin.glass.Dydock
import com.zaka.douyin.glass.SettingsScreen
import com.zaka.douyin.glass.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 抖音当前 Activity 上的设置弹层。
 * Compose 由模块 APK 的隔离类加载器运行；Dialog 不创建新 Activity，
 * 窗口背景透明且不设置 dim，底下抖音页面保持原亮度。
 */
class IsoSettingsOverlay private constructor(activity: Activity) : Dialog(activity) {
    private val host = SettingsDialogHost(activity)

    init {
        val state = SettingsState(activity)
        host.compose { SettingsScreen(state, Dydock.VER, { dismiss() }, overlay = true) }
        setContentView(host)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { host.destroyOwner() }
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
            w.setGravity(Gravity.CENTER)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.decorView.setPadding(0, 0, 0, 0)
        }
    }

    companion object {
        @JvmStatic
        fun show(activity: Activity) {
            if (activity.isFinishing || activity.isDestroyed) return
            IsoSettingsOverlay(activity).show()
        }
    }
}

private class SettingsDialogHost(context: Context) : FrameLayout(context),
    LifecycleOwner, SavedStateRegistryOwner {
    private val inner = ComposeView(context)
    private val registry = LifecycleRegistry(this)
    private val savedController = SavedStateRegistryController.create(this)
    private val recomposer = Recomposer(AndroidUiDispatcher.Main)
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + Job())

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    init {
        setBackgroundColor(Color.TRANSPARENT)
        savedController.performAttach()
        savedController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        inner.setViewTreeLifecycleOwner(this)
        inner.setViewTreeSavedStateRegistryOwner(this)
        inner.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        try {
            val field = androidx.compose.ui.platform.AbstractComposeView::class.java
                .getDeclaredField("parentContext")
            field.isAccessible = true
            field.set(inner, recomposer)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
        } catch (t: Throwable) {
            Dydock.warnOnce("settings-recomposer", "设置面板 Recomposer 初始化失败: $t")
            try { recomposer.cancel() } catch (_: Throwable) {}
            try { scope.cancel() } catch (_: Throwable) {}
        }
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun compose(content: @androidx.compose.runtime.Composable () -> Unit) {
        inner.setContent { content() }
    }

    fun destroyOwner() {
        try { inner.disposeComposition() } catch (_: Throwable) {}
        try { recomposer.cancel() } catch (_: Throwable) {}
        try { scope.cancel() } catch (_: Throwable) {}
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
