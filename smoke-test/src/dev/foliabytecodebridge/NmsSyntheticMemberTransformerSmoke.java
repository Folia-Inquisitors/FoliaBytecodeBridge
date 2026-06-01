package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NmsSyntheticMemberTransformerSmoke {

    private NmsSyntheticMemberTransformerSmoke() {
    }

    public static String assertMinecraftServerCurrentTickShim() {
        byte[] input = fakeMinecraftServerWithoutCurrentTick();
        byte[] output = new NmsSyntheticMemberTransformer().transform(null, null,
                NmsSyntheticMemberTransformer.MINECRAFT_SERVER, null,
                (ProtectionDomain) null, input);
        if (output == null) {
            throw new IllegalStateException("Expected MinecraftServer synthetic member transform output");
        }

        AtomicBoolean foundField = new AtomicBoolean(false);
        AtomicBoolean foundTickHook = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(output);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if ("currentTick".equals(name) && "I".equals(descriptor)
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    foundField.set(true);
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"tickServer".equals(name)
                        || !NmsSyntheticMemberTransformer.TICK_SERVER_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.PUTSTATIC
                                && NmsSyntheticMemberTransformer.MINECRAFT_SERVER.equals(owner)
                                && "currentTick".equals(fieldName)
                                && "I".equals(fieldDescriptor)) {
                            foundTickHook.set(true);
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        if (!foundField.get()) {
            throw new IllegalStateException("Synthetic currentTick:I field was not added");
        }
        if (!foundTickHook.get()) {
            throw new IllegalStateException("Synthetic currentTick update hook was not added");
        }
        return "synthetic-currentTick-field+tickServer-hook";
    }

    private static byte[] fakeMinecraftServerWithoutCurrentTick() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, NmsSyntheticMemberTransformer.MINECRAFT_SERVER,
                null, "java/lang/Object", null);

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tickServer",
                NmsSyntheticMemberTransformer.TICK_SERVER_DESCRIPTOR, null, null);
        tick.visitCode();
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 8);
        tick.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
