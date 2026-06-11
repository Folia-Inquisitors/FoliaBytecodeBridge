package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects Bukkit's normal listener execution boundary to the synthetic event
 * model.
 *
 * <p>Plugin-dispatched custom events are already caught at
 * {@code PluginManager#callEvent(Event)}. Built-in server-fired events enter
 * through {@code RegisteredListener#callEvent(Event)}, so this exact server API
 * rewrite gives those listener callbacks the same context/lane diagnostics
 * without rewriting every plugin listener class.</p>
 */
final class RawRegisteredListenerTransformer implements ClassFileTransformer {

    private static final String REGISTERED_LISTENER = "org/bukkit/plugin/RegisteredListener";
    private static final String EVENT = "Lorg/bukkit/event/Event;";
    private static final String CALL_EVENT_DESCRIPTOR = "(" + EVENT + ")V";
    private static final String BRIDGE = "dev/foliabytecodebridge/SyntheticEventDispatchBridge";
    private static final String BRIDGE_DESCRIPTOR = "(Lorg/bukkit/plugin/RegisteredListener;" + EVENT + ")V";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!BridgeConfig.syntheticListenerBoundary() || !REGISTERED_LISTENER.equals(className)) return null;

        AtomicBoolean changed = new AtomicBoolean(false);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"callEvent".equals(name) || !CALL_EVENT_DESCRIPTOR.equals(descriptor)) {
                        return delegate;
                    }

                    changed.set(true);
                    delegate.visitCode();
                    delegate.visitVarInsn(Opcodes.ALOAD, 0);
                    delegate.visitVarInsn(Opcodes.ALOAD, 1);
                    delegate.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                            "callRegisteredListener", BRIDGE_DESCRIPTOR, false);
                    delegate.visitInsn(Opcodes.RETURN);
                    delegate.visitMaxs(2, 2);
                    delegate.visitEnd();
                    return null;
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeRuntimeVisibility.ensureBridgeVisible(loader, className,
                    "dev.foliabytecodebridge.SyntheticEventDispatchBridge");
            BridgeDiagnostics.rawRegisteredListenerTransformed(className, loader);
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }
}
