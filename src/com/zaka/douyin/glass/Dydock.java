package com.zaka.douyin.glass;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;

import de.robv.android.xposed.XposedBridge;

/**
 * Dydock diagnostics.
 *
 * Keeps the normal LSPosed log and additionally appends a copy to
 * Download/Dydock/dydock-diagnostic.log on Android 10+.
 */
public final class Dydock {

    public static final String TAG = "dydock";
    public static final String VER = "1.1.4";
    public static final String HOST = "com.ss.android.ugc.aweme";
    public static final String PKG = "com.zaka.douyin.glass";

    private static final int MAX = 120;
    private static final ArrayDeque<String> RING = new ArrayDeque<String>(MAX);
    private static final HashSet<String> ONCE = new HashSet<String>();
    private static final Object FILE_LOCK = new Object();

    private static volatile Context appContext;
    private static volatile Uri logUri;
    private static volatile File fallbackFile;
    private static volatile boolean fileReady;
    /** 日志开关：默认开，设置页里关掉之后日常流程不再写日志。 */
    private static volatile boolean verbose = true;
    private static volatile Thread.UncaughtExceptionHandler previousCrashHandler;

    private Dydock() {
    }

    /** Called as soon as the host Application has completed onCreate. */
    public static void attach(Context context) {
        if (context == null) return;
        synchronized (FILE_LOCK) {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
        }
        try {
            verbose = DockPrefs.logEnabled(context);
        } catch (Throwable ignored) {
        }
        // 诊断文件走 MediaStore，放到后台线程建，别在宿主启动那一下卡主线程。
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                initFile();
            }
        }, "dydock-diagnostics");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable t) {
            Log.e(TAG, "Unable to start diagnostic writer", t);
        }
    }

    private static void initFile() {
        synchronized (FILE_LOCK) {
            // 两条路都铺上：MediaStore 的 Download/Dydock（文件管理器直接能看到），
            // 以及宿主自己的外部目录 Android/data/<宿主>/files/Documents（MediaStore 被挡时兜底）。
            // 少一条都可能变成「用户那边根本找不到日志」。
            try {
                File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    fallbackFile = new File(dir, "dydock-diagnostic.log");
                }
            } catch (Throwable ignored) {
            }
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    logUri = findOrCreateDownloadLog(appContext);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Unable to initialize MediaStore log", t);
            }
            fileReady = logUri != null || fallbackFile != null;

            if (fileReady) {
                appendFile("===== Dydock diagnostic session started; module " + VER
                        + " · host " + hostSignature() + " · Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ") =====");
                synchronized (RING) {
                    for (String line : RING) appendFile(line);
                }
            }
        }
    }

    private static Uri findOrCreateDownloadLog(Context context) {
        ContentResolver resolver = context.getContentResolver();
        // 每个会话一个时间戳文件名：重名会被 MediaStore 改名甚至拒绝，
        // 之前固定名字 + 几十个副本之后日志就彻底不写了（0.7.4/0.7.5 全是瞎跑）。
        String name = "dydock-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".log";
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/Dydock/";
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                            + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{name, relativePath},
                    MediaStore.Downloads._ID + " DESC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Long.toString(id));
            }
        } finally {
            if (cursor != null) cursor.close();
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }

    /** Preserve the existing process crash handler while recording Java fatal exceptions. */
    public static synchronized void installCrashHandler() {
        if (previousCrashHandler != null) return;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        previousCrashHandler = previous;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    error("UNCAUGHT on " + thread.getName(), error);
                    flushFile();
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public static void log(String msg) {
        if (!verbose) return;
        push("I", msg, null);
    }

    /** 设置页的日志开关：关掉之后日常流程不再刷日志，警告/错误照旧。 */
    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static boolean verbose() {
        return verbose;
    }

    public static void warn(String msg) {
        push("W", msg, null);
    }

    public static void error(String what, Throwable t) {
        push("E", what, t);
    }

    public static void warnOnce(String key, String msg) {
        synchronized (ONCE) {
            if (!ONCE.add(key)) return;
        }
        push("W", msg, null);
    }

    private static void push(String level, String msg, Throwable error) {
        String line = level + "/" + msg;
        if (error != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            line += "\n" + sw.toString();
        }
        synchronized (RING) {
            if (RING.size() >= MAX) RING.pollFirst();
            RING.addLast(line);
        }
        try {
            int priority = "I".equals(level) ? Log.INFO
                    : ("W".equals(level) ? Log.WARN : Log.ERROR);
            if (error == null) Log.println(priority, TAG, msg);
            else Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(error));
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.log(TAG + ": " + msg
                    + (error == null ? "" : "\n" + Log.getStackTraceString(error)));
        } catch (Throwable ignored) {
        }
        if (fileReady) appendFile(line);
    }

    private static void appendFile(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        boolean any = false;
        synchronized (FILE_LOCK) {
            if (logUri != null && appContext != null) {
                try {
                    OutputStream out = appContext.getContentResolver().openOutputStream(logUri, "wa");
                    if (out != null) {
                        out.write(bytes);
                        out.close();
                        any = true;
                    }
                } catch (Throwable t) {
                    logUri = null;
                    try { Log.e(TAG, "Diagnostic MediaStore write failed", t); } catch (Throwable ignored) { }
                }
            }
            if (fallbackFile != null) {
                try {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(fallbackFile, true);
                    out.write(bytes);
                    out.close();
                    any = true;
                } catch (Throwable t) {
                    try { Log.e(TAG, "Diagnostic file write failed", t); } catch (Throwable ignored) { }
                }
            }
            fileReady = any;
        }
    }

    /** 宿主包名 + 版本，日志里一眼能看出装的是哪个抖音。 */
    private static String hostSignature() {
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return appContext.getPackageName() + " " + info.versionName
                    + "(" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return appContext == null ? "?" : appContext.getPackageName();
        }
    }

    private static void flushFile() {
        // Each record is closed after writing, so there is no buffered data to flush.
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (RING) {
            for (String s : RING) sb.append(s).append('\n');
        }
        return sb.toString();
    }
}
