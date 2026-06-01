package dev.foliabytecodebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class MetadataJarReporter {

    private MetadataJarReporter() {
    }

    static void reportPluginDirectory(Path pluginDirectory) {
        if (!Files.isDirectory(pluginDirectory)) return;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginDirectory, "*.jar")) {
            for (Path jar : jars) {
                if (jar.getFileName().toString().equalsIgnoreCase("FoliaBytecodeBridge.jar")) continue;
                reportPluginJar(jar);
            }
        } catch (IOException exception) {
            BridgeDiagnostics.metadataJar("unknown", pluginDirectory.toString(),
                    "scan-failed", BridgeConfig.metadataOverlay(), exception.getClass().getSimpleName());
        }
    }

    private static void reportPluginJar(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry pluginYml = jarFile.getJarEntry("plugin.yml");
            if (pluginYml == null) {
                BridgeDiagnostics.metadataJar("unknown", jar.toString(),
                        "scan-skipped", BridgeConfig.metadataOverlay(), "missing-plugin-yml");
                return;
            }
            String text;
            try (InputStream stream = jarFile.getInputStream(pluginYml)) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            String pluginName = valueFor(text, "name");
            String foliaSupported = valueFor(text, "folia-supported");
            String result = "true".equalsIgnoreCase(foliaSupported)
                    ? "already-supported"
                    : "overlay-will-force-true";
            BridgeDiagnostics.metadataJar(pluginName == null ? "unknown" : pluginName,
                    jar.toString(), "jar-scan", BridgeConfig.metadataOverlay(), result);
        } catch (IOException exception) {
            BridgeDiagnostics.metadataJar("unknown", jar.toString(),
                    "scan-failed", BridgeConfig.metadataOverlay(), exception.getClass().getSimpleName());
        }
    }

    private static String valueFor(String text, String key) {
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
