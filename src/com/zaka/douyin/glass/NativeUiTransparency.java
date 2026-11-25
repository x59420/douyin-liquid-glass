package com.zaka.douyin.glass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

final class NativeUiTransparency {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Runnable> POLLERS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, WeakHashMap<View, Float>> ALPHAS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, WeakHashMap<View, Background>> BACKGROUNDS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Bars> BARS = new WeakHashMap<>();
    private static final int NODE_BUDGET = 1800;

    private NativeUiTransparency() {}

    static void start(final Activity activity) {
        if (activity == null) return;
        synchronized (POLLERS) {
            if (POLLERS.containsKey(activity)) return;
            Runnable poller = new Runnable() {
                @Override public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) { stop(activity); return; }
                    try { apply(activity); } catch (Throwable t) {
                        Dydock.warnOnce("native-transparency", "原生透明度应用失败: " + t);
                    }
                    MAIN.postDelayed(this, 700L);
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
        restoreViews(activity);
        restoreBars(activity);
    }

    static void applyAll() {
        List<Activity> activities;
        synchronized (POLLERS) { activities = new ArrayList<>(POLLERS.keySet()); }
        for (Activity activity : activities) try { apply(activity); } catch (Throwable ignored) {}
    }

    static void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        applySystemBars(activity);
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) { restoreViews(activity); return; }

        boolean topOn = DockPrefs.nativeTopEnabled(activity);
        boolean controlsOn = DockPrefs.nativeControlsEnabled(activity);
        boolean bottomOn = DockPrefs.nativeBottomEnabled(activity);
        boolean homeTopOn = DockPrefs.homeTopTransparent(activity);
        boolean homeBottomOn = DockPrefs.homeBottomTransparent(activity);
        List<Candidate> found = new ArrayList<>();
        collect((ViewGroup) content, content, 0, new int[]{NODE_BUDGET}, found);
        Candidate top = (topOn || homeTopOn) ? choose(found, 1, null, null) : null;
        Candidate controls = controlsOn ? choose(found, 2, top, null) : null;
        Candidate bottom = (bottomOn || homeBottomOn) ? choose(found, 3, top, controls) : null;
        if (bottom != null && DockController.isManagedNativeDock(activity, bottom.view)) {
            // The original navigation tree remains the real click target but is visually
            // suppressed by DockController; its opacity must not be multiplied by this setting.
            bottom = null;
        }

        WeakHashMap<View, Integer> alphaTargets = new WeakHashMap<>();
        WeakHashMap<View, Boolean> backgroundTargets = new WeakHashMap<>();
        if (topOn && top != null) alphaTargets.put(top.view,
                DockPrefs.nativeTopAlpha(activity));
        if (controlsOn && controls != null) alphaTargets.put(controls.view,
                DockPrefs.nativeControlsAlpha(activity));
        if (bottomOn && bottom != null) alphaTargets.put(bottom.view,
                DockPrefs.nativeBottomAlpha(activity));
        if (homeTopOn && top != null) backgroundTargets.put(top.view, true);
        if (homeBottomOn && bottom != null) backgroundTargets.put(bottom.view, true);
        applyAlphas(activity, alphaTargets);
        applyBackgrounds(activity, backgroundTargets);
    }

    /**
     * Edge-to-Edge：内容布局延伸到状态栏和导航栏后方。
     * 系统栏透明时由宿主内容绘制背景；隐藏系统栏只控制可见性，不改变布局模式。
     */
    static void applySystemBars(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            Window window = activity.getWindow();
            View decor = window.getDecorView();
            Bars original;
            synchronized (BARS) {
                original = BARS.get(activity);
                if (original == null) {
                    original = new Bars(window, decor);
                    BARS.put(activity, original);
                }
            }

            boolean hideStatus = DockPrefs.hideStatusBar(activity);
            int layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            int visibilityFlags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

            // When the status bar is hidden, fullscreen already expands app content into
            // its former area. Keeping LAYOUT_FULLSCREEN can preserve the old top inset,
            // exposing an unpainted (often black) strip with the status icons gone.
            // Only lay out behind the status bar while it remains visible.
            int flags = decor.getSystemUiVisibility() & ~(visibilityFlags | layoutFlags);
            // Keep the host's navigation-bar inset and native bottom-menu position.
            // DockController explicitly clears LAYOUT_HIDE_NAVIGATION; do not re-enable it here.
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!hideStatus) {
                flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            } else {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            }
            if (DockPrefs.hideNavigationBar(activity)) {
                flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            decor.setSystemUiVisibility(flags);

            // 状态栏透明，状态图标明暗继续交给宿主原有主题处理。
            window.setStatusBarColor(Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.setStatusBarContrastEnforced(false);
            }
            if (DockPrefs.navigationTransparent(activity)) {
                window.setNavigationBarColor(Color.TRANSPARENT);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.setNavigationBarDividerColor(Color.TRANSPARENT);
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.setNavigationBarContrastEnforced(false);
                }
            } else {
                window.setNavigationBarColor(original.navigationColor);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.setNavigationBarDividerColor(original.dividerColor);
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.setNavigationBarContrastEnforced(original.contrast);
                }
            }
        } catch (Throwable t) {
            Dydock.warnOnce("native-system-bars", "系统栏边到边布局设置失败: " + t);
        }
    }

    private static void restoreBars(Activity activity) {
        Bars original;
        synchronized (BARS) { original = BARS.remove(activity); }
        if (original == null || activity.getWindow() == null) return;
        try {
            Window w = activity.getWindow();
            w.getDecorView().setSystemUiVisibility(original.flags);
            w.setStatusBarColor(original.statusColor);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                w.setStatusBarContrastEnforced(original.statusContrast);
            }
            w.setNavigationBarColor(original.navigationColor);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                w.setNavigationBarDividerColor(original.dividerColor);
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(original.contrast);
            }
        } catch (Throwable ignored) {}
    }

    private static void collect(View v, View root, int depth, int[] budget, List<Candidate> out) {
        // Do not filter on alpha: 0% is a valid user setting and must remain applied
        // across scans. Visibility and host-owned surface exclusions are sufficient here.
        if (v == null || depth > 22 || budget[0]-- <= 0 || v.getVisibility() != View.VISIBLE
                || v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) return;
        String cls = v.getClass().getName();
        if (cls.startsWith("com.zaka.douyin.glass")) return;
        if (v instanceof ViewGroup && v != root && v.getWidth() > 0 && v.getHeight() > 0) {
            Rect page = Shield.rectOf(root), bounds = Shield.rectOf(v);
            if (!page.isEmpty() && bounds.intersect(page)) {
                float d = v.getResources().getDisplayMetrics().density;
                String id = identity(v);
                boolean top = bounds.top <= page.top + page.height() * .24f
                        && bounds.height() >= 24*d && bounds.height() <= 150*d
                        && bounds.width() >= page.width()*.46f
                        && (hasAny(id,"toolbar","topbar","titlebar","header","top_bar")
                            || hasLabels(v,1,0,new int[]{72}));
                boolean controls = bounds.height() >= 24*d && bounds.height() <= page.height()*.70f
                        && bounds.width() >= page.width()*.15f
                        && bounds.bottom <= page.bottom-page.height()*.12f
                        && (hasAny(id,"playercontrol","video_control","videocontrol","playbackcontrol","action_panel")
                            || hasLabels(v,2,0,new int[]{96}));
                boolean bottom = bounds.bottom >= page.bottom-page.height()*.22f
                        && bounds.height() >= 24*d && bounds.height() <= 180*d
                        && bounds.width() >= page.width()*.50f
                        && (hasAny(id,"bottomnavigation","bottom_navigation","bottom_nav","bottom_bar","tabbar","navigation_bar")
                            || hasLabels(v,3,0,new int[]{72}));
                if (top || controls || bottom) out.add(new Candidate(v,bounds,top,controls,bottom,depth));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount() && budget[0]>0;i++) collect(g.getChildAt(i),root,depth+1,budget,out);
        }
    }

    private static Candidate choose(List<Candidate> all,int kind,Candidate a,Candidate b) {
        Candidate best=null;
        for(Candidate c:all) {
            boolean match=kind==1?c.top:kind==2?c.controls:c.bottom;
            if(!match) continue;
            if(a!=null && (a.view==c.view || ancestor(a.view,c.view))) continue;
            if(b!=null && (b.view==c.view || ancestor(b.view,c.view))) continue;
            if(best==null || (kind==1?c.depth<best.depth:c.depth>best.depth)
                    || (c.depth==best.depth && (kind==1?area(c.rect)>area(best.rect):area(c.rect)<area(best.rect)))) best=c;
        }
        return best;
    }

    private static boolean ancestor(View a,View b) {
        android.view.ViewParent p=b==null?null:b.getParent();
        for(int i=0;i<32 && p!=null;i++,p=p.getParent()) if(p==a) return true;
        return false;
    }
    private static long area(Rect r){return (long)r.width()*r.height();}

    private static String identity(View v) {
        String s=v.getClass().getName().toLowerCase();
        if(v.getId()!=View.NO_ID) try{s+=" "+v.getResources().getResourceEntryName(v.getId()).toLowerCase();}catch(Throwable ignored){}
        return s;
    }
    private static String label(View v) {
        String s="";
        if(v instanceof TextView){CharSequence t=((TextView)v).getText();if(t!=null)s=t.toString();}
        CharSequence d=v.getContentDescription();if(d!=null)s+=" "+d;
        return s.toLowerCase();
    }
    private static boolean hasLabels(View v,int kind,int depth,int[] budget) {
        if(v==null || depth>(kind==2?7:6) || budget[0]--<=0)return false;
        String s=label(v);
        boolean hit=kind==1?contains(s,"推荐","关注","同城","朋友","搜索","扫一扫","直播")
                :kind==2?contains(s,"暂停","播放","倍速","进度","点赞","评论","分享","收藏","音量","全屏","选集")
                :contains(s,"首页","朋友","消息","商城","拍摄","我");
        if(hit)return true;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount()&&budget[0]>0;i++)if(hasLabels(g.getChildAt(i),kind,depth+1,budget))return true;}
        return false;
    }
    private static boolean contains(String s,String... words){for(String w:words)if(s.contains(w.toLowerCase()))return true;return false;}
    private static boolean hasAny(String s,String... words){return contains(s,words);}

    private static void applyAlphas(Activity a,WeakHashMap<View,Integer> wanted) {
        WeakHashMap<View,Float> old;
        synchronized(ALPHAS){old=ALPHAS.get(a);if(old==null){old=new WeakHashMap<>();ALPHAS.put(a,old);}}
        for(View v:new ArrayList<>(old.keySet()))if(!wanted.containsKey(v)){Float f=old.remove(v);if(v!=null&&f!=null)try{v.setAlpha(f);}catch(Throwable ignored){}}
        for(View v:wanted.keySet())if(v!=null)try{Float f=old.get(v);if(f==null){f=v.getAlpha();old.put(v,f);}v.setAlpha(f*wanted.get(v)/100f);}catch(Throwable ignored){}
        if(old.isEmpty())synchronized(ALPHAS){ALPHAS.remove(a);}
    }
    private static void applyBackgrounds(Activity a,WeakHashMap<View,Boolean> wanted) {
        WeakHashMap<View,Background> old;
        synchronized(BACKGROUNDS){old=BACKGROUNDS.get(a);if(old==null){old=new WeakHashMap<>();BACKGROUNDS.put(a,old);}}
        for(View v:new ArrayList<>(old.keySet()))if(!wanted.containsKey(v)){Background b=old.remove(v);if(v!=null&&b!=null)try{v.setBackground(b.drawable);}catch(Throwable ignored){}}
        for(View v:wanted.keySet())if(v!=null)try{if(!old.containsKey(v))old.put(v,new Background(v.getBackground()));v.setBackgroundColor(Color.TRANSPARENT);}catch(Throwable ignored){}
        if(old.isEmpty())synchronized(BACKGROUNDS){BACKGROUNDS.remove(a);}
    }
    private static void restoreViews(Activity a) {
        WeakHashMap<View,Float> alphas;synchronized(ALPHAS){alphas=ALPHAS.remove(a);}
        if(alphas!=null)for(View v:alphas.keySet()){Float f=alphas.get(v);if(v!=null&&f!=null)try{v.setAlpha(f);}catch(Throwable ignored){}}
        WeakHashMap<View,Background> bgs;synchronized(BACKGROUNDS){bgs=BACKGROUNDS.remove(a);}
        if(bgs!=null)for(View v:bgs.keySet()){Background b=bgs.get(v);if(v!=null&&b!=null)try{v.setBackground(b.drawable);}catch(Throwable ignored){}}
    }

    private static final class Candidate {
        final View view; final Rect rect; final boolean top,controls,bottom; final int depth;
        Candidate(View v,Rect r,boolean t,boolean c,boolean b,int d){view=v;rect=new Rect(r);top=t;controls=c;bottom=b;depth=d;}
    }
    private static final class Background { final Drawable drawable; Background(Drawable d){drawable=d;} }
    private static final class Bars {
        final int flags,statusColor,navigationColor,dividerColor;
        final boolean contrast,statusContrast;
        Bars(Window w,View d){flags=d.getSystemUiVisibility();statusColor=w.getStatusBarColor();
            navigationColor=w.getNavigationBarColor();
            dividerColor=android.os.Build.VERSION.SDK_INT>=28?w.getNavigationBarDividerColor():navigationColor;
            contrast=android.os.Build.VERSION.SDK_INT>=29 && w.isNavigationBarContrastEnforced();
            statusContrast=android.os.Build.VERSION.SDK_INT>=29 && w.isStatusBarContrastEnforced();}
    }
}
