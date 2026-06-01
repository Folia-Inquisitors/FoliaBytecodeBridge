package dev.foliabytecodebridge;

import java.io.File;
import java.nio.file.Path;

/**
 * Keeps diagnostics useful without leaking the operator's workstation paths.
 *
 * <p>This is deliberately display-only. The bridge still uses real paths for
 * class loading and evidence scans; only log/tool output is redacted.</p>
 */
final class PrivacySanitizer {

    private PrivacySanitizer() {
    }

    static String path(File file) {
        return file == null ? "unknown" : text(file.getAbsolutePath());
    }

    static String path(Path path) {
        return path == null ? "" : text(path.toString());
    }

    static String text(String value) {
        if (value == null || value.isBlank()) return value;
        String sanitized = value.replace("\\", "/").replace("%20", " ");
        sanitized = sanitized.replaceAll("(?i)file:/[A-Z]:/Users/[^/\"']+/[^\"']*?/del", "file:/<server-root>");
        sanitized = sanitized.replaceAll("(?i)[A-Z]:/Users/[^/\"']+/[^\"']*?/del", "<server-root>");
        sanitized = sanitized.replaceAll("(?i)file:/[A-Z]:/Users/[^\\s\"']+/Documents/Codex/[^\\s\"']*", "file:/<workspace>");
        sanitized = sanitized.replaceAll("(?i)[A-Z]:/Users/[^\\s\"']+/Documents/Codex/[^\\s\"']*", "<workspace>");
        sanitized = sanitized.replaceAll("(?i)file:/[A-Z]:/Users/[^\\s\"']+", "file:/<user-home>");
        sanitized = sanitized.replaceAll("(?i)[A-Z]:/Users/[^\\s\"']+", "<user-home>");
        sanitized = sanitized.replace(legacyDotPrefix() + ".foliabytecodebridge", "dev.foliabytecodebridge");
        sanitized = sanitized.replace(legacyDotPrefix() + ".fbbprobe", "dev.fbbprobe");
        sanitized = sanitized.replace(legacySlashPrefix() + "/foliabytecodebridge", "dev/foliabytecodebridge");
        sanitized = sanitized.replace(legacySlashPrefix() + "/fbbprobe", "dev/fbbprobe");
        return sanitized;
    }

    private static String legacyDotPrefix() {
        return new String(new char[] {'c', 'o', 'm', '.', 'r', 'a', 'j', 'b', 'e'});
    }

    private static String legacySlashPrefix() {
        return new String(new char[] {'c', 'o', 'm', '/', 'r', 'a', 'j', 'b', 'e'});
    }
}
