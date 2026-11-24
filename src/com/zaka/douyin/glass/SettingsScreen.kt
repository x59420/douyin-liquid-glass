package com.zaka.douyin.glass

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.zaka.douyin.glass.ksu.liquid.vibrancy
import com.zaka.douyin.glass.iso.SettingsModel

/**
 * dydock 设置界面。
 *
 * 跑在模块自己的进程里（不是抖音进程），写的还是模块包名下的那份配置；
 * 抖音那边每秒同步一次，改完回到抖音一秒内就作用到 Dock 上。
 *
 * 视觉跟底栏同一套：整屏背景先记进一个 backdrop 图层，卡片用 drawBackdrop 去采样
 * （模糊 + vibrancy + 一层很淡的表面色）。没有 RuntimeShader 的机器（API < 33）
 * 自动退回半透明卡片，不会出现「卡片里什么都没有」。
 */
internal class SettingsState(context: Context) : SettingsModel {

    private val ctx = context.applicationContext

    override val enabled = mutableStateOf(DockPrefs.enabled(ctx))
    override val showPlus = mutableStateOf(DockPrefs.showPlusButton(ctx))
    override val showLabels = mutableStateOf(DockPrefs.showLabels(ctx))
    override val logEnabled = mutableStateOf(DockPrefs.logEnabled(ctx))
    override val themeMode = mutableStateOf(DockPrefs.themeMode(ctx))

    override val height = mutableStateOf(DockPrefs.heightDp(ctx))
    override val tabWidth = mutableStateOf(DockPrefs.tabWidthDp(ctx))
    override val sideMargin = mutableStateOf(DockPrefs.sideMarginDp(ctx))
    override val bottomGap = mutableStateOf(DockPrefs.bottomGapDp(ctx))
    override val nativeTopEnabled = mutableStateOf(DockPrefs.nativeTopEnabled(ctx))
    override val nativeTopPlayAlpha = mutableStateOf(DockPrefs.nativeTopPlayAlpha(ctx))
    override val nativeTopPauseAlpha = mutableStateOf(DockPrefs.nativeTopPauseAlpha(ctx))
    override val nativeControlsEnabled = mutableStateOf(DockPrefs.nativeControlsEnabled(ctx))
    override val nativeControlsPlayAlpha = mutableStateOf(DockPrefs.nativeControlsPlayAlpha(ctx))
    override val nativeControlsPauseAlpha = mutableStateOf(DockPrefs.nativeControlsPauseAlpha(ctx))

    override fun setEnabled(value: Boolean) {
        enabled.value = value
        DockPrefs.setEnabled(ctx, value)
        push()
    }

    override fun setShowPlus(value: Boolean) {
        showPlus.value = value
        DockPrefs.setShowPlusButton(ctx, value)
        push()
    }

    override fun setShowLabels(value: Boolean) {
        showLabels.value = value
        DockPrefs.setShowLabels(ctx, value)
        push()
    }

    override fun setLogEnabled(value: Boolean) {
        logEnabled.value = value
        DockPrefs.setLogEnabled(ctx, value)
        push()
    }

    override fun setThemeMode(value: Int) {
        val mode = value.coerceIn(0, 2)
        themeMode.value = mode
        DockPrefs.setThemeMode(ctx, mode)
    }

    override fun setHeight(value: Int) {
        height.value = value
        DockPrefs.setHeightDp(ctx, value)
        push()
    }

    override fun setTabWidth(value: Int) {
        tabWidth.value = value
        DockPrefs.setTabWidthDp(ctx, value)
        push()
    }

    override fun setSideMargin(value: Int) {
        sideMargin.value = value
        DockPrefs.setSideMarginDp(ctx, value)
        push()
    }

    override fun setBottomGap(value: Int) {
        bottomGap.value = value
        DockPrefs.setBottomGapDp(ctx, value)
        push()
    }

    override fun setNativeTopEnabled(value: Boolean) {
        nativeTopEnabled.value = value
        DockPrefs.setNativeTopEnabled(ctx, value)
        push()
    }
    override fun setNativeTopPlayAlpha(value: Int) {
        nativeTopPlayAlpha.value = value.coerceIn(0, 100)
        DockPrefs.setNativeTopPlayAlpha(ctx, value)
        push()
    }
    override fun setNativeTopPauseAlpha(value: Int) {
        nativeTopPauseAlpha.value = value.coerceIn(0, 100)
        DockPrefs.setNativeTopPauseAlpha(ctx, value)
        push()
    }
    override fun setNativeControlsEnabled(value: Boolean) {
        nativeControlsEnabled.value = value
        DockPrefs.setNativeControlsEnabled(ctx, value)
        push()
    }
    override fun setNativeControlsPlayAlpha(value: Int) {
        nativeControlsPlayAlpha.value = value.coerceIn(0, 100)
        DockPrefs.setNativeControlsPlayAlpha(ctx, value)
        push()
    }
    override fun setNativeControlsPauseAlpha(value: Int) {
        nativeControlsPauseAlpha.value = value.coerceIn(0, 100)
        DockPrefs.setNativeControlsPauseAlpha(ctx, value)
        push()
    }

    /**
     * 把当前这一份配置推给抖音进程。
     *
     * 走广播（最快、不依赖文件权限）：宿主收到之后写进自己的 prefs 并立刻重排 Dock。
     * 同时 DockPrefs 那边还会把 prefs 文件设成可读，作为下次启动的兜底。
     */
    private fun push() {
        SettingsSync.push(
            ctx,
            enabled.value,
            showPlus.value,
            showLabels.value,
            logEnabled.value,
            height.value,
            tabWidth.value,
            sideMargin.value,
            bottomGap.value,
            nativeTopEnabled.value,
            nativeTopPlayAlpha.value,
            nativeTopPauseAlpha.value,
            nativeControlsEnabled.value,
            nativeControlsPlayAlpha.value,
            nativeControlsPauseAlpha.value,
        )
    }

    override fun reset() {
        setEnabled(true)
        setShowPlus(true)
        setShowLabels(true)
        setHeight(DockPrefs.DEF_HEIGHT)
        setTabWidth(DockPrefs.DEF_TAB_WIDTH)
        setSideMargin(DockPrefs.DEF_SIDE_MARGIN)
        setBottomGap(DockPrefs.DEF_BOTTOM_GAP)
        setNativeTopEnabled(false)
        setNativeTopPlayAlpha(100)
        setNativeTopPauseAlpha(100)
        setNativeControlsEnabled(false)
        setNativeControlsPlayAlpha(100)
        setNativeControlsPauseAlpha(100)
    }
}

private val GlassCardShape = RoundedCornerShape(26.dp)
private data class SettingsPalette(
    val background: Color, val panel: Color, val card: Color, val primary: Color,
    val secondary: Color, val accent: Color, val selectedText: Color,
    val trackOn: Color, val trackOff: Color, val border: Color, val divider: Color,
)
private val DarkSettingsPalette = SettingsPalette(
    Color(0xFF070A12), Color(0xF0161B25), Color(0xCC202735), Color(0xFFF7F8FA),
    Color(0xFFA6ABB4), Color(0xFFD8DDE6), Color(0xFF171A20), Color(0xFFD8DDE6), Color(0x33FFFFFF),
    Color(0x24FFFFFF), Color(0x18FFFFFF),
)
private val LightSettingsPalette = SettingsPalette(
    Color(0xFFF2F4F8), Color(0xF7FFFFFF), Color(0xDDE8ECF2), Color(0xFF171A20),
    Color(0xFF666D78), Color(0xFF424B59), Color.White, Color(0xFF424B59), Color(0x22000000),
    Color(0x1F000000), Color(0x16000000),
)
private val LocalSettingsPalette = staticCompositionLocalOf { DarkSettingsPalette }
private val LocalSettingsOverlay = staticCompositionLocalOf { false }

@Composable
private fun Label(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align ?: TextAlign.Start,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
        ),
    )
}

@Composable
internal fun SettingsScreen(
    state: SettingsModel,
    version: String,
    onBack: () -> Unit,
    overlay: Boolean = false,
) {
    val dark = when (state.themeMode.value) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkSettingsPalette else LightSettingsPalette
    CompositionLocalProvider(LocalSettingsPalette provides palette, LocalSettingsOverlay provides overlay) {
        val scene = rememberLayerBackdrop()
        Box((if (overlay) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .background(if (overlay) Color.Transparent else palette.background)) {
            if (!overlay) {
                Box(Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF17181B), Color(0xFF0D0E10))))
                    .layerBackdrop(scene))
            }
            val panel = if (overlay) {
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.86f).dp)
                    .clip(RoundedCornerShape(28.dp)).background(palette.panel)
                    .border(1.dp, palette.border, RoundedCornerShape(28.dp))
            } else Modifier.fillMaxSize()
            Column(panel.verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = if (overlay) 24.dp else 36.dp)) {
            TopBar(
                version = version,
                onBack = onBack,
                themeMode = state.themeMode.value,
                onThemeChange = state::setThemeMode,
            )
            Spacer(Modifier.height(16.dp))

            SectionTitle("外观")
            GlassCard(scene) {
                SwitchRow(
                    title = "启用玻璃 Dock",
                    subtitle = "关掉就完全用抖音原生底栏",
                    checked = state.enabled.value,
                    onChange = state::setEnabled,
                )
                Divider()
                SwitchRow(
                    title = "显示「＋」",
                    subtitle = "右边那颗独立的小圆",
                    checked = state.showPlus.value,
                    onChange = state::setShowPlus,
                )
                Divider()
                SwitchRow(
                    title = "显示文字标签",
                    subtitle = "关掉只留图标，图标会自动居中",
                    checked = state.showLabels.value,
                    onChange = state::setShowLabels,
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("尺寸")
            GlassCard(scene) {
                SliderRow("Dock 高度", state.height.value,
                    52, 88, 2, state::setHeight)
                Divider()
                SliderRow("单个图标宽度", state.tabWidth.value,
                    60, 100, 2, state::setTabWidth)
                Divider()
                SliderRow("左右留白", state.sideMargin.value,
                    12, 40, 2, state::setSideMargin)
                Divider()
                SliderRow("离底部距离", state.bottomGap.value,
                    0, 28, 1, state::setBottomGap)
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("原生界面透明度")
            GlassCard(scene) {
                SwitchRow(
                    title = "顶部原生菜单透明",
                    subtitle = "只处理抖音顶部原生控件，不影响 dyDock",
                    checked = state.nativeTopEnabled.value,
                    onChange = state::setNativeTopEnabled,
                )
                Divider()
                SliderRow("播放时顶部透明度", state.nativeTopPlayAlpha.value,
                    0, 100, 5, state::setNativeTopPlayAlpha, "%")
                Divider()
                SliderRow("暂停时顶部透明度", state.nativeTopPauseAlpha.value,
                    0, 100, 5, state::setNativeTopPauseAlpha, "%")
                Divider()
                SwitchRow(
                    title = "原生视频控件透明",
                    subtitle = "只处理视频页原生控制层，不处理底部 Dock",
                    checked = state.nativeControlsEnabled.value,
                    onChange = state::setNativeControlsEnabled,
                )
                Divider()
                SliderRow("播放时视频控件透明度", state.nativeControlsPlayAlpha.value,
                    0, 100, 5, state::setNativeControlsPlayAlpha, "%")
                Divider()
                SliderRow("暂停时视频控件透明度", state.nativeControlsPauseAlpha.value,
                    0, 100, 5, state::setNativeControlsPauseAlpha, "%")
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("调试")
            GlassCard(scene) {
                SwitchRow(
                    title = "记录日志",
                    subtitle = "开启后到 LSPosed 里搜 tag「dydock」看过程",
                    checked = state.logEnabled.value,
                    onChange = state::setLogEnabled,
                )
            }

            Spacer(Modifier.height(18.dp))
            GlassCard(scene) {
                TextRow(
                    title = "恢复默认设置",
                    subtitle = "恢复 Dock 外观、尺寸和原生透明度选项",
                    onClick = state::reset,
                )
            }

            Spacer(Modifier.height(22.dp))
            Label(
                text = "dydock $version",
                color = LocalSettingsPalette.current.secondary,
                size = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Label(
                text = "液态玻璃渲染移植自 KernelSU（Compose + Miuix），折射与色散参数与上游一致；"
                    + "模糊采样由 compose-miuix-ui 提供。本模块与抖音官方无关，仅限个人非商业用途。",
                color = LocalSettingsPalette.current.secondary.copy(alpha = 0.75f),
                size = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                align = TextAlign.Center,
                lineHeight = 16.sp,
            )
            }
        }
    }
}
@Composable
private fun TopBar(
    version: String,
    onBack: () -> Unit,
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
) {
    val palette = LocalSettingsPalette.current
    var themeMenuOpen by remember { mutableStateOf(false) }
    val menuOffset = with(LocalDensity.current) { IntOffset(0, 48.dp.roundToPx()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(17.dp)) {
                val w = size.minDimension * 0.13f
                drawLine(
                    color = palette.primary,
                    start = Offset(size.width * 0.62f, size.height * 0.12f),
                    end = Offset(size.width * 0.28f, size.height * 0.5f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = palette.primary,
                    start = Offset(size.width * 0.28f, size.height * 0.5f),
                    end = Offset(size.width * 0.62f, size.height * 0.88f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Label("dydock 设置", palette.primary, 19.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Label(version, palette.secondary, 12.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(42.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(13.dp))
                    .background(palette.card.copy(alpha = 0.72f))
                    .border(1.dp, palette.border, RoundedCornerShape(13.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { themeMenuOpen = !themeMenuOpen },
                contentAlignment = Alignment.Center,
            ) {
                Label("◐", palette.primary, 19.sp, weight = FontWeight.SemiBold)
            }
            if (themeMenuOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = menuOffset,
                    onDismissRequest = { themeMenuOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier.width(148.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.panel.copy(alpha = 0.97f))
                            .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                            .padding(6.dp)
                    ) {
                        listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                            val selected = themeMode == index
                            Box(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) palette.accent.copy(alpha = 0.88f) else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        onThemeChange(index)
                                        themeMenuOpen = false
                                    }
                                    .padding(horizontal = 13.dp, vertical = 11.dp)
                            ) {
                                Label(
                                    label,
                                    if (selected) palette.selectedText else palette.primary,
                                    13.sp,
                                    weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Label(
        text = text,
        color = LocalSettingsPalette.current.secondary,
        size = 12.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun GlassCard(backdrop: Backdrop, content: @Composable () -> Unit) {
    val shape: Shape = GlassCardShape
    val palette = LocalSettingsPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .glassSurface(backdrop, shape)
            .border(1.dp, palette.border, shape)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 有 RuntimeShader 就用真玻璃（模糊 + vibrancy），没有就退回半透明底。 */
@Composable
private fun Modifier.glassSurface(backdrop: Backdrop, shape: Shape): Modifier {
    val supported = remember { android.os.Build.VERSION.SDK_INT >= 33 }
    val palette = LocalSettingsPalette.current
    return if (LocalSettingsOverlay.current) {
        this.background(palette.card, shape)
    } else if (supported) {
        val surfaceTint = palette.border.copy(alpha = 0.35f)
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(22.dp.toPx(), 22.dp.toPx())
                vibrancy()
            },
            onDrawSurface = { drawRect(surfaceTint) },
        )
    } else {
        this.background(LocalSettingsPalette.current.card, shape)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalSettingsPalette.current.divider)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) }
            .padding(vertical = 13.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, LocalSettingsPalette.current.primary, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, LocalSettingsPalette.current.secondary, 11.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked)
    }
}

@Composable
private fun GlassSwitch(checked: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "switch",
    )
    Box(
        modifier = Modifier
            .size(50.dp, 30.dp)
            .clip(CircleShape)
            .background(lerp(LocalSettingsPalette.current.trackOff, LocalSettingsPalette.current.trackOn, progress)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = (3f + 20f * progress).dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    suffix: String = "dp",
) {
    val palette = LocalSettingsPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(title, palette.primary, 15.sp, modifier = Modifier.weight(1f))
            Label("$value $suffix", LocalSettingsPalette.current.secondary, 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        GlassSlider(value, min, max, step, onChange)
    }
}

@Composable
private fun GlassSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val palette = LocalSettingsPalette.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)

    fun report(fractionX: Float) {
        val ratio = (fractionX / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = min + ratio * (max - min)
        val snapped = Math.round(raw / step.toFloat()) * step
        onChange(snapped.coerceIn(min, max))
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(min, max, step) {
                widthPx = size.width.toFloat().coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    report(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        report(change.position.x)
                        change.consume()
                    }
                }
            }
    ) {
        widthPx = size.width.coerceAtLeast(1f)
        val trackY = size.height / 2f
        val trackH = 6.dp.toPx()
        val radius = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(
            color = palette.trackOff,
            topLeft = Offset(0f, trackY - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = palette.trackOn,
            topLeft = Offset(0f, trackY - trackH / 2f),
            size = Size(size.width * fraction, trackH),
            cornerRadius = radius,
        )
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = Offset(size.width * fraction, trackY),
        )
    }
}

@Composable
private fun TextRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 15.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, LocalSettingsPalette.current.primary, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, LocalSettingsPalette.current.secondary, 11.5.sp)
        }
        Label("›", LocalSettingsPalette.current.secondary, 20.sp)
    }
}
