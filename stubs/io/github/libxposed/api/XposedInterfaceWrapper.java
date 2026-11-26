package io.github.libxposed.api;

import java.lang.reflect.Executable;

/** Compile-time stub of the real libxposed API (io.github.libxposed:api:102). */
public abstract class XposedInterfaceWrapper implements XposedInterface {
    @Override
    public HookBuilder hook(Executable origin) {
        return null;
    }

    @Override
    public HookBuilder hookClassInitializer(Class<?> origin) {
        return null;
    }

    @Override
    public void log(int priority, String tag, String msg) {
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
    }
}
