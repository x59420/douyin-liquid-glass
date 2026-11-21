package com.zaka.douyin.glass;

import android.graphics.Bitmap;

/**
 * 可分离盒式模糊（三次迭代逼近高斯），纯 Java，不依赖 RenderEffect，
 * 所以 Android 8~15 表现一致。
 */
final class BoxBlur {

    static void blur(Bitmap bmp, int radius) {
        if (bmp == null || radius < 1) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 2 || h < 2) return;

        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);

        int[] tmp = new int[Math.max(w, h)];
        int r = radius;
        for (int i = 0; i < 3; i++) {
            pass(pix, tmp, w, h, r, true);
            pass(pix, tmp, w, h, r, false);
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] pix, int[] tmp, int w, int h, int radius, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int window = radius * 2 + 1;

        for (int line = 0; line < lines; line++) {
            // 取出这一行/列
            if (horizontal) {
                System.arraycopy(pix, line * w, tmp, 0, w);
            } else {
                for (int i = 0; i < h; i++) tmp[i] = pix[i * w + line];
            }

            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int idx = clamp(i, len);
                int c = tmp[idx];
                sa += (c >>> 24) & 0xFF;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }

            for (int i = 0; i < len; i++) {
                int a = (int) (sa / window);
                int rr = (int) (sr / window);
                int gg = (int) (sg / window);
                int bb = (int) (sb / window);
                int out = (a << 24) | (rr << 16) | (gg << 8) | bb;

                int addIdx = clamp(i + radius + 1, len);
                int subIdx = clamp(i - radius, len);
                int ac = tmp[addIdx];
                int sc = tmp[subIdx];
                sa += ((ac >>> 24) & 0xFF) - ((sc >>> 24) & 0xFF);
                sr += ((ac >> 16) & 0xFF) - ((sc >> 16) & 0xFF);
                sg += ((ac >> 8) & 0xFF) - ((sc >> 8) & 0xFF);
                sb += (ac & 0xFF) - (sc & 0xFF);

                if (horizontal) {
                    pix[line * w + i] = out;
                } else {
                    pix[i * w + line] = out;
                }
            }
        }
    }

    private static int clamp(int i, int len) {
        if (i < 0) return 0;
        if (i >= len) return len - 1;
        return i;
    }

    private BoxBlur() {
    }
}
