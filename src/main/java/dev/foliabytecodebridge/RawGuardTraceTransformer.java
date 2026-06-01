package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class RawGuardTraceTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!BridgeDiagnostics.traceGuardPaths()) return null;
        if (className == null || RawSchedulerTransformer.shouldIgnore(className)) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            GuardDecision decision = decision(owner, methodName, methodDescriptor);
                            if (decision != null) {
                                BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                        name, descriptor, owner, methodName, methodDescriptor,
                                        decision.routeFamily, decision.guard, decision.action, decision.reason);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
        }
        return null;
    }

    private static GuardDecision decision(String owner, String name, String descriptor) {
        if ("org/bukkit/World".equals(owner)) {
            if ("loadChunk".equals(name) || "unloadChunk".equals(name) || "unloadChunkRequest".equals(name)
                    || "regenerateChunk".equals(name) || "refreshChunk".equals(name)) {
                return trace(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-load-unload",
                        "paper-guard-audit: chunk state needs a region/chunk route");
            }
            if ("save".equals(name)) {
                return trace(RouteFamily.S_GLOBAL, "CraftWorld#world-save",
                        "paper-guard-audit: world save is global/server state");
            }
            if ("playSound".equals(name) || "stopSound".equals(name)) {
                return trace(RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                        "paper-guard-audit: world sound needs location or emitter context");
            }
        }

        if ("org/bukkit/Server".equals(owner) || "org/bukkit/Bukkit".equals(owner)) {
            if ("dispatchCommand".equals(name)) {
                return trace(RouteFamily.S_GLOBAL, "CraftServer#dispatchCommand",
                        "paper-guard-audit: unsupported dispatch descriptor or transformer missed raw-command-dispatch");
            }
            if ("playSound".equals(name) || "stopSound".equals(name)) {
                return trace(RouteFamily.S_GLOBAL, "CraftServer#sound",
                        "paper-guard-audit: server-wide sound lacks one obvious region owner");
            }
            if ("getScoreboardManager".equals(name)) {
                return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboardManager",
                        "paper-guard-audit: scoreboard manager is an unowned UI/model factory; bridge-owned scoreboards use the D_PLAYER_UI shim model");
            }
        }

        if ("org/bukkit/scoreboard/ScoreboardManager".equals(owner)
                && ("getNewScoreboard".equals(name) || "getMainScoreboard".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboardManager",
                    "paper-guard-audit: unowned scoreboard creation is modeled only for the exact getNewScoreboard descriptor; main scoreboard remains diagnostic");
        }

        if ("org/bukkit/entity/Player".equals(owner)) {
            if ("getScoreboard".equals(name) || "setScoreboard".equals(name)) {
                return trace(RouteFamily.D_PLAYER_UI, "CraftPlayer#scoreboard",
                        "paper-guard-audit: player scoreboard access belongs to player-owned UI context");
            }
            if ("kickPlayer".equals(name) || "kick".equals(name)) {
                return trace(RouteFamily.A_ENTITY, "CraftPlayer#kick",
                        "paper-guard-audit: player kick belongs to the player/entity route");
            }
            if ("getSentChunkKeys".equals(name) || "getSentChunks".equals(name) || "isChunkSent".equals(name)) {
                return trace(RouteFamily.A_ENTITY, "CraftPlayer#sent-chunks",
                        "paper-guard-audit: sent chunk access is player-owned");
            }
        }

        if ("org/bukkit/scoreboard/Scoreboard".equals(owner)
                && ("getTeam".equals(name) || "registerNewTeam".equals(name)
                || "registerNewObjective".equals(name) || "getObjective".equals(name)
                || "getObjectives".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "paper-guard-audit: bridge-owned scoreboard model paths are rewritten by exact descriptor; unsupported owners stay diagnostic");
        }

        if ("org/bukkit/scoreboard/Objective".equals(owner)
                && ("setDisplaySlot".equals(name) || "setDisplayName".equals(name)
                || "displayName".equals(name) || "numberFormat".equals(name)
                || "getScore".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                    "paper-guard-audit: objective mutation/read belongs to the D_PLAYER_UI scoreboard model");
        }

        if ("org/bukkit/scoreboard/Score".equals(owner)
                && ("setScore".equals(name) || "getScore".equals(name) || "resetScore".equals(name)
                || "customName".equals(name) || "numberFormat".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                    "paper-guard-audit: score mutation/read belongs to the D_PLAYER_UI scoreboard model");
        }

        if ("org/bukkit/scoreboard/Team".equals(owner)
                && ("setOption".equals(name) || "addEntry".equals(name) || "removeEntry".equals(name)
                || "setPrefix".equals(name) || "setSuffix".equals(name) || "setDisplayName".equals(name)
                || "setColor".equals(name) || "setAllowFriendlyFire".equals(name)
                || "setCanSeeFriendlyInvisibles".equals(name) || "prefix".equals(name)
                || "suffix".equals(name) || "displayName".equals(name) || "color".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "paper-guard-audit: scoreboard team mutation is player UI/model state; bridge-owned teams are modeled");
        }

        if ("org/bukkit/entity/HumanEntity".equals(owner)
                && ("openWorkbench".equals(name) || "openEnchanting".equals(name)
                || "openMerchant".equals(name) || "openAnvil".equals(name)
                || "openCartographyTable".equals(name) || "openGrindstone".equals(name)
                || "openLoom".equals(name) || "openSmithingTable".equals(name)
                || "openStonecutter".equals(name))) {
            return trace(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                    "paper-guard-audit: player UI opens belong to player/entity context");
        }

        return null;
    }

    private static GuardDecision trace(RouteFamily routeFamily, String guard, String reason) {
        return new GuardDecision(routeFamily, guard, "trace-only", reason);
    }

    private record GuardDecision(RouteFamily routeFamily, String guard, String action, String reason) {
    }
}
