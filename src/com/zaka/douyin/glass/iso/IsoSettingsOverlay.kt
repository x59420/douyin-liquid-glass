package com.zaka.douyin.glass.iso

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
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
import com.zaka.douyin.glass.SettingsScreen
import com.zaka.douyin.glass.iso.SettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 抖音当前 Activity 上的设置弹层。
 * Compose 由模块 APK 的隔离类加载器运行；Dialog 不创建新 Activity，
 * 窗口背景透明且不设置 dim，底下抖音页面保持原亮度。
 */
class IsoSettingsOverlay private constructor(activity: Activity, version: String) : Dialog(activity) {
    private val host = SettingsDialogHost(activity)

    init {
        val state = IsoSettingsState(activity)
        host.compose { SettingsScreen(state, version, { dismiss() }, overlay = true) }
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
        fun show(activity: Activity, version: String) {
            if (activity.isFinishing || activity.isDestroyed) return
            IsoSettingsOverlay(activity, version).show()
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
            android.util.Log.w("dydock", "设置面板 Recomposer 初始化失败", t)
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

/**
 * 隔离类加载器中的轻量配置模型。
 * 只依赖 Android SharedPreferences / Intent，不加载宿主的 Xposed 配置类。
 */
private class IsoSettingsState(context: Context) : SettingsModel {
    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("dydock_settings", Context.MODE_PRIVATE)

    override val enabled = mutableStateOf(prefs.getBoolean("enabled", true))
    override val showPlus = mutableStateOf(prefs.getBoolean("show_plus", true))
    override val showLabels = mutableStateOf(prefs.getBoolean("show_labels", true))
    override val logEnabled = mutableStateOf(prefs.getBoolean("verbose_log", true))
    override val themeMode = mutableStateOf(prefs.getInt("theme_mode", 0).coerceIn(0, 2))
    override val height = mutableStateOf(prefs.getInt("dock_height_dp", 64).coerceIn(52, 88))
    override val tabWidth = mutableStateOf(prefs.getInt("tab_width_dp", 76).coerceIn(60, 100))
    override val sideMargin = mutableStateOf(prefs.getInt("side_margin_dp", 24).coerceIn(12, 40))
    override val bottomGap = mutableStateOf(prefs.getInt("bottom_gap_dp", 8).coerceIn(0, 28))
    override val nativeTopEnabled = mutableStateOf(prefs.getBoolean("native_top_transparency_enabled", false))
    override val nativeTopPlayAlpha = mutableStateOf(prefs.getInt("native_top_play_alpha", 100).coerceIn(0, 100))
    override val nativeTopPauseAlpha = mutableStateOf(prefs.getInt("native_top_pause_alpha", 100).coerceIn(0, 100))
    override val nativeControlsEnabled = mutableStateOf(prefs.getBoolean("native_controls_transparency_enabled", false))
    override val nativeControlsPlayAlpha = mutableStateOf(prefs.getInt("native_controls_play_alpha", 100).coerceIn(0, 100))
    override val nativeControlsPauseAlpha = mutableStateOf(prefs.getInt("native_controls_pause_alpha", 100).coerceIn(0, 100))
    override val nativeBottomEnabled = mutableStateOf(prefs.getBoolean("native_bottom_transparency_enabled", false))
    override val nativeBottomPlayAlpha = mutableStateOf(prefs.getInt("native_bottom_play_alpha", 100).coerceIn(0, 100))
    override val nativeBottomPauseAlpha = mutableStateOf(prefs.getInt("native_bottom_pause_alpha", 100).coerceIn(0, 100))
    override val homeTopTransparent = mutableStateOf(prefs.getBoolean("home_top_transparent_enabled", false))
    override val homeBottomTransparent = mutableStateOf(prefs.getBoolean("home_bottom_transparent_enabled", false))
    override val navigationTransparent = mutableStateOf(prefs.getBoolean("navigation_bar_transparent_enabled", true))
    override val hideNavigationBar = mutableStateOf(prefs.getBoolean("hide_navigation_bar_enabled", false))
    override val hideStatusBar = mutableStateOf(prefs.getBoolean("hide_status_bar_enabled", false))

    override fun setEnabled(value: Boolean) { enabled.value = value; persistAndPush() }
    override fun setShowPlus(value: Boolean) { showPlus.value = value; persistAndPush() }
    override fun setShowLabels(value: Boolean) { showLabels.value = value; persistAndPush() }
    override fun setLogEnabled(value: Boolean) { logEnabled.value = value; persistAndPush() }
    override fun setHeight(value: Int) { height.value = value.coerceIn(52, 88); persistAndPush() }
    override fun setTabWidth(value: Int) { tabWidth.value = value.coerceIn(60, 100); persistAndPush() }
    override fun setSideMargin(value: Int) { sideMargin.value = value.coerceIn(12, 40); persistAndPush() }
    override fun setBottomGap(value: Int) { bottomGap.value = value.coerceIn(0, 28); persistAndPush() }
    override fun setNativeTopEnabled(value: Boolean) { nativeTopEnabled.value = value; persistAndPush() }
    override fun setNativeTopPlayAlpha(value: Int) { nativeTopPlayAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setNativeTopPauseAlpha(value: Int) { nativeTopPauseAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setNativeControlsEnabled(value: Boolean) { nativeControlsEnabled.value = value; persistAndPush() }
    override fun setNativeControlsPlayAlpha(value: Int) { nativeControlsPlayAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setNativeControlsPauseAlpha(value: Int) { nativeControlsPauseAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setNativeBottomEnabled(value: Boolean) { nativeBottomEnabled.value = value; persistAndPush() }
    override fun setNativeBottomPlayAlpha(value: Int) { nativeBottomPlayAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setNativeBottomPauseAlpha(value: Int) { nativeBottomPauseAlpha.value = value.coerceIn(0, 100); persistAndPush() }
    override fun setHomeTopTransparent(value: Boolean) { homeTopTransparent.value = value; persistAndPush() }
    override fun setHomeBottomTransparent(value: Boolean) { homeBottomTransparent.value = value; persistAndPush() }
    override fun setNavigationTransparent(value: Boolean) { navigationTransparent.value = value; persistAndPush() }
    override fun setHideNavigationBar(value: Boolean) { hideNavigationBar.value = value; persistAndPush() }
    override fun setHideStatusBar(value: Boolean) { hideStatusBar.value = value; persistAndPush() }

    override fun setThemeMode(value: Int) {
        themeMode.value = value.coerceIn(0, 2)
        prefs.edit().putInt("theme_mode", themeMode.value)
            .putLong("settings_rev", System.currentTimeMillis()).commit()
    }

    override fun reset() {
        enabled.value = true
        showPlus.value = true
        showLabels.value = true
        height.value = 64
        tabWidth.value = 76
        sideMargin.value = 24
        bottomGap.value = 8
        nativeTopEnabled.value = false
        nativeTopPlayAlpha.value = 100
        nativeTopPauseAlpha.value = 100
        nativeControlsEnabled.value = false
        nativeControlsPlayAlpha.value = 100
        nativeControlsPauseAlpha.value = 100
        nativeBottomEnabled.value = false
        nativeBottomPlayAlpha.value = 100
        nativeBottomPauseAlpha.value = 100
        homeTopTransparent.value = false
        homeBottomTransparent.value = false
        navigationTransparent.value = true
        hideNavigationBar.value = false
        hideStatusBar.value = false
        persistAndPush()
    }

    private fun persistAndPush() {
        prefs.edit()
            .putBoolean("enabled", enabled.value)
            .putBoolean("show_plus", showPlus.value)
            .putBoolean("show_labels", showLabels.value)
            .putBoolean("verbose_log", logEnabled.value)
            .putInt("dock_height_dp", height.value)
            .putInt("tab_width_dp", tabWidth.value)
            .putInt("side_margin_dp", sideMargin.value)
            .putInt("bottom_gap_dp", bottomGap.value)
            .putInt("theme_mode", themeMode.value)
            .putBoolean("native_top_transparency_enabled", nativeTopEnabled.value)
            .putInt("native_top_play_alpha", nativeTopPlayAlpha.value)
            .putInt("native_top_pause_alpha", nativeTopPauseAlpha.value)
            .putBoolean("native_controls_transparency_enabled", nativeControlsEnabled.value)
            .putInt("native_controls_play_alpha", nativeControlsPlayAlpha.value)
            .putInt("native_controls_pause_alpha", nativeControlsPauseAlpha.value)
            .putBoolean("native_bottom_transparency_enabled", nativeBottomEnabled.value)
            .putInt("native_bottom_play_alpha", nativeBottomPlayAlpha.value)
            .putInt("native_bottom_pause_alpha", nativeBottomPauseAlpha.value)
            .putBoolean("home_top_transparent_enabled", homeTopTransparent.value)
            .putBoolean("home_bottom_transparent_enabled", homeBottomTransparent.value)
            .putBoolean("navigation_bar_transparent_enabled", navigationTransparent.value)
            .putBoolean("hide_navigation_bar_enabled", hideNavigationBar.value)
            .putBoolean("hide_status_bar_enabled", hideStatusBar.value)
            .putLong("settings_rev", System.currentTimeMillis())
            .commit()

        val intent = Intent("com.zaka.douyin.glass.SETTINGS_CHANGED")
            .setPackage("com.ss.android.ugc.aweme")
            .putExtra("enabled", enabled.value)
            .putExtra("show_plus", showPlus.value)
            .putExtra("show_labels", showLabels.value)
            .putExtra("verbose_log", logEnabled.value)
            .putExtra("height", height.value)
            .putExtra("tab_width", tabWidth.value)
            .putExtra("side_margin", sideMargin.value)
            .putExtra("bottom_gap", bottomGap.value)
            .putExtra("native_top_enabled", nativeTopEnabled.value)
            .putExtra("native_top_play_alpha", nativeTopPlayAlpha.value)
            .putExtra("native_top_pause_alpha", nativeTopPauseAlpha.value)
            .putExtra("native_controls_enabled", nativeControlsEnabled.value)
            .putExtra("native_controls_play_alpha", nativeControlsPlayAlpha.value)
            .putExtra("native_controls_pause_alpha", nativeControlsPauseAlpha.value)
            .putExtra("native_bottom_enabled", nativeBottomEnabled.value)
            .putExtra("native_bottom_play_alpha", nativeBottomPlayAlpha.value)
            .putExtra("native_bottom_pause_alpha", nativeBottomPauseAlpha.value)
            .putExtra("home_top_transparent", homeTopTransparent.value)
            .putExtra("home_bottom_transparent", homeBottomTransparent.value)
            .putExtra("navigation_transparent", navigationTransparent.value)
            .putExtra("hide_navigation_bar", hideNavigationBar.value)
            .putExtra("hide_status_bar", hideStatusBar.value)
        app.sendBroadcast(intent)
    }
}


