package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RawSchedulerTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/foliabytecodebridge/ObjectSchedulerBridge";
    private static final String BUKKIT_TASK = "org/bukkit/scheduler/BukkitTask";
    private static final String BUKKIT_TASK_DESCRIPTOR = "Lorg/bukkit/scheduler/BukkitTask;";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || shouldIgnore(className)) return null;
        AtomicBoolean changed = new AtomicBoolean(false);
        AtomicInteger replacements = new AtomicInteger();
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private boolean anonymousBukkitRunnableSubclass;
                private boolean hasRunTask;
                private boolean hasRunTaskLater;
                private boolean hasRunTaskTimer;
                private boolean hasRunTaskAsync;
                private boolean hasRunTaskLaterAsync;
                private boolean hasRunTaskTimerAsync;

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    anonymousBukkitRunnableSubclass = classBeingRedefined == null
                            && isAnonymousOwnerShape(name)
                            && "org/bukkit/scheduler/BukkitRunnable".equals(superName);
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    recordBukkitRunnableOverride(name, descriptor);
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String currentMethod = name;
                    String currentDescriptor = descriptor;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            Replacement replacement = replacement(opcode, owner, methodName, methodDescriptor);
                            if (replacement == null) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }
                            changed.set(true);
                            replacements.incrementAndGet();
                            BridgeDiagnostics.bytecodePath(className, currentMethod, currentDescriptor,
                                    owner, methodName, methodDescriptor,
                                    replacement.methodName, replacement.descriptor, replacement.routeFamily);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    replacement.methodName, replacement.descriptor, false);
                            if (replacement.castTo != null) {
                                super.visitTypeInsn(Opcodes.CHECKCAST, replacement.castTo);
                            }
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    if (anonymousBukkitRunnableSubclass) {
                        // Some plugin classes can load before self-attach sees their caller class. Anonymous
                        // exposed this with PlayersConfigManager#loadConfig still invoking
                        // PlayersConfigManager$1#runTaskAsynchronously(Plugin). Adding narrow overrides on the
                        // anonymous BukkitRunnable receiver lets virtual dispatch reach the bridge even when the
                        // already-loaded caller method could not be rewritten.
                        addMissingBukkitRunnableOverrides(this);
                    }
                    super.visitEnd();
                }

                private void recordBukkitRunnableOverride(String name, String descriptor) {
                    if (!anonymousBukkitRunnableSubclass) return;
                    if ("runTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTask = true;
                    } else if ("runTaskLater".equals(name) && "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTaskLater = true;
                    } else if ("runTaskTimer".equals(name) && "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTaskTimer = true;
                    } else if ("runTaskAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTaskAsync = true;
                    } else if ("runTaskLaterAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTaskLaterAsync = true;
                    } else if ("runTaskTimerAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
                        hasRunTaskTimerAsync = true;
                    }
                }

                private void addMissingBukkitRunnableOverrides(ClassVisitor visitor) {
                    if (!hasRunTask) {
                        addBukkitRunnableOverride(visitor, "runTask",
                                "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTask",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                "", RouteFamily.S_GLOBAL);
                    }
                    if (!hasRunTaskLater) {
                        addBukkitRunnableOverride(visitor, "runTaskLater",
                                "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTaskLater",
                                "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;",
                                "J", RouteFamily.S_GLOBAL);
                    }
                    if (!hasRunTaskTimer) {
                        addBukkitRunnableOverride(visitor, "runTaskTimer",
                                "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTaskTimer",
                                "(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;",
                                "JJ", RouteFamily.S_GLOBAL);
                    }
                    if (!hasRunTaskAsync) {
                        addBukkitRunnableOverride(visitor, "runTaskAsynchronously",
                                "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTaskAsynchronously",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                "", RouteFamily.S_ASYNC);
                    }
                    if (!hasRunTaskLaterAsync) {
                        addBukkitRunnableOverride(visitor, "runTaskLaterAsynchronously",
                                "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTaskLaterAsynchronously",
                                "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;",
                                "J", RouteFamily.S_ASYNC);
                    }
                    if (!hasRunTaskTimerAsync) {
                        addBukkitRunnableOverride(visitor, "runTaskTimerAsynchronously",
                                "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                                "bukkitRunnableRunTaskTimerAsynchronously",
                                "(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;",
                                "JJ", RouteFamily.S_ASYNC);
                    }
                }

                private void addBukkitRunnableOverride(ClassVisitor visitor, String name, String descriptor,
                                                       String bridgeMethod, String bridgeDescriptor,
                                                       String primitiveTail, RouteFamily routeFamily) {
                    MethodVisitor method = visitor.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                            name, descriptor, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    int local = 2;
                    for (int i = 0; i < primitiveTail.length(); i++) {
                        char type = primitiveTail.charAt(i);
                        if (type == 'J') {
                            method.visitVarInsn(Opcodes.LLOAD, local);
                            local += 2;
                        }
                    }
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, bridgeMethod, bridgeDescriptor, false);
                    method.visitTypeInsn(Opcodes.CHECKCAST, BUKKIT_TASK);
                    method.visitInsn(Opcodes.ARETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                    changed.set(true);
                    replacements.incrementAndGet();
                    BridgeDiagnostics.bytecodePath(className, "<anonymous-bukkit-runnable-override>", descriptor,
                            className, name, descriptor, bridgeMethod, bridgeDescriptor, routeFamily);
                }
            }, 0);
            if (!changed.get()) return null;
            BridgeDiagnostics.rawSchedulerTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }

    private static Replacement replacement(int opcode, String owner, String name, String descriptor) {
        if ("org/bukkit/scheduler/BukkitScheduler".equals(owner)) {
            return schedulerReplacement(name, descriptor);
        }
        // Anonymous BukkitRunnable subclasses compile calls with the anonymous class as the bytecode owner,
        // for example PlayersConfigManager$1#runTaskAsynchronously(Plugin). Only accept that explicit
        // anonymous-owner shape here. A live Essentials smoke test showed plugin helper methods such as
        // Essentials#runTaskAsynchronously(Runnable) can share the same name/return type but are not
        // BukkitRunnable receivers; widening this guard misroutes them into the runnable bridge.
        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)
                && isBukkitRunnableOwnerShape(owner)
                && descriptor.startsWith("(L")) {
            return bukkitRunnableReplacement(name, descriptor);
        }
        return null;
    }

    private static boolean isBukkitRunnableOwnerShape(String owner) {
        if ("org/bukkit/scheduler/BukkitRunnable".equals(owner)) return true;

        return isAnonymousOwnerShape(owner);
    }

    private static boolean isAnonymousOwnerShape(String owner) {
        int marker = owner.lastIndexOf('$');
        if (marker < 0 || marker == owner.length() - 1) return false;

        // Javac anonymous classes compile to Outer$1, Outer$2, etc. Named inner classes and
        // lambda/helper owners stay out until a smoke log proves a more precise bridge shape.
        return Character.isDigit(owner.charAt(marker + 1));
    }

    private static Replacement schedulerReplacement(String name, String descriptor) {
        if ("runTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTask", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskLater".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTaskLater", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskTimer".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTaskTimer", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTaskAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        if ("runTaskLaterAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTaskLaterAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        if ("runTaskTimerAsynchronously".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;".equals(descriptor)) {
            return task("runTaskTimerAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)Ljava/lang/Object;", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        if ("scheduleSyncDelayedTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I".equals(descriptor)) {
            return primitive("scheduleSyncDelayedTask", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)I", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("scheduleSyncDelayedTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)I".equals(descriptor)) {
            return primitive("scheduleSyncDelayedTaskLater", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)I", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("scheduleSyncRepeatingTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I".equals(descriptor)) {
            return primitive("scheduleSyncRepeatingTask", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)I", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("scheduleAsyncDelayedTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I".equals(descriptor)) {
            return primitive("scheduleAsyncDelayedTask", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)I", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        if ("scheduleAsyncDelayedTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)I".equals(descriptor)) {
            return primitive("scheduleAsyncDelayedTaskLater", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)I", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        // Live testing exposed this legacy int-returning async repeating shape. It belongs to the
        // scheduler route family, not to a plugin-specific rule: any plugin using this exact
        // BukkitScheduler descriptor should route through Folia's async scheduler.
        if ("scheduleAsyncRepeatingTask".equals(name) && "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I".equals(descriptor)) {
            return primitive("scheduleAsyncRepeatingTask", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)I", schedulerApi(name), RouteFamily.S_ASYNC);
        }
        if ("cancelTask".equals(name) && "(I)V".equals(descriptor)) {
            return primitive("cancelTask", "(Ljava/lang/Object;I)V", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        if ("cancelTasks".equals(name) && "(Lorg/bukkit/plugin/Plugin;)V".equals(descriptor)) {
            return primitive("cancelTasks", "(Ljava/lang/Object;Ljava/lang/Object;)V", schedulerApi(name), RouteFamily.S_GLOBAL);
        }
        return null;
    }

    private static Replacement bukkitRunnableReplacement(String name, String descriptor) {
        if ("runTask".equals(name) && objectThenReturnsTask(descriptor, "")) {
            return task("bukkitRunnableRunTask", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskLater".equals(name) && objectThenReturnsTask(descriptor, "J")) {
            return task("bukkitRunnableRunTaskLater", "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskTimer".equals(name) && objectThenReturnsTask(descriptor, "JJ")) {
            return task("bukkitRunnableRunTaskTimer", "(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_GLOBAL);
        }
        if ("runTaskAsynchronously".equals(name) && objectThenReturnsTask(descriptor, "")) {
            return task("bukkitRunnableRunTaskAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_ASYNC);
        }
        if ("runTaskLaterAsynchronously".equals(name) && objectThenReturnsTask(descriptor, "J")) {
            return task("bukkitRunnableRunTaskLaterAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_ASYNC);
        }
        if ("runTaskTimerAsynchronously".equals(name) && objectThenReturnsTask(descriptor, "JJ")) {
            return task("bukkitRunnableRunTaskTimerAsynchronously", "(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;", bukkitRunnableApi(name), RouteFamily.S_ASYNC);
        }
        return null;
    }

    private static boolean objectThenReturnsTask(String descriptor, String primitiveTail) {
        int close = descriptor.indexOf(')');
        if (close < 0 || !BUKKIT_TASK_DESCRIPTOR.equals(descriptor.substring(close + 1))) {
            return false;
        }
        String args = descriptor.substring(1, close);
        return args.startsWith("L") && args.endsWith(";" + primitiveTail);
    }

    private static Replacement task(String methodName, String descriptor, String sourceApi, RouteFamily routeFamily) {
        return new Replacement(methodName, descriptor, BUKKIT_TASK, sourceApi, routeFamily);
    }

    private static Replacement primitive(String methodName, String descriptor, String sourceApi, RouteFamily routeFamily) {
        return new Replacement(methodName, descriptor, null, sourceApi, routeFamily);
    }

    private static String schedulerApi(String name) {
        return "BukkitScheduler#" + name;
    }

    private static String bukkitRunnableApi(String name) {
        return "BukkitRunnable#" + name;
    }

    static boolean shouldIgnore(String className) {
        // Raw ASM fallback is intentionally broad for plugin jars, but it must still leave Folia/Paper
        // bootstrap and dependency libraries alone. If a live-server failure points at one of these
        // prefixes, keep the evidence and add the narrowest missing server-library prefix here.
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("net/bytebuddy/")
                || className.startsWith("org/bukkit/")
                || className.startsWith("io/papermc/")
                || className.startsWith("net/minecraft/")
                || className.startsWith("com/mojang/")
                || className.startsWith("ca/spottedleaf/")
                || className.startsWith("it/unimi/dsi/")
                || className.startsWith("joptsimple/")
                || className.startsWith("net/minecrell/")
                || className.startsWith("org/jline/")
                || className.startsWith("io/netty/")
                || className.startsWith("org/xml/")
                || className.startsWith("oshi/")
                || className.startsWith("org/joml/")
                || className.startsWith("com/lmax/")
                || className.startsWith("org/spongepowered/")
                || className.startsWith("org/yaml/")
                || className.startsWith("io/leangen/")
                || className.startsWith("com/mysql/")
                || className.startsWith("com/velocitypowered/")
                || className.startsWith("net/md_5/")
                || className.startsWith("dev/foliabytecodebridge/");
    }

    private record Replacement(String methodName, String descriptor, String castTo,
                               String sourceApi, RouteFamily routeFamily) {
    }
}
