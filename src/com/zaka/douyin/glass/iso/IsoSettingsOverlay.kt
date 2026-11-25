package com.zaka.douyin.glass.iso

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.zaka.douyin.glass.iso.LogSession
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
    override val glassAlpha = mutableStateOf(prefs.getInt("glass_alpha", 100).coerceIn(0, 100))
    override val hideBackground = mutableStateOf(prefs.getBoolean("hide_glass_background", false))
    override val height = mutableStateOf(prefs.getInt("dock_height_dp", 64).coerceIn(52, 88))
    override val tabWidth = mutableStateOf(prefs.getInt("tab_width_dp", 76).coerceIn(60, 100))
    override val sideMargin = mutableStateOf(prefs.getInt("side_margin_dp", 24).coerceIn(12, 40))
    override val bottomGap = mutableStateOf(prefs.getInt("bottom_gap_dp", 8).coerceIn(0, 28))

    override val logSessions = mutableStateOf<List<LogSession>>(emptyList())
    override val selectedLog = mutableStateOf<LogSession?>(null)
    override val selectedLogContent = mutableStateOf("")

    init { refreshLogs() }

    override fun setEnabled(value: Boolean) { enabled.value = value; persistAndPush() }
    override fun setShowPlus(value: Boolean) { showPlus.value = value; persistAndPush() }
    override fun setShowLabels(value: Boolean) { showLabels.value = value; persistAndPush() }
    override fun setLogEnabled(value: Boolean) { logEnabled.value = value; persistAndPush() }

    override fun refreshLogs() {
        val directory = sessionDirectory()
        val files = directory?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        logSessions.value = files.map { file ->
            val marker = File(directory, file.name.removeSuffix(".log") + ".active")
            val header = try { file.bufferedReader().use { it.readLine().orEmpty() } }
                catch (_: Throwable) { "" }
            val state = when {
                marker.exists() -> "正在记录"
                header.contains("SESSION END: UNKNOWN") || file.readText().contains("SESSION END: UNKNOWN") ->
                    "结束时间未知（下次启动时封存）"
                else -> "已结束"
            }
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
            LogSession(file.name, date, "$state · \${formatSize(file.length())}")
        }
    }

    override fun viewLog(fileName: String) {
        val file = sessionFile(fileName)
        if (file == null || !file.isFile) {
            refreshLogs()
            Toast.makeText(app, "这段日志不存在或已被清除", Toast.LENGTH_LONG).show()
            return
        }
        selectedLog.value = logSessions.value.firstOrNull { it.fileName == fileName }
            ?: LogSession(fileName, file.name, formatSize(file.length()))
        selectedLogContent.value = try { file.readText(Charsets.UTF_8) }
            catch (error: Throwable) { "读取日志失败：\${error.message}" }
    }

    override fun closeLogViewer() {
        selectedLog.value = null
        selectedLogContent.value = ""
    }

    override fun exportLog(fileName: String) {
        val context = app
        Thread({
            var destination: Uri? = null
            var message: String
            try {
                val source = sessionFile(fileName)
                    ?: throw IllegalStateException("找不到这段日志")
                if (!source.isFile) throw IllegalStateException("这段日志已被清除")
                val exportName = "dydock-" + source.name
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dydock/")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    destination = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("无法在下载目录创建文件")
                    context.contentResolver.openOutputStream(destination!!, "w")?.use { output ->
                        FileInputStream(source).use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法写入导出文件")
                    context.contentResolver.update(destination!!,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    message = "已导出到 Download/Dydock/$exportName"
                } else {
                    val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?: context.filesDir
                    val output = File(directory, exportName)
                    FileInputStream(source).use { input ->
                        FileOutputStream(output).use { input.copyTo(it) }
                    }
                    message = "日志已导出：\${output.absolutePath}"
                }
            } catch (error: Throwable) {
                try { destination?.let { context.contentResolver.delete(it, null, null) } }
                catch (_: Throwable) { }
                message = "导出失败：\${error.message ?: error.javaClass.simpleName}"
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }, "dydock-session-export").start()
    }

    override fun deleteLog(fileName: String) {
        val file = sessionFile(fileName) ?: return
        val deleted = (!file.exists() || file.delete())
        File(file.parentFile, file.nameWithoutExtension + ".active").delete()
        if (selectedLog.value?.fileName == fileName) closeLogViewer()
        refreshLogs()
        Toast.makeText(app, if (deleted) "已删除这段日志" else "删除失败", Toast.LENGTH_SHORT).show()
    }

    override fun clearLogs() {
        val directory = sessionDirectory()
        val files = directory?.listFiles().orEmpty()
        var failed = 0
        files.filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".active")) }
            .forEach { if (it.exists() && !it.delete()) failed++ }
        closeLogViewer()
        refreshLogs()
        Toast.makeText(app, if (failed == 0) "历史日志已清除" else "有 $failed 个文件未能删除",
            Toast.LENGTH_LONG).show()
    }

    override fun exportLogs() {
        val latest = logSessions.value.firstOrNull()
        if (latest == null) {
            Toast.makeText(app, "目前还没有运行会话日志", Toast.LENGTH_SHORT).show()
        } else exportLog(latest.fileName)
    }

    private fun sessionDirectory(): File? {
        val documents = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
        return File(documents, "dydock-sessions")
    }

    private fun sessionFile(fileName: String): File? {
        if (fileName.contains("/") || fileName.contains("\\")) return null
        return sessionDirectory()?.let { File(it, fileName) }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)

    override fun setHeight(value: Int) { height.value = value.coerceIn(52, 88); persistAndPush() }
    override fun setTabWidth(value: Int) { tabWidth.value = value.coerceIn(60, 100); persistAndPush() }
    override fun setSideMargin(value: Int) { sideMargin.value = value.coerceIn(12, 40); persistAndPush() }
    override fun setBottomGap(value: Int) { bottomGap.value = value.coerceIn(0, 28); persistAndPush() }

    override fun setThemeMode(value: Int) {
        themeMode.value = value.coerceIn(0, 2)
        persistAndPush()
    }

    override fun setGlassAlpha(value: Int) {
        glassAlpha.value = value.coerceIn(0, 100)
        persistAndPush()
    }

    override fun setHideBackground(value: Boolean) {
        hideBackground.value = value
        persistAndPush()
    }

    override fun reset() {
        enabled.value = true
        showPlus.value = true
        showLabels.value = true
        height.value = 64
        tabWidth.value = 76
        sideMargin.value = 24
        bottomGap.value = 8
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
            .putInt("glass_alpha", glassAlpha.value)
            .putBoolean("hide_glass_background", hideBackground.value)
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
            .putExtra("theme_mode", themeMode.value)
            .putExtra("glass_alpha", glassAlpha.value)
            .putExtra("hide_glass_background", hideBackground.value)
        app.sendBroadcast(intent)
    }
}


