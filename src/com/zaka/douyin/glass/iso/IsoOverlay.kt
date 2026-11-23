package com.zaka.douyin.glass.iso

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zaka.douyin.glass.ksu.FloatingBottomBar
import com.zaka.douyin.glass.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * 玻璃层本体。
 *
 * **这个类只会在独立类加载器里被加载**（见 IsoDock），所以它可以随便用
 * androidx.compose / Miuix，而不会跟抖音自己那份 androidx 撞车。
 * 因此这里绝对不引用宿主那边的模块类（Dydock / Backdrop / DockController），
 * 只接收框架类型：Context、Bitmap、Rect 和 java.util.function 回调。
 *
 * 渲染器仍是 KernelSU 那一套：FloatingBottomBar + lens / innerShadow / vibrancy，
 * 参数一个字没改。区别只有两处，都是抖音这边的客观差异：
 *   1. 背景不是 Compose 兄弟图层，而是宿主采来的一整块 Bitmap，先画进图层再让透镜去折射；
 *   2. 四个 tab 的胶囊和「＋」是分开的两块，位置由宿主算好后传进来。
 */
class IsoOverlay private constructor(
    context: Context,
    private val onLog: Consumer<String>,
    private val onTabClick: IntConsumer,
    private val onPlusClick: Runnable
) : FrameLayout(context) {

    private val overlayOwner = OverlayLifecycleOwner()
    private val composeView = SafeComposeHost(context)
    private val drawn = AtomicBoolean(false)

    private var region by mutableStateOf(Rect())
    private var tabsRect by mutableStateOf(Rect())
    private var plusRect by mutableStateOf(Rect())
    private var backdropImage by mutableStateOf<ImageBitmap?>(null)
    private var labels by mutableStateOf<List<String>>(emptyList())
    private var icons by mutableStateOf<List<ImageBitmap?>>(emptyList())
    private var selectedIndex by mutableIntStateOf(0)

    @Volatile
    private var disposed = false

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setWillNotDraw(false)
        composeView.onBroken = { where, t ->
            log("Compose 在 $where 阶段出错：$t")
            try {
                visibility = GONE
            } catch (_: Throwable) {
            }
        }
        composeView.setViewTreeLifecycleOwner(overlayOwner)
        composeView.inner.setViewTreeLifecycleOwner(overlayOwner)
        composeView.setViewTreeSavedStateRegistryOwner(overlayOwner)
        composeView.inner.setViewTreeSavedStateRegistryOwner(overlayOwner)
        overlayOwner.resume()
        composeView.inner.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        composeView.inner.setContent {
            // 只创建一次：ThemeController 自带状态和协程，放在 composable 体里
            // 会随每一帧重组重新 new（背景 30fps 刷新时就是每秒几十个），几十秒能把宿主内存拖爆。
            val systemDark = isSystemInDarkTheme()
            val controller = remember(systemDark) {
                ThemeController(
                    ColorSchemeMode.System,
                    isDark = systemDark,
                    paletteStyle = ThemePaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.Spec2025
                )
            }
            MiuixTheme(controller = controller) {
                GlassSurface(
                    image = backdropImage,
                    region = region,
                    tabsRect = tabsRect,
                    plusRect = plusRect,
                    labels = labels,
                    icons = icons,
                    selectedIndex = selectedIndex,
                    onTabClick = { index -> onTabClick.accept(index) },
                    onPlusClick = { onPlusClick.run() },
                    onDrawn = {
                        if (drawn.compareAndSet(false, true)) log("玻璃已绘制")
                    }
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun create(
            context: Context,
            onLog: Consumer<String>,
            onTabClick: IntConsumer,
            onPlusClick: Runnable
        ): IsoOverlay = IsoOverlay(context, onLog, onTabClick, onPlusClick)
    }

    /** 宿主算好的位置（都在覆盖层自己的坐标系里）。 */
    fun setGeometry(region: Rect, tabs: Rect, plus: Rect) {
        if (disposed) return
        this.region = Rect(region)
        this.tabsRect = Rect(tabs)
        this.plusRect = Rect(plus)
    }

    /** 宿主采来的底栏那一条真实画面。所有权交给这里，宿主每帧给的都是新副本。 */
    fun setBackdrop(bitmap: Bitmap?) {
        if (disposed || bitmap == null || bitmap.isRecycled) return
        backdropImage = try {
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            log("背景位图转换失败：$t")
            null
        }
    }

    fun setTabs(labelArray: Array<String>, iconArray: Array<Bitmap?>, selected: Int) {
        if (disposed) return
        labels = labelArray.toList()
        icons = iconArray.map { bitmap ->
            if (bitmap == null || bitmap.isRecycled) {
                null
            } else {
                try {
                    bitmap.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
        }
        selectedIndex = selected.coerceIn(0, maxOf(0, labelArray.size - 1))
    }

    fun isBroken(): Boolean = composeView.broken

    fun hasDrawn(): Boolean = drawn.get()

    fun dispose() {
        if (disposed) return
        disposed = true
        try {
            composeView.inner.disposeComposition()
        } catch (_: Throwable) {
        }
        overlayOwner.destroy()
        backdropImage = null
    }

    private fun log(message: String) {
        try {
            onLog.accept(message)
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun GlassSurface(
    image: ImageBitmap?,
    region: Rect,
    tabsRect: Rect,
    plusRect: Rect,
    labels: List<String>,
    icons: List<ImageBitmap?>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    onPlusClick: () -> Unit,
    onDrawn: () -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    // 33 以下没有 RuntimeShader；个别机型/ROM 上 AGSL 编译会直接抛错，
    // 这里先探一次：探不通就走 Miuix 自带的「无模糊」降级，绝不让一帧着色器异常把整层玻璃干掉。
    val isBlurEnabled = remember {
        Build.VERSION.SDK_INT >= 33 && runCatching {
            android.graphics.RuntimeShader(
                "uniform float4 c; half4 main(float2 p) { return half4(c); }"
            )
        }.isSuccess
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onDrawn() }
    ) {
        if (image != null && region.width() > 0 && region.height() > 0) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset(with(density) { region.left.toDp() }, with(density) { region.top.toDp() })
                    .size(
                        with(density) { region.width().toDp() },
                        with(density) { region.height().toDp() }
                    )
                    .layerBackdrop(backdrop)
            )
        }

        if (labels.isNotEmpty() && tabsRect.width() > 0 && tabsRect.height() > 0) {
            Box(
                modifier = Modifier
                    .offset(with(density) { tabsRect.left.toDp() }, with(density) { tabsRect.top.toDp() })
                    .width(with(density) { tabsRect.width().toDp() }),
                contentAlignment = Alignment.TopCenter
            ) {
                FloatingBottomBar(
                    modifier = Modifier,
                    selectedIndex = selectedIndex.coerceIn(0, labels.lastIndex),
                    onSelected = onTabClick,
                    backdrop = backdrop,
                    tabsCount = labels.size,
                    isBlurEnabled = isBlurEnabled
                ) { activate ->
                    labels.forEachIndexed { index, label ->
                        FloatingBottomBarItem(
                            selected = index == selectedIndex,
                            onClick = { activate(index) }
                        ) {
                            val fallback = when (index) {
                                0 -> Icons.Rounded.Home
                                1 -> Icons.Rounded.PeopleAlt
                                2 -> Icons.Rounded.ChatBubbleOutline
                                else -> Icons.Rounded.PersonOutline
                            }
                            val icon = icons.getOrNull(index)
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(24.dp),
                                    colorFilter = ColorFilter.tint(LocalContentColor.current)
                                )
                            } else {
                                Icon(
                                    imageVector = fallback,
                                    contentDescription = label,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            }
        }

        if (plusRect.width() > 0 && plusRect.height() > 0) {
            Box(
                modifier = Modifier
                    .offset(with(density) { plusRect.left.toDp() }, with(density) { plusRect.top.toDp() })
                    .size(
                        with(density) { plusRect.width().toDp() },
                        with(density) { plusRect.height().toDp() }
                    )
                    .clip(CircleShape)
                    .background(Color(0xFFFE2C55))
                    .clickable(onClick = onPlusClick),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 34.sp, lineHeight = 34.sp)
            }
        }
    }
}

/** ComposeView 需要的生命周期 / 状态宿主；抖音的 DecorView 上没有现成的。 */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    fun resume() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * ComposeView 的护栏外壳。
 *
 * 测量 / 布局 / 绘制任何一环抛错，都不许让它冒到宿主主线程（那就是抖音闪退）。
 * 出错就把绘制锁死并上报，宿主会把原生底栏还回去。
 */
private class SafeComposeHost(context: Context) : FrameLayout(context) {

    val inner = ComposeView(context)

    @Volatile
    var broken = false
        private set

    var onBroken: ((String, Throwable) -> Unit)? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun guard(where: String, block: () -> Unit) {
        if (broken) return
        try {
            block()
        } catch (t: Throwable) {
            broken = true
            onBroken?.invoke(where, t)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        guard("measure") { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        if (broken) setMeasuredDimension(0, 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        guard("layout") { super.onLayout(changed, left, top, right, bottom) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        guard("draw") { super.dispatchDraw(canvas) }
    }
}
