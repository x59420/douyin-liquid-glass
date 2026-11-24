package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Applies Doula-inspired opacity to Douyin-owned chrome only.
 *
 * This intentionally does not touch the native bottom navigation row (dyDock replaces it),
 * our own overlay, video SurfaceView/TextureView pixels, or any layout/touch properties.
 * Host view recognition is heuristic because Doula's implementation is not public; candidates
 * are kept conservative and can be disabled independently in settings.
 */
final class NativeUiTransparency {
    private static final WeakHashMap<Activity, WeakHashMap<View, Float>> ORIGINAL =
            new WeakHashMap<Activity, WeakHashMap<View, Float>>();
    private static final int NODE_BUDGET = 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Runnable> POLLERS = new WeakHashMap<Activity, Runnable>();

    private NativeUiTransparency() {}

    static void start(final Activity activity) {
        if (activity == null) return;
        synchronized (POLLERS) {
            if (POLLERS.containsKey(activity)) return;
            Runnable poller = new Runnable() {
                @Override public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        stop(activity);
                        return;
                    }
                    try { apply(activity); } catch (Throwable ignored) {}
                    MAIN.postDelayed(this, 850L);
                }
            };
            POLLERS.put(activity, poller);
            MAIN.post(poller);
        }
    }

    static void stop(Activity activity) {
        if (activity == null) return;
        Runnable poller;
        synchronized (POLLERS) { poller = POLLERS.remove(activity); }
        if (poller != null) MAIN.removeCallbacks(poller);
        restore(activity);
    }

    static void applyAll() {
        List<Activity> activities;
        synchronized (POLLERS) { activities = new ArrayList<Activity>(POLLERS.keySet()); }
        for (Activity activity : activities) {
            try { apply(activity); } catch (Throwable ignored) {}
        }
    }

    static void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) return;

        boolean topEnabled = DockPrefs.nativeTopEnabled(activity);
        boolean controlsEnabled = DockPrefs.nativeControlsEnabled(activity);
        if (!topEnabled && !controlsEnabled) { restore(activity); return; }

        boolean paused = isPaused((ViewGroup) root);
        int topPercent = paused ? DockPrefs.nativeTopPauseAlpha(activity)
                : DockPrefs.nativeTopPlayAlpha(activity);
        int controlsPercent = paused ? DockPrefs.nativeControlsPauseAlpha(activity)
                : DockPrefs.nativeControlsPlayAlpha(activity);

        List<Candidate> nodes = new ArrayList<Candidate>();
        collect((ViewGroup) root, root, 0, new int[]{NODE_BUDGET}, nodes);
        Candidate top = topEnabled ? chooseTop(nodes) : null;
        Candidate controls = controlsEnabled ? chooseControls(nodes, top) : null;

        WeakHashMap<View, Integer> desired = new WeakHashMap<View, Integer>();
        if (top != null) desired.put(top.view, topPercent);
        if (controls != null && (top == null || !isAncestor(top.view, controls.view))) {
            desired.put(controls.view, controlsPercent);
        }
        applyDesired(activity, desired);
    }

    static void restore(Activity activity) {
        WeakHashMap<View, Float> values;
        synchronized (ORIGINAL) { values = ORIGINAL.remove(activity); }
        if (values == null) return;
        for (View view : values.keySet()) {
            Float alpha = values.get(view);
            if (view != null && alpha != null) {
                try { view.setAlpha(alpha); } catch (Throwable ignored) {}
            }
        }
    }

    private static void applyDesired(Activity activity, WeakHashMap<View, Integer> desired) {
        WeakHashMap<View, Float> old;
        synchronized (ORIGINAL) {
            old = ORIGINAL.get(activity);
            if (old == null) {
                old = new WeakHashMap<View, Float>();
                ORIGINAL.put(activity, old);
            }
        }
        // Restore views that stopped matching, without flashing current candidates.
        for (View previous : new ArrayList<View>(old.keySet())) {
            if (!desired.containsKey(previous)) {
                Float original = old.remove(previous);
                if (previous != null && original != null) {
                    try { previous.setAlpha(original); } catch (Throwable ignored) {}
                }
            }
        }
        for (View view : desired.keySet()) {
            if (view == null) continue;
            try {
                Float original = old.get(view);
                if (original == null) {
                    original = view.getAlpha();
                    old.put(view, original);
                }
                int percent = desired.get(view);
                view.setAlpha(original * (percent / 100f));
            } catch (Throwable ignored) {}
        }
        if (old.isEmpty()) {
            synchronized (ORIGINAL) { ORIGINAL.remove(activity); }
        }
    }

    private static void collect(View view, View root, int depth, int[] budget,
                                List<Candidate> out) {
        if (view == null || depth > 22 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.03f) return;
        if (view instanceof android.view.SurfaceView || view instanceof android.view.TextureView) return;
        String cls = view.getClass().getName();
        if (cls.startsWith("com.zaka.douyin.glass")) return;
        if (view instanceof ViewGroup && view != root && view.getWidth() > 0 && view.getHeight() > 0) {
            Rect page = Shield.rectOf(root);
            Rect bounds = Shield.rectOf(view);
            if (!page.isEmpty() && bounds.intersect(page)) {
                float density = view.getResources().getDisplayMetrics().density;
                String identity = identity(view);
                boolean top = bounds.top <= page.top + page.height() * 0.24f
                        && bounds.height() >= 24f * density && bounds.height() <= 132f * density
                        && bounds.width() >= page.width() * 0.48f
                        && (hasAny(identity, "toolbar", "topbar", "titlebar", "header", "top_bar")
                            || hasTopLabels(view, 0, new int[]{64}));
                boolean controls = bounds.height() >= 24f * density
                        && bounds.height() <= page.height() * 0.70f
                        && bounds.width() >= page.width() * 0.16f
                        && bounds.bottom <= page.bottom - page.height() * 0.16f
                        && (hasAny(identity, "playercontrol", "video_control", "videocontrol",
                                    "playbackcontrol", "player_toolbar", "action_panel")
                            || hasVideoControlLabels(view, 0, new int[]{72}));
                if (top || controls) out.add(new Candidate(view, bounds, top, controls, depth));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                collect(group.getChildAt(i), root, depth + 1, budget, out);
            }
        }
    }

    private static Candidate chooseTop(List<Candidate> nodes) {
        Candidate best = null;
        for (Candidate c : nodes) {
            if (!c.top) continue;
            if (best == null || c.depth < best.depth
                    || (c.depth == best.depth && area(c.bounds) > area(best.bounds))) best = c;
        }
        return best;
    }

    private static Candidate chooseControls(List<Candidate> nodes, Candidate top) {
        Candidate best = null;
        for (Candidate c : nodes) {
            if (!c.controls || (top != null && isAncestor(top.view, c.view))) continue;
            // Prefer the most specific control container to avoid fading the entire video page.
            if (best == null || c.depth > best.depth
                    || (c.depth == best.depth && area(c.bounds) < area(best.bounds))) best = c;
        }
        return best;
    }

    private static boolean isAncestor(View ancestor, View child) {
        if (ancestor == null || child == null) return false;
        android.view.ViewParent p = child.getParent();
        for (int i = 0; i < 32 && p != null; i++, p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static long area(Rect r) { return (long) r.width() * r.height(); }

    private static String identity(View v) {
        StringBuilder b = new StringBuilder(v.getClass().getName().toLowerCase());
        if (v.getId() != View.NO_ID) {
            try { b.append(' ').append(v.getResources().getResourceEntryName(v.getId()).toLowerCase()); }
            catch (Throwable ignored) {}
        }
        return b.toString();
    }

    private static boolean hasTopLabels(View view, int depth, int[] budget) {
        if (view == null || depth > 6 || budget[0]-- <= 0) return false;
        String value = label(view);
        if (containsAny(value, "推荐", "关注", "同城", "朋友", "搜索", "扫一扫", "直播")) return true;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++)
                if (hasTopLabels(g.getChildAt(i), depth + 1, budget)) return true;
        }
        return false;
    }

    private static boolean hasVideoControlLabels(View view, int depth, int[] budget) {
        if (view == null || depth > 7 || budget[0]-- <= 0) return false;
        String value = label(view);
        if (containsAny(value, "暂停", "播放", "倍速", "进度", "点赞", "评论", "分享",
                "收藏", "音量", "全屏", "选集")) return true;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++)
                if (hasVideoControlLabels(g.getChildAt(i), depth + 1, budget)) return true;
        }
        return false;
    }

    private static boolean isPaused(View root) {
        return findPlaybackState(root, 0, new int[]{900});
    }

    private static boolean findPlaybackState(View view, int depth, int[] budget) {
        if (view == null || depth > 18 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE) return false;
        String identity = identity(view);
        String value = label(view).toLowerCase();
        boolean buttonLike = view.isClickable() || view.hasOnClickListeners()
                || hasAny(identity, "player", "playback", "control", "play_button", "pause_button");
        if (buttonLike) {
            if (value.contains("播放") || value.contains("play")) return true;
            if (value.contains("暂停") || value.contains("pause")) return false;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++) {
                View child = g.getChildAt(i);
                if (findPlaybackState(child, depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static String label(View view) {
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        return value.toLowerCase();
    }

    private static boolean containsAny(String value, String... words) {
        if (value == null || value.isEmpty()) return false;
        for (String word : words) if (value.contains(word.toLowerCase())) return true;
        return false;
    }

    private static boolean hasAny(String value, String... words) {
        return containsAny(value, words);
    }

    private static final class Candidate {
        final View view;
        final Rect bounds;
        final boolean top;
        final boolean controls;
        final int depth;
        Candidate(View view, Rect bounds, boolean top, boolean controls, int depth) {
            this.view = view; this.bounds = new Rect(bounds);
            this.top = top; this.controls = controls; this.depth = depth;
        }
    }
}
