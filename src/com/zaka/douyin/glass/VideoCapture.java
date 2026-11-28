package com.zaka.douyin.glass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * 视频页的真实背景：录屏授权 + 镜像帧。
 *
 * 为什么必须走录屏：抖音的视频/直播画面在独立的 Surface 上，`View.draw()` 画不到、
 * `PixelCopy` 对受保护 Surface 返回 code=1。唯一能拿到「玻璃后面那块真实像素」的办法
 * 就是 MediaProjection。
 *
 * 流程：
 *   1. 用户在设置里打开「视频页真实背景（录屏）」
 *   2. 宿主（抖音进程）里发一次系统授权框（授权绑定的是调用方 uid，所以必须在宿主里发起）
 *   3. 授权成功后建 VirtualDisplay + ImageReader（半分辨率），按 30fps 取帧
 *   4. 每帧裁出「Dock 那条带子」，推给玻璃层当背景位图
 *
 * 失败（比如系统要求前台服务）就整体降级：不抛异常、不挡抖音，只是玻璃回到原来的样子。
 */
final class VideoCapture {

    /** 请求码，和 GlassEntry 里的 onActivityResult 钩子对应。 */
    static final int REQUEST = 0x5d0c1;

    private static volatile boolean pending;
    private static volatile boolean active;
    /** 本次进程里已经自动请求过几次：只自动弹一次，避免和用户手上的授权框互相顶掉。 */
    private static volatile int attempts;
    private static volatile long lastRequestAt;
    private static volatile MediaProjection projection;
    private static volatile VirtualDisplay display;
    private static volatile ImageReader reader;
    private static volatile HandlerThread thread;

    private VideoCapture() {
    }

    static boolean isActive() {
        return active;
    }

    static boolean isPending() {
        return pending;
    }

    /** 从宿主 Activity 发起一次录屏授权。同一时间只允许一个请求在飞。 */
    static void request(Activity activity) {
        if (activity == null || active || pending) return;
        // 关键：每个进程只自动弹一次。
        // 之前是「没成功就每 2 秒再弹」，结果第一个授权框还没点完，
        // 第二个就起来了 —— 前一个被系统当作取消（RESULT_CANCELED），
        // 于是永远拿不到授权，看起来就是「一直重复弹窗」。
        if (attempts >= 1) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRequestAt < 10_000L) return;
        lastRequestAt = now;
        attempts++;
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) return;
            pending = true;
            activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST);
            Dydock.log("录屏:已发起授权请求");
        } catch (Throwable t) {
            pending = false;
            Dydock.warnOnce("capture-request", "发起录屏授权失败: " + t);
        }
    }

    /** 用户在设置里重新打开开关时调用：允许再自动请求一次。 */
    static void resetAttempts() {
        attempts = 0;
        lastRequestAt = 0L;
        pending = false;
    }

    /** GlassEntry 里 Activity.onActivityResult 的回调。 */
    static void onResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST) return;
        pending = false;
        if (resultCode != Activity.RESULT_OK || data == null) {
            Dydock.log("录屏:用户拒绝或取消授权，保持原样");
            return;
        }
        start(activity, resultCode, data);
    }

    private static void start(Activity activity, int resultCode, Intent data) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mp = manager.getMediaProjection(resultCode, data);
            if (mp == null) {
                Dydock.warn("录屏:getMediaProjection 返回 null");
                return;
            }
            int w = Math.max(64, activity.getResources().getDisplayMetrics().widthPixels / 2);
            int h = Math.max(64, activity.getResources().getDisplayMetrics().heightPixels / 2);
            int dpi = activity.getResources().getDisplayMetrics().densityDpi;

            thread = new HandlerThread("dydock-capture");
            thread.start();
            Handler handler = new Handler(thread.getLooper());

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            display = mp.createVirtualDisplay("dydock", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image == null) return;
                        pushFrame(image);
                    } catch (Throwable t) {
                        Dydock.warnOnce("capture-frame", "取录屏帧失败: " + t);
                    } finally {
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }, handler);

            projection = mp;
            active = true;
            Dydock.log("录屏:已开始（" + w + "x" + h + "），视频页玻璃将折射真实画面");
            // 让玻璃换成「用位图」的那条路（实时图层画不到 Surface）
            DockController.requestReinstall();
        } catch (Throwable t) {
            Dydock.warn("录屏:启动失败（系统可能要求前台服务）: " + t);
            active = false;
        }
    }

    /** 把一帧里的「Dock 那条带子」裁出来，推给正在显示的玻璃层。 */
    private static void pushFrame(Image image) {
        android.media.Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return;
        java.nio.ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int imgW = image.getWidth();
        int imgH = image.getHeight();

        Bitmap full = Bitmap.createBitmap(imgW + (rowStride - pixelStride * imgW) / pixelStride,
                imgH, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        full.copyPixelsFromBuffer(buffer);

        java.util.List<Activity> acts = DockController.captureTargets();
        if (acts.isEmpty()) {
            full.recycle();
            return;
        }
        for (Activity act : acts) {
            Rect band = DockController.captureBand(act);
            if (band == null || band.isEmpty()) continue;
            int scaleX = imgW;
            int screenW = Math.max(1, act.getResources().getDisplayMetrics().widthPixels);
            int screenH = Math.max(1, act.getResources().getDisplayMetrics().heightPixels);
            float kx = imgW / (float) screenW;
            float ky = imgH / (float) screenH;
            Rect src = new Rect(
                    Math.max(0, Math.round(band.left * kx)),
                    Math.max(0, Math.round(band.top * ky)),
                    Math.min(imgW, Math.round(band.right * kx)),
                    Math.min(imgH, Math.round(band.bottom * ky)));
            if (src.width() < 8 || src.height() < 8) continue;
            Bitmap crop = Bitmap.createBitmap(full, src.left, src.top, src.width(), src.height());
            DockController.pushCapturedFrame(act, crop);
        }
        full.recycle();
    }
}
