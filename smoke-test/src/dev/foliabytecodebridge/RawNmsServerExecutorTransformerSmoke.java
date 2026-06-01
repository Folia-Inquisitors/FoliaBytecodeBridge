package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RawNmsServerExecutorTransformerSmoke {

    private RawNmsServerExecutorTransformerSmoke() {
    }

    public static String assertMinecraftServerExecuteRewrite() {
        byte[] output = new RawNmsServerExecutorTransformer().transform(null, null,
                "smoketest/NmsServerExecutorShape", null, (ProtectionDomain) null, fakeCallerClass());
        if (output == null) {
            throw new IllegalStateException("Expected MinecraftServer#execute transform output");
        }

        AtomicBoolean foundBridge = new AtomicBoolean(false);
        AtomicBoolean foundRawExecute = new AtomicBoolean(false);
        new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "dev/foliabytecodebridge/ServerExecutorBridge".equals(owner)
                                && "minecraftServerExecute".equals(methodName)) {
                            foundBridge.set(true);
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "net/minecraft/server/MinecraftServer".equals(owner)
                                && "execute".equals(methodName)
                                && "(Ljava/lang/Runnable;)V".equals(methodDescriptor)) {
                            foundRawExecute.set(true);
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        if (!foundBridge.get()) {
            throw new IllegalStateException("MinecraftServer#execute was not routed through ServerExecutorBridge");
        }
        if (foundRawExecute.get()) {
            throw new IllegalStateException("Raw MinecraftServer#execute call was left in the class");
        }
        return "nms-minecraftserver-execute-global-route";
    }

    private static byte[] fakeCallerClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "smoketest/NmsServerExecutorShape",
                null, "java/lang/Object", null);

        FieldVisitor server = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "SERVER", "Lnet/minecraft/server/MinecraftServer;", null, null);
        server.visitEnd();
        FieldVisitor runnable = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "NOOP", "Ljava/lang/Runnable;", null, null);
        runnable.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "smoketest/NmsServerExecutorShape",
                "SERVER", "Lnet/minecraft/server/MinecraftServer;");
        method.visitFieldInsn(Opcodes.GETSTATIC, "smoketest/NmsServerExecutorShape",
                "NOOP", "Ljava/lang/Runnable;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer",
                "execute", "(Ljava/lang/Runnable;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
