package com.zaka.douyin.glass;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 判断某个控件是不是被「同一个 Activity 里叠上来的整屏页面」盖住了。
 *
 * 这里记一笔坑（0.0.2 底栏整条消失的根因）：
 * 上一版判据只看「兄弟节点够不够大」——只要有个 92% 宽、80% 高的 ViewGroup
 * 画在我后面，就认定我被盖住了。结果抖音首页跟底栏同父的信息流容器
 * 每帧都满足，玻璃一挂上就被设成 GONE；两秒看门狗一看没画出来，
 * 又把官方底栏 alpha 还原回去 —— 用户看到的就是「dock 回到官方，模块没生效」。
 *
 * 现在必须同时满足三条才算被盖住：
 *   1. 它排在 v 后面（真的压在 v 上面，不是垫在下面）
 *   2. 它的矩形把 v 那一块**整个**盖住（只盖一半的不算）
 *   3. 它是个有内容的整屏页面（高度接近 content、里面有实际控件）
 */
final class Shield {

    private Shield() {
    }

    /** Resolve the Activity that owns a host View without assuming a direct Activity context. */
    static Activity activityOf(View view) {
        if (view == null) return null;
        Context context = view.getContext();
        for (int i = 0; i < 12 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    /**
     * First-pass long-press filter: accept only a large video/feed touch surface in the
     * central playback area. Small buttons and navigation items must not hide the Dock.
     */
    static boolean isVideoLongPressTarget(View view, Activity activity) {
        if (view == null || activity == null || view.getVisibility() != View.VISIBLE
                || view.getAlpha() < 0.05f || !view.isShown()) return false;
        String name = view.getClass().getName().toLowerCase();
        if (name.contains("dockoverlay") || name.contains("bottomnavigation")
                || name.contains("tabview") || name.contains("navigationbar")) return false;
        // 列表 / 翻页容器本身不是视频触摸面。消息 Tab 的聊天列表整块都满足
        // 「够大、在页面中间」，以前手指在列表上按住超过判定线就会把 Dock 收掉。
        if (name.contains("recyclerview") || name.contains("listview")
                || name.contains("scrollview") || name.contains("viewpager")
                || name.contains("pager") || name.contains("horizontalscroll")) return false;
        View content = activity.findViewById(android.R.id.content);
        if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return false;
        Rect page = rectOf(content);
        Rect bounds = rectOf(view);
        if (!bounds.intersect(page)) return false;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        return bounds.width() >= page.width() * 0.32f
                && bounds.height() >= page.height() * 0.22f
                && cx >= page.left + page.width() * 0.08f
                && cx <= page.right - page.width() * 0.08f
                && cy >= page.top + page.height() * 0.12f
                && cy <= page.bottom - page.height() * 0.22f;
    }

    /**
     * Clear-screen detection is deliberately state-based, not gesture-based.
     * It looks for explicit visible host markers (view/resource names or exit labels).
     * This is a conservative first pass; the exact Douyin marker still needs device
     * verification because host builds can rename or remove these views.
     */
    static boolean hasClearScreenState(View root) {
        return findClearScreenMarker(root, 0, new int[]{1400});
    }

    private static boolean findClearScreenMarker(View view, int depth, int[] budget) {
        if (view == null || depth > 16 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return false;
        String name = view.getClass().getName().toLowerCase();
        if (name.contains("clearscreen") || name.contains("clear_screen")
                || name.contains("cleanscreen") || name.contains("clean_screen")
                || name.contains("screenclean") || name.contains("clearview")
                || name.contains("qingping")) return true;
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                String resource = view.getResources().getResourceEntryName(id).toLowerCase();
                if (resource.contains("clear_screen") || resource.contains("clearscreen")
                        || resource.contains("clean_screen") || resource.contains("cleanscreen")
                        || resource.contains("qingping") || resource.contains("clear_view")) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        String lowerValue = value.toLowerCase();
        if (value.contains("退出清屏") || value.contains("退出清屏模式")
                || value.contains("恢复显示") || value.contains("退出纯净模式")
                || value.contains("清屏播放") || value.contains("清屏模式")
                || value.contains("纯净模式") || value.contains("轻触屏幕退出")
                || lowerValue.contains("exit clear screen")
                || lowerValue.contains("clear screen mode")) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                if (findClearScreenMarker(group.getChildAt(i), depth + 1, budget)) return true;
            }
        }
        return false;
    }

    static Rect rectOf(View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    static boolean covered(View v, View content) {
        if (v == null || content == null) return false;
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;

        Rect target = rectOf(v);
        Rect page = rectOf(content);
        if (target.isEmpty() || page.isEmpty()) return false;

        float tol = 6f * v.getResources().getDisplayMetrics().density;

        View cur = v;
        for (int guard = 0; guard < 12 && cur != null && cur != content; guard++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) p;
            int idx = g.indexOfChild(cur);
            if (idx < 0) break;
            for (int i = idx + 1; i < g.getChildCount(); i++) {
                if (covers(g.getChildAt(i), target, page, tol)) return true;
            }
            cur = g;
        }
        return false;
    }

    /**
     * 当前页是不是被另一个整屏页面盖住了（会话页、聊天框里点开的视频、详情页、我的页展开类页面）。
     *
     * 与 covered() 的区别：这里判的是「把 Dock 这一条整个盖住」的整屏容器，
     * 不要求它自带背景色 —— 抖音不少覆盖页是透明容器 + 内部各自铺底。
     * 但保留两条硬约束，缺一就会被信息流容器误伤（0.0.2 底栏整条消失的老坑）：
     *   1. 它必须排在 Dock 之后（真的压在上面，而不是垫在下面/排在前面）；
     *   2. 它必须是铺满整页、并且有实际内容的整屏页面。
     * 信息流容器虽然也铺满，但它排在底栏前面，第 1 条直接否掉。
     *
     * @param dockRect Dock 那一带的窗口坐标（Dock 已被设成 GONE，用缓存的矩形更稳）
     */
    static boolean pageCoversDock(View dock, Rect dockRect, View content) {
        if (dock == null || content == null) return false;
        Rect page = rectOf(content);
        if (page.isEmpty() || page.width() <= 0 || page.height() <= 0) return false;
        Rect target = dockRect != null && !dockRect.isEmpty() ? dockRect : rectOf(dock);
        if (target.isEmpty()) return false;
        float tol = 8f * content.getResources().getDisplayMetrics().density;

        // 顺着 Dock 自己的父链往上走，只检查每一层里「排在 Dock 后面」的兄弟。
        // 排在前面的是内容区（信息流就在那里），它盖不住底栏，也不该被当成覆盖页。
        View cur = dock;
        for (int guard = 0; guard < 14 && cur != null && cur != content; guard++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) p;
            int idx = g.indexOfChild(cur);
            if (idx < 0) break;
            for (int j = idx + 1; j < g.getChildCount(); j++) {
                if (coversDockFully(g.getChildAt(j), target, page, tol)) return true;
            }
            cur = g;
        }
        return false;
    }

    private static boolean coversDockFully(View s, Rect target, Rect page, float tol) {
        if (s == null || s.getVisibility() != View.VISIBLE || s.getAlpha() < 0.06f) return false;
        if (!s.isShown()) return false;
        if (s.getWidth() <= 0 || s.getHeight() <= 0) return false;
        if (s instanceof android.view.SurfaceView
                || s instanceof android.view.TextureView) return false;
        String cn = s.getClass().getName();
        if (cn.startsWith("com.zaka.douyin.glass")) return false;

        Rect r = rectOf(s);
        if (r.width() < page.width() * 0.90f) return false;
        if (r.height() < page.height() * 0.82f) return false;
        // 必须把 Dock 那一块整个盖住：只盖住一半的（信息流、半屏弹层）不算。
        if (r.left > target.left + tol) return false;
        if (r.top > target.top + tol) return false;
        if (r.right < target.right - tol) return false;
        if (r.bottom < target.bottom - tol) return false;

        String className = s.getClass().getName().toLowerCase();
        boolean profileContainer = className.contains("userprofile")
                || className.contains("userdetail") || className.contains("personalpage")
                || className.contains("profiledetail") || className.contains("othershomepage");
        if (profileContainer || hasProfileSignature(s)) return true;
        if (s.getBackground() != null) return true;
        if (!(s instanceof ViewGroup)) return false;
        return bigVisibleChildren((ViewGroup) s) >= 2;
    }

    /** 透明容器里至少要有两个够大的可见子控件，才算「真的是一页内容」。 */
    private static int bigVisibleChildren(ViewGroup g) {
        int n = 0;
        int min = (int) (24f * g.getResources().getDisplayMetrics().density);
        for (int i = 0; i < g.getChildCount() && n < 3; i++) {
            View c = g.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE && c.getAlpha() > 0.1f
                    && c.getWidth() > min && c.getHeight() > min) n++;
        }
        return n;
    }

    /**
     * 识别他人个人主页。自己的「我」页面通常带有「编辑资料」，必须明确排除。
     * 他人主页同时具备粉丝、关注和私信等资料页操作特征，避免把普通信息流误判。
     */
    static boolean isOtherProfilePage(View root) {
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return false;
        // 单独优先扫描本人主页标记，避免深层视图树超过普通扫描预算后漏掉「编辑资料」。
        if (containsVisibleMarker(root, "编辑资料", 0, new int[]{6000})) return false;
        String[] markers = {"编辑资料", "粉丝", "关注", "私信", "发消息", "抖音号", "获赞"};
        boolean[] found = new boolean[markers.length];
        collectProfileMarkers(root, 0, markers, found, new int[]{6000});
        // 本人主页的「编辑资料」优先级最高，避免误隐藏「我」主 Tab。
        if (found[0]) return false;
        // 他人主页必须同时出现资料页的粉丝/关注关系和私信操作。
        return found[1] && (found[5] || found[6])
                && (found[2] || found[3] || found[4]);
    }

    /**
     * 页面里有没有正在显示的、够大的视频层。
     *
     * 长按倍速只在这些页面上有意义；消息列表、我的页这类页面没有视频层，
     * 手指按住列表超过判定线时不该把 Dock 收掉 —— 那是「上下滚动会显隐 Dock」的来源之一。
     */
    static boolean hasVideoSurface(View root) {
        if (root == null) return false;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return false;
        long minArea = (long) w * h * 32L / 100L;
        return findVideoSurface(root, 0, new int[]{900}, minArea);
    }

    private static boolean findVideoSurface(View v, int depth, int[] budget, long minArea) {
        if (v == null || depth > 32 || budget[0]-- <= 0) return false;
        if (v.getVisibility() != View.VISIBLE || !v.isShown() || v.getAlpha() < 0.1f) return false;
        if (v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) {
            return (long) v.getWidth() * v.getHeight() >= minArea;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++) {
                if (findVideoSurface(g.getChildAt(i), depth + 1, budget, minArea)) return true;
            }
        }
        return false;
    }

    /**
     * 菜单、侧栏和个人页展开面板：不一定完全盖住底栏，但 Dock 也不应浮在菜单上。
     * 只检查 Dock 所在祖先链上、绘制顺序排在 Dock 后面的兄弟浮层。
     */
    static boolean hasExpandedOverlay(View dock, View content, View decor) {
        if (dock == null || content == null) return false;
        Rect page = rectOf(content);
        if (page.isEmpty()) return false;
        Rect dockRect = rectOf(dock);

        // 先检查 Dock 所在内容树中排在它后面的兄弟视图。
        View cur = dock;
        for (int guard = 0; guard < 12 && cur != null && cur != content; guard++) {
            ViewParent parent = cur.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup group = (ViewGroup) parent;
            int index = group.indexOfChild(cur);
            if (index < 0) break;
            for (int i = index + 1; i < group.getChildCount(); i++) {
                if (containsExpandedPanel(group.getChildAt(i), page, dockRect, true, 0, new int[]{220})) {
                    return true;
                }
            }
            cur = group;
        }

        // 关键修复：玻璃 Dock 被添加到 DecorView，位于 android.R.id.content 之后。
        // 抖音有些菜单/弹层直接挂在 DecorView，而不是 content 的子树里；
        // 旧逻辑走到 content 就停止，永远看不到这些窗口级兄弟视图。
        if (decor instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) decor;
            int contentIndex = root.indexOfChild(content);
            if (contentIndex >= 0) {
                for (int i = contentIndex + 1; i < root.getChildCount(); i++) {
                    View candidate = root.getChildAt(i);
                    if (candidate == null) continue;
                    String className = candidate.getClass().getName();
                    // 我们自己的玻璃层也挂在 DecorView 上，别把它当成宿主浮层。
                    if (className.startsWith("com.zaka.douyin.glass")) continue;
                    if (containsExpandedPanel(candidate, page, dockRect, false, 0, new int[]{220})) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * 抖音长按视频进入倍速播放时会在页面层显示倍速提示。
     * 识别可见文案/无障碍描述，不把普通长按暂停当成倍速。
     */
    static boolean hasSpeedPlaybackOverlay(View root) {
        return hasSpeedMarker(root, 0, new int[]{1800});
    }

    private static boolean hasSpeedMarker(View view, int depth, int[] budget) {
        if (view == null || depth > 14 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return false;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        String lower = value.toLowerCase();
        if (value.contains("倍速") || lower.contains("倍速")
                || lower.contains("长按加速") || lower.contains("加速播放中")
                || lower.contains("speed playback") || lower.contains("playback speed")
                || lower.contains("speed up")) return true;
        // 只显示「2x / 1.5x / ×2」这类纯倍率标签，避免正文里的数字被误判。
        String trimmed = value.trim();
        if (trimmed.matches("(?i)^\\d{1,2}(\\.\\d{1,2})?\\s*[x×]$")) return true;
        // 视图类名 / 资源名里带 speed 的提示控件也算（换皮时文字节点可能被换掉）。
        String cls = view.getClass().getName().toLowerCase();
        if (cls.contains("speedplay") || cls.contains("speedtip")
                || cls.contains("playbackspeed") || cls.contains("playbackrate")) return true;
        int vid = view.getId();
        if (vid != View.NO_ID) {
            try {
                String res = view.getResources().getResourceEntryName(vid).toLowerCase();
                if (res.contains("speed_play") || res.contains("play_speed")
                        || res.contains("speed_tip") || res.contains("playback_rate")) return true;
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                View child = group.getChildAt(i);
                if (child != null && child.getClass().getName().startsWith("com.zaka.douyin.glass")) continue;
                if (hasSpeedMarker(child, depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static boolean containsExpandedPanel(View view, Rect page, Rect dockRect,
                                                 boolean insidePage, int depth, int[] budget) {
        if (view == null || depth > 8 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.08f
                || !(view instanceof ViewGroup)) return false;

        // Small descendants cannot contain a qualifying expanded menu/profile panel.
        // Pruning them avoids repeatedly walking the entire feed tree on the UI thread.
        Rect bounds = rectOf(view);
        Rect visibleBounds = new Rect(bounds);
        if (!visibleBounds.intersect(page)
                || visibleBounds.width() < page.width() * 0.20f
                || visibleBounds.height() < page.height() * 0.20f
                || bounds.width() < page.width() * 0.34f
                || bounds.height() < page.height() * 0.28f) return false;

        String className = view.getClass().getName();
        if (className.startsWith("com.zaka.douyin.glass")) return false;
        if (isExpandedPanel(view, page, dockRect, insidePage)) return true;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
            if (containsExpandedPanel(group.getChildAt(i), page, dockRect, insidePage,
                    depth + 1, budget)) return true;
        }
        return false;
    }

    /**
     * 一个 ViewGroup 是不是「压在 Dock 上的展开面板」。
     *
     * 消息 Tab 的聊天列表之前就是这么被误判成会话浮层的：它铺满整页、带输入框，
     * 于是手指一滑列表，Dock 就被当成「被浮层盖住」收掉。
     * 现在有两条硬约束：
     *   1. 浮层矩形必须真的和 Dock 那一带相交 —— 停在 Dock 上方的页面内容不算；
     *   2. 页面内容树（content 子树）里只认按类名明确写了 menu/drawer/sheet/dialog/panel
     *      的容器；「有输入框 / 有会话文案」这类内容启发式只对独立浮层窗口生效。
     */
    private static boolean isExpandedPanel(View view, Rect page, Rect dockRect, boolean insidePage) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.08f
                || !(view instanceof ViewGroup) || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect r = rectOf(view);
        Rect visibleBounds = new Rect(r);
        if (!visibleBounds.intersect(page)
                || visibleBounds.width() < page.width() * 0.20f
                || visibleBounds.height() < page.height() * 0.20f
                || r.width() < page.width() * 0.34f
                || r.height() < page.height() * 0.28f) return false;
        if (dockRect != null && !dockRect.isEmpty() && !Rect.intersects(r, dockRect)) return false;
        String name = view.getClass().getName().toLowerCase();
        // 评论区：抖音原生在这里**不会**收底栏，底栏一直贴在评论区底下。
        // 旧版把评论面板当「压在 Dock 上的浮层」，展开时收一次、面板铺好后判据又不成立，
        // Dock 再冒出来 —— 用户看到的就是闪一下。
        // 注意只排除「半屏的评论面板」：整屏页面（别人主页、详情页）照旧要走下面的判据。
        if (isCommentSheet(view, r, page, name)) return true;
        // Do not classify arbitrary large background containers as menus.
        // The host's normal feed/page containers also have backgrounds and children;
        // treating them as expanded panels makes the Dock appear briefly, then vanish.
        // Only positively identified popup/menu/drawer/sheet/dialog/panel views qualify.
        boolean namedPanel = name.contains("popup") || name.contains("menu")
                || name.contains("drawer") || name.contains("sheet")
                || name.contains("dialog") || name.contains("panel");
        if (insidePage) {
            // 页面自己的内容（消息列表、个人页主页）不按文案判浮层，只看类名。
            if (namedPanel && hasContent((ViewGroup) view, 0)) return true;
            // 抖音「我的」页右上角那个菜单、以及各种侧滑面板，常常就是一个普通 FrameLayout，
            // 类名里没有 drawer/menu/sheet 这些字样。这里按几何兜住：
            // 既然它排在 Dock 之后（调用方已经筛过）、又和 Dock 那一带相交、
            // 还占走了半屏以上，那它大概率是盖在 Dock 上的浮层。
            // 但必须排除「整屏容器」：那种要么是覆盖页（pageCoversDock 管），
            // 要么就是页面自己的内容层 —— 把它当浮层就是「Dock 刚出来就被收掉」的老毛病。
            // 判据：至少要有一条边比页面内缩 4% 以上，浮层/抽屉/半屏面板天然满足。
            boolean tall = r.height() >= page.height() * 0.45f;
            boolean wide = r.width() >= page.width() * 0.28f;
            float insetX = page.width() * 0.04f;
            float insetY = page.height() * 0.04f;
            boolean inset = r.top - page.top > insetY
                    || page.bottom - r.bottom > insetY
                    || r.left - page.left > insetX
                    || page.right - r.right > insetX;
            return tall && wide && inset && hasContent((ViewGroup) view, 0);
        }
        boolean profilePage = name.contains("userprofile") || name.contains("userdetail")
                || name.contains("personalpage") || name.contains("profiledetail")
                || name.contains("othershomepage");
        boolean menuText = hasMenuMarker(view, 0);
        boolean profileText = hasProfileSignature(view);
        boolean conversationPanel = isConversationPanel(view, r, page, name);
        if (!namedPanel && !profilePage && !menuText && !profileText && !conversationPanel) return false;
        return hasContent((ViewGroup) view, 0);
    }

    /**
     * 判断评论面板是否仍有足够大的可见区域。
     *
     * rectOf() 只返回 View 的布局矩形；评论面板向下拖动关闭时，布局矩形可能仍在屏幕内，
     * 但真正显示出来的部分已经被父容器裁掉。这里用 getGlobalVisibleRect() 取得裁剪后的
     * 可见矩形，并与页面可见区域求交，避免把正在退出的面板继续当成打开状态。
     */
    private static boolean isCommentPanelActuallyVisible(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        View root = view.getRootView();
        if (root == null) return false;

        Rect pageVisible = new Rect();
        Rect panelVisible = new Rect();
        if (!root.getGlobalVisibleRect(pageVisible)
                || !view.getGlobalVisibleRect(panelVisible)
                || pageVisible.isEmpty()
                || !panelVisible.intersect(pageVisible)) return false;

        Rect panelLayout = rectOf(view);
        float minVisibleHeight = Math.max(
                pageVisible.height() * 0.12f,
                panelLayout.height() * 0.45f);
        return panelVisible.width() >= pageVisible.width() * 0.55f
                && panelVisible.height() >= minVisibleHeight
                && panelVisible.bottom >= pageVisible.bottom - pageVisible.height() * 0.45f;
    }

    /**
     * 这是不是「评论区面板」：只接受评论容器，并且它当前仍有足够大的可见区域。
     * 整屏页面一律不算，否则别人主页、详情页会被一起排掉。
     */
    private static boolean isCommentSheet(View view, Rect bounds, Rect page, String className) {
        if (view == null || page.height() <= 0) return false;
        if (bounds.height() > page.height() * 0.78f) return false;
        if (bounds.width() < page.width() * 0.55f) return false;
        // 只认真正的评论容器类名，并要求面板当前仍有足够的实际可见区域。
        // 向下拖动关闭时，裁剪后的可见高度会迅速缩小，不能继续锁住 Dock。
        return className.contains("comment") && isCommentPanelActuallyVisible(view);
    }

    /**
     * 当前页面上有没有「评论输入框 / 评论区文案」。
     *
     * 用来判断评论区是不是开着：开着的时候 Dock 不参与「底栏脱离 / 被整屏盖住」这类结构隐藏，
     * 因为抖音原生在评论区里本来就不收底栏。查得比较克制（预算 900 个节点）。
     */
    static boolean commentSheetOpen(View root) {
        if (root == null) return false;
        // 不再用整棵页面树里的输入提示文案判定：视频页可能常驻评论入口/输入节点。
        // 仅当底部确实挂着评论容器时才算评论区仍打开。
        return findCommentPanel(root, 0, new int[]{1800});
    }

    /** 评论面板重建时文案可能暂时消失；用底部面板容器作为第二识别线索。 */
    private static boolean findCommentPanel(View view, int depth, int[] budget) {
        if (view == null || depth > 24 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return false;
        String name = view.getClass().getName().toLowerCase();
        if (name.contains("comment") && view.getWidth() > 0 && view.getHeight() > 0) {
            View root = view.getRootView();
            Rect page = rectOf(root);
            Rect bounds = rectOf(view);
            if (page.height() > 0 && bounds.height() <= page.height() * 0.80f
                    && isCommentPanelActuallyVisible(view)) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                if (findCommentPanel(group.getChildAt(i), depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static boolean findCommentMarker(View view, int depth, int[] budget) {
        if (view == null || depth > 26 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return false;
        String value = "";
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            if (hint != null) value = hint.toString();
            CharSequence text = ((EditText) view).getText();
            if (text != null) value += " " + text;
        } else if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        // 「条评论」常驻在视频页的评论按钮上，不能作为评论面板已打开的证据。
        // 只认评论输入框提示或面板内的明确输入文案。
        boolean inputHint = view instanceof EditText
                && (value.contains("写评论") || value.contains("说点什么")
                || value.contains("留下你的精彩评论"));
        boolean panelInputText = value.contains("留下你的精彩评论")
                || value.contains("善语结善缘");
        if (inputHint || panelInputText) {
            Rect bounds = rectOf(view);
            Rect page = rectOf(view.getRootView());
            if (page.height() > 0 && bounds.bottom >= page.bottom - page.height() * 0.45f
                    && bounds.width() >= page.width() * 0.20f) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                if (findCommentMarker(group.getChildAt(i), depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static boolean isConversationPanel(View view, Rect bounds, Rect page, String className) {
        if (bounds.width() < page.width() * 0.55f
                || bounds.height() < page.height() * 0.16f
                || bounds.bottom < page.bottom - page.height() * 0.22f) return false;

        boolean conversationClass = className.contains("comment") || className.contains("chat")
                || className.contains("conversation") || className.contains("message");
        boolean input = hasVisibleInput(view, 0);
        boolean commentText = hasConversationMarker(view, 0);
        return conversationClass || input || commentText;
    }

    private static boolean hasConversationMarker(View view, int depth) {
        if (view == null || depth > 8 || view.getVisibility() != View.VISIBLE) return false;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        String[] markers = {"写评论", "说点什么", "发条友善的评论", "评论区", "发送消息", "输入消息"};
        for (String marker : markers) if (value.contains(marker)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasConversationMarker(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 菜单面板使用较明确的菜单文案，避免普通信息流中的“关注”等词误触发。 */
    private static boolean hasMenuMarker(View view, int depth) {
        if (view == null || depth > 8 || view.getVisibility() != View.VISIBLE) return false;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        String[] markers = {
                "设置", "扫一扫", "我的二维码", "创作者服务中心", "钱包", "观看历史",
                "帮助与客服", "反馈与帮助", "青少年模式", "更多功能"
        };
        for (String marker : markers) if (value.contains(marker)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasMenuMarker(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 个人资料页必须同时出现至少两个资料页特征，避免普通信息流文案误触发。 */
    private static boolean hasProfileSignature(View root) {
        // "关注" and "作品" also occur in ordinary feeds. They are not enough
        // to identify a profile page and could keep the Dock hidden after returning.
        if (containsVisibleMarker(root, "编辑资料", 0, new int[]{6000})) return false;
        String[] markers = {"编辑资料", "抖音号", "获赞", "粉丝", "关注", "作品", "私信"};
        boolean[] found = new boolean[markers.length];
        collectProfileMarkers(root, 0, markers, found, new int[]{6000});
        if (found[0]) return false;
        int count = 0;
        for (boolean hit : found) if (hit) count++;
        boolean strongIdentity = found[0] || found[1] || found[2];
        return strongIdentity && count >= 2;
    }

    /**
     * 忽略仍留在视图树、但已经被父容器裁出屏幕的旧页面节点。
     * 页面切换动画结束后，旧主页节点可能仍保持 VISIBLE，造成资料页特征残留。
     */
    private static boolean isVisibleInWindow(View view) {
        Rect visible = new Rect();
        return view.getGlobalVisibleRect(visible) && !visible.isEmpty();
    }

    private static boolean containsVisibleMarker(View view, String marker,
                                                  int depth, int[] budget) {
        if (view == null || depth > 28 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f
                || !view.isShown()) return false;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        if (value.contains(marker) && isVisibleInWindow(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                if (containsVisibleMarker(group.getChildAt(i), marker, depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static void collectProfileMarkers(View view, int depth,
                                              String[] markers, boolean[] found, int[] budget) {
        if (view == null || depth > 22 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f
                || !view.isShown()) return;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        boolean markerMatch = false;
        for (int i = 0; i < markers.length; i++) {
            if (value.contains(markers[i])) markerMatch = true;
        }
        if (markerMatch) {
            for (int i = 0; i < markers.length; i++) {
                if (value.contains(markers[i])) found[i] = true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                collectProfileMarkers(group.getChildAt(i), depth + 1, markers, found, budget);
            }
        }
    }

    private static boolean covers(View s, Rect target, Rect page, float tol) {
        if (s == null || s.getVisibility() != View.VISIBLE || s.getAlpha() < 0.05f) return false;
        if (s.getWidth() <= 0 || s.getHeight() <= 0) return false;
        if (!(s instanceof ViewGroup)) return false;

        // 真浮层自己会铺一层底（白底/黑底/遮罩）。信息流那种容器是透明的，
        // 它就算铺满整屏也挡不住底下的底栏 —— 把它当遮挡就会误杀，
        // 0.0.3 的 dock 整条消失就是这么来的。
        android.graphics.drawable.Drawable bg = s.getBackground();
        if (bg == null) return false;

        Rect r = rectOf(s);
        if (r.width() < page.width() * 0.92f) return false;
        if (r.height() < page.height() * 0.85f) return false;

        // 必须把目标那一块整个盖上（信息流容器只铺到底栏上方，这里会直接否掉）
        if (r.left > target.left + tol) return false;
        if (r.top > target.top + tol) return false;
        if (r.right < target.right - tol) return false;
        if (r.bottom < target.bottom - tol) return false;

        return hasContent((ViewGroup) s, 0);
    }

    private static boolean hasContent(ViewGroup g, int depth) {
        if (depth > 6 || g.getChildCount() <= 0) return false;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            if (c.getWidth() > 8 && c.getHeight() > 8) return true;
            if (c instanceof ViewGroup && hasContent((ViewGroup) c, depth + 1)) return true;
        }
        return false;
    }

    /** 这棵子树里有没有输入框（聊天页、搜索页的输入条） */
    static boolean hasInput(View v) {
        return hasInput(v, 0);
    }

    /** 只认「真的显示出来」的输入框：搜索页顶上那个搜索框就是这种 */
    static boolean hasVisibleInput(View v) {
        return hasVisibleInput(v, 0);
    }

    /**
     * 聊天会话页的输入区固定在页面底部。消息列表/搜索页的搜索框通常在顶部，
     * 因此用「可见输入框 + 底部位置」识别会话页，避免只靠 Activity 类名漏掉内嵌聊天框。
     * 评论区底部输入框也会命中，但评论区本来就需要隐藏 Dock。
     */
    static boolean hasBottomInputComposer(View root) {
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return false;
        Rect page = new Rect();
        if (!root.getGlobalVisibleRect(page) || page.isEmpty()) return false;
        return hasBottomInputComposer(root, page, 0, new int[]{2400});
    }

    private static boolean hasBottomInputComposer(View view, Rect page, int depth, int[] budget) {
        if (view == null || depth > 20 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f
                || !view.isShown()) return false;
        String name = view.getClass().getName();
        if (view instanceof EditText || name.contains("EditText") || name.contains("EditTextProxy")) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds) && !bounds.isEmpty()
                    && bounds.centerY() >= page.top + page.height() * 0.62f
                    && bounds.bottom >= page.bottom - page.height() * 0.38f) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                if (hasBottomInputComposer(group.getChildAt(i), page, depth + 1, budget)) return true;
            }
        }
        return false;
    }

    private static boolean hasVisibleInput(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v.getVisibility() != View.VISIBLE || v.getWidth() < 2 || v.getHeight() < 2) return false;
        if (v instanceof EditText) return true;
        String n = v.getClass().getName();
        if (n.contains("EditText")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasVisibleInput(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean hasInput(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v instanceof EditText) return true;
        String n = v.getClass().getName();
        if (n.contains("EditText") || n.contains("EditTextProxy")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasInput(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }
}
