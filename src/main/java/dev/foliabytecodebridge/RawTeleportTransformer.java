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

final class RawTeleportTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/foliabytecodebridge/UnsafeCallBridge";
    private static final String LOCATION = "Lorg/bukkit/Location;";
    private static final String CAUSE = "Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;";
    private static final String ENTITY = "Lorg/bukkit/entity/Entity;";
    private static final String PLAYER = "Lorg/bukkit/entity/Player;";
    private static final String FUTURE_BOOLEAN = "Ljava/util/concurrent/CompletableFuture;";
    private static final String STATIC_TELEPORT_ASYNC_DESCRIPTOR = "(" + ENTITY + LOCATION + CAUSE + ")" + FUTURE_BOOLEAN;
    private static final String STATIC_PLAYER_TELEPORT_ASYNC_DESCRIPTOR = "(" + PLAYER + LOCATION + CAUSE + ")" + FUTURE_BOOLEAN;
    private static final String HELPER_ENTITY_LOCATION_PREFIX = "(" + ENTITY + LOCATION;
    private static final String HELPER_PLAYER_LOCATION_PREFIX = "(" + PLAYER + LOCATION;
    private static final String BRIDGE_STATIC_TELEPORT_DESCRIPTOR = "(" + ENTITY + LOCATION + CAUSE
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + FUTURE_BOOLEAN;
    private static final String ENTITY_TELEPORT_ASYNC_DESCRIPTOR = "(" + LOCATION + CAUSE + ")" + FUTURE_BOOLEAN;
    private static final String TELEPORT_LOCATION_DESCRIPTOR = "(" + LOCATION + ")Z";
    private static final String TELEPORT_CAUSE_DESCRIPTOR = "(" + LOCATION + CAUSE + ")Z";
    private static final String BRIDGE_ENTITY_TELEPORT_LOCATION_DESCRIPTOR = "(" + ENTITY + LOCATION + ")Z";
    private static final String BRIDGE_PLAYER_TELEPORT_LOCATION_DESCRIPTOR = "(" + PLAYER + LOCATION + ")Z";
    private static final String BRIDGE_ENTITY_TELEPORT_CAUSE_DESCRIPTOR = "(" + ENTITY + LOCATION + CAUSE + ")Z";
    private static final String BRIDGE_PLAYER_TELEPORT_CAUSE_DESCRIPTOR = "(" + PLAYER + LOCATION + CAUSE + ")Z";

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
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            TeleportDecision decision = decision(opcode, owner, methodName, methodDescriptor);
                            if (decision == null) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            BridgeDiagnostics.teleportPath(className, loader, protectionDomain,
                                    name, descriptor, owner, methodName, methodDescriptor,
                                    RouteFamily.A_ENTITY, decision.rule, decision.action, decision.outcome,
                                    decision.bridge);

                            if (!decision.rewrite) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            changed.set(true);
                            replacements.incrementAndGet();
                            if (decision.appendBytecodeMetadata) {
                                // Generic PaperLib-style static helpers have no plugin parameter and no receiver owner
                                // we can trust. Preserve the exact bytecode owner in the runtime log, then delegate to
                                // Entity#teleportAsync so shaded helper packages are treated as one A_ENTITY family.
                                super.visitLdcInsn(owner);
                                super.visitLdcInsn(methodName);
                                super.visitLdcInsn(methodDescriptor);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    decision.bridgeMethod, decision.bridgeDescriptor, false);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeRuntimeVisibility.ensureBridgeVisible(loader, className);
            BridgeDiagnostics.rawTeleportTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }

    private static TeleportDecision decision(int opcode, String owner, String name, String descriptor) {
        if (opcode == Opcodes.INVOKESTATIC && "teleportAsync".equals(name)) {
            if (STATIC_TELEPORT_ASYNC_DESCRIPTOR.equals(descriptor)
                    || STATIC_PLAYER_TELEPORT_ASYNC_DESCRIPTOR.equals(descriptor)) {
                return new TeleportDecision(true, "generic-shape", "rewritten", "rewritten-generic-static-shape",
                        "UnsafeCallBridge#staticTeleportAsync", "staticTeleportAsync",
                        BRIDGE_STATIC_TELEPORT_DESCRIPTOR, true);
            }
            if (descriptor.contains(LOCATION) && descriptor.contains(CAUSE)
                    && descriptor.endsWith(FUTURE_BOOLEAN)) {
                return new TeleportDecision(false, "generic-shape-probe", "missed", "missed-unsupported-static-shape",
                        "none", null, null, false);
            }
        }

        if ("teleportAsync".equals(name)
                && (descriptor.startsWith(HELPER_ENTITY_LOCATION_PREFIX)
                || descriptor.startsWith(HELPER_PLAYER_LOCATION_PREFIX))) {
            return new TeleportDecision(false, "generic-helper-shape", "trace-only",
                    "observed-entity-location-helper", "none", null, null, false);
        }

        if ("org/bukkit/entity/Entity".equals(owner)
                && "teleportAsync".equals(name)
                && ENTITY_TELEPORT_ASYNC_DESCRIPTOR.equals(descriptor)) {
            return new TeleportDecision(false, "bukkit-api-owner", "trace-only", "typed-transform-expected",
                    "UnsafeCallBridge#entityTeleportAsync", null, null, false);
        }

        // Observed home/warp bytecode often builds a Location, applies block-centering math such as
        // Location#add(0.5, 0.0, 0.5), then calls Player#teleport(Location). Keep this generic to the
        // Bukkit API owner and descriptor so home-command-style evidence becomes a reusable A_ENTITY path.
        if (("org/bukkit/entity/Entity".equals(owner) || "org/bukkit/entity/Player".equals(owner))
                && "teleport".equals(name)
                && (TELEPORT_LOCATION_DESCRIPTOR.equals(descriptor) || TELEPORT_CAUSE_DESCRIPTOR.equals(descriptor))) {
            boolean player = "org/bukkit/entity/Player".equals(owner);
            boolean cause = TELEPORT_CAUSE_DESCRIPTOR.equals(descriptor);
            return new TeleportDecision(true, "bukkit-api-owner", "rewritten",
                    "rewritten-direct-teleport-async-shim",
                    "UnsafeCallBridge#" + (player ? "playerTeleport" : "entityTeleport"),
                    player ? "playerTeleport" : "entityTeleport",
                    bridgeTeleportDescriptor(player, cause),
                    false);
        }

        return null;
    }

    private static String bridgeTeleportDescriptor(boolean player, boolean cause) {
        if (player) {
            return cause ? BRIDGE_PLAYER_TELEPORT_CAUSE_DESCRIPTOR : BRIDGE_PLAYER_TELEPORT_LOCATION_DESCRIPTOR;
        }
        return cause ? BRIDGE_ENTITY_TELEPORT_CAUSE_DESCRIPTOR : BRIDGE_ENTITY_TELEPORT_LOCATION_DESCRIPTOR;
    }

    private record TeleportDecision(boolean rewrite, String rule, String action, String outcome, String bridge,
                                    String bridgeMethod, String bridgeDescriptor, boolean appendBytecodeMetadata) {
    }
}
