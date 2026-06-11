package dev.foliabytecodebridge;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SelfAttachInstaller {

    private static final long ATTACH_TIMEOUT_SECONDS = 20L;

    private SelfAttachInstaller() {
    }

    /**
     * Public bootstrap boundary for plugin-only startup.
     *
     * <p>Even though self-attach is not the preferred startup mode, the plugin
     * entrypoint can link this class through the helper-runtime loader, so the
     * method must be public across that boundary.</p>
     */
    public static void installFromPlugin(JavaPlugin plugin, File jarFile) {
        Logger logger = plugin.getLogger();
        BridgeDiagnostics.setLogger(logger);

        if (agentInstalled()) {
            String mode = System.getProperty("foliabytecodebridge.agentMode", "JAVA_AGENT");
            logger.info("[FBB attach] mode=" + mode + " installed=true phase=onLoad reason=already-installed");
            return;
        }

        if (!Boolean.parseBoolean(System.getProperty("foliabytecodebridge.selfAttach", "true"))) {
            logger.warning("[FBB attach-warning] mode=SELF_ATTACH installed=false phase=onLoad reason=disabled");
            return;
        }

        String pid = currentPid();
        Instant started = Instant.now();
        logger.info("[FBB attach] mode=SELF_ATTACH installed=false phase=onLoad action=spawn-helper pid="
                + pid + " jar=" + jarFile.getAbsolutePath());

        try {
            AttachResult result = runAttachHelper(pid, jarFile);
            long elapsedMs = Duration.between(started, Instant.now()).toMillis();
            boolean installed = Boolean.parseBoolean(System.getProperty("foliabytecodebridge.agentInstalled", "false"));
            if (result.exitCode == 0 && installed) {
                logger.info("[FBB attach] mode=SELF_ATTACH installed=true phase=onLoad exit="
                        + result.exitCode + " elapsedMs=" + elapsedMs);
                return;
            }

            logger.warning("[FBB attach-warning] mode=SELF_ATTACH installed=" + installed
                    + " phase=onLoad exit=" + result.exitCode
                    + " elapsedMs=" + elapsedMs
                    + " output=" + compact(result.output));
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "[FBB attach-warning] mode=SELF_ATTACH installed=false phase=onLoad reason="
                    + throwable.getClass().getName() + ": " + throwable.getMessage(), throwable);
        }
    }

    private static AttachResult runAttachHelper(String pid, File jarFile) throws Exception {
        String javaBinary = javaBinary();
        List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.add("--add-modules");
        command.add("jdk.attach");
        command.add("-cp");
        command.add(jarFile.getAbsolutePath());
        command.add(SelfAttachInstaller.AttachHelper.class.getName());
        command.add(pid);
        command.add(jarFile.getAbsolutePath());
        command.add("SELF_ATTACH");

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("attach helper timed out after " + ATTACH_TIMEOUT_SECONDS + "s");
        }
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new AttachResult(process.exitValue(), output);
    }

    private static String currentPid() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (Throwable ignored) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int at = runtimeName.indexOf('@');
            return at > 0 ? runtimeName.substring(0, at) : runtimeName;
        }
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        String executable = isWindows() ? "java.exe" : "java";
        File binary = new File(new File(javaHome, "bin"), executable);
        return binary.isFile() ? binary.getAbsolutePath() : executable;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String compact(String text) {
        if (text == null || text.isBlank()) return "none";
        String compacted = text.replace('\r', ' ').replace('\n', ' ').trim();
        return compacted.length() <= 500 ? compacted : compacted.substring(0, 500) + "...";
    }

    private static boolean agentInstalled() {
        // Avoid touching FoliaBytecodeBridgeAgent from plugin bootstrap. That
        // class intentionally imports Byte Buddy for transformation, while this
        // installer can also run from the helper-runtime loader before Byte
        // Buddy is visible there.
        return Boolean.parseBoolean(System.getProperty("foliabytecodebridge.agentInstalled", "false"));
    }

    private static final class AttachResult {
        private final int exitCode;
        private final String output;

        private AttachResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    public static final class AttachHelper {

        private AttachHelper() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length < 2) {
                throw new IllegalArgumentException("Usage: AttachHelper <pid> <agent-jar> [agent-args]");
            }
            String pid = args[0];
            String agentJar = args[1];
            String agentArgs = args.length >= 3 ? args[2] : "SELF_ATTACH";

            Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
            Method attach = vmType.getMethod("attach", String.class);
            Object vm = invoke(attach, null, pid);
            try {
                Method loadAgent = vmType.getMethod("loadAgent", String.class, String.class);
                invoke(loadAgent, vm, agentJar, agentArgs);
            } finally {
                Method detach = vmType.getMethod("detach");
                invoke(detach, vm);
            }
        }

        private static Object invoke(Method method, Object target, Object... args) throws Exception {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw exception;
            }
        }
    }
}
