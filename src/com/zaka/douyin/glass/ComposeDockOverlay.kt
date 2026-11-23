package com.zaka.douyin.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PersonOutline
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import com.zaka.douyin.glass.ksu.FloatingBottomBar
import com.zaka.douyin.glass.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Compose replacement for the old Java Canvas DockOverlay.
 *
 * The host View tree and LSPosed hooks remain Java. This view owns only the Dock
 * presentation and delegates navigation clicks back to Douyin's original tab Views.
 * The backdrop bitmap is still captured from the host because a Compose overlay cannot
 * sample sibling Android Views directly; Miuix then runs the same KSU blur/lens pipeline
 * over that sampled image.
 */
class ComposeDockOverlay private constructor(
    context: Context,
    private val backdrop: Backdrop,
    private val densityScale: Float
) : ComposeView(context) {

    private class OverlayLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            if (registry.currentState == Lifecycle.State.CREATED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            if (registry.currentState == Lifecycle.State.STARTED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        fun stop() {
            if (registry.currentState == Lifecycle.State.RESUMED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (registry.currentState == Lifecycle.State.STARTED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }

        fun destroy() {
            stop()
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    internal data class TabSnapshot(
        val label: String,
        val icon: ImageBitmap?,
        val selected: Boolean
    )

    internal data class DockLayout(
        val band: Rect = Rect(),
        val region: Rect = Rect(),
        val left: Int = 0,
        val top: Int = 0,
        val leftWidth: Int = 0,
        val plusLeft: Int = 0,
        val plusTop: Int = 0,
        val plusSize: Int = 0
    )

    private val lifecycleOwner = OverlayLifecycleOwner()
    private val main = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<View>()
    private var plusTab: View? = null
    private var dockLayout by mutableStateOf(DockLayout())
    private var backdropImage by mutableStateOf<ImageBitmap?>(null)
    private var tabSnapshots by mutableStateOf<List<TabSnapshot>>(emptyList())
    private var selectedIndex by mutableIntStateOf(0)
    private var drawn = AtomicBoolean(false)
    private var disposed = false

    private val hostPoll = object : Runnable {
        override fun run() {
            if (!disposed && isAttachedToWindow && visibility == VISIBLE) {
                refreshHostState()
                main.postDelayed(this, 500L)
            }
        }
    }

    private val videoPump = object : Runnable {
        override fun run() {
            if (!disposed && isAttachedToWindow && visibility == VISIBLE) {
                try {
                    backdrop.pumpVideo()
                } catch (t: Throwable) {
                    Dydock.warnOnce("compose-video-pump", "Compose 背景视频刷新失败: $t")
                }
                main.postDelayed(this, 90L)
            }
        }
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setWillNotDraw(false)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        ViewTreeLifecycleOwner.set(this, lifecycleOwner)
        lifecycleOwner.start()

        setContent {
            MiuixTheme {
                ComposeDockSurface(
                    image = backdropImage,
                    layout = dockLayout,
                    tabs = tabSnapshots,
                    selectedIndex = selectedIndex,
                    hasPlus = true,
                    onTabClick = ::activateTab,
                    onPlusClick = { plusTab?.performClick() },
                    onDrawn = { drawn.set(true) }
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun create(context: Context, backdrop: Backdrop, density: Float): ComposeDockOverlay =
            ComposeDockOverlay(context, backdrop, density)
    }

    fun setTabs(items: List<View>, plus: View?) {
        tabs.clear()
        tabs.addAll(items)
        plusTab = plus
        refreshHostState()
        updateLayout()
    }

    fun attachDock(dock: View?) {
        // Keep only the native View reference for host click forwarding.
    }

    fun setBand(rect: Rect?) {
        if (rect == null || rect.isEmpty) return
        dockLayout = dockLayout.copy(band = Rect(rect))
        updateLayout()
    }

    fun pinPosition() {
        // The Dock is positioned from the first recognized host band and then stays fixed.
    }

    fun hasDrawn(): Boolean = drawn.get()

    fun dispose() {
        disposed = true
        main.removeCallbacks(videoPump)
        main.removeCallbacks(hostPoll)
        lifecycleOwner.destroy()
        // Release PixelCopy/TextureView frames and bitmap buffers when the Activity is destroyed.
        backdrop.setOnFrame(null)
        backdrop.release()
        tabs.clear()
        plusTab = null
    }

    /** Called by the Java controller after a host capture or video-layer update. */
    fun refreshBackdrop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { refreshBackdrop() }
            return
        }
        val bitmap = try { backdrop.bitmap() } catch (_: Throwable) { null }
        backdropImage = bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
        updateLayout()
        invalidate()
    }

    private fun refreshHostState() {
        val snapshots = tabs.mapIndexed { index, view ->
            val label = findText(view).ifBlank {
                when (index) {
                    0 -> "首页"
                    1 -> "朋友"
                    2 -> "消息"
                    else -> "我"
                }
            }
            val drawable = findDrawable(view)
            val icon = drawable?.let { drawableToImage(it) }
            TabSnapshot(label, icon, isSelectedDeep(view))
        }
        tabSnapshots = snapshots
        val selected = snapshots.indexOfFirst { it.selected }
        if (selected >= 0) selectedIndex = selected.coerceIn(0, maxOf(0, snapshots.size - 1))
    }

    private fun drawableToImage(drawable: Drawable): ImageBitmap? = try {
        val w = drawable.intrinsicWidth.coerceIn(1, 96)
        val h = drawable.intrinsicHeight.coerceIn(1, 96)
        drawable.toBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Throwable) {
        null
    }

    private fun findText(view: View): String {
        if (view is TextView && view.text != null && view.text.isNotBlank()) {
            return view.text.toString()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val text = findText(view.getChildAt(i))
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun findDrawable(view: View): Drawable? {
        if (view is ImageView && view.drawable != null) return view.drawable
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findDrawable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun isSelectedDeep(view: View): Boolean {
        if (view.isSelected) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isSelectedDeep(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun activateTab(index: Int) {
        val target = tabs.getOrNull(index) ?: return
        selectedIndex = index
        try {
            target.performClick()
            refreshHostState()
        } catch (t: Throwable) {
            Dydock.warnOnce("compose-tab-click", "转发原生导航点击失败: $t")
        }
    }

    private fun updateLayout() {
        if (width <= 0 || height <= 0 || dockLayout.band.isEmpty) return
        val location = IntArray(2)
        getLocationInWindow(location)
        val band = dockLayout.band
        val bandLeft = band.left - location[0]
        val bandTop = band.top - location[1]
        val bandWidth = band.width()
        // KernelSU uses 28dp horizontal inset. Keep the independent 64dp Douyin
        // publish button and use a compact gap so the four-tab capsule stays centered.
        val edge = (28f * densityScale).toInt()
        val gap = (8f * densityScale).toInt()
        val pillHeight = (64f * densityScale).toInt()
        val plusSize = pillHeight
        val plusLeft = bandLeft + bandWidth - edge - plusSize
        val left = bandLeft + edge
        val available = (plusLeft - gap - left).coerceAtLeast(1)
        val desired = (tabs.size * 76f * densityScale + 8f * densityScale).toInt()
        val leftWidth = min(desired, available)
        val top = bandTop + band.height() - (6f * densityScale).toInt() - pillHeight
        val regionPad = (40f * densityScale).toInt()
        val region = Rect(
            left - regionPad,
            top - regionPad,
            plusLeft + plusSize + regionPad,
            top + pillHeight + regionPad
        )
        val regionInWindow = Rect(region).apply { offset(location[0], location[1]) }
        backdrop.setRegion(regionInWindow)
        dockLayout = DockLayout(
            band = Rect(band),
            region = region,
            left = left.coerceAtLeast(0),
            top = top.coerceAtLeast(0),
            leftWidth = leftWidth,
            plusLeft = plusLeft.coerceAtLeast(0),
            plusTop = top.coerceAtLeast(0),
            plusSize = plusSize
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout()
        post { try { backdrop.capture(); refreshBackdrop() } catch (_: Throwable) { } }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleOwner.start()
        main.removeCallbacks(videoPump)
        main.post(videoPump)
        main.post(hostPoll)
        refreshBackdrop()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(videoPump)
        main.removeCallbacks(hostPoll)
        lifecycleOwner.stop()
        super.onDetachedFromWindow()
    }
}

@Composable
private fun ComposeDockSurface(
    image: ImageBitmap?,
    layout: ComposeDockOverlay.DockLayout,
    tabs: List<ComposeDockOverlay.TabSnapshot>,
    selectedIndex: Int,
    hasPlus: Boolean,
    onTabClick: (Int) -> Unit,
    onPlusClick: () -> Unit,
    onDrawn: () -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val isBlurEnabled = android.os.Build.VERSION.SDK_INT >= 33

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onDrawn() }
    ) {
        if (image != null && !layout.region.isEmpty) {
            val regionLeft = with(density) { layout.region.left.toDp() }
            val regionTop = with(density) { layout.region.top.toDp() }
            val regionWidth = with(density) { layout.region.width().toDp() }
            val regionHeight = with(density) { layout.region.height().toDp() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset(regionLeft, regionTop)
                    .size(regionWidth, regionHeight)
                    .layerBackdrop(backdrop)
            )
        }

        if (tabs.isNotEmpty() && layout.leftWidth > 0) {
            val left = with(density) { layout.left.toDp() }
            val top = with(density) { layout.top.toDp() }
            val width = with(density) { layout.leftWidth.toDp() }
            FloatingBottomBar(
                modifier = Modifier.offset(left, top),
                selectedIndex = selectedIndex.coerceIn(0, tabs.lastIndex),
                onSelected = onTabClick,
                backdrop = backdrop,
                tabsCount = tabs.size,
                barWidth = width,
                isBlurEnabled = isBlurEnabled
            ) { activate ->
                tabs.forEachIndexed { index, tab ->
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
                        if (tab.icon != null) {
                            Image(
                                bitmap = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = fallback,
                                contentDescription = tab.label,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = tab.label,
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

        if (hasPlus && layout.plusSize > 0) {
            val x = with(density) { layout.plusLeft.toDp() }
            val y = with(density) { layout.plusTop.toDp() }
            val size = with(density) { layout.plusSize.toDp() }
            Box(
                modifier = Modifier
                    .offset(x, y)
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFFE91F49))
                    .clickable(onClick = onPlusClick),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 34.sp, lineHeight = 34.sp)
            }
        }
    }
}
