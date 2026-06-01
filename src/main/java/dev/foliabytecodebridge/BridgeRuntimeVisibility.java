package dev.foliabytecodebridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
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
        if (targetLoader == null || CHECKED_LOADERS.contains(targetLoader)) return;
        synchronized (CHECKED_LOADERS) {
            if (!CHECKED_LOADERS.add(targetLoader)) return;
        }

        String loaderName = targetLoader.getClass().getName();
        if (canResolve(targetLoader)) {
            BridgeDiagnostics.helperVisibility(className, targetLoader, "already-visible", "none");
            return;
        }

        if (!"io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader".equals(loaderName)) {
            BridgeDiagnostics.helperVisibility(className, targetLoader, "not-visible", "non-paper-loader-no-adapter");
            return;
        }

        try {
            URL bridgeJar = bridgeJarUrl();
            URLClassLoader libraryLoader = paperLibraryLoader(targetLoader);
            if (bridgeJar == null || libraryLoader == null) {
                BridgeDiagnostics.helperVisibility(className, targetLoader,
                        "not-visible", "paper-library-loader-unavailable");
                return;
            }

            addUrl(libraryLoader, bridgeJar);
            boolean visible = canResolve(targetLoader);
            BridgeDiagnostics.helperVisibility(className, targetLoader,
                    visible ? "patched-visible" : "patched-still-missing",
                    "paper-library-loader-add-url");
        } catch (Throwable throwable) {
            BridgeDiagnostics.helperVisibilityFailure(className, targetLoader, throwable);
        }
    }

    private static boolean canResolve(ClassLoader loader) {
        try {
            Class.forName(UNSAFE_BRIDGE, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static URL bridgeJarUrl() {
        CodeSource source = FoliaBytecodeBridgeAgent.class.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
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
