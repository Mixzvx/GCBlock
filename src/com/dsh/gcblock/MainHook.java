package com.dsh.gcblock;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 阻断「游戏管家」(com.hihonor.gameassistant) 对「游戏中心」(com.hihonor.gamecenter /
 * com.honor.quickgamecenter) 的调用。
 *
 * 阻断的四条通道：
 *   1. ContentResolver.call / acquire*ContentProviderClient  -> 返回 null
 *   2. ContentProviderClient.call("get_gcdownloadinstallservice_binder" / "shortcutCreated") -> null
 *   3. ContextImpl.bindService(AsUser) -> false
 *   4. startActivity(gamecenter:// / quickgamecenter://) -> 丢弃
 * 另外让 PackageManager 认为游戏中心未安装（抛 NameNotFoundException），
 * 以及让 /system/priv-app/GameCenter 的存在性检查返回 false。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GCBlock";
    private static final String TARGET_PKG = "com.hihonor.gameassistant";

    private static final String GC_PKG = "com.hihonor.gamecenter";
    private static final String QGC_PKG = "com.honor.quickgamecenter";

    private static int sCount = 0;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded in " + lp.packageName + " / " + lp.processName);

        safe("resolver", new Runnable() { public void run() { hookResolver(lp); } });
        safe("providerClient", new Runnable() { public void run() { hookProviderClient(lp); } });
        safe("packageManager", new Runnable() { public void run() { hookPackageManager(lp); } });
        safe("bindService", new Runnable() { public void run() { hookBindService(lp); } });
        safe("startActivity", new Runnable() { public void run() { hookStartActivity(lp); } });
        safe("fileExists", new Runnable() { public void run() { hookFileExists(); } });
        safe("welfare", new Runnable() { public void run() { hookWelfare(lp); } });
        safe("welfareTab", new Runnable() { public void run() { hookWelfareTab(lp); } });
        safe("gameLibrary", new Runnable() { public void run() { hookGameLibrary(lp); } });
        safe("gameLibraryTab", new Runnable() { public void run() { hookGameLibraryTab(lp); } });
        safe("searchBox", new Runnable() { public void run() { hookSearchBox(lp); } });
    }

    /* ------------------------------------------------------------------ */

    private static void hookResolver(final LoadPackageParam lp) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0) {
                    return;
                }
                String key = authorityOf(p.args[0]);
                if (isGc(key)) {
                    block(p, "ContentResolver call/acquire on " + key);
                }
            }
        };
        String[] names = {
                "call",
                "acquireUnstableContentProviderClient",
                "acquireContentProviderClient",
                "query",
        };
        for (String n : names) {
            XposedBridge.hookAllMethods(ContentResolver.class, n, hook);
        }
    }

    private static void hookProviderClient(LoadPackageParam lp) {
        Class<?> cpc = XposedHelpers.findClass("android.content.ContentProviderClient", lp.classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof String)) {
                    return;
                }
                String method = (String) p.args[0];
                if ("get_gcdownloadinstallservice_binder".equals(method)
                        || "shortcutCreated".equals(method)) {
                    block(p, "ContentProviderClient.call(" + method + ")");
                }
            }
        };
        for (String n : new String[]{"call", "query"}) {
            XposedBridge.hookAllMethods(cpc, n, hook);
        }
    }

    private static void hookPackageManager(LoadPackageParam lp) {
        Class<?> apm = XposedHelpers.findClass("android.app.ApplicationPackageManager", lp.classLoader);

        // getPackageInfo / getApplicationInfo -> 当作未安装
        XC_MethodHook notFound = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof String)) {
                    return;
                }
                String pkg = (String) p.args[0];
                if (isGcPackage(pkg)) {
                    count("PackageManager." + p.method.getName() + "(" + pkg + ") -> NameNotFound");
                    p.setThrowable(new PackageManager.NameNotFoundException(pkg));
                }
            }
        };
        XposedBridge.hookAllMethods(apm, "getPackageInfo", notFound);
        XposedBridge.hookAllMethods(apm, "getApplicationInfo", notFound);
        XposedBridge.hookAllMethods(apm, "getPackageUid", notFound);

        // resolveActivity / queryIntentActivities / getLaunchIntentForPackage -> null / empty
        XC_MethodHook resolve = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0) {
                    return;
                }
                Object a0 = p.args[0];
                if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                    block(p, "PackageManager.resolveActivity");
                } else if (a0 instanceof String && isGcPackage((String) a0)) {
                    block(p, "PackageManager.getLaunchIntentForPackage(" + a0 + ")");
                }
            }
        };
        XposedBridge.hookAllMethods(apm, "resolveActivity", resolve);
        XposedBridge.hookAllMethods(apm, "getLaunchIntentForPackage", resolve);

        XC_MethodHook queryIntents = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof Intent)) {
                    return;
                }
                if (isGcIntent((Intent) p.args[0])) {
                    count("PackageManager.queryIntentActivities -> empty");
                    p.setResult(Collections.emptyList());
                }
            }
        };
        XposedBridge.hookAllMethods(apm, "queryIntentActivities", queryIntents);
    }

    private static void hookBindService(LoadPackageParam lp) {
        Class<?> impl = XposedHelpers.findClass("android.app.ContextImpl", lp.classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof Intent)) {
                    return;
                }
                Intent it = (Intent) p.args[0];
                if (isGcIntent(it)) {
                    count("ContextImpl." + p.method.getName() + " -> false");
                    p.setResult(Boolean.FALSE);
                }
            }
        };
        for (String n : new String[]{"bindService", "bindServiceAsUser", "bindServiceInstance"}) {
            XposedBridge.hookAllMethods(impl, n, hook);
        }
    }

    private static void hookStartActivity(LoadPackageParam lp) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof Intent)) {
                    return;
                }
                Intent it = (Intent) p.args[0];
                if (isGcIntent(it)) {
                    count("startActivity(" + it.getDataString() + ") -> dropped");
                    p.setResult(null);
                }
            }
        };
        Class<?> impl = XposedHelpers.findClass("android.app.ContextImpl", lp.classLoader);
        for (String n : new String[]{"startActivity", "startActivityAsUser", "startActivityForResult"}) {
            XposedBridge.hookAllMethods(impl, n, hook);
        }
        for (String n : new String[]{"startActivity", "startActivityForResult"}) {
            XposedBridge.hookAllMethods(Activity.class, n, hook);
        }
        // startActivityIfNeeded 返回 boolean，必须给 false 而不是 null
        XC_MethodHook boolHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof Intent)) {
                    return;
                }
                Intent it = (Intent) p.args[0];
                if (isGcIntent(it)) {
                    count("startActivityIfNeeded(" + it.getDataString() + ") -> false");
                    p.setResult(Boolean.FALSE);
                }
            }
        };
        XposedBridge.hookAllMethods(Activity.class, "startActivityIfNeeded", boolHook);
        for (String n : new String[]{"startNextMatchingActivity"}) {
            XposedBridge.hookAllMethods(Activity.class, n, boolHook);
        }
    }

    private static void hookFileExists() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object o = p.thisObject;
                if (!(o instanceof File)) {
                    return;
                }
                String path = ((File) o).getPath();
                if (path != null && path.indexOf("/priv-app/GameCenter") >= 0) {
                    count("File.exists(" + path + ") -> false");
                    p.setResult(Boolean.FALSE);
                }
            }
        };
        XposedBridge.hookAllMethods(File.class, "exists", hook);
    }

    /** 悬浮窗的「福利」页是游戏管家自己的 View，数据走它自己的 HTTPS 接口，直接隐藏。 */
    private static void hookWelfare(final LoadPackageParam lp) {
        final Class<?> wv = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.buoywindow.view.WelfareView", lp.classLoader);
        if (wv == null) {
            XposedBridge.log(TAG + ": WelfareView not found, skip");
            return;
        }
        XposedBridge.hookAllConstructors(wv, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                hideWelfare(p.thisObject, "ctor");
            }
        });
        try {
            XposedHelpers.findAndHookMethod(wv, "onAttachedToWindow", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam p) {
                    hideWelfare(p.thisObject, "attach");
                    return null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": welfare attach hook fail " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(wv, "setViewIsVisible", boolean.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam p) {
                            return null;
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(wv, "getViewIsVisible", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam p) {
                    return Boolean.FALSE;
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(wv, "setWelfarePageShowing", boolean.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam p) {
                            return null;
                        }
                    });
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + ": welfare view hooks installed");
    }

    private static void hideWelfare(Object o, String from) {
        if (!(o instanceof android.view.View)) {
            return;
        }
        ((android.view.View) o).setVisibility(android.view.View.GONE);
        count("WelfareView hidden (" + from + ")");
    }

    /** 去掉悬浮窗的「福利」标签（图标+红点），并阻止服务端回调把它加回来。 */
    private static void hookWelfareTab(final LoadPackageParam lp) {
        Class<?> tsm = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.router.util.WelfareSelectTabManager", lp.classLoader);
        if (tsm != null) {
            try {
                XposedHelpers.findAndHookMethod(tsm, "e", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam p) {
                        return Boolean.FALSE;
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": tsm.e hook fail " + t);
            }
        }

        Class<?> av = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.buoywindow.view.AssistantView", lp.classLoader);
        if (av == null) {
            XposedBridge.log(TAG + ": AssistantView not found, skip welfare tab");
            return;
        }
        XposedBridge.hookAllConstructors(av, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                forceHideWelfareTab(p.thisObject);
            }
        });
        for (Method m : av.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("Z".equals(m.getName()) && types.length == 2 && types[1] == Boolean.class) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam p) {
                            count("skip isSupportBenefit callback -> welfare tab stays hidden");
                            return null;
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": Z hook fail " + t);
                }
            }
            if ("v0".equals(m.getName()) && types.length == 2 && types[0] == int.class) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length > 0 && Integer.valueOf(1).equals(p.args[0])) {
                                p.args[0] = Integer.valueOf(0);
                                count("tab index 1(welfare) -> 0(performance)");
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": v0 hook fail " + t);
                }
            }
        }
        XposedBridge.log(TAG + ": welfare tab hooks installed");
    }

    private static void forceHideWelfareTab(Object av) {
        if (av == null) {
            return;
        }
        try {
            Field show = field(av.getClass(), "s");
            if (show != null && show.getType() == boolean.class) {
                show.setBoolean(av, false);
            }
            Object binding = value(av, "i");
            if (binding != null) {
                Object tab = value(binding, "r");
                if (tab instanceof android.view.View) {
                    ((android.view.View) tab).setVisibility(android.view.View.GONE);
                    count("welfare tab container -> GONE");
                }
                Object badge = value(binding, "t");
                if (badge instanceof android.view.View) {
                    ((android.view.View) badge).setVisibility(android.view.View.GONE);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forceHideWelfareTab fail " + t);
        }
    }

    private static Object value(Object target, String name) {
        Field f = field(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 过滤游戏空间主页的「游戏库」卡片（MainAppBean.isGameLibrary()==true）。 */
    private static void hookGameLibrary(final LoadPackageParam lp) {
        final Class<?> la = XposedHelpers.findClassIfExists(
                "androidx.recyclerview.widget.ListAdapter", lp.classLoader);
        if (la == null) {
            XposedBridge.log(TAG + ": ListAdapter not found");
            return;
        }
        boolean installed = false;
        for (final Method m : la.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (!"submitList".equals(m.getName()) || types.length < 1 || types.length > 2
                    || types[0] != java.util.List.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Object self = p.thisObject;
                        if (self == null || !"com.hihonor.gameassistant.gamespace.adapter.MainAppAdapter"
                                .equals(self.getClass().getName())) {
                            return;
                        }
                        if (p.args.length == 0 || !(p.args[0] instanceof java.util.List)) {
                            return;
                        }
                        java.util.List<?> src = (java.util.List<?>) p.args[0];
                        java.util.ArrayList<Object> keep = new java.util.ArrayList<>();
                        int removed = 0;
                        for (Object o : src) {
                            if (isLibraryItem(o)) {
                                removed++;
                            } else {
                                keep.add(o);
                            }
                        }
                        if (removed > 0) {
                            p.args[0] = keep;
                            count("MainApp list: dropped " + removed + " game-library card(s)");
                        }
                    }
                });
                installed = true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": submitList hook fail " + t);
            }
        }
        XposedBridge.log(TAG + (installed ? ": game library filter installed" : ": game library filter NOT installed"));

        final Class<?> adapter = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.gamespace.adapter.MainAppAdapter", lp.classLoader);
        if (adapter != null) {
            try {
                XposedBridge.hookAllMethods(adapter, "onBindViewHolder", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object self = p.thisObject;
                            Object cur = self.getClass().getMethod("getCurrentList").invoke(self);
                            if (!(p.args[1] instanceof Integer)) {
                                return;
                            }
                            int pos = (Integer) p.args[1];
                            if (cur instanceof java.util.List && pos >= 0
                                    && pos < ((java.util.List<?>) cur).size()
                                    && isLibraryItem(((java.util.List<?>) cur).get(pos))) {
                                hideHolder(p.args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": onBindViewHolder hook fail " + t);
            }
        }
    }

    private static void hideHolder(Object holder) {
        try {
            Field f = field(holder.getClass(), "itemView");
            Object v = f == null ? null : f.get(holder);
            if (v instanceof android.view.View) {
                android.view.View view = (android.view.View) v;
                view.setVisibility(android.view.View.GONE);
                android.view.ViewGroup.LayoutParams lpview = view.getLayoutParams();
                if (lpview != null) {
                    lpview.height = 0;
                    view.setLayoutParams(lpview);
                }
                count("hide game-library holder itemView");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hideHolder fail " + t);
        }
    }

    private static boolean isLibraryItem(Object o) {
        if (o == null) {
            return false;
        }
        try {
            Method m = o.getClass().getMethod("isGameLibrary");
            return Boolean.TRUE.equals(m.invoke(o));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 过滤游戏空间主页顶部「游戏库」标签（TabPagerAdapter 的项）。 */
    private static void hookGameLibraryTab(final LoadPackageParam lp) {
        Class<?> tpa = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.gamespace.adapter.TabPagerAdapter", lp.classLoader);
        if (tpa == null) {
            XposedBridge.log(TAG + ": TabPagerAdapter not found");
            return;
        }
        for (final Method m : tpa.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("h".equals(m.getName()) && types.length == 1 && types[0] == java.util.ArrayList.class) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length == 0 || !(p.args[0] instanceof java.util.ArrayList)) {
                                return;
                            }
                            java.util.ArrayList<Object> keep = new java.util.ArrayList<>();
                            StringBuilder titles = new StringBuilder();
                            int removed = 0;
                            for (Object o : (java.util.ArrayList<?>) p.args[0]) {
                                String t = tabTitle(o);
                                if (t != null) {
                                    titles.append(t).append('|');
                                }
                                if (isLibraryTab(t)) {
                                    removed++;
                                } else {
                                    keep.add(o);
                                }
                            }
                            count("tabs=[" + titles + "] dropped=" + removed);
                            if (removed > 0) {
                                p.args[0] = keep;
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": tpa.h hook fail " + t);
                }
            }
            if ("a".equals(m.getName()) && types.length == 1
                    && types[0].getName().endsWith("TabPagerAdapter$a")) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length > 0 && isLibraryTab(tabTitle(p.args[0]))) {
                                count("skip adding game-library tab");
                                p.setResult(null);   // void 方法：跳过原逻辑
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": tpa.a hook fail " + t);
                }
            }
        }
        XposedBridge.log(TAG + ": game library tab filter installed");
    }

    private static String tabTitle(Object item) {
        if (item == null) {
            return null;
        }
        try {
            Object t = item.getClass().getMethod("b").invoke(item);
            return t instanceof String ? (String) t : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLibraryTab(String title) {
        if (title == null) {
            return false;
        }
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        return title.contains("\u6e38\u620f\u5e93") || title.contains("\u904a\u6232\u5e93")
                || lower.contains("game library") || lower.contains("gamelibrary");
    }

    private static int sSearchBoxId = 0;

    /** 搜索框 = app:id/search_box，只按 id 精确隐藏，并在 inflate 完成时就藏，避免开场闪一下。 */
    private static void hookSearchBox(final LoadPackageParam lp) {
        Class<?> inflater = XposedHelpers.findClassIfExists("android.view.LayoutInflater", lp.classLoader);
        if (inflater != null) {
            for (final java.lang.reflect.Method m : inflater.getDeclaredMethods()) {
                if (!"inflate".equals(m.getName())
                        || m.getReturnType() != android.view.View.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (r instanceof android.view.View) {
                                hideSearchInView((android.view.View) r);
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": inflate hook fail " + t);
                }
            }
        }
        Class<?> act = XposedHelpers.findClassIfExists(
                "com.hihonor.gameassistant.gamespace.GameSpaceActivity", lp.classLoader);
        if (act != null) {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    hideSearchById(p.thisObject);
                    scheduleRehide(p.thisObject);
                }
            };
            try {
                XposedHelpers.findAndHookMethod(act, "onCreate", android.os.Bundle.class, h);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": onCreate hook fail " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(act, "onResume", h);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": onResume hook fail " + t);
            }
        }
        hookSearchVisibility();
        XposedBridge.log(TAG + ": search box hooks installed (app:id/search_box)");
    }

    /** 任何把 search_box 设成可见的调用都吞掉，只允许 GONE。 */
    private static void hookSearchVisibility() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!(p.thisObject instanceof android.view.View) || p.args.length == 0
                            || !(p.args[0] instanceof Integer)) {
                        return;
                    }
                    android.view.View v = (android.view.View) p.thisObject;
                    int id = searchBoxId(v);
                    if (id > 0 && v.getId() == id && ((Integer) p.args[0]) != android.view.View.GONE) {
                        count("block search_box setVisibility(" + p.args[0] + ")");
                        p.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + ": search_box setVisibility guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setVisibility hook fail " + t);
        }
    }

    private static void scheduleRehide(final Object activity) {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable task = new Runnable() {
                public void run() {
                    hideSearchById(activity);
                }
            };
            handler.postDelayed(task, 300);
            handler.postDelayed(task, 1200);
        } catch (Throwable ignored) {
        }
    }

    private static void hideSearchInView(android.view.View root) {
        int id = searchBoxId(root);
        if (id <= 0) {
            return;
        }
        try {
            android.view.View box = root.findViewById(id);
            if (box != null && box.getVisibility() != android.view.View.GONE) {
                box.setVisibility(android.view.View.GONE);
                count("hide search_box at inflate");
            }
        } catch (Throwable ignored) {
        }
    }

    private static int searchBoxId(android.view.View v) {
        if (sSearchBoxId != 0) {
            return sSearchBoxId;
        }
        try {
            android.content.Context c = v.getContext();
            int id = c.getResources().getIdentifier("search_box", "id", c.getPackageName());
            if (id > 0) {
                sSearchBoxId = id;
            }
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void hideSearchById(Object activity) {
        if (!(activity instanceof android.app.Activity)) {
            return;
        }
        try {
            android.app.Activity act = (android.app.Activity) activity;
            int id = searchBoxId(act.getWindow().getDecorView());
            if (id <= 0) {
                return;
            }
            android.view.View v = act.getWindow().getDecorView().findViewById(id);
            if (v != null && v.getVisibility() != android.view.View.GONE) {
                v.setVisibility(android.view.View.GONE);
                count("hide search_box (id 0x" + Integer.toHexString(id) + ")");
            }
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ helpers --------------------------- */

    private static String authorityOf(Object o) {
        if (o instanceof Uri) {
            return ((Uri) o).getAuthority();
        }
        if (o instanceof String) {
            return (String) o;
        }
        return null;
    }

    private static boolean isGc(String key) {
        if (key == null) {
            return false;
        }
        String l = key.toLowerCase(Locale.ROOT);
        return l.contains("gamecenter") || l.contains("game_space.provider");
    }

    private static boolean isGcPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        return pkg.equals(GC_PKG) || pkg.equals(QGC_PKG)
                || pkg.startsWith(GC_PKG + ".") || pkg.startsWith(QGC_PKG + ".");
    }

    private static boolean isGcIntent(Intent it) {
        if (it == null) {
            return false;
        }
        try {
            if (it.getComponent() != null && isGcPackage(it.getComponent().getPackageName())) {
                return true;
            }
            if (it.getPackage() != null && isGcPackage(it.getPackage())) {
                return true;
            }
            Uri data = it.getData();
            if (data != null && isGc(data.getScheme())) {
                return true;
            }
            String action = it.getAction();
            if (isGc(action)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void block(XC_MethodHook.MethodHookParam p, String what) {
        count(what);
        p.setResult(null);
    }

    private static synchronized void count(String what) {
        sCount++;
        if (sCount <= 200) {
            XposedBridge.log(TAG + ": BLOCK #" + sCount + " " + what);
        }
    }

    private static void safe(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + " failed: " + t);
        }
    }
}
