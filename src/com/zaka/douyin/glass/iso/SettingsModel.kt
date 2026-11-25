package com.zaka.douyin.glass.iso

import androidx.compose.runtime.MutableState

/**
 * SettingsScreen 与配置存储之间的窄接口。
 * 让隔离渲染器不必加载带有 Xposed API 引用的宿主配置类。
 */
internal data class LogSession(
    val fileName: String,
    val title: String,
    val subtitle: String,
)

internal interface SettingsModel {
    val logSessions: MutableState<List<LogSession>>
    val selectedLog: MutableState<LogSession?>
    val selectedLogContent: MutableState<String>
    fun refreshLogs()
    fun viewLog(fileName: String)
    fun closeLogViewer()
    fun exportLog(fileName: String)
    fun deleteLog(fileName: String)
    fun clearLogs()

    val enabled: MutableState<Boolean>
    val showPlus: MutableState<Boolean>
    val showLabels: MutableState<Boolean>
    val logEnabled: MutableState<Boolean>
    val themeMode: MutableState<Int>
    val glassAlpha: MutableState<Int>
    val hideBackground: MutableState<Boolean>
    val height: MutableState<Int>
    val tabWidth: MutableState<Int>
    val sideMargin: MutableState<Int>
    val bottomGap: MutableState<Int>

    fun setEnabled(value: Boolean)
    fun setShowPlus(value: Boolean)
    fun setShowLabels(value: Boolean)
    fun setLogEnabled(value: Boolean)
    fun setThemeMode(value: Int)
    fun setGlassAlpha(value: Int)
    fun setHideBackground(value: Boolean)
    fun setHeight(value: Int)
    fun setTabWidth(value: Int)
    fun setSideMargin(value: Int)
    fun setBottomGap(value: Int)
    /** Export the complete host diagnostic log to a user-accessible file. */
    fun exportLogs()
    fun reset()
}
