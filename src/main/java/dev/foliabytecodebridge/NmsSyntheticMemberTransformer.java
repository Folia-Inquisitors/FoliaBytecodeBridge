package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds tightly scoped server-internal compatibility members during first class
 * definition.
 *
 * <p>This is not a Folia ownership route. It exists for NMS_VERSION_COMPAT
 * failures where a legacy adapter expects a Paper server member that Folia's
 * runtime class no longer exposes. Keep each rule tied to a concrete member
 * shape and source reference; do not use this as a broad NMS patch bucket.</p>
 */
final class NmsSyntheticMemberTransformer implements ClassFileTransformer {

    static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
    static final String CURRENT_TICK = "currentTick";
    static final String INT_DESCRIPTOR = "I";
    static final String TICK_SERVER_DESCRIPTOR =
            "(JJJLio/papermc/paper/threadedregions/TickRegions$TickRegionData;)V";
    private static final String TICK_REGION_DATA =
            "io/papermc/paper/threadedregions/TickRegions$TickRegionData";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!MINECRAFT_SERVER.equals(className)) return null;
        if (classBeingRedefined != null) {
            BridgeDiagnostics.nmsCompatSyntheticMember(className, CURRENT_TICK, INT_DESCRIPTOR,
                    "skipped", "class-already-defined", "initial-load-only");
            return null;
        }

        AtomicBoolean hasCurrentTick = new AtomicBoolean(false);
        AtomicBoolean addedField = new AtomicBoolean(false);
        AtomicBoolean hookedTickServer = new AtomicBoolean(false);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    if (CURRENT_TICK.equals(name) && INT_DESCRIPTOR.equals(descriptor)) {
                        hasCurrentTick.set(true);
                    }
                    return super.visitField(access, name, descriptor, signature, value);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (hasCurrentTick.get()
                            || !"tickServer".equals(name)
                            || !TICK_SERVER_DESCRIPTOR.equals(descriptor)) {
                        return delegate;
                    }
                    hookedTickServer.set(true);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Folia has region ticks instead of Paper's single main tick. The closest
                            // safe model for legacy currentTick readers is the current region tick.
                            super.visitVarInsn(Opcodes.ALOAD, 7);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TICK_REGION_DATA,
                                    "getCurrentTick", "()J", false);
                            super.visitInsn(Opcodes.L2I);
                            super.visitFieldInsn(Opcodes.PUTSTATIC, MINECRAFT_SERVER,
                                    CURRENT_TICK, INT_DESCRIPTOR);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    if (!hasCurrentTick.get()) {
                        FieldVisitor field = super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                CURRENT_TICK, INT_DESCRIPTOR, null, null);
                        if (field != null) field.visitEnd();
                        addedField.set(true);
                    }
                    super.visitEnd();
                }
            }, 0);

            if (!addedField.get()) {
                BridgeDiagnostics.nmsCompatSyntheticMember(className, CURRENT_TICK, INT_DESCRIPTOR,
                        "trace-only", "field-already-present", "no-synthetic-needed");
                return null;
            }
            String hook = hookedTickServer.get() ? "tickServer-region-currentTick" : "missing-tickServer-hook";
            BridgeDiagnostics.nmsCompatSyntheticMember(className, CURRENT_TICK, INT_DESCRIPTOR,
                    "patched", "synthetic-field-added", hook);
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.nmsCompatSyntheticMember(className, CURRENT_TICK, INT_DESCRIPTOR,
                    "failed", throwable.getClass().getSimpleName(), String.valueOf(throwable.getMessage()));
            return null;
        }
    }
}
