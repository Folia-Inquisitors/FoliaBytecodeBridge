package dev.foliabytecodebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class BridgeConfig {

    private static final String PREFIX = "foliabytecodebridge.";
    private static final Path DEFAULT_CONFIG_PATH = Path.of("plugins", "FoliaBytecodeBridge", "config.properties");
    private static final Properties FILE_PROPERTIES = new Properties();
    private static volatile boolean loaded;

    private BridgeConfig() {
    }

    static synchronized void load() {
        if (loaded) return;
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (InputStream inputStream = Files.newInputStream(path)) {
                FILE_PROPERTIES.load(inputStream);
            } catch (IOException exception) {
                System.err.println("[FoliaBytecodeBridge] Could not load config " + path + ": " + exception.getMessage());
            }
        }
        loaded = true;
    }

    static boolean enabled() {
        return bool("enabled", true);
    }

    static boolean forceTranslation() {
        return bool("forceTranslation", true);
    }

    static boolean forceNonFolia() {
        return bool("forceNonFolia", false);
    }

    static boolean debug() {
        return bool("debug", false);
    }

    static boolean debugFile() {
        return bool("debugFile", true);
    }

    static boolean debugFileVerbose() {
        return bool("debugFileVerbose", true);
    }

    static String debugFilePath() {
        return string("debugFilePath", "plugins/FoliaBytecodeBridge/debug.log");
    }

    static boolean consoleVerbose() {
        return bool("consoleVerbose", false);
    }

    static boolean traceTransforms() {
        return bool("traceTransforms", false);
    }

    static boolean traceTransformSkips() {
        return bool("traceTransformSkips", false);
    }

    static boolean traceBytecodePaths() {
        return bool("traceBytecodePaths", false);
    }

    static boolean traceGuardPaths() {
        return bool("traceGuardPaths", false);
    }

    static boolean traceSchedulerCalls() {
        return bool("traceSchedulerCalls", false);
    }

    static boolean traceUnsafeCalls() {
        return bool("traceUnsafeCalls", false);
    }

    static boolean modelReports() {
        return bool("modelReports", true);
    }

    static int modelSummaryIntervalSeconds() {
        return integer("modelSummaryIntervalSeconds", 30);
    }

    static int repeatDiagnosticFirstLines() {
        return integer("repeatDiagnosticFirstLines", 3);
    }

    static int repeatDiagnosticEvery() {
        return integer("repeatDiagnosticEvery", 100);
    }

    static String metadataOverlay() {
        return string("metadataOverlay", "off").toLowerCase(java.util.Locale.ROOT);
    }

    static boolean metadataOverlayAll() {
        return "all".equals(metadataOverlay());
    }

    static Path activeConfigPath() {
        return configPath();
    }

    private static boolean bool(String key, boolean defaultValue) {
        load();
        String value = System.getProperty(PREFIX + key);
        if (value == null) value = FILE_PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }

    private static String string(String key, String defaultValue) {
        load();
        String value = System.getProperty(PREFIX + key);
        if (value == null) value = FILE_PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return value.trim();
    }

    private static int integer(String key, int defaultValue) {
        load();
        String value = System.getProperty(PREFIX + key);
        if (value == null) value = FILE_PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Path configPath() {
        String override = System.getProperty(PREFIX + "config");
        if (override == null || override.isBlank()) return DEFAULT_CONFIG_PATH;
        return Path.of(override);
    }
}
