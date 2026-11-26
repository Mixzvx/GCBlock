package io.github.libxposed.api;

/** Compile-time stub of the real libxposed API (io.github.libxposed:api:102). */
public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        boolean isSystemServer();
        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
        ClassLoader getClassLoader();
        boolean isFirstPackage();
    }

    default void onModuleLoaded(ModuleLoadedParam param) {
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }
}
