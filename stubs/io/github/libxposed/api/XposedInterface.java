package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.util.List;

/** Compile-time stub of the real libxposed API (io.github.libxposed:api:102). */
public interface XposedInterface {
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_HIGHEST = 10000;
    int PRIORITY_LOWEST = -10000;

    interface HookHandle {
        Executable getExecutable();
        void unhook();
        String getId();
        HookHandle replaceHook(Hooker hooker);
    }

    interface Chain {
        Executable getExecutable();
        Object getThisObject();
        List<Object> getArgs();
        Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException;
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
        Object proceedWith(Object thisObject) throws Throwable;
        Object proceedWith(Object thisObject, Object[] args) throws Throwable;
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode mode);
        HookBuilder setId(String id);
        HookHandle intercept(Hooker hooker);
    }

    default int getApiVersion() {
        return 102;
    }

    HookBuilder hook(Executable origin);
    HookBuilder hookClassInitializer(Class<?> origin);
    void log(int priority, String tag, String msg);
    void log(int priority, String tag, String msg, Throwable tr);
}
