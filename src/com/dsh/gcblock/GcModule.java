package com.dsh.gcblock;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * libxposed API 102 版本入口（Vector / 新版 LSPosed 走这里）。
 *
 * 和 MainHook（旧版 API 82）做同一件事：阻断游戏管家对游戏中心的调用，
 * 只是换了框架 API：拦截链模型——不调用 chain.proceed() 并直接返回替代值即等于"拦截"。
 */
public class GcModule extends XposedModule {

    private static final String TAG = "GCBlock";
    private static final String TARGET = "com.hihonor.gameassistant";
    private static final String GC = "com.hihonor.gamecenter";
    private static final String QGC = "com.honor.quickgamecenter";
    private static final int INFO = 4;

    private static int sCount = 0;

    // ---------------- hookers ----------------

    private final Hooker resolverHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            String key = firstString(chain);
            if (isGc(key)) {
                count("ContentResolver." + name(chain) + " " + key);
                return null;
            }
            return chain.proceed();
        }
    };

    private final Hooker providerClientHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            String key = firstString(chain);
            if ("get_gcdownloadinstallservice_binder".equals(key) || "shortcutCreated".equals(key)) {
                count("ContentProviderClient." + name(chain) + "(" + key + ")");
                return null;
            }
            return chain.proceed();
        }
    };

    private final Hooker notFoundHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            String pkg = firstString(chain);
            if (isGcPackage(pkg)) {
                count("PackageManager." + name(chain) + "(" + pkg + ") -> NameNotFound");
                throw new PackageManager.NameNotFoundException(pkg);
            }
            return chain.proceed();
        }
    };

    private final Hooker resolveHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object a0 = argAt(chain, 0);
            if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                count("PackageManager." + name(chain) + " -> null");
                return null;
            }
            if (a0 instanceof String && isGcPackage((String) a0)) {
                count("PackageManager." + name(chain) + "(" + a0 + ") -> null");
                return null;
            }
            return chain.proceed();
        }
    };

    private final Hooker queryIntentHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object a0 = argAt(chain, 0);
            if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                count("PackageManager.queryIntentActivities -> empty");
                return new java.util.ArrayList<Object>();
            }
            return chain.proceed();
        }
    };

    private final Hooker bindHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object a0 = argAt(chain, 0);
            if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                count("ContextImpl." + name(chain) + " -> false");
                return Boolean.FALSE;
            }
            return chain.proceed();
        }
    };

    private final Hooker startHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object a0 = argAt(chain, 0);
            if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                count(name(chain) + "(" + dataString((Intent) a0) + ") -> dropped");
                return null;
            }
            return chain.proceed();
        }
    };

    private final Hooker startBoolHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object a0 = argAt(chain, 0);
            if (a0 instanceof Intent && isGcIntent((Intent) a0)) {
                count(name(chain) + "(" + dataString((Intent) a0) + ") -> false");
                return Boolean.FALSE;
            }
            return chain.proceed();
        }
    };

    private final Hooker fileHook = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            Object self = chain.getThisObject();
            if (self instanceof File) {
                String path = ((File) self).getPath();
                if (path != null && path.indexOf("/priv-app/GameCenter") >= 0) {
                    count("File.exists(" + path + ") -> false");
                    return Boolean.FALSE;
                }
            }
            return chain.proceed();
        }
    };

    // ---------------- lifecycle ----------------

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        log(INFO, TAG, "libxposed api=" + getApiVersion() + " pkg=" + pkg);
        if (!TARGET.equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getDefaultClassLoader();

        hookMethods(ContentResolver.class, "call", resolverHook);
        hookMethods(ContentResolver.class, "acquireUnstableContentProviderClient", resolverHook);
        hookMethods(ContentResolver.class, "acquireContentProviderClient", resolverHook);
        hookMethods(ContentResolver.class, "query", resolverHook);

        Class<?> cpc = load("android.content.ContentProviderClient", cl);
        hookMethods(cpc, "call", providerClientHook);
        hookMethods(cpc, "query", providerClientHook);

        Class<?> apm = load("android.app.ApplicationPackageManager", cl);
        hookMethods(apm, "getPackageInfo", notFoundHook);
        hookMethods(apm, "getApplicationInfo", notFoundHook);
        hookMethods(apm, "getPackageUid", notFoundHook);
        hookMethods(apm, "resolveActivity", resolveHook);
        hookMethods(apm, "getLaunchIntentForPackage", resolveHook);
        hookMethods(apm, "queryIntentActivities", queryIntentHook);

        Class<?> impl = load("android.app.ContextImpl", cl);
        hookMethods(impl, "bindService", bindHook);
        hookMethods(impl, "bindServiceAsUser", bindHook);
        hookMethods(impl, "bindServiceInstance", bindHook);
        hookMethods(impl, "startActivity", startHook);
        hookMethods(impl, "startActivityAsUser", startHook);
        hookMethods(impl, "startActivityForResult", startHook);

        hookMethods(Activity.class, "startActivity", startHook);
        hookMethods(Activity.class, "startActivityForResult", startHook);
        hookMethods(Activity.class, "startActivityIfNeeded", startBoolHook);
        hookMethods(Activity.class, "startNextMatchingActivity", startBoolHook);

        hookMethods(File.class, "exists", fileHook);

        hookWelfare(cl);
        hookWelfareTab(cl);
        hookGameLibrary(cl);
        hookGameLibraryTab(cl);
        hookSearchBox(cl);

        log(INFO, TAG, "hooks installed for " + pkg);
    }

    // ---------------- welfare UI ----------------

    /**
     * 悬浮窗里的「福利」页是游戏管家自己的原生 View（WelfareView），
     * 数据来自它自己的 HTTPS 接口，和游戏中心 App 没有 IPC。
     * 这里直接把 WelfareView 隐藏掉，它就不再显示。
     */
    private void hookWelfare(ClassLoader cl) {
        Class<?> wv = load("com.hihonor.gameassistant.buoywindow.view.WelfareView", cl);
        if (wv == null) {
            log(5, TAG, "WelfareView not found, skip welfare hook");
            return;
        }
        Hooker hide = new Hooker() {
            @Override
            public Object intercept(Chain chain) throws Throwable {
                Object obj = chain.proceed();
                hideWelfareView(obj, "ctor");
                return obj;
            }
        };
        for (Constructor<?> ctor : wv.getDeclaredConstructors()) {
            try {
                hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hide);
            } catch (Throwable t) {
                log(5, TAG, "welfare ctor hook fail: " + t);
            }
        }
        hookMethods(wv, "onAttachedToWindow", new Hooker() {
            @Override
            public Object intercept(Chain chain) {
                hideWelfareView(chain.getThisObject(), "attach");
                return null;
            }
        });
        hookMethods(wv, "setViewIsVisible", new Hooker() {
            @Override
            public Object intercept(Chain chain) {
                hideWelfareView(chain.getThisObject(), "setViewIsVisible");
                return null;
            }
        });
        hookMethods(wv, "getViewIsVisible", new Hooker() {
            @Override
            public Object intercept(Chain chain) {
                return Boolean.FALSE;
            }
        });
        hookMethods(wv, "setWelfarePageShowing", new Hooker() {
            @Override
            public Object intercept(Chain chain) {
                return null;
            }
        });
        log(INFO, TAG, "welfare view hooks installed");
    }

    private void hideWelfareView(Object o, String from) {
        if (!(o instanceof View)) {
            return;
        }
        View v = (View) o;
        v.setVisibility(View.GONE);
        count("WelfareView hidden (" + from + ")");
    }

    /**
     * 悬浮窗顶部三个标签：0=性能 1=福利 2=工具（AssistantView.s0() / v0()）。
     * 福利标签是 AssistantLayoutBinding.r（含图标 s / 红点 t / 文字 v），
     * 由 isShowWelfareTab 字段（AssistantView.s）控制显隐；
     * 清数据后会由服务端 isSupportBenefit 回调把该字段置 true —— 这就是"网络环境里又冒出来"的原因。
     */
    private void hookWelfareTab(ClassLoader cl) {
        Class<?> tsm = load("com.hihonor.gameassistant.router.util.WelfareSelectTabManager", cl);
        if (tsm != null) {
            // e() = isShowWelfareTab()，构造时用它初始化 AssistantView.s
            hookMethods(tsm, "e", new Hooker() {
                @Override
                public Object intercept(Chain chain) {
                    return Boolean.FALSE;
                }
            });
            log(INFO, TAG, "WelfareSelectTabManager.e() fixed to false");
        } else {
            log(5, TAG, "WelfareSelectTabManager not found");
        }

        Class<?> av = load("com.hihonor.gameassistant.buoywindow.view.AssistantView", cl);
        if (av == null) {
            log(5, TAG, "AssistantView not found, skip welfare tab hook");
            return;
        }

        for (final Constructor<?> ctor : av.getDeclaredConstructors()) {
            try {
                hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                Object o = chain.proceed();
                                forceHideWelfareTab(o);
                                return o;
                            }
                        });
            } catch (Throwable t) {
                log(5, TAG, "AssistantView ctor hook fail: " + t);
            }
        }

        for (final Method m : av.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("Z".equals(m.getName()) && types.length == 2 && types[1] == Boolean.class) {
                // Z(AssistantView, Boolean) = 服务端 isSupportBenefit 回调，放行就会把标签加回来
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) {
                                    count("skip isSupportBenefit callback -> welfare tab stays hidden");
                                    return null;
                                }
                            });
                } catch (Throwable t) {
                    log(5, TAG, "AssistantView.Z hook fail: " + t);
                }
            }
            if ("v0".equals(m.getName()) && types.length == 2 && types[0] == int.class) {
                // 切换标签：把 index 1（福利）重定向到 0（性能），避免落到空页
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object[] args = chain.getArgs().toArray(new Object[0]);
                                    if (args.length > 0 && Integer.valueOf(1).equals(args[0])) {
                                        args[0] = Integer.valueOf(0);
                                        count("tab index 1(welfare) -> 0(performance)");
                                    }
                                    return chain.proceed(args);
                                }
                            });
                } catch (Throwable t) {
                    log(5, TAG, "AssistantView.v0 hook fail: " + t);
                }
            }
        }
        log(INFO, TAG, "welfare tab hooks installed");
    }

    private void forceHideWelfareTab(Object av) {
        if (av == null) {
            return;
        }
        try {
            Field show = field(av.getClass(), "s");          // isShowWelfareTab
            if (show != null && show.getType() == boolean.class) {
                show.setBoolean(av, false);
            }
            Field bf = field(av.getClass(), "i");            // AssistantLayoutBinding
            Object binding = bf == null ? null : bf.get(av);
            if (binding != null) {
                Object tab = value(binding, "r");            // 福利标签容器
                if (tab instanceof View) {
                    ((View) tab).setVisibility(View.GONE);
                    count("welfare tab container -> GONE");
                }
                Object badge = value(binding, "t");          // 红点
                if (badge instanceof View) {
                    ((View) badge).setVisibility(View.GONE);
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "forceHideWelfareTab fail: " + t);
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

    /**
     * 游戏空间主页的「游戏库」卡片：MainAppAdapter（ListAdapter<MainAppBean>）里
     * 带 isGameLibrary()==true 的那一项，点进去是推荐安装页。
     * 在 submitList 时把它过滤掉，卡片就不会出现，也不会留下空格。
     */
    private void hookGameLibrary(ClassLoader cl) {
        Class<?> la = load("androidx.recyclerview.widget.ListAdapter", cl);
        if (la == null) {
            log(5, TAG, "ListAdapter not found, skip game library filter");
            return;
        }
        boolean installed = false;
        for (final Method m : la.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            // ListAdapter 有两个重载：submitList(List) 和 submitList(List, Runnable)
            if (!"submitList".equals(m.getName()) || types.length < 1 || types.length > 2
                    || types[0] != List.class) {
                continue;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                if (self == null || !"com.hihonor.gameassistant.gamespace.adapter.MainAppAdapter"
                                        .equals(self.getClass().getName())) {
                                    return chain.proceed();
                                }
                                Object arg = argAt(chain, 0);
                                if (!(arg instanceof List)) {
                                    return chain.proceed();
                                }
                                List<Object> keep = new ArrayList<>();
                                int removed = 0;
                                for (Object o : (List<?>) arg) {
                                    if (isLibraryItem(o)) {
                                        removed++;
                                    } else {
                                        keep.add(o);
                                    }
                                }
                                if (removed == 0) {
                                    return chain.proceed();
                                }
                                count("MainApp list: dropped " + removed + " game-library card(s)");
                                Object[] args = chain.getArgs().toArray(new Object[0]);
                                args[0] = keep;
                                return chain.proceed(args);
                            }
                        });
                installed = true;
            } catch (Throwable t) {
                log(5, TAG, "submitList hook fail: " + t);
            }
        }
        log(INFO, TAG, installed ? "game library filter installed" : "game library filter NOT installed");

        // 兜底：万一列表过滤没覆盖到，绑定时把「游戏库」条目的 View 收成 0 高
        Class<?> adapter = load("com.hihonor.gameassistant.gamespace.adapter.MainAppAdapter", cl);
        if (adapter != null) {
            hookMethods(adapter, "onBindViewHolder", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object holder = argAt(chain, 0);
                    Object posObj = argAt(chain, 1);
                    Object out = chain.proceed();
                    try {
                        if (holder != null && posObj instanceof Integer) {
                            Object self = chain.getThisObject();
                            Object cur = self.getClass().getMethod("getCurrentList").invoke(self);
                            int pos = (Integer) posObj;
                            if (cur instanceof List && pos >= 0 && pos < ((List<?>) cur).size()
                                    && isLibraryItem(((List<?>) cur).get(pos))) {
                                hideHolder(holder);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return out;
                }
            });
        }
    }

    private void hideHolder(Object holder) {
        try {
            Field f = field(holder.getClass(), "itemView");
            Object v = f == null ? null : f.get(holder);
            if (v instanceof View) {
                View view = (View) v;
                view.setVisibility(View.GONE);
                android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    view.setLayoutParams(lp);
                }
                count("hide game-library holder itemView");
            }
        } catch (Throwable t) {
            log(5, TAG, "hideHolder fail: " + t);
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

    /**
     * 游戏空间主页顶部的标签由 TabPagerAdapter 驱动：
     * 每项是 TabPagerAdapter$a(title, pageFactory)，标题从 a.b() 取。
     * 把标题为「游戏库」的项从列表里去掉，标签和对应页面一起消失。
     */
    private static final String LIB_TAG_1 = "\u6e38\u620f\u5e93";   // 游戏库
    private static final String LIB_TAG_2 = "\u904a\u6232\u5e93";   // 遊戲庫

    private void hookGameLibraryTab(ClassLoader cl) {
        Class<?> tpa = load("com.hihonor.gameassistant.gamespace.adapter.TabPagerAdapter", cl);
        if (tpa == null) {
            log(5, TAG, "TabPagerAdapter not found");
            return;
        }
        for (final Method m : tpa.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("h".equals(m.getName()) && types.length == 1 && types[0] == ArrayList.class) {
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object arg = argAt(chain, 0);
                                    if (!(arg instanceof ArrayList)) {
                                        return chain.proceed();
                                    }
                                    ArrayList<Object> keep = new ArrayList<>();
                                    StringBuilder titles = new StringBuilder();
                                    int removed = 0;
                                    for (Object o : (ArrayList<?>) arg) {
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
                                    if (removed == 0) {
                                        return chain.proceed();
                                    }
                                    Object[] args = chain.getArgs().toArray(new Object[0]);
                                    args[0] = keep;
                                    return chain.proceed(args);
                                }
                            });
                } catch (Throwable t) {
                    log(5, TAG, "TabPagerAdapter.h hook fail: " + t);
                }
            }
            if ("a".equals(m.getName()) && types.length == 1
                    && types[0].getName().endsWith("TabPagerAdapter$a")) {
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    if (isLibraryTab(tabTitle(argAt(chain, 0)))) {
                                        count("skip adding game-library tab");
                                        return null;
                                    }
                                    return chain.proceed();
                                }
                            });
                } catch (Throwable t) {
                    log(5, TAG, "TabPagerAdapter.a hook fail: " + t);
                }
            }
        }
        log(INFO, TAG, "game library tab filter installed");
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
        String lower = title.toLowerCase(Locale.ROOT);
        return title.contains(LIB_TAG_1) || title.contains(LIB_TAG_2)
                || lower.contains("game library") || lower.contains("gamelibrary");
    }

    /**
     * 游戏空间主页顶部的搜索框：由 uikit 的 SearchBar / HwSearchView 或带「搜索」提示的 EditText 构成，
     * 内容来自游戏中心服务端。这里在 Activity 创建/恢复后把它藏掉。
     */
    private static int sSearchBoxId = 0;

    /**
     * 搜索框 = android.widget.LinearLayout，app:id/search_box（实测 0x7f0909fa，右上角 1759,49-2172,147）。
     * 只按 id 精确隐藏，不再用"类名/hint/父容器"之类的启发式，避免误伤背景板、开始游戏按钮。
     * 在 LayoutInflater.inflate 返回时就藏，开机第一帧就不会闪。
     */
    private void hookSearchBox(ClassLoader cl) {
        Class<?> inflater = load("android.view.LayoutInflater", cl);
        if (inflater != null) {
            for (final Method m : inflater.getDeclaredMethods()) {
                if (!"inflate".equals(m.getName()) || m.getReturnType() != View.class) {
                    continue;
                }
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object view = chain.proceed();
                                    hideSearchInView(view);
                                    return view;
                                }
                            });
                } catch (Throwable t) {
                    log(5, TAG, "inflate hook fail: " + t);
                }
            }
        }
        Class<?> act = load("com.hihonor.gameassistant.gamespace.GameSpaceActivity", cl);
        if (act != null) {
            Hooker h = new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object out = chain.proceed();
                    Object self = chain.getThisObject();
                    hideSearchById(self);
                    scheduleRehide(self);
                    return out;
                }
            };
            hookMethods(act, "onCreate", h);
            hookMethods(act, "onResume", h);
        }
        hookSearchVisibility();
        log(INFO, TAG, "search box hooks installed (app:id/search_box)");
    }

    /**
     * 从源头封死：任何把 search_box 设成可见的调用都吞掉，只允许 GONE。
     * 这样即使 app 在 inflate 后又 setVisibility(VISIBLE)，也不会闪一帧。
     */
    private void hookSearchVisibility() {
        for (final Method m : View.class.getDeclaredMethods()) {
            if (!"setVisibility".equals(m.getName()) || m.getParameterTypes().length != 1
                    || m.getParameterTypes()[0] != int.class) {
                continue;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                Object arg = argAt(chain, 0);
                                if (self instanceof View && arg instanceof Integer) {
                                    View v = (View) self;
                                    int id = searchBoxId(v);
                                    if (id > 0 && v.getId() == id) {
                                        if (((Integer) arg) != View.GONE) {
                                            count("block search_box setVisibility(" + arg + ")");
                                            return null;
                                        }
                                    }
                                }
                                return chain.proceed();
                            }
                        });
            } catch (Throwable t) {
                log(5, TAG, "setVisibility hook fail: " + t);
            }
        }
        log(INFO, TAG, "search_box setVisibility guard installed");
    }

    private void scheduleRehide(final Object activity) {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    hideSearchById(activity);
                }
            };
            handler.postDelayed(task, 300);
            handler.postDelayed(task, 1200);
        } catch (Throwable ignored) {
        }
    }

    private void hideSearchInView(Object v) {
        if (!(v instanceof View)) {
            return;
        }
        View root = (View) v;
        int id = searchBoxId(root);
        if (id <= 0) {
            return;
        }
        try {
            View box = root.findViewById(id);
            if (box != null && box.getVisibility() != View.GONE) {
                box.setVisibility(View.GONE);
                count("hide search_box at inflate");
            }
        } catch (Throwable ignored) {
        }
    }

    private static int searchBoxId(View v) {
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

    private void hideSearchById(Object activity) {
        if (!(activity instanceof android.app.Activity)) {
            return;
        }
        try {
            android.app.Activity act = (android.app.Activity) activity;
            int id = searchBoxId(act.getWindow().getDecorView());
            if (id <= 0) {
                return;
            }
            View v = act.getWindow().getDecorView().findViewById(id);
            if (v != null && v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                count("hide search_box (id 0x" + Integer.toHexString(id) + ")");
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- helpers ----------------

    private void hookMethods(Class<?> cls, String methodName, Hooker hooker) {
        if (cls == null) {
            return;
        }
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                try {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
                } catch (Throwable t) {
                    log(5, TAG, "hook fail " + cls.getName() + "#" + methodName + ": " + t);
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "hookMethods fail " + methodName + ": " + t);
        }
    }

    private static Class<?> load(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String name(Chain chain) {
        Executable e = chain.getExecutable();
        return e == null ? "?" : e.getName();
    }

    private static Object argAt(Chain chain, int i) {
        try {
            return chain.getArgs().get(i);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstString(Chain chain) {
        Object o = argAt(chain, 0);
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Uri) {
            return ((Uri) o).getAuthority();
        }
        return null;
    }

    private static String dataString(Intent it) {
        try {
            return String.valueOf(it.getDataString());
        } catch (Throwable t) {
            return "?";
        }
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
        return pkg.equals(GC) || pkg.equals(QGC)
                || pkg.startsWith(GC + ".") || pkg.startsWith(QGC + ".");
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
            return isGc(it.getAction());
        } catch (Throwable t) {
            return false;
        }
    }

    private void count(String what) {
        String line;
        synchronized (GcModule.class) {
            sCount++;
            if (sCount > 200) {
                return;
            }
            line = "BLOCK #" + sCount + " " + what;
        }
        log(INFO, TAG, line);
    }
}
