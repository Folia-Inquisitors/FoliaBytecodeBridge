package dev.foliabytecodebridge;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps diagnostics useful without leaking the operator's workstation paths.
 *
 * <p>This is deliberately display-only. The bridge still uses real paths for
 * class loading and evidence scans; only log/tool output is redacted.</p>
 */
final class PrivacySanitizer {
    private static final Pattern SERVER_ROOT_FILE = Pattern.compile(
            "(?i)file:/[A-Z]:/Users/[^/\"']+/[^\"']*?/del");
    private static final Pattern SERVER_ROOT = Pattern.compile(
            "(?i)[A-Z]:/Users/[^/\"']+/[^\"']*?/del");
    private static final Pattern WORKSPACE_FILE = Pattern.compile(
            "(?i)file:/[A-Z]:/Users/[^\\s\"']+/Documents/Codex/[^\\s\"']*");
    private static final Pattern WORKSPACE = Pattern.compile(
            "(?i)[A-Z]:/Users/[^\\s\"']+/Documents/Codex/[^\\s\"']*");
    private static final Pattern USER_HOME_FILE = Pattern.compile(
            "(?i)file:/[A-Z]:/Users/[^\\s\"']+");
    private static final Pattern USER_HOME = Pattern.compile(
            "(?i)[A-Z]:/Users/[^\\s\"']+");

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
        if (looksLikeUserPath(sanitized)) {
            sanitized = SERVER_ROOT_FILE.matcher(sanitized).replaceAll("file:/<server-root>");
            sanitized = SERVER_ROOT.matcher(sanitized).replaceAll("<server-root>");
            sanitized = WORKSPACE_FILE.matcher(sanitized).replaceAll("file:/<workspace>");
            sanitized = WORKSPACE.matcher(sanitized).replaceAll("<workspace>");
            sanitized = USER_HOME_FILE.matcher(sanitized).replaceAll("file:/<user-home>");
            sanitized = USER_HOME.matcher(sanitized).replaceAll("<user-home>");
        }
        sanitized = redactLegacyUserPackages(sanitized);
        return sanitized;
    }

    private static String redactLegacyUserPackages(String value) {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) return value;

        // Older experimental builds used a personal package prefix. Derive the
        // local user token at runtime so source releases do not carry it.
        String token = userName.toLowerCase(Locale.ROOT);
        String sanitized = value;
        sanitized = sanitized.replace("com." + token + ".foliabytecodebridge", "dev.foliabytecodebridge");
        sanitized = sanitized.replace("com." + token + ".fbbprobe", "dev.fbbprobe");
        sanitized = sanitized.replace("com/" + token + "/foliabytecodebridge", "dev/foliabytecodebridge");
        sanitized = sanitized.replace("com/" + token + "/fbbprobe", "dev/fbbprobe");
        return sanitized;
    }

    private static boolean looksLikeUserPath(String value) {
        // Most hot-path diagnostics are route labels and stack frames, not file
        // paths. Avoid regex matching on Folia owner threads unless the line
        // actually contains a Windows user path shape that could leak identity.
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("/users/") || lower.contains(":/users/");
    }
}
