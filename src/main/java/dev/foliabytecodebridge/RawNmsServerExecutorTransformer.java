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

/**
 * Rewrites legacy Paper/NMS "main server executor" calls into the global route.
 *
 * <p>Folia rejects direct {@code MinecraftServer#execute(Runnable)} because the
 * old single-main-thread executor no longer owns every server operation. This
 * bytecode shape is route-family evidence, not a plugin-specific patch:
 * any plugin adapter that calls the same NMS executor shape is routed through
 * {@link ServerExecutorBridge} so the failure remains grouped under
 * {@link RouteFamily#S_GLOBAL}.</p>
 */
final class RawNmsServerExecutorTransformer implements ClassFileTransformer {

    private static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
    private static final String BRIDGE = "dev/foliabytecodebridge/ServerExecutorBridge";
    private static final String EXECUTE_DESCRIPTOR = "(Ljava/lang/Runnable;)V";
    private static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Runnable;)V";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || RawSchedulerTransformer.shouldIgnore(className)) return null;

        AtomicBoolean changed = new AtomicBoolean(false);
        AtomicInteger replacements = new AtomicInteger();
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            boolean replacement = opcode == Opcodes.INVOKEVIRTUAL
                                    && MINECRAFT_SERVER.equals(owner)
                                    && "execute".equals(methodName)
                                    && EXECUTE_DESCRIPTOR.equals(methodDescriptor);
                            if (!replacement) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                    name, descriptor, MINECRAFT_SERVER, "execute",
                                    EXECUTE_DESCRIPTOR, RouteFamily.S_GLOBAL,
                                    "MinecraftServer#server-executor",
                                    "rewritten",
                                    "rewritten: NMS MinecraftServer executor routed through Folia global scheduler");
                            changed.set(true);
                            replacements.incrementAndGet();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    "minecraftServerExecute", BRIDGE_DESCRIPTOR, false);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeRuntimeVisibility.ensureBridgeVisible(loader, className);
            BridgeDiagnostics.rawNmsServerExecutorTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }
}
