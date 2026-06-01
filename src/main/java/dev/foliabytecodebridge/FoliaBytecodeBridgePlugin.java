package dev.foliabytecodebridge;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class FoliaBytecodeBridgePlugin extends JavaPlugin {

    private Handler compatibilityHandler;

    @Override
    public void onLoad() {
        SchedulerBridge.setLogger(getLogger());
        BridgeDiagnostics.setLogger(getLogger());
        UnsafeCallBridge.setBridgePlugin(this);
        BridgeDiagnostics.buildMarker("onLoad", getDescription().getVersion(), getFile());
        SelfAttachInstaller.installFromPlugin(this, getFile());
    }

    @Override
    public void onEnable() {
        SchedulerBridge.setLogger(getLogger());
        BridgeDiagnostics.setLogger(getLogger());
        UnsafeCallBridge.setBridgePlugin(this);
        BridgeDiagnostics.buildMarker("onEnable", getDescription().getVersion(), getFile());
        installCompatibilityLogHandler();
        if (FoliaBytecodeBridgeAgent.isInstalled()
                || Boolean.parseBoolean(System.getProperty("foliabytecodebridge.agentInstalled", "false"))) {
            String mode = System.getProperty("foliabytecodebridge.agentMode", "unknown");
            getLogger().info("Bytecode transformer is installed. mode=" + mode);
            return;
        }
        getLogger().warning("The plugin loaded, but the bytecode transformer is not installed.");
        getLogger().warning("Start the server with -javaagent:plugins/FoliaBytecodeBridge.jar");
        getLogger().warning("Self-attach also failed or was disabled. This plugin only provides the runtime bridge classes.");
    }

    @Override
    public void onDisable() {
        if (compatibilityHandler != null) {
            Logger.getLogger("").removeHandler(compatibilityHandler);
            compatibilityHandler = null;
        }
    }

    private void installCompatibilityLogHandler() {
        if (compatibilityHandler != null) return;
        compatibilityHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || record.getThrown() == null) return;
                BridgeDiagnostics.compatibilityFailure(record.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("").addHandler(compatibilityHandler);
    }
}
