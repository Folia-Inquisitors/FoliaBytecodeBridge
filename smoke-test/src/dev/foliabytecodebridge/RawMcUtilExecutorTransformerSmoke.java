package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RawMcUtilExecutorTransformerSmoke {

    private RawMcUtilExecutorTransformerSmoke() {
    }

    public static String assertMcUtilMainExecutorRewrite() {
        byte[] output = new RawMcUtilExecutorTransformer().transform(null, null,
                "smoketest/McUtilExecutorShape", null, (ProtectionDomain) null, fakeCallerClass());
        if (output == null) {
            throw new IllegalStateException("Expected MCUtil executor transform output");
        }

        AtomicBoolean foundBridge = new AtomicBoolean(false);
        AtomicBoolean foundRawExecutorExecute = new AtomicBoolean(false);
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
                                && "mcUtilMainExecutorExecute".equals(methodName)) {
                            foundBridge.set(true);
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && "java/util/concurrent/Executor".equals(owner)
                                && "execute".equals(methodName)
                                && "(Ljava/lang/Runnable;)V".equals(methodDescriptor)) {
                            foundRawExecutorExecute.set(true);
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        if (!foundBridge.get()) {
            throw new IllegalStateException("MCUtil MAIN_EXECUTOR execute was not routed through ServerExecutorBridge");
        }
        if (foundRawExecutorExecute.get()) {
            throw new IllegalStateException("Raw Executor#execute call was left in MCUtil MAIN_EXECUTOR shape");
        }
        return "mcutil-main-executor-global-route";
    }

    private static byte[] fakeCallerClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "smoketest/McUtilExecutorShape",
                null, "java/lang/Object", null);

        FieldVisitor ignored = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "NOOP", "Ljava/lang/Runnable;", null, null);
        ignored.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "io/papermc/paper/util/MCUtil",
                "MAIN_EXECUTOR", "Ljava/util/concurrent/Executor;");
        method.visitFieldInsn(Opcodes.GETSTATIC, "smoketest/McUtilExecutorShape",
                "NOOP", "Ljava/lang/Runnable;");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Executor",
                "execute", "(Ljava/lang/Runnable;)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
