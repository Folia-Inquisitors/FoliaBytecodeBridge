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

final class RawServerCommandTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/foliabytecodebridge/UnsafeCallBridge";
    private static final String COMMAND_SENDER = "Lorg/bukkit/command/CommandSender;";
    private static final String SERVER = "Lorg/bukkit/Server;";
    private static final String DISPATCH_DESCRIPTOR = "(" + COMMAND_SENDER + "Ljava/lang/String;)Z";
    private static final String SERVER_BRIDGE_DESCRIPTOR = "(" + SERVER + COMMAND_SENDER + "Ljava/lang/String;)Z";

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
                            Replacement replacement = replacement(opcode, owner, methodName, methodDescriptor);
                            if (replacement == null) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                    name, descriptor, owner, methodName, methodDescriptor,
                                    RouteFamily.S_GLOBAL, "CraftServer#dispatchCommand",
                                    "rewritten", replacement.reason);

                            changed.set(true);
                            replacements.incrementAndGet();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    replacement.bridgeMethod, replacement.bridgeDescriptor, false);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeRuntimeVisibility.ensureBridgeVisible(loader, className);
            BridgeDiagnostics.rawCommandTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }

    private static Replacement replacement(int opcode, String owner, String name, String descriptor) {
        if (!"dispatchCommand".equals(name) || !DISPATCH_DESCRIPTOR.equals(descriptor)) {
            return null;
        }
        if (opcode == Opcodes.INVOKESTATIC && "org/bukkit/Bukkit".equals(owner)) {
            return new Replacement("bukkitDispatchCommand", DISPATCH_DESCRIPTOR,
                    "rewritten: command dispatch scheduled through Folia global/entity route; return=scheduled-true");
        }
        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                && "org/bukkit/Server".equals(owner)) {
            return new Replacement("serverDispatchCommand", SERVER_BRIDGE_DESCRIPTOR,
                    "rewritten: server command dispatch scheduled through Folia global/entity route; return=scheduled-true");
        }
        return null;
    }

    private record Replacement(String bridgeMethod, String bridgeDescriptor, String reason) {
    }
}
