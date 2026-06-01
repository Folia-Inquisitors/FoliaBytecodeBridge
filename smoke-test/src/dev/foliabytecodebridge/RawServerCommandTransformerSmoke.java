package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;

public final class RawServerCommandTransformerSmoke {

    private static final String SMOKE_TARGET = "smoketest/SmokeTarget";
    private static final String BRIDGE = "dev/foliabytecodebridge/UnsafeCallBridge";
    private static final String DISPATCH_DESCRIPTOR = "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z";
    private static final String SERVER_BRIDGE_DESCRIPTOR =
            "(Lorg/bukkit/Server;Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z";

    private RawServerCommandTransformerSmoke() {
    }

    public static int assertSmokeTargetCommandDispatch() {
        byte[] original = readClass(SMOKE_TARGET + ".class");
        byte[] transformed = new RawServerCommandTransformer().transform(
                null, null, SMOKE_TARGET, null, null, original);
        if (transformed == null) {
            throw new IllegalStateException("Raw command transformer missed Bukkit/Server#dispatchCommand");
        }

        MethodHits hits = scan(transformed);
        if (hits.bridgeBukkitDispatchCalls != 1 || hits.bridgeServerDispatchCalls != 1
                || hits.legacyDispatchCalls != 0) {
            throw new IllegalStateException("Unexpected command dispatch transform result: bukkitBridge="
                    + hits.bridgeBukkitDispatchCalls
                    + " serverBridge=" + hits.bridgeServerDispatchCalls
                    + " legacy=" + hits.legacyDispatchCalls);
        }
        return hits.bridgeBukkitDispatchCalls + hits.bridgeServerDispatchCalls;
    }

    private static byte[] readClass(String resourceName) {
        try (InputStream inputStream = RawServerCommandTransformerSmoke.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to find smoke class resource " + resourceName);
            }
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read smoke class resource " + resourceName, exception);
        }
    }

    private static MethodHits scan(byte[] classBytes) {
        MethodHits hits = new MethodHits();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && BRIDGE.equals(owner)
                                && "bukkitDispatchCommand".equals(methodName)
                                && DISPATCH_DESCRIPTOR.equals(methodDescriptor)) {
                            hits.bridgeBukkitDispatchCalls++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && BRIDGE.equals(owner)
                                && "serverDispatchCommand".equals(methodName)
                                && SERVER_BRIDGE_DESCRIPTOR.equals(methodDescriptor)) {
                            hits.bridgeServerDispatchCalls++;
                        }
                        if (("org/bukkit/Bukkit".equals(owner) || "org/bukkit/Server".equals(owner))
                                && "dispatchCommand".equals(methodName)
                                && DISPATCH_DESCRIPTOR.equals(methodDescriptor)) {
                            hits.legacyDispatchCalls++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hits;
    }

    private static final class MethodHits {
        private int bridgeBukkitDispatchCalls;
        private int bridgeServerDispatchCalls;
        private int legacyDispatchCalls;
    }
}
