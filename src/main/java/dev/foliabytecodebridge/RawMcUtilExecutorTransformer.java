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

final class RawMcUtilExecutorTransformer implements ClassFileTransformer {

    private static final String MCUTIL = "io/papermc/paper/util/MCUtil";
    private static final String MAIN_EXECUTOR_DESCRIPTOR = "Ljava/util/concurrent/Executor;";
    private static final String EXECUTOR = "java/util/concurrent/Executor";
    private static final String BRIDGE = "dev/foliabytecodebridge/ServerExecutorBridge";
    private static final String BRIDGE_DESCRIPTOR = "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V";

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
                        private boolean sawMcUtilMainExecutor;

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                            if (opcode == Opcodes.GETSTATIC
                                    && MCUTIL.equals(owner)
                                    && "MAIN_EXECUTOR".equals(fieldName)
                                    && MAIN_EXECUTOR_DESCRIPTOR.equals(fieldDescriptor)) {
                                sawMcUtilMainExecutor = true;
                            }
                            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            boolean replacement = sawMcUtilMainExecutor
                                    && opcode == Opcodes.INVOKEINTERFACE
                                    && EXECUTOR.equals(owner)
                                    && "execute".equals(methodName)
                                    && "(Ljava/lang/Runnable;)V".equals(methodDescriptor);
                            if (!replacement) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            BridgeDiagnostics.nmsCompatExecutorPath(className, loader, protectionDomain,
                                    name, descriptor, MCUTIL, "MAIN_EXECUTOR.execute",
                                    "(Ljava/lang/Runnable;)V", "MCUTIL_MAIN_EXECUTOR_CONTEXT",
                                    "rewritten",
                                    "server-internal-main-executor-shape-observed");
                            BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                    name, descriptor, MCUTIL, "MAIN_EXECUTOR.execute",
                                    "(Ljava/lang/Runnable;)V", RouteFamily.S_GLOBAL,
                                    "MCUtil#main-executor",
                                    "rewritten",
                                    "rewritten: Paper MCUtil main executor routed through Folia global scheduler");
                            changed.set(true);
                            replacements.incrementAndGet();
                            sawMcUtilMainExecutor = false;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    "mcUtilMainExecutorExecute", BRIDGE_DESCRIPTOR, false);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeRuntimeVisibility.ensureBridgeVisible(loader, className);
            BridgeDiagnostics.rawMcUtilExecutorTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }
}
