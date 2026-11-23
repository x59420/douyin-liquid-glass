package com.zaka.douyin.glass;

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
     * 菜单、侧栏和个人页展开面板：不一定完全盖住底栏，但 Dock 也不应浮在菜单上。
     * 只检查 Dock 所在祖先链上、绘制顺序排在 Dock 后面的兄弟浮层。
     */
    static boolean hasExpandedOverlay(View dock, View content, View decor) {
        if (dock == null || content == null) return false;
        Rect page = rectOf(content);
        if (page.isEmpty()) return false;

        // 先检查 Dock 所在内容树中排在它后面的兄弟视图。
        View cur = dock;
        for (int guard = 0; guard < 12 && cur != null && cur != content; guard++) {
            ViewParent parent = cur.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup group = (ViewGroup) parent;
            int index = group.indexOfChild(cur);
            if (index < 0) break;
            for (int i = index + 1; i < group.getChildCount(); i++) {
                if (containsExpandedPanel(group.getChildAt(i), page, 0, new int[]{220})) return true;
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
                    if (className.contains("DockOverlay")) continue;
                    if (containsExpandedPanel(candidate, page, 0, new int[]{220})) return true;
                }
            }
        }
        return false;
    }

    private static boolean containsExpandedPanel(View view, Rect page, int depth, int[] budget) {
        if (view == null || depth > 8 || budget[0]-- <= 0
                || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.08f
                || !(view instanceof ViewGroup)) return false;

        // Small descendants cannot contain a qualifying expanded menu/profile panel.
        // Pruning them avoids repeatedly walking the entire feed tree on the UI thread.
        Rect bounds = rectOf(view);
        if (bounds.width() < page.width() * 0.34f
                || bounds.height() < page.height() * 0.28f) return false;

        String className = view.getClass().getName();
        if (className.contains("DockOverlay")) return false;
        if (isExpandedPanel(view, page)) return true;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
            if (containsExpandedPanel(group.getChildAt(i), page, depth + 1, budget)) return true;
        }
        return false;
    }

    private static boolean isExpandedPanel(View view, Rect page) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.08f
                || !(view instanceof ViewGroup) || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect r = rectOf(view);
        if (r.width() < page.width() * 0.34f || r.height() < page.height() * 0.28f) return false;
        String name = view.getClass().getName().toLowerCase();
        // Do not classify arbitrary large background containers as menus.
        // The host's normal feed/page containers also have backgrounds and children;
        // treating them as expanded panels makes the Dock appear briefly, then vanish.
        // Only positively identified popup/menu/drawer/sheet/dialog/panel views qualify.
        boolean namedPanel = name.contains("popup") || name.contains("menu")
                || name.contains("drawer") || name.contains("sheet")
                || name.contains("dialog") || name.contains("panel");
        boolean profilePage = name.contains("userprofile") || name.contains("userdetail")
                || name.contains("personalpage") || name.contains("profiledetail")
                || name.contains("othershomepage");
        boolean menuText = hasMenuMarker(view, 0);
        boolean profileText = hasProfileSignature(view);
        if (!namedPanel && !profilePage && !menuText && !profileText) return false;
        return hasContent((ViewGroup) view, 0);
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
        String[] markers = {"编辑资料", "抖音号", "获赞", "粉丝", "关注", "作品", "私信"};
        boolean[] found = new boolean[markers.length];
        collectProfileMarkers(root, 0, markers, found);
        int count = 0;
        for (boolean hit : found) if (hit) count++;
        boolean strongIdentity = found[0] || found[1] || found[2];
        return strongIdentity && count >= 2;
    }

    private static void collectProfileMarkers(View view, int depth,
                                              String[] markers, boolean[] found) {
        if (view == null || depth > 8 || view.getVisibility() != View.VISIBLE) return;
        String value = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) value = text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null) value += " " + desc;
        for (int i = 0; i < markers.length; i++) {
            if (value.contains(markers[i])) found[i] = true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectProfileMarkers(group.getChildAt(i), depth + 1, markers, found);
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
