package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Shares the FoliaBytecodeBridge plugin owner with helper classes that may be
 * resolved from a parent/system-visible copy of this jar. Rewritten plugin
 * bytecode often needs a Plugin instance for Folia schedulers; keeping that
 * state centralized avoids every bridge helper doing a slightly different
 * classloader-sensitive Bukkit lookup.
 */
public final class BridgePluginResolver {

    private static final String PLUGIN_NAME = "FoliaBytecodeBridge";
    private static volatile Plugin bridgePlugin;

    private BridgePluginResolver() {
    }

    /**
     * Publishes the bridge plugin owner into every visible resolver copy.
     *
     * <p>This method is public because the Bukkit-loaded plugin class and the
     * helper-runtime classes can be in different classloaders. The state itself
     * remains centralized here so scheduler/event helpers all resolve the same
     * bridge owner.</p>
     */
    public static void publishPlugin(Plugin plugin, String source) {
        setBridgePlugin(plugin, source + ":local");
        publishToLoader(BridgePluginResolver.class.getClassLoader(), plugin, source + ":own-loader");
        publishToLoader(ClassLoader.getSystemClassLoader(), plugin, source + ":system-loader");
        ClassLoader pluginLoader = plugin == null ? null : plugin.getClass().getClassLoader();
        publishToLoader(pluginLoader, plugin, source + ":plugin-loader");
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        publishToLoader(contextLoader, plugin, source + ":context-loader");
    }

    public static void setBridgePlugin(Plugin plugin, String source) {
        if (plugin == null) {
            BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                    "publish-skipped", source, "plugin=null");
            return;
        }
        bridgePlugin = plugin;
        BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                plugin.isEnabled() ? "published-enabled" : "published-not-yet-enabled",
                source, "plugin=" + plugin.getName());
    }

    @SuppressWarnings("unused")
    private static void setBridgePluginFromObject(Object plugin, String source) {
        if (plugin instanceof Plugin bukkitPlugin) {
            setBridgePlugin(bukkitPlugin, source);
            return;
        }
        String detail = plugin == null ? "plugin=null" : "pluginClass=" + plugin.getClass().getName();
        BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                "publish-rejected", source, detail);
    }

    static Plugin pluginOrNull(String purpose) {
        Plugin plugin = bridgePlugin;
        if (plugin != null) return plugin;
        try {
            plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (plugin != null) {
                bridgePlugin = plugin;
                BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                        plugin.isEnabled() ? "lookup-enabled" : "lookup-not-yet-enabled",
                        purpose, "plugin=" + plugin.getName());
                return plugin;
            }
            BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                    "lookup-missing", purpose, "pluginManagerReturned=null");
        } catch (Throwable throwable) {
            BridgeDiagnostics.helperState("BridgePluginResolver", BridgePluginResolver.class.getClassLoader(),
                    "lookup-failed", purpose,
                    "throwable=" + throwable.getClass().getName() + ":" + safe(throwable.getMessage()));
        }
        return null;
    }

    static Plugin requirePlugin(String purpose) {
        Plugin plugin = pluginOrNull(purpose);
        if (plugin != null) return plugin;
        throw new IllegalStateException("FoliaBytecodeBridge plugin is not available for " + purpose);
    }

    private static void publishToLoader(ClassLoader loader, Plugin plugin, String source) {
        if (loader == null) return;
        try {
            Class<?> resolver = Class.forName(BridgePluginResolver.class.getName(), false, loader);
            Method method = resolver.getDeclaredMethod("setBridgePluginFromObject", Object.class, String.class);
            method.setAccessible(true);
            method.invoke(null, plugin, source);
        } catch (Throwable throwable) {
            BridgeDiagnostics.helperState("BridgePluginResolver", loader,
                    "publish-copy-failed", source,
                    "throwable=" + throwable.getClass().getName() + ":" + safe(throwable.getMessage()));
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unspecified";
        return value.replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
    }
}
