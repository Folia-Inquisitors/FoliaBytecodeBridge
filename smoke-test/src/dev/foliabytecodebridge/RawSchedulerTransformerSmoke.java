package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class RawSchedulerTransformerSmoke {

    private static final String PLAYERKITS_PLAYERS_CONFIG = "pk/ajneb97/configs/PlayersConfigManager";
    private static final String PLAYERKITS_PLAYERS_CONFIG_TASK = "pk/ajneb97/configs/PlayersConfigManager$1";
    private static final String ESSENTIALS_MAIN = "com/earth2me/essentials/Essentials";
    private static final String FAWE_TASK_MANAGER = "com/fastasyncworldedit/bukkit/util/BukkitTaskManager";
    private static final String BRIDGE = "dev/foliabytecodebridge/ObjectSchedulerBridge";

    private RawSchedulerTransformerSmoke() {
    }

    public static int assertPlayerKitsInheritedBukkitRunnableAsync(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            byte[] original = readClass(jar, PLAYERKITS_PLAYERS_CONFIG + ".class");
            if (original == null) continue;

            byte[] transformed = new RawSchedulerTransformer().transform(
                    null, null, PLAYERKITS_PLAYERS_CONFIG, null, null, original);
            if (transformed == null) {
                throw new IllegalStateException("Raw scheduler transformer missed PlayerKits2 "
                        + "PlayersConfigManager inherited BukkitRunnable#runTaskAsynchronously");
            }

            MethodHits hits = scan(transformed);
            if (hits.bridgeAsyncRunnableCalls != 1 || hits.legacyAsyncRunnableCalls != 0) {
                throw new IllegalStateException("Unexpected PlayerKits2 raw transform result: bridge="
                        + hits.bridgeAsyncRunnableCalls + " legacy=" + hits.legacyAsyncRunnableCalls);
            }
            return 1;
        }
        return 0;
    }

    public static int assertPlayerKitsAnonymousRunnableOverride(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            byte[] original = readClass(jar, PLAYERKITS_PLAYERS_CONFIG_TASK + ".class");
            if (original == null) continue;

            byte[] transformed = new RawSchedulerTransformer().transform(
                    null, null, PLAYERKITS_PLAYERS_CONFIG_TASK, null, null, original);
            if (transformed == null) {
                throw new IllegalStateException("Raw scheduler transformer did not add PlayerKits2 "
                        + "anonymous BukkitRunnable override bridge");
            }

            MethodHits hits = scan(transformed);
            if (hits.asyncRunnableOverrideMethods != 1 || hits.bridgeAsyncRunnableCalls < 1) {
                throw new IllegalStateException("Unexpected PlayerKits2 anonymous override result: overrides="
                        + hits.asyncRunnableOverrideMethods + " bridge=" + hits.bridgeAsyncRunnableCalls);
            }
            return 1;
        }
        return 0;
    }

    public static int assertEssentialsHelperNotMisclassified(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            byte[] original = readClass(jar, ESSENTIALS_MAIN + ".class");
            if (original == null) continue;

            byte[] transformed = new RawSchedulerTransformer().transform(
                    null, null, ESSENTIALS_MAIN, null, null, original);
            if (transformed == null) {
                throw new IllegalStateException("Raw scheduler transformer missed Essentials scheduler wrappers");
            }

            MethodHits hits = scan(transformed);
            if (hits.bridgeAsyncRunnableCalls != 0) {
                throw new IllegalStateException("Essentials helper method was misclassified as BukkitRunnable: bridge="
                        + hits.bridgeAsyncRunnableCalls);
            }
            if (hits.bridgeSchedulerAsyncCalls == 0) {
                throw new IllegalStateException("Essentials scheduler wrapper was not routed through BukkitScheduler bridge");
            }
            return 1;
        }
        return 0;
    }

    public static int assertFaweLegacyAsyncRepeatingScheduler(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            byte[] original = readClass(jar, FAWE_TASK_MANAGER + ".class");
            if (original == null) continue;

            byte[] transformed = new RawSchedulerTransformer().transform(
                    null, null, FAWE_TASK_MANAGER, null, null, original);
            if (transformed == null) {
                throw new IllegalStateException("Raw scheduler transformer missed FAWE legacy "
                        + "BukkitScheduler#scheduleAsyncRepeatingTask");
            }

            MethodHits hits = scan(transformed);
            if (hits.bridgeScheduleAsyncRepeatingCalls != 1 || hits.legacyScheduleAsyncRepeatingCalls != 0) {
                throw new IllegalStateException("Unexpected FAWE legacy async repeating transform result: bridge="
                        + hits.bridgeScheduleAsyncRepeatingCalls + " legacy="
                        + hits.legacyScheduleAsyncRepeatingCalls);
            }
            return 1;
        }
        return 0;
    }

    private static byte[] readClass(Path jar, String entryName) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) return null;
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                return inputStream.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + entryName + " from " + jar, exception);
        }
    }

    private static MethodHits scan(byte[] classBytes) {
        MethodHits hits = new MethodHits();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("runTaskAsynchronously".equals(name)
                        && "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                    hits.asyncRunnableOverrideMethods++;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && BRIDGE.equals(owner)
                                && "bukkitRunnableRunTaskAsynchronously".equals(methodName)
                                && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(methodDescriptor)) {
                            hits.bridgeAsyncRunnableCalls++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && BRIDGE.equals(owner)
                                && "runTaskAsynchronously".equals(methodName)
                                && "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;".equals(methodDescriptor)) {
                            hits.bridgeSchedulerAsyncCalls++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && BRIDGE.equals(owner)
                                && "scheduleAsyncRepeatingTask".equals(methodName)
                                && "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)I".equals(methodDescriptor)) {
                            hits.bridgeScheduleAsyncRepeatingCalls++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "runTaskAsynchronously".equals(methodName)
                                && methodDescriptor.endsWith(")Lorg/bukkit/scheduler/BukkitTask;")) {
                            hits.legacyAsyncRunnableCalls++;
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && "org/bukkit/scheduler/BukkitScheduler".equals(owner)
                                && "scheduleAsyncRepeatingTask".equals(methodName)
                                && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I".equals(methodDescriptor)) {
                            hits.legacyScheduleAsyncRepeatingCalls++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hits;
    }

    private static final class MethodHits {
        private int bridgeAsyncRunnableCalls;
        private int bridgeSchedulerAsyncCalls;
        private int bridgeScheduleAsyncRepeatingCalls;
        private int legacyAsyncRunnableCalls;
        private int legacyScheduleAsyncRepeatingCalls;
        private int asyncRunnableOverrideMethods;
    }
}
