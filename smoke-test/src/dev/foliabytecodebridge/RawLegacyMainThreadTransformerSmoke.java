package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RawLegacyMainThreadTransformerSmoke {

    private RawLegacyMainThreadTransformerSmoke() {
    }

    public static String assertFaweMainThreadFallback() {
        byte[] input = fakeFaweClass();
        byte[] output = new RawLegacyMainThreadTransformer().transform(null, null,
                RawLegacyMainThreadTransformer.FAWE_OWNER, null,
                (ProtectionDomain) null, input);
        if (output == null) {
            throw new IllegalStateException("Expected FAWE main-thread transform output");
        }

        AtomicBoolean preservedOriginalThreadCheck = new AtomicBoolean(false);
        AtomicBoolean foundBridgeFallback = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(output);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"isMainThread".equals(name) || !"()Z".equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETFIELD
                                && RawLegacyMainThreadTransformer.FAWE_OWNER.equals(owner)
                                && "thread".equals(fieldName)
                                && "Ljava/lang/Thread;".equals(fieldDescriptor)) {
                            preservedOriginalThreadCheck.set(true);
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && RawLegacyMainThreadTransformer.BRIDGE.equals(owner)
                                && RawLegacyMainThreadTransformer.BRIDGE_METHOD.equals(methodName)
                                && RawLegacyMainThreadTransformer.BRIDGE_DESCRIPTOR.equals(methodDescriptor)) {
                            foundBridgeFallback.set(true);
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        if (!preservedOriginalThreadCheck.get()) {
            throw new IllegalStateException("FAWE original thread comparison was not preserved");
        }
        if (!foundBridgeFallback.get()) {
            throw new IllegalStateException("LegacyMainThreadBridge fallback was not injected");
        }
        new VerifyingLoader().defineAndResolve(output);
        return "fawe-isMainThread-original-check+folia-fallback";
    }

    private static final class VerifyingLoader extends ClassLoader {
        Class<?> defineAndResolve(byte[] bytes) {
            Class<?> type = defineClass(RawLegacyMainThreadTransformer.FAWE_OWNER.replace('/', '.'),
                    bytes, 0, bytes.length);
            resolveClass(type);
            return type;
        }
    }

    private static byte[] fakeFaweClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RawLegacyMainThreadTransformer.FAWE_OWNER,
                null, "java/lang/Object", null);

        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "instance",
                "L" + RawLegacyMainThreadTransformer.FAWE_OWNER + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "thread", "Ljava/lang/Thread;", null, null).visitEnd();

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor original = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "isMainThread", "()Z", null, null);
        original.visitCode();
        original.visitFieldInsn(Opcodes.GETSTATIC, RawLegacyMainThreadTransformer.FAWE_OWNER,
                "instance", "L" + RawLegacyMainThreadTransformer.FAWE_OWNER + ";");
        net.bytebuddy.jar.asm.Label trueLabel = new net.bytebuddy.jar.asm.Label();
        original.visitJumpInsn(Opcodes.IFNULL, trueLabel);
        original.visitFieldInsn(Opcodes.GETSTATIC, RawLegacyMainThreadTransformer.FAWE_OWNER,
                "instance", "L" + RawLegacyMainThreadTransformer.FAWE_OWNER + ";");
        original.visitFieldInsn(Opcodes.GETFIELD, RawLegacyMainThreadTransformer.FAWE_OWNER,
                "thread", "Ljava/lang/Thread;");
        original.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread",
                "currentThread", "()Ljava/lang/Thread;", false);
        original.visitJumpInsn(Opcodes.IF_ACMPEQ, trueLabel);
        original.visitInsn(Opcodes.ICONST_0);
        original.visitInsn(Opcodes.IRETURN);
        original.visitLabel(trueLabel);
        original.visitInsn(Opcodes.ICONST_1);
        original.visitInsn(Opcodes.IRETURN);
        original.visitMaxs(2, 0);
        original.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
