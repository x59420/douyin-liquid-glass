package com.zaka.douyin.glass

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.zaka.douyin.glass.ksu.liquid.vibrancy

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
internal class SettingsState(context: Context) {

    private val ctx = context.applicationContext

    val enabled = mutableStateOf(DockPrefs.enabled(ctx))
    val showPlus = mutableStateOf(DockPrefs.showPlusButton(ctx))
    val showLabels = mutableStateOf(DockPrefs.showLabels(ctx))
    val logEnabled = mutableStateOf(DockPrefs.logEnabled(ctx))

    val height = mutableStateOf(DockPrefs.heightDp(ctx))
    val tabWidth = mutableStateOf(DockPrefs.tabWidthDp(ctx))
    val sideMargin = mutableStateOf(DockPrefs.sideMarginDp(ctx))
    val bottomGap = mutableStateOf(DockPrefs.bottomGapDp(ctx))

    fun setEnabled(value: Boolean) {
        enabled.value = value
        DockPrefs.setEnabled(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setShowPlus(value: Boolean) {
        showPlus.value = value
        DockPrefs.setShowPlusButton(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setShowLabels(value: Boolean) {
        showLabels.value = value
        DockPrefs.setShowLabels(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setLogEnabled(value: Boolean) {
        logEnabled.value = value
        DockPrefs.setLogEnabled(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setHeight(value: Int) {
        height.value = value
        DockPrefs.setHeightDp(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setTabWidth(value: Int) {
        tabWidth.value = value
        DockPrefs.setTabWidthDp(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setSideMargin(value: Int) {
        sideMargin.value = value
        DockPrefs.setSideMarginDp(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun setBottomGap(value: Int) {
        bottomGap.value = value
        DockPrefs.setBottomGapDp(ctx, value)
        DockPrefs.publish(ctx)
    }

    fun reset() {
        setEnabled(true)
        setShowPlus(true)
        setShowLabels(true)
        setHeight(DockPrefs.DEF_HEIGHT)
        setTabWidth(DockPrefs.DEF_TAB_WIDTH)
        setSideMargin(DockPrefs.DEF_SIDE_MARGIN)
        setBottomGap(DockPrefs.DEF_BOTTOM_GAP)
    }
}

private val GlassCardShape = RoundedCornerShape(26.dp)
private val Accent = Color(0xFF5B8CFF)
private val SubText = Color(0xFF9BA6BC)

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
    state: SettingsState,
    version: String,
    onBack: () -> Unit,
) {
    val scene = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(Color(0xFF070A12))) {
        // 背景层：深色渐变 + 三团柔光，整块记进 scene，卡片采样用。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B1226), Color(0xFF131C33), Color(0xFF070A12))
                    )
                )
                .layerBackdrop(scene)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                fun blob(cx: Float, cy: Float, color: Color, r: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color, color.copy(alpha = 0f)),
                            center = Offset(size.width * cx, size.height * cy),
                            radius = size.maxDimension * r,
                        ),
                        radius = size.maxDimension * r,
                        center = Offset(size.width * cx, size.height * cy),
                    )
                }
                blob(0.18f, 0.10f, Color(0x663E6BFF), 0.55f)
                blob(0.95f, 0.32f, Color(0x5532D3C8), 0.45f)
                blob(0.08f, 0.84f, Color(0x44E05A9B), 0.50f)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 36.dp)
        ) {
            TopBar(version = version, onBack = onBack)
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
                    DockPrefs.MIN_HEIGHT, DockPrefs.MAX_HEIGHT, 2, state::setHeight)
                Divider()
                SliderRow("单个图标宽度", state.tabWidth.value,
                    DockPrefs.MIN_TAB_WIDTH, DockPrefs.MAX_TAB_WIDTH, 2, state::setTabWidth)
                Divider()
                SliderRow("左右留白", state.sideMargin.value,
                    DockPrefs.MIN_SIDE_MARGIN, DockPrefs.MAX_SIDE_MARGIN, 2, state::setSideMargin)
                Divider()
                SliderRow("离底部距离", state.bottomGap.value,
                    DockPrefs.MIN_BOTTOM_GAP, DockPrefs.MAX_BOTTOM_GAP, 1, state::setBottomGap)
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
                    title = "恢复默认尺寸",
                    subtitle = "只重置尺寸和开关，不动别的",
                    onClick = state::reset,
                )
            }

            Spacer(Modifier.height(22.dp))
            Label(
                text = "dydock $version",
                color = SubText,
                size = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Label(
                text = "液态玻璃渲染移植自 KernelSU（Compose + Miuix），折射与色散参数与上游一致；"
                    + "模糊采样由 compose-miuix-ui 提供。本模块与抖音官方无关，仅限个人非商业用途。",
                color = SubText.copy(alpha = 0.75f),
                size = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                align = TextAlign.Center,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun TopBar(version: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val w = size.minDimension * 0.14f
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.62f, size.height * 0.12f),
                    end = Offset(size.width * 0.28f, size.height * 0.5f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.28f, size.height * 0.5f),
                    end = Offset(size.width * 0.62f, size.height * 0.88f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Label("dydock 设置", Color.White, 20.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Label(version, SubText, 11.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Label(
        text = text,
        color = SubText,
        size = 12.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun GlassCard(backdrop: Backdrop, content: @Composable () -> Unit) {
    val shape: Shape = GlassCardShape
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .glassSurface(backdrop, shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 有 RuntimeShader 就用真玻璃（模糊 + vibrancy），没有就退回半透明底。 */
@Composable
private fun Modifier.glassSurface(backdrop: Backdrop, shape: Shape): Modifier {
    val supported = remember { android.os.Build.VERSION.SDK_INT >= 33 }
    return if (supported) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(22.dp.toPx(), 22.dp.toPx())
                vibrancy()
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.07f)) },
        )
    } else {
        this.background(Color(0xFF161D2E).copy(alpha = 0.94f), shape)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f))
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
            Label(title, Color.White, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, SubText, 11.5.sp)
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
            .background(lerp(Color.White.copy(alpha = 0.16f), Accent, progress)),
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
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(title, Color.White, 15.sp, modifier = Modifier.weight(1f))
            Label("$value dp", SubText, 12.5.sp)
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
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(0f, trackY - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Accent, Color(0xFF37D6C9))),
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
            Label(title, Color.White, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, SubText, 11.5.sp)
        }
        Label("›", SubText, 20.sp)
    }
}
