package com.zaka.douyin.glass;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashSet;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志。
 *
 * 只有一件事要做：出问题时能在 LSPosed 日志里看到卡在哪一步。
 * 这里不弹窗、不写文件、不广播，宿主进程里除了 Log 之外不留任何痕迹。
 */
public final class Dydock {

    public static final String TAG = "dydock";
    public static final String VER = "0.0.3";
    public static final String HOST = "com.ss.android.ugc.aweme";
    public static final String PKG = "com.zaka.douyin.glass";

    private static final int MAX = 120;
    private static final ArrayDeque<String> RING = new ArrayDeque<String>(MAX);
    private static final HashSet<String> ONCE = new HashSet<String>();

    private Dydock() {
    }

    public static void log(String msg) {
        push("I", msg);
    }

    public static void warn(String msg) {
        push("W", msg);
    }

    public static void error(String what, Throwable t) {
        push("E", what + ": " + t);
    }

    public static void warnOnce(String key, String msg) {
        synchronized (ONCE) {
            if (!ONCE.add(key)) return;
        }
        push("W", msg);
    }

    private static void push(String level, String msg) {
        String line = level + "/" + msg;
        synchronized (RING) {
            if (RING.size() >= MAX) RING.pollFirst();
            RING.addLast(line);
        }
        try {
            Log.println("I".equals(level) ? Log.INFO : ("W".equals(level) ? Log.WARN : Log.ERROR),
                    TAG, msg);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (RING) {
            for (String s : RING) sb.append(s).append('\n');
        }
        return sb.toString();
    }
}
