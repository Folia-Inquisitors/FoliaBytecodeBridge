package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

final class RawMetadataTransformer implements ClassFileTransformer {

    private static final String PLUGIN_DESCRIPTION_FILE = "org/bukkit/plugin/PluginDescriptionFile";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!BridgeConfig.metadataOverlayAll() || !PLUGIN_DESCRIPTION_FILE.equals(className)) return null;
        AtomicBoolean changed = new AtomicBoolean(false);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"isFoliaSupported".equals(name) || !"()Z".equals(descriptor)) {
                        return delegate;
                    }

                    // Experimental metadata overlay: this opens the Folia load gate so the bytecode bridge
                    // can collect evidence from legacy plugins. Keep this method self-contained: Folia's
                    // early plugin metadata classloader cannot resolve bridge helper classes yet.
                    changed.set(true);
                    delegate.visitCode();
                    delegate.visitInsn(Opcodes.ICONST_1);
                    delegate.visitInsn(Opcodes.IRETURN);
                    delegate.visitMaxs(1, 1);
                    delegate.visitEnd();
                    return null;
                }
            }, 0);
            if (!changed.get()) return null;
            BridgeDiagnostics.metadataTransform(className, loader, protectionDomain, "patched-return-true");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className, throwable);
            return null;
        }
    }
}
