package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RawLegacyMainThreadTransformer implements ClassFileTransformer {

    static final String LEGACY_MAIN_THREAD_OWNER = "com/fastasyncworldedit/core/Fawe";
    static final String BRIDGE = "dev/foliabytecodebridge/LegacyMainThreadBridge";
    static final String BRIDGE_METHOD = "compatibleWhenLegacyMainThreadFalse";
    static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;)Z";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || RawSchedulerTransformer.shouldIgnore(className)) return null;

        AtomicBoolean changed = new AtomicBoolean(false);
        AtomicInteger evidence = new AtomicInteger();
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (LEGACY_MAIN_THREAD_OWNER.equals(className)
                            && "isMainThread".equals(name)
                            && "()Z".equals(descriptor)) {
                        MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                        writeFaweMainThreadFallback(method);
                        changed.set(true);
                        evidence.incrementAndGet();
                        BridgeDiagnostics.legacyMainThreadPath(className, loader, protectionDomain,
                                name, descriptor, className, name, descriptor, RouteFamily.S_GLOBAL,
                                "exact-owner-method-body",
                                "rewritten",
                                "preserve-original-thread-check-then-folia-tick-thread-fallback");
                        return null;
                    }

                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC
                                    && "isMainThread".equals(methodName)
                                    && "()Z".equals(methodDescriptor)) {
                                evidence.incrementAndGet();
                                BridgeDiagnostics.legacyMainThreadPath(className, loader, protectionDomain,
                                        name, descriptor, owner, methodName, methodDescriptor,
                                        RouteFamily.S_GLOBAL,
                                        LEGACY_MAIN_THREAD_OWNER.equals(owner) ? "exact-owner-callsite" : "generic-name-descriptor",
                                        "trace-only",
                                        LEGACY_MAIN_THREAD_OWNER.equals(owner)
                                                ? "callee-method-body-rewrite-handles-this-call"
                                                : "observed-static-isMainThread-shape-needs-owner-model-before-rewrite");
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeDiagnostics.rawLegacyMainThreadTransformed(className, loader, evidence.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }

    private static void writeFaweMainThreadFallback(MethodVisitor method) {
        Label returnTrue = new Label();
        Label fallback = new Label();

        method.visitCode();

        // Preserve the original predicate exactly: no instance means safe, and the captured
        // startup thread is still treated as main. Only false results fall through to the Folia
        // compatibility answer.
        method.visitFieldInsn(Opcodes.GETSTATIC, LEGACY_MAIN_THREAD_OWNER, "instance", "L" + LEGACY_MAIN_THREAD_OWNER + ";");
        method.visitJumpInsn(Opcodes.IFNULL, returnTrue);
        method.visitFieldInsn(Opcodes.GETSTATIC, LEGACY_MAIN_THREAD_OWNER, "instance", "L" + LEGACY_MAIN_THREAD_OWNER + ";");
        method.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MAIN_THREAD_OWNER, "thread", "Ljava/lang/Thread;");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        method.visitJumpInsn(Opcodes.IF_ACMPNE, fallback);
        method.visitLabel(returnTrue);
        // Preserve existing class frames by avoiding whole-class frame recomputation. Because
        // this replacement method has two branch targets, emit its small stack-map frames
        // directly instead of asking ASM to infer every method in this owner.
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(fallback);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitLdcInsn(LEGACY_MAIN_THREAD_OWNER.replace('/', '.'));
        method.visitLdcInsn("isMainThread");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, BRIDGE_METHOD, BRIDGE_DESCRIPTOR, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
