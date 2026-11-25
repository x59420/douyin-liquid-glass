package com.zaka.douyin.glass.iso

import androidx.compose.runtime.MutableState

/**
 * SettingsScreen 与配置存储之间的窄接口。
 * 让隔离渲染器不必加载带有 Xposed API 引用的宿主配置类。
 */
internal interface SettingsModel {
    val enabled: MutableState<Boolean>
    val showPlus: MutableState<Boolean>
    val showLabels: MutableState<Boolean>
    val logEnabled: MutableState<Boolean>
    val themeMode: MutableState<Int>
    val height: MutableState<Int>
    val tabWidth: MutableState<Int>
    val sideMargin: MutableState<Int>
    val bottomGap: MutableState<Int>
    val nativeTopEnabled: MutableState<Boolean>
    val nativeTopAlpha: MutableState<Int>
    val nativeControlsEnabled: MutableState<Boolean>
    val nativeControlsAlpha: MutableState<Int>
    val nativeBottomEnabled: MutableState<Boolean>
    val nativeBottomAlpha: MutableState<Int>
    val homeTopTransparent: MutableState<Boolean>
    val homeBottomTransparent: MutableState<Boolean>
    val navigationTransparent: MutableState<Boolean>
    val hideNavigationBar: MutableState<Boolean>
    val hideStatusBar: MutableState<Boolean>
    val followNativeDockVisibility: MutableState<Boolean>

    fun setEnabled(value: Boolean)
    fun setShowPlus(value: Boolean)
    fun setShowLabels(value: Boolean)
    fun setLogEnabled(value: Boolean)
    fun setThemeMode(value: Int)
    fun setHeight(value: Int)
    fun setTabWidth(value: Int)
    fun setSideMargin(value: Int)
    fun setBottomGap(value: Int)
    fun setNativeTopEnabled(value: Boolean)
    fun setNativeTopAlpha(value: Int)
    fun setNativeControlsEnabled(value: Boolean)
    fun setNativeControlsAlpha(value: Int)
    fun setNativeBottomEnabled(value: Boolean)
    fun setNativeBottomAlpha(value: Int)
    fun setHomeTopTransparent(value: Boolean)
    fun setHomeBottomTransparent(value: Boolean)
    fun setNavigationTransparent(value: Boolean)
    fun setHideNavigationBar(value: Boolean)
    fun setHideStatusBar(value: Boolean)
    fun setFollowNativeDockVisibility(value: Boolean)
    fun reset()
}
