package dev.foliabytecodebridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps rewritten plugin bytecode honest: if a transformer emits an
 * INVOKESTATIC to one of FBB's bridge helpers, the target plugin loader must be
 * able to resolve that helper at runtime.
 */
final class BridgeRuntimeVisibility {

    private static final String UNSAFE_BRIDGE = "dev.foliabytecodebridge.UnsafeCallBridge";
    private static final Set<ClassLoader> CHECKED_LOADERS =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private BridgeRuntimeVisibility() {
    }

    static void ensureBridgeVisible(ClassLoader targetLoader, String className) {
        ensureBridgeVisible(targetLoader, className, UNSAFE_BRIDGE);
    }

    static void ensureBridgeVisible(ClassLoader targetLoader, String className, String helperClassName) {
        if (targetLoader == null || CHECKED_LOADERS.contains(targetLoader)) return;
        synchronized (CHECKED_LOADERS) {
            if (!CHECKED_LOADERS.add(targetLoader)) return;
        }

        String helper = simpleName(helperClassName);
        String loaderName = targetLoader.getClass().getName();
        if (canResolve(targetLoader, helperClassName)) {
            BridgeDiagnostics.helperVisibility(className, targetLoader, helper, "already-visible", "none");
            return;
        }

        // Folia/Paper API classes such as RegisteredListener can be loaded by a plain URLClassLoader.
        // When we rewrite those server API boundaries, that loader must see the synthetic bridge helper
        // before the rewritten INVOKESTATIC runs.
        if (targetLoader instanceof URLClassLoader urlClassLoader) {
            patchUrlClassLoader(className, targetLoader, urlClassLoader, helperClassName,
                    helper, "urlclassloader-add-url");
            return;
        }

        if (!"io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader".equals(loaderName)) {
            BridgeDiagnostics.helperVisibility(className, targetLoader, helper,
                    "not-visible", "non-paper-loader-no-adapter");
            return;
        }

        try {
            URL bridgeJar = bridgeJarUrl();
            URLClassLoader libraryLoader = paperLibraryLoader(targetLoader);
            if (bridgeJar == null || libraryLoader == null) {
                BridgeDiagnostics.helperVisibility(className, targetLoader, helper,
                        "not-visible", "paper-library-loader-unavailable");
                return;
            }

            addUrl(libraryLoader, bridgeJar);
            boolean visible = canResolve(targetLoader, helperClassName);
            BridgeDiagnostics.helperVisibility(className, targetLoader, helper,
                    visible ? "patched-visible" : "patched-still-missing",
                    "paper-library-loader-add-url");
        } catch (Throwable throwable) {
            BridgeDiagnostics.helperVisibilityFailure(className, targetLoader, helper, throwable);
        }
    }

    private static void patchUrlClassLoader(String className, ClassLoader targetLoader, URLClassLoader urlClassLoader,
                                            String helperClassName, String helper, String action) {
        try {
            URL bridgeJar = bridgeJarUrl();
            if (bridgeJar == null) {
                BridgeDiagnostics.helperVisibility(className, targetLoader, helper,
                        "not-visible", action + "-bridge-jar-missing");
                return;
            }

            addUrl(urlClassLoader, bridgeJar);
            boolean visible = canResolve(targetLoader, helperClassName);
            BridgeDiagnostics.helperVisibility(className, targetLoader, helper,
                    visible ? "patched-visible" : "patched-still-missing", action);
        } catch (Throwable throwable) {
            BridgeDiagnostics.helperVisibilityFailure(className, targetLoader, helper, throwable);
        }
    }

    private static boolean canResolve(ClassLoader loader, String helperClassName) {
        try {
            Class.forName(helperClassName, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String simpleName(String className) {
        int index = className.lastIndexOf('.');
        return index < 0 ? className : className.substring(index + 1);
    }

    private static URL bridgeJarUrl() {
        return FoliaBytecodeBridgeAgent.bridgeHelperJarUrl();
    }

    private static URLClassLoader paperLibraryLoader(ClassLoader loader) throws ReflectiveOperationException {
        Field field = loader.getClass().getDeclaredField("libraryLoader");
        field.setAccessible(true);
        Object value = field.get(loader);
        return value instanceof URLClassLoader ? (URLClassLoader) value : null;
    }

    private static void addUrl(URLClassLoader loader, URL url) throws ReflectiveOperationException {
        for (URL existing : loader.getURLs()) {
            if (existing.equals(url)) return;
        }
        Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        addUrl.setAccessible(true);
        addUrl.invoke(loader, url);
    }
}
