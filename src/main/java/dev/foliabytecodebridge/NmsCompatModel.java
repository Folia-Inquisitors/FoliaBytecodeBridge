package dev.foliabytecodebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies binary/server-internal shape failures separately from Folia
 * ownership routes. These failures are about a plugin expecting an NMS/Craft
 * member that the running server jar does not expose, so they need adapter
 * research before any bytecode rewrite is considered.
 */
public final class NmsCompatModel {

    public static final String CATEGORY = NmsCompatFamily.NMS_VERSION_COMPAT.label();

    private static final Pattern NO_SUCH_FIELD = Pattern.compile(
            "NoSuchFieldError: Class ([^ ]+) does not have member field '([^']+)'");
    private static final Pattern NO_SUCH_METHOD = Pattern.compile(
            "NoSuchMethodError: '?([^'\\s]+).*");
    private static final Pattern CLASS_NOT_FOUND = Pattern.compile(
            "(ClassNotFoundException|NoClassDefFoundError):\\s+([^\\s]+)");
    private static final Pattern STACK_FRAME = Pattern.compile(
            "\\s*at\\s+([^/\\s]+\\.jar)//([^\\s(]+)\\.([^\\s(]+)\\(([^:()]+):(\\d+)\\).*");

    private NmsCompatModel() {
    }

    public static List<Report> fromLogLines(List<String> lines) {
        List<Report> reports = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Optional<Failure> failure = classifyFailure(line);
            if (failure.isEmpty()) continue;

            StackFrame frame = findPluginFrame(lines, index + 1);
            reports.add(new Report(failure.get(), frame, line.trim()));
        }
        return reports;
    }

    public static Optional<Report> fromThrowable(Throwable throwable) {
        for (Throwable cursor = throwable; cursor != null; cursor = cursor.getCause()) {
            Optional<Failure> failure = classifyFailure(cursor.toString());
            if (failure.isEmpty()) continue;
            StackFrame frame = findPluginFrame(cursor.getStackTrace());
            return Optional.of(new Report(failure.get(), frame, cursor.toString()));
        }
        return Optional.empty();
    }

    public static Optional<ExecutorContextReport> executorContextFromThrowable(String sourceApi,
                                                                               String scheduledFrom,
                                                                               Throwable throwable) {
        if (throwable == null) return Optional.empty();
        Throwable cursor = throwable;
        while (cursor != null) {
            if (looksLikeMissingRegionizedWorldData(cursor) || hasServerExecutorFrame(cursor.getStackTrace())) {
                return Optional.of(new ExecutorContextReport(sourceApi, scheduledFrom,
                        probableServerExecutorOwner(cursor.getStackTrace()), cursor));
            }
            cursor = cursor.getCause();
        }
        return Optional.empty();
    }

    public static Optional<Failure> classifyFailure(String line) {
        Matcher field = NO_SUCH_FIELD.matcher(line);
        if (field.find()) {
            String owner = field.group(1);
            String rawMember = field.group(2);
            String descriptor = "unknown";
            String name = rawMember;
            int lastSpace = rawMember.lastIndexOf(' ');
            if (lastSpace >= 0 && lastSpace + 1 < rawMember.length()) {
                descriptor = descriptorFromJavaType(rawMember.substring(0, lastSpace));
                name = rawMember.substring(lastSpace + 1);
            }
            return Optional.of(new Failure("NoSuchFieldError", owner, name, descriptor,
                    "field", "server-internal-field-missing"));
        }

        Matcher method = NO_SUCH_METHOD.matcher(line);
        if (method.find()) {
            return Optional.of(new Failure("NoSuchMethodError", method.group(1),
                    "unknown", "unknown", "method", "server-internal-method-missing"));
        }

        Matcher missingClass = CLASS_NOT_FOUND.matcher(line);
        if (missingClass.find()) {
            return Optional.of(new Failure(missingClass.group(1), missingClass.group(2),
                    "class", "n/a", "class", "optional-or-server-class-missing"));
        }
        return Optional.empty();
    }

    public static boolean isServerInternalOwner(String owner) {
        String normalized = owner.replace('.', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("net/minecraft/")
                || normalized.startsWith("org/bukkit/craftbukkit/")
                || normalized.startsWith("io/papermc/paper/")
                || normalized.startsWith("ca/spottedleaf/");
    }

    public static String nextAction(Failure failure) {
        return switch (failure.kind()) {
            case "field" -> "inspect-running-server-member-map-before-bytecode-adapter";
            case "method" -> "inspect-running-server-method-map-before-bytecode-adapter";
            default -> "verify-dependency-or-server-class-before-adapter";
        };
    }

    private static boolean looksLikeMissingRegionizedWorldData(Throwable throwable) {
        String text = throwable.toString();
        return text.contains("getCurrentRegionizedWorldData()")
                || text.contains("currentRegionizedWorldData");
    }

    private static boolean hasServerExecutorFrame(StackTraceElement[] stackTrace) {
        for (StackTraceElement frame : stackTrace) {
            String className = frame.getClassName();
            if (className.contains("ServerChunkCache$MainThreadExecutor")
                    || className.equals("net.minecraft.server.MinecraftServer")
                    || className.contains("paper.util.MCUtil")) {
                return true;
            }
        }
        return false;
    }

    private static String probableServerExecutorOwner(StackTraceElement[] stackTrace) {
        for (StackTraceElement frame : stackTrace) {
            String className = frame.getClassName();
            if (className.contains("ServerChunkCache$MainThreadExecutor")) {
                return className + "#" + frame.getMethodName();
            }
        }
        for (StackTraceElement frame : stackTrace) {
            String className = frame.getClassName();
            if (className.startsWith("net.minecraft.") || className.startsWith("io.papermc.paper.")) {
                return className + "#" + frame.getMethodName();
            }
        }
        return "unknown";
    }

    private static StackFrame findPluginFrame(List<String> lines, int start) {
        for (int index = start; index < Math.min(lines.size(), start + 24); index++) {
            Matcher matcher = STACK_FRAME.matcher(lines.get(index));
            if (matcher.find()) {
                return new StackFrame(matcher.group(1), matcher.group(2), matcher.group(3),
                        matcher.group(4), Integer.parseInt(matcher.group(5)));
            }
        }
        return StackFrame.unknown();
    }

    private static StackFrame findPluginFrame(StackTraceElement[] stackTrace) {
        for (StackTraceElement frame : stackTrace) {
            String className = frame.getClassName();
            if (className.startsWith("java.")
                    || className.startsWith("jdk.")
                    || className.startsWith("sun.")
                    || className.startsWith("org.bukkit.")
                    || className.startsWith("io.papermc.paper.")
                    || className.startsWith("dev.foliabytecodebridge.")) {
                continue;
            }
            return new StackFrame("unknown", className, frame.getMethodName(),
                    frame.getFileName(), frame.getLineNumber());
        }
        return StackFrame.unknown();
    }

    private static String descriptorFromJavaType(String javaType) {
        return switch (javaType) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "double" -> "D";
            case "float" -> "F";
            case "int" -> "I";
            case "long" -> "J";
            case "short" -> "S";
            default -> "L" + javaType.replace('.', '/') + ";";
        };
    }

    public record Failure(String throwable, String owner, String name, String descriptor,
                          String kind, String reason) {
    }

    public record StackFrame(String jar, String className, String methodName,
                             String fileName, int lineNumber) {
        static StackFrame unknown() {
            return new StackFrame("unknown", "unknown", "unknown", "unknown", -1);
        }
    }

    public record Report(Failure failure, StackFrame frame, String sourceLine) {
        public String toEvidenceLine() {
            return "[FBB compat] category=" + CATEGORY
                    + " throwable=" + failure.throwable()
                    + " owner=" + failure.owner()
                    + " name=" + failure.name()
                    + " descriptor=" + failure.descriptor()
                    + " kind=" + failure.kind()
                    + " pluginJar=" + frame.jar()
                    + " caller=" + frame.className() + "#" + frame.methodName()
                    + "(" + frame.fileName() + ":" + frame.lineNumber() + ")"
                    + " action=diagnostic-only"
                    + " reason=" + failure.reason()
                    + " next=" + nextAction(failure);
        }
    }

    public record ExecutorContextReport(String sourceApi, String scheduledFrom, String owner,
                                        Throwable throwable) {
        public String toEvidenceLine() {
            return "[FBB nms-compat] category=" + NmsCompatFamily.NMS_EXECUTOR_CONTEXT.label()
                    + " model=SERVER_EXECUTOR_CONTEXT"
                    + " api=" + sourceApi
                    + " owner=" + owner
                    + " route=none"
                    + " previousRoute=S_GLOBAL"
                    + " result=owner-context-missing"
                    + " action=diagnostic-only"
                    + " scheduledFrom=" + scheduledFrom
                    + " throwable=" + throwable.getClass().getName()
                    + ": " + throwable.getMessage()
                    + " next=derive-world-or-chunk-owner-before-promoting-executor-route"
                    + " note=nms-server-internal-executor-context-not-bukkit-route-family";
        }
    }
}
