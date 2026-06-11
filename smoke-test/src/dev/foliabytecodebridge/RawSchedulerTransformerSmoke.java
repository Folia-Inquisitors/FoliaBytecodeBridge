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

    private static final String BRIDGE = "dev/foliabytecodebridge/ObjectSchedulerBridge";

    private RawSchedulerTransformerSmoke() {
    }

    public static int assertInheritedBukkitRunnableAsync(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jarFile.entries())) {
                    String className = className(entry);
                    if (className == null) continue;
                    byte[] original = readClass(jarFile, entry);
                    byte[] transformed = new RawSchedulerTransformer().transform(
                            null, null, className, null, null, original);
                    if (transformed == null) continue;

                    MethodHits hits = scan(transformed);
                    if (hits.bridgeAsyncRunnableCalls > 0) {
                        if (hits.legacyAsyncRunnableCalls != 0) {
                            throw new IllegalStateException("Unexpected anonymous runnable raw transform result: bridge="
                                    + hits.bridgeAsyncRunnableCalls + " legacy=" + hits.legacyAsyncRunnableCalls);
                        }
                        return 1;
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to scan anonymous runnable references in " + jar, exception);
            }
        }
        return 0;
    }

    public static int assertAnonymousRunnableOverride(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jarFile.entries())) {
                    String className = className(entry);
                    if (className == null || !isAnonymousOwnerShape(className)) continue;
                    byte[] original = readClass(jarFile, entry);
                    byte[] transformed = new RawSchedulerTransformer().transform(
                            null, null, className, null, null, original);
                    if (transformed == null) continue;

                    MethodHits hits = scan(transformed);
                    if (hits.asyncRunnableOverrideMethods > 0 || hits.bridgeAsyncRunnableCalls > 0) {
                        if (hits.bridgeAsyncRunnableCalls < 1) {
                            throw new IllegalStateException("Unexpected anonymous runnable override result: overrides="
                                    + hits.asyncRunnableOverrideMethods + " bridge=" + hits.bridgeAsyncRunnableCalls);
                        }
                        return 1;
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to scan anonymous runnable override references in " + jar, exception);
            }
        }
        return 0;
    }

    public static int assertPluginSchedulerHelperNotMisclassified(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jarFile.entries())) {
                    String className = className(entry);
                    if (className == null || isAnonymousOwnerShape(className)) continue;
                    byte[] original = readClass(jarFile, entry);
                    MethodHits before = scan(original);
                    if (before.legacySchedulerAsyncCalls == 0) continue;

                    byte[] transformed = new RawSchedulerTransformer().transform(
                            null, null, className, null, null, original);
                    if (transformed == null) {
                        throw new IllegalStateException("Raw scheduler transformer missed plugin scheduler wrappers");
                    }

                    MethodHits hits = scan(transformed);
                    if (hits.bridgeAsyncRunnableCalls != 0) {
                        throw new IllegalStateException("Plugin helper method was misclassified as BukkitRunnable: bridge="
                                + hits.bridgeAsyncRunnableCalls);
                    }
                    if (hits.bridgeSchedulerAsyncCalls == 0) {
                        throw new IllegalStateException("Plugin scheduler wrapper was not routed through BukkitScheduler bridge");
                    }
                    return 1;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to scan plugin scheduler helper references in " + jar, exception);
            }
        }
        return 0;
    }

    public static int assertLegacyAsyncRepeatingScheduler(Path[] pluginJars) {
        if (pluginJars.length == 0) return 0;
        for (Path jar : pluginJars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jarFile.entries())) {
                    String className = className(entry);
                    if (className == null) continue;
                    byte[] original = readClass(jarFile, entry);
                    MethodHits before = scan(original);
                    if (before.legacyScheduleAsyncRepeatingCalls == 0) continue;

                    byte[] transformed = new RawSchedulerTransformer().transform(
                            null, null, className, null, null, original);
                    if (transformed == null) {
                        throw new IllegalStateException("Raw scheduler transformer missed legacy "
                                + "BukkitScheduler#scheduleAsyncRepeatingTask");
                    }

                    MethodHits hits = scan(transformed);
                    if (hits.bridgeScheduleAsyncRepeatingCalls == 0 || hits.legacyScheduleAsyncRepeatingCalls != 0) {
                        throw new IllegalStateException("Unexpected legacy async repeating transform result: bridge="
                                + hits.bridgeScheduleAsyncRepeatingCalls + " legacy="
                                + hits.legacyScheduleAsyncRepeatingCalls);
                    }
                    return 1;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to scan legacy async repeating scheduler references in " + jar, exception);
            }
        }
        return 0;
    }

    private static byte[] readClass(Path jar, String entryName) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) return null;
            return readClass(jarFile, entry);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + entryName + " from " + jar, exception);
        }
    }

    private static byte[] readClass(JarFile jarFile, JarEntry entry) throws IOException {
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
            return inputStream.readAllBytes();
        }
    }

    private static String className(JarEntry entry) {
        String name = entry.getName();
        if (entry.isDirectory() || !name.endsWith(".class")) return null;
        return name.substring(0, name.length() - ".class".length());
    }

    private static boolean isAnonymousOwnerShape(String owner) {
        int marker = owner.lastIndexOf('$');
        return marker >= 0
                && marker < owner.length() - 1
                && Character.isDigit(owner.charAt(marker + 1));
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
                        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && "runTaskAsynchronously".equals(methodName)
                                && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;".equals(methodDescriptor)) {
                            hits.legacySchedulerAsyncCalls++;
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
        private int legacySchedulerAsyncCalls;
        private int bridgeScheduleAsyncRepeatingCalls;
        private int legacyAsyncRunnableCalls;
        private int legacyScheduleAsyncRepeatingCalls;
        private int asyncRunnableOverrideMethods;
    }
}
