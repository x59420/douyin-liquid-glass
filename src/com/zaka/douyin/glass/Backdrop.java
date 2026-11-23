package com.zaka.douyin.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 玻璃底下那块真实画面。
 *
 * 只采**底栏那一条**（区域由 DockController 给），不是整屏：
 *  - 便宜。只采 1080 x 240 左右的一条，交给 Miuix 在 GPU 上做模糊，CPU 不做任何模糊。
 *  - 不会把自己画进去。采集根是 Activity 的 android.R.id.content，
 *    而 dock 挂在 DecorView 上，跟 content 是兄弟节点 —— 天然没有回授，
 *    不需要「采集时把玻璃藏起来」那种会闪一下的招。
 *
 * 视频层（SurfaceView / TextureView）的像素不在 View 的绘制里，
 * 直接画树只会得到一块黑 —— 老版本满屏黑块就是这么来的。
 * 这里对每个视频层单独走 PixelCopy 抓它**在底栏那一条里的**像素，
 * 再把 UI 层里那块挖空，两层叠起来才是「玻璃后面真实看到的画面」。
 */
public final class Backdrop {

    /** 采样位图相对屏幕的缩放。33+ 交给 Miuix 在 GPU 上模糊，1:1 采样保证清晰度。 */
    private float downscaleFactor = 4f;
    /** 视频层重新扫描的间隔 */
    private static final long RESCAN_MS = 1500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint clear = new Paint();

    private static final class Layer {
        View view;
        Bitmap frame;
        Bitmap textureFull;
        Rect dst = new Rect();
        boolean pending;
        boolean valid;
        long stamp;
    }

    private View content;
    private final Rect region = new Rect();
    private Bitmap ui;
    private Bitmap vid;
    private Bitmap out;

    /** 宿主窗口：抓「屏幕上真实画面」的那一路。 */
    private android.view.Window window;
    /** 窗口帧（已缩到采样分辨率），和视频层一起决定玻璃后面是什么。 */
    private Bitmap winFrame;
    private long winStamp;
    /** 1:1 抓取缓冲，抓完再缩到采样分辨率。 */
    private Bitmap winBuffer;
    private boolean winPending;
    private long lastWinRequestAt;
    private int winFailures;
    /** 抓窗口帧之前 / 之后动一下玻璃层（几乎透明一帧，避免把自己的样子抓进去）。 */
    private Runnable maskBefore;
    private Runnable maskAfter;
    /** 宿主这一页重绘了多少次：没重绘就说明画面没变，不用再拍。 */
    private final java.util.concurrent.atomic.AtomicInteger drawCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private int lastPumpedDrawCount;
    private android.view.ViewTreeObserver registeredVto;
    private final android.view.ViewTreeObserver.OnPreDrawListener preDrawListener =
            new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    drawCount.incrementAndGet();
                    return true;
                }
            };

    /** 两次窗口抓取的最小间隔。 */
    private static final long WINDOW_FRAME_MIN_GAP_MS = 650L;
    /** 视频帧多久算「新鲜」，新鲜时不抓窗口帧。 */
    private static final long VIDEO_FRESH_MS = 420L;

    private final List<Layer> layers = new ArrayList<Layer>();
    private long lastScan;
    /** 同一时刻只允许一个 PixelCopy 在飞：30fps 叠多层视频层会堆几十个请求，
     *  渲染线程被压住之后宿主会直接崩，这里统一收口。 */
    private int inflight;
    private long lastRequestAt;
    private long frameStamp;
    private int lastLoggedLayerCount = -1;
    private int meanAlpha;
    private int meanLuma = 255;
    private String note = "未采集";
    private String lastDiagnostic = "";
    private long lastDiagnosticAt;

    Backdrop() {
        clear.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    /** 模糊全部由 Miuix 的 GPU 着色器完成，这里只需要决定采样分辨率。 */
    void setSampleScale() {
        downscaleFactor = android.os.Build.VERSION.SDK_INT >= 33 ? 1f : 4f;
    }

    float downscaleFactor() {
        return downscaleFactor;
    }

    void setContent(View v) {
        if (v == content) return;
        detachPreDraw();
        content = v;
        releaseLayers();
        releaseBitmaps();
        attachPreDraw();
        note = "换采集根";
    }

    void setWindow(android.view.Window w) {
        window = w;
    }

    /**
     * 抓窗口帧前后要做的动作。
     *
     * 玻璃层就画在这个窗口上，直接抓会把自己的样子也拍进去（越糊越糊，还会自激）。
     * 抓之前把玻璃压到几乎透明，窗口像素里它只剩 2%，采样结果基本就是真实画面；
     * 抓完再抬回去。代价是一帧的变淡，几乎看不出来。
     */
    void setCaptureMask(Runnable before, Runnable after) {
        maskBefore = before;
        maskAfter = after;
    }

    View content() {
        return content;
    }

    /** 采集区域（窗口坐标，px） */
    void setRegion(Rect r) {
        if (r == null || r.isEmpty()) return;
        if (r.equals(region)) return;
        region.set(r);
        note = "换区域 " + r.width() + "x" + r.height();
    }

    Rect region() {
        return region;
    }

    Bitmap bitmap() {
        if (out == null || out.isRecycled()) return null;
        if (meanAlpha < 8 && !hasVideo() && !hasWindowFrame()) return null;
        return out;
    }

    private boolean hasVideo() {
        for (Layer l : layers) {
            if (l.valid) return true;
        }
        return false;
    }

    /**
     * 手上有没有一帧窗口画面。
     *
     * 这里不按时间过期：画面有没有变由宿主的重绘计数决定（见 pumpWindow）。
     * 静态页面本来就不重绘，按时间作废只会让背景凭空消失、然后又补一帧闪一下。
     */
    private boolean hasWindowFrame() {
        return winFrame != null && !winFrame.isRecycled();
    }

    /** 有没有一帧可用的画面（没有就别指望玻璃透出东西来） */
    boolean hasFrame() {
        return bitmap() != null;
    }

    int luma() { return meanLuma; }

    /**
     * 这一帧采出来是不是整片发黑。
     *
     * 有视频层帧、或者拿到过窗口画面时不判黑（暗场视频、暗色页面都属于画面内容）；
     * 只有在两路都没有、采样又几乎全黑时，才说明底下画的是抖音那层底栏背景色，
     * 这时候把背景图丢掉，玻璃只保留自己的模糊，不会变成一块黑玻璃。
     */
    boolean looksBlack() {
        return meanLuma < 14 && !hasFrameLayer() && !hasWindowFrame();
    }

    private boolean hasFrameLayer() {
        for (Layer l : layers) {
            if (l.valid && l.frame != null && !l.frame.isRecycled()) return true;
        }
        return false;
    }

    long frameStamp() {
        return frameStamp;
    }

    String desc() {
        return note + " · " + region.width() + "x" + region.height()
                + " · 视频层 " + layers.size()
                + " · alpha " + meanAlpha;
    }

    void setOnFrame(Runnable r) {
        onFrame = r;
    }

    private Runnable onFrame;

    // ------------------------------------------------------------------ 采集

    /** 整条重采集：UI 层 + 视频层。开销比 pumpVideo 大，别每帧都调。 */
    boolean capture() {
        View c = content;
        if (c == null || region.isEmpty()) {
            note = "没根视图或没区域";
            return false;
        }
        int w = c.getWidth();
        int h = c.getHeight();
        if (w < 16 || h < 16 || !c.isShown()) {
            note = "根视图 " + w + "x" + h;
            return false;
        }

        int sw = Math.max(8, Math.round(region.width() / downscaleFactor));
        int sh = Math.max(8, Math.round(region.height() / downscaleFactor));
        float kx = sw / (float) region.width();
        float ky = sh / (float) region.height();

        try {
            if (ui == null || ui.isRecycled()
                    || ui.getWidth() != sw || ui.getHeight() != sh) {
                releaseBitmaps();
                ui = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                vid = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            }
        } catch (Throwable t) {
            Dydock.error("建背景位图", t);
            releaseBitmaps();
            return false;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastScan > RESCAN_MS || layers.isEmpty()) {
            rescanLayers(c, now);
        }

        // 1. 先算视频层落在哪
        for (Layer l : layers) {
            updateDst(l, c, kx, ky);
        }

        // 2. UI 层。视频已经把这一条铺满的时候就不用画树了 ——
        //    抖音首页每 1.2 秒重画一遍整棵信息流，卡顿就是这么来的
        boolean covered = videoCoversAll(sw, sh);
        try {
            Canvas uc = new Canvas(ui);
            uc.drawColor(0, PorterDuff.Mode.CLEAR);
            if (!covered) {
                uc.scale(kx, ky);
                int[] cl = new int[2];
                c.getLocationInWindow(cl);
                uc.translate(cl[0] - region.left, cl[1] - region.top);
                c.draw(uc);
            }
        } catch (Throwable t) {
            // 宿主树里常有 SurfaceView / 硬件位图，软件画布画到一半失败是常态
            Dydock.warnOnce("ui-draw", "UI 层绘制中断（通常无害）: " + t);
        }

        // 3. 请求新帧
        for (Layer l : layers) {
            if (!l.pending) requestFrame(l);
        }

        // 4. UI 层里视频所在的那块挖空，否则视频会被容器自己的深色底盖住 —— 就是黑块
        try {
            Canvas uc = new Canvas(ui);
            if (covered) {
                // 视频已经把这一条铺满：整块 UI 层都不要了。
                // 底栏那一带的渐变遮罩、容器底色全在这一层里，留着就是「玻璃一片黑」。
                uc.drawColor(0, PorterDuff.Mode.CLEAR);
            } else {
                for (Layer l : layers) {
                    if (l.dst.isEmpty()) continue;
                    uc.drawRect(l.dst.left, l.dst.top, l.dst.right, l.dst.bottom, clear);
                }
            }
        } catch (Throwable t) {
            Dydock.warnOnce("ui-erase", "挖空视频区失败: " + t);
        }

        compose();
        note = "ok";
        logCaptureState(now);
        // 手里还没有可用的窗口画面时先补一帧：这是「不管屏幕上是什么画面」
        // 都能映射到位的那一路，视频层抓不到时全靠它。
        if (!hasWindowFrame()) requestWindowFrame();
        return true;
    }

    /** 视频层是不是已经把这一条铺满了（以最近抓到的帧为准） */
    private boolean videoCoversAll(int sw, int sh) {
        if (layers.isEmpty()) return false;
        long total = (long) sw * sh;
        long sum = 0;
        for (Layer l : layers) {
            if (!l.valid || l.dst.isEmpty()) return false;
            sum += (long) l.dst.width() * l.dst.height();
        }
        return sum >= total * 0.94f;
    }

    /** 只推视频帧：整条重采集太贵，但视频在动，玻璃得跟着动 */
    void pumpVideo() {
        if (layers.isEmpty() || vid == null || vid.isRecycled()) return;
        View c = content;
        if (c == null || region.isEmpty()) return;
        float kx = vid.getWidth() / (float) region.width();
        float ky = vid.getHeight() / (float) region.height();
        for (Layer l : layers) {
            updateDst(l, c, kx, ky);
            if (!l.pending) requestFrame(l);
        }
    }

    private void logCaptureState(long now) {
        String state = (hasFrame() ? "frame" : "no-frame") + " · " + desc();
        if (!state.equals(lastDiagnostic) || now - lastDiagnosticAt >= 5000L) {
            lastDiagnostic = state;
            lastDiagnosticAt = now;
            Dydock.log("背景采样: " + state
                    + (layers.isEmpty() ? " · 当前只采 UI 树，未发现视频 Surface/Texture 层" : ""));
        }
    }

    private void compose() {
        if (out == null || ui == null || vid == null) return;
        try {
            Canvas vc = new Canvas(vid);
            vc.drawColor(0, PorterDuff.Mode.CLEAR);
            for (Layer l : layers) {
                if (!l.valid || l.frame == null || l.frame.isRecycled()) continue;
                if (l.dst.isEmpty()) continue;
                vc.drawBitmap(l.frame, null, l.dst, paint);
            }

            Canvas oc = new Canvas(out);
            oc.drawColor(0, PorterDuff.Mode.CLEAR);
            if (hasWindowFrame()) {
                // 窗口真实画面打底：视频、图片、直播、网页、聊天页……屏幕上是什么就是什么。
                oc.drawBitmap(winFrame, null, new Rect(0, 0, out.getWidth(), out.getHeight()), paint);
                // 视频层再叠一次：窗口帧间隔较大，视频要跟上 30fps 的节奏才不糊成一片。
                oc.drawBitmap(vid, 0f, 0f, paint);
            } else {
                // 窗口帧拿不到（部分机型/受保护画面）才退回「软件画 UI 树 + 视频层」。
                oc.drawBitmap(vid, 0f, 0f, paint);
                oc.drawBitmap(ui, 0f, 0f, paint);
            }
        } catch (Throwable t) {
            Dydock.error("合成背景", t);
            return;
        }

        measure();
        frameStamp = System.nanoTime();
    }

    private void onVideoFrame() {
        compose();
        Runnable cb = onFrame;
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable t) {
                Dydock.error("玻璃刷新", t);
            }
        }
    }

    // ------------------------------------------------------------------ 窗口帧

    /**
     * 外部每隔几百毫秒调一次，需要的时候补一帧窗口画面。
     *
     * 抓窗口的时候玻璃要压到几乎透明一帧（否则会把自己拍进去），所以这里只在
     * 「没有实时视频层可用」的时候抓：刷视频时视频层本来就 30fps 跟着，玻璃不动也不闪；
     * 图片、直播封面、网页、聊天页这些静态或低频画面才走窗口帧，这一段本来也不需要 30fps。
     */
    void pumpWindow() {
        if (window == null || region.isEmpty() || winPending) return;
        if (out != null && !out.isRecycled()
                && videoCoversAll(out.getWidth(), out.getHeight()) && videoFresh()) return;
        attachPreDraw();
        int drawn = drawCount.get();
        // 宿主一次都没重绘：屏幕上还是那一帧，再拍一次只会让玻璃白闪一下。
        if (hasWindowFrame() && drawn == lastPumpedDrawCount) return;
        if (requestWindowFrame()) lastPumpedDrawCount = drawn;
    }

    /**
     * 盯住宿主页面的重绘。
     *
     * 视频走 SurfaceView，不动 View 树，所以刷视频时这里几乎不增长（正好，视频有自己那一路）；
     * 滚动列表、切页、图片加载会重绘，这时候才需要补一帧窗口画面。
     */
    private void attachPreDraw() {
        View c = content;
        if (c == null) return;
        try {
            android.view.ViewTreeObserver vto = c.getViewTreeObserver();
            if (vto == registeredVto && vto.isAlive()) return;
            detachPreDraw();
            vto.addOnPreDrawListener(preDrawListener);
            registeredVto = vto;
        } catch (Throwable ignored) {
        }
    }

    private void detachPreDraw() {
        try {
            if (registeredVto != null && registeredVto.isAlive()) {
                registeredVto.removeOnPreDrawListener(preDrawListener);
            }
        } catch (Throwable ignored) {
        }
        registeredVto = null;
    }

    private boolean videoFresh() {
        long now = SystemClock.uptimeMillis();
        for (Layer l : layers) {
            if (l.valid && now - l.stamp <= VIDEO_FRESH_MS) return true;
        }
        return false;
    }

    private boolean requestWindowFrame() {
        final android.view.Window w = window;
        if (w == null || region.isEmpty() || winPending) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastWinRequestAt < WINDOW_FRAME_MIN_GAP_MS) return false;

        // 采集区域必须落在窗口里：PixelCopy 对越界的 srcRect 会直接抛异常，
        // 有的机型底栏会贴着/压过窗口底边，这里先裁一刀。
        final Rect src = new Rect(region);
        try {
            View decor = w.peekDecorView();
            int dw = decor == null ? 0 : decor.getWidth();
            int dh = decor == null ? 0 : decor.getHeight();
            if (dw > 0 && dh > 0) {
                if (!src.intersect(0, 0, dw, dh)) return false;
            }
        } catch (Throwable ignored) {
        }
        final int rw = src.width();
        final int rh = src.height();
        if (rw <= 0 || rh <= 0) return false;
        try {
            if (winBuffer == null || winBuffer.isRecycled()
                    || winBuffer.getWidth() != rw || winBuffer.getHeight() != rh) {
                winBuffer = recycle(winBuffer);
                winBuffer = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            }
        } catch (Throwable t) {
            note = "窗口缓冲建不出来";
            return false;
        }

        lastWinRequestAt = now;
        winPending = true;
        runMask(maskBefore);
        try {
            PixelCopy.request(w, src, winBuffer,
                    new PixelCopy.OnPixelCopyFinishedListener() {
                        @Override
                        public void onPixelCopyFinished(int result) {
                            winPending = false;
                            runMask(maskAfter);
                            if (result != PixelCopy.SUCCESS) {
                                if (winFailures++ < 3) {
                                    Dydock.warnOnce("window-pixelcopy",
                                            "抓窗口画面失败 code=" + result + "，暂时用 UI 层采样");
                                }
                                return;
                            }
                            winFailures = 0;
                            storeWindowFrame();
                        }
                    }, main);
            return true;
        } catch (Throwable t) {
            winPending = false;
            runMask(maskAfter);
            if (winFailures++ < 3) {
                Dydock.warnOnce("window-pixelcopy-err", "抓窗口画面异常: " + t);
            }
            return false;
        }
    }

    private void storeWindowFrame() {
        if (winBuffer == null || winBuffer.isRecycled()) return;
        if (ui == null || ui.isRecycled()) return;
        int w = ui.getWidth();
        int h = ui.getHeight();
        if (w <= 0 || h <= 0) return;
        try {
            if (winFrame == null || winFrame.isRecycled()
                    || winFrame.getWidth() != w || winFrame.getHeight() != h) {
                winFrame = recycle(winFrame);
                winFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Canvas c = new Canvas(winFrame);
            c.drawColor(0, PorterDuff.Mode.CLEAR);
            c.drawBitmap(winBuffer, null, new Rect(0, 0, w, h), paint);
            winStamp = SystemClock.uptimeMillis();
        } catch (Throwable t) {
            Dydock.warnOnce("window-frame-store", "保存窗口画面失败: " + t);
            return;
        }
        compose();
        Runnable cb = onFrame;
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable t) {
                Dydock.error("玻璃刷新", t);
            }
        }
    }

    private void runMask(Runnable r) {
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }

    private void measure() {
        if (out == null || out.isRecycled()) return;
        int w = out.getWidth();
        int h = out.getHeight();
        int stepX = Math.max(1, w / 20);
        int stepY = Math.max(1, h / 12);
        long alphaSum = 0L;
        long lumaSum = 0L;
        int count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int pixel = out.getPixel(x, y);
                alphaSum += (pixel >>> 24) & 0xFF;
                lumaSum += ((pixel >> 16 & 0xFF) * 299
                        + (pixel >> 8 & 0xFF) * 587
                        + (pixel & 0xFF) * 114) / 1000;
                count++;
            }
        }
        if (count > 0) {
            meanAlpha = (int) (alphaSum / count);
            meanLuma = (int) (lumaSum / count);
        }
    }

    // ------------------------------------------------------------------ 视频层

    private void rescanLayers(View root, long now) {
        lastScan = now;
        List<View> found = new ArrayList<View>();
        collect(root, found, 0);
        boolean same = found.size() == layers.size();
        if (same) {
            for (int i = 0; i < found.size(); i++) {
                if (layers.get(i).view != found.get(i)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        releaseLayers();
        for (View v : found) {
            Layer l = new Layer();
            l.view = v;
            layers.add(l);
        }
        if (layers.size() != lastLoggedLayerCount) {
            lastLoggedLayerCount = layers.size();
            if (layers.isEmpty()) {
                Dydock.warn("背景采样：当前 Dock 区域未发现 SurfaceView/TextureView，玻璃将暂用半透明回退");
            } else {
                Dydock.log("背景采样：检测到视频层 " + layers.size() + " 个（"
                        + layers.get(0).view.getClass().getSimpleName() + "）");
            }
        }
    }

    private void collect(View v, List<View> outList, int depth) {
        if (v == null || depth > 40 || outList.size() >= 3) return;
        if (v.getVisibility() != View.VISIBLE || !v.isShown()) return;
        if (v instanceof SurfaceView || v instanceof TextureView) {
            if (overlapsRegion(v)) outList.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), outList, depth + 1);
            }
        }
    }

    private boolean overlapsRegion(View v) {
        int[] wl = new int[2];
        v.getLocationInWindow(wl);
        Rect r = new Rect(wl[0], wl[1], wl[0] + v.getWidth(), wl[1] + v.getHeight());
        return Rect.intersects(r, region) && v.getWidth() > 16 && v.getHeight() > 16;
    }

    private void updateDst(Layer l, View root, float kx, float ky) {
        try {
            int[] vl = new int[2];
            l.view.getLocationInWindow(vl);
            Rect r = new Rect(vl[0], vl[1], vl[0] + l.view.getWidth(), vl[1] + l.view.getHeight());
            if (!Rect.intersects(r, region)) {
                l.dst.setEmpty();
                return;
            }
            Rect inter = new Rect(Math.max(r.left, region.left), Math.max(r.top, region.top),
                    Math.min(r.right, region.right), Math.min(r.bottom, region.bottom));
            l.dst.set(
                    Math.round((inter.left - region.left) * kx),
                    Math.round((inter.top - region.top) * ky),
                    Math.round((inter.right - region.left) * kx),
                    Math.round((inter.bottom - region.top) * ky));
        } catch (Throwable t) {
            l.dst.setEmpty();
        }
    }

    private void requestFrame(final Layer l) {
        try {
            if (l.dst.isEmpty()) return;

            if (l.view instanceof TextureView) {
                TextureView tv = (TextureView) l.view;
                if (!tv.isAvailable()) return;
                int fullW = Math.max(4, Math.round(tv.getWidth() / downscaleFactor));
                int fullH = Math.max(4, Math.round(tv.getHeight() / downscaleFactor));
                if (l.textureFull == null || l.textureFull.isRecycled()
                        || l.textureFull.getWidth() != fullW
                        || l.textureFull.getHeight() != fullH) {
                    l.textureFull = recycle(l.textureFull);
                    l.textureFull = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888);
                }
                Bitmap got = tv.getBitmap(l.textureFull);
                if (got == null) {
                    l.valid = false;
                    return;
                }
                int[] vl = new int[2];
                tv.getLocationInWindow(vl);
                float sourceScaleX = l.textureFull.getWidth() / (float) tv.getWidth();
                float sourceScaleY = l.textureFull.getHeight() / (float) tv.getHeight();
                Rect src = new Rect(
                        Math.round(Math.max(0, region.left - vl[0]) * sourceScaleX),
                        Math.round(Math.max(0, region.top - vl[1]) * sourceScaleY),
                        Math.round(Math.min(tv.getWidth(), region.right - vl[0]) * sourceScaleX),
                        Math.round(Math.min(tv.getHeight(), region.bottom - vl[1]) * sourceScaleY));
                if (src.isEmpty()) {
                    l.valid = false;
                    return;
                }
                int w = Math.max(4, l.dst.width());
                int h = Math.max(4, l.dst.height());
                if (l.frame == null || l.frame.isRecycled()
                        || l.frame.getWidth() != w || l.frame.getHeight() != h) {
                    l.frame = recycle(l.frame);
                    l.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                Canvas crop = new Canvas(l.frame);
                crop.drawColor(0, PorterDuff.Mode.CLEAR);
                crop.drawBitmap(l.textureFull, src, new Rect(0, 0, w, h), paint);
                l.valid = true;
                l.stamp = SystemClock.uptimeMillis();
                onVideoFrame();
                return;
            }

            if (!(l.view instanceof SurfaceView)) return;
            SurfaceView sv = (SurfaceView) l.view;
            if (!sv.getHolder().getSurface().isValid()) return;

            int w = Math.max(4, l.dst.width());
            int h = Math.max(4, l.dst.height());
            if (l.frame == null || l.frame.isRecycled()
                    || l.frame.getWidth() != w || l.frame.getHeight() != h) {
                l.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                l.valid = false;
            }

            // 只抓「底栏那一条」在视频里的那一块
            int[] vl = new int[2];
            sv.getLocationInWindow(vl);
            Rect svRect = new Rect(0, 0, sv.getWidth(), sv.getHeight());
            Rect inter = new Rect(
                    Math.max(region.left, vl[0]) - vl[0],
                    Math.max(region.top, vl[1]) - vl[1],
                    Math.min(region.right, vl[0] + sv.getWidth()) - vl[0],
                    Math.min(region.bottom, vl[1] + sv.getHeight()) - vl[1]);
            if (inter.isEmpty() || !Rect.intersects(inter, svRect)) {
                l.valid = false;
                return;
            }

            long nowMs = SystemClock.uptimeMillis();
            if (inflight > 0 || nowMs - lastRequestAt < 8L) return;
            lastRequestAt = nowMs;
            inflight++;
            l.pending = true;
            PixelCopy.request(sv, inter, l.frame, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    l.pending = false;
                    synchronized (Backdrop.this) {
                        if (inflight > 0) inflight--;
                    }
                    if (result == PixelCopy.SUCCESS) {
                        l.valid = true;
                        l.stamp = SystemClock.uptimeMillis();
                        onVideoFrame();
                    } else {
                        l.valid = false;
                        Dydock.warnOnce("pixelcopy", "抓视频画面失败 code=" + result
                                + "（画面受保护时玻璃退回 UI 层）");
                    }
                }
            }, main);
        } catch (Throwable t) {
            if (l.pending) {
                l.pending = false;
                synchronized (Backdrop.this) {
                    if (inflight > 0) inflight--;
                }
            }
            l.valid = false;
            Dydock.warnOnce("pixelcopy-err", "抓视频画面异常: " + t);
        }
    }

    // ------------------------------------------------------------------ 释放

    private void releaseLayers() {
        for (Layer l : layers) {
            if (l.frame != null && !l.frame.isRecycled() && !l.pending) {
                try {
                    l.frame.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (l.textureFull != null && !l.textureFull.isRecycled()) {
                try {
                    l.textureFull.recycle();
                } catch (Throwable ignored) {
                }
            }
            l.frame = null;
            l.textureFull = null;
        }
        layers.clear();
    }

    private void releaseBitmaps() {
        ui = recycle(ui);
        vid = recycle(vid);
        out = recycle(out);
    }

    private static Bitmap recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try {
                b.recycle();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    void release() {
        main.removeCallbacksAndMessages(null);
        detachPreDraw();
        releaseLayers();
        releaseBitmaps();
        winFrame = recycle(winFrame);
        winBuffer = recycle(winBuffer);
        winPending = false;
        winStamp = 0L;
        window = null;
        maskBefore = null;
        maskAfter = null;
        content = null;
    }
}
