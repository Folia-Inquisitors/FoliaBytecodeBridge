package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RawDirectUnsafeTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/foliabytecodebridge/UnsafeCallBridge";
    private static final String ENTITY = "Lorg/bukkit/entity/Entity;";
    private static final String HUMAN_ENTITY = "Lorg/bukkit/entity/HumanEntity;";
    private static final String LIVING_ENTITY = "Lorg/bukkit/entity/LivingEntity;";
    private static final String WORLD = "Lorg/bukkit/World;";
    private static final String LOCATION = "Lorg/bukkit/Location;";
    private static final String BLOCK = "Lorg/bukkit/block/Block;";
    private static final String CHUNK = "Lorg/bukkit/Chunk;";
    private static final String CHUNK_ARRAY = "[Lorg/bukkit/Chunk;";
    private static final String ENTITY_LIST = "Ljava/util/List;";
    private static final String COLLECTION = "Ljava/util/Collection;";
    private static final String ENTITY_ARRAY = "[Lorg/bukkit/entity/Entity;";
    private static final String ENTITY_TYPE = "Lorg/bukkit/entity/EntityType;";
    private static final String ITEM_STACK = "Lorg/bukkit/inventory/ItemStack;";
    private static final String INVENTORY = "Lorg/bukkit/inventory/Inventory;";
    private static final String INVENTORY_VIEW = "Lorg/bukkit/inventory/InventoryView;";
    private static final String ITEM = "Lorg/bukkit/entity/Item;";
    private static final String LIGHTNING = "Lorg/bukkit/entity/LightningStrike;";
    private static final String SOUND = "Lorg/bukkit/Sound;";
    private static final String SOUND_CATEGORY = "Lorg/bukkit/SoundCategory;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String CLASS = "Ljava/lang/Class;";
    private static final String CLASS_ARRAY = "[Ljava/lang/Class;";
    private static final String TREE_TYPE = "Lorg/bukkit/TreeType;";
    private static final String MATERIAL = "Lorg/bukkit/Material;";
    private static final String BLOCK_DATA = "Lorg/bukkit/block/data/BlockData;";
    private static final String POTION_EFFECT = "Lorg/bukkit/potion/PotionEffect;";
    private static final String POTION_EFFECT_TYPE = "Lorg/bukkit/potion/PotionEffectType;";
    private static final String PLAYER = "Lorg/bukkit/entity/Player;";
    private static final String SCOREBOARD_MANAGER = "Lorg/bukkit/scoreboard/ScoreboardManager;";
    private static final String SCOREBOARD = "Lorg/bukkit/scoreboard/Scoreboard;";
    private static final String OBJECTIVE = "Lorg/bukkit/scoreboard/Objective;";
    private static final String SCORE = "Lorg/bukkit/scoreboard/Score;";
    private static final String DISPLAY_SLOT = "Lorg/bukkit/scoreboard/DisplaySlot;";
    private static final String CRITERIA = "Lorg/bukkit/scoreboard/Criteria;";
    private static final String RENDER_TYPE = "Lorg/bukkit/scoreboard/RenderType;";
    private static final String TEAM = "Lorg/bukkit/scoreboard/Team;";
    private static final String TEAM_OPTION = "Lorg/bukkit/scoreboard/Team$Option;";
    private static final String TEAM_OPTION_STATUS = "Lorg/bukkit/scoreboard/Team$OptionStatus;";
    private static final String COMPONENT = "Lnet/kyori/adventure/text/Component;";
    private static final String OFFLINE_PLAYER = "Lorg/bukkit/OfflinePlayer;";
    private static final String NUMBER_FORMAT = "Lio/papermc/paper/scoreboard/numbers/NumberFormat;";
    private static final String CHAT_COLOR = "Lorg/bukkit/ChatColor;";
    private static final String NAMED_TEXT_COLOR = "Lnet/kyori/adventure/text/format/NamedTextColor;";

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
                            Replacement replacement = replacement(opcode, owner, methodName, methodDescriptor);
                            if (replacement == null) {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                return;
                            }

                            BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                    name, descriptor, owner, methodName, methodDescriptor,
                                    replacement.routeFamily, replacement.guard,
                                    "rewritten", replacement.reason);

                            changed.set(true);
                            replacements.incrementAndGet();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                    replacement.bridgeMethod, replacement.bridgeDescriptor, false);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String indyName, String indyDescriptor,
                                                           Handle bootstrapMethodHandle,
                                                           Object... bootstrapMethodArguments) {
                            Object[] rewrittenArguments = bootstrapMethodArguments;
                            for (Object argument : bootstrapMethodArguments) {
                                if (!(argument instanceof Handle handle)) continue;
                                Replacement replacement = replacement(handleOpcode(handle.getTag()),
                                        handle.getOwner(), handle.getName(), handle.getDesc());
                                if (replacement == null) continue;
                                if (!canRewriteInvokedynamic(indyDescriptor, replacement.bridgeDescriptor)) {
                                    // Captured method references are stricter than normal invoke rewrites:
                                    // LambdaMetafactory validates the captured receiver type exactly. If a plugin
                                    // captures a Player while the declared bytecode owner is HumanEntity, replacing
                                    // the handle with a HumanEntity bridge method can raise BootstrapMethodError
                                    // before the probe/plugin body runs. Keep this visible as trace-only evidence
                                    // and let ordinary invoke instructions cover the same owner shape where present.
                                    BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                            name, descriptor, handle.getOwner(), handle.getName(), handle.getDesc(),
                                            replacement.routeFamily, replacement.guard, "trace-only",
                                            "trace-only: invokedynamic receiver mismatch; ordinary invoke rewrite required");
                                    continue;
                                }

                                if (rewrittenArguments == bootstrapMethodArguments) {
                                    rewrittenArguments = bootstrapMethodArguments.clone();
                                }
                                for (int i = 0; i < rewrittenArguments.length; i++) {
                                    if (rewrittenArguments[i] == argument) {
                                        rewrittenArguments[i] = new Handle(Opcodes.H_INVOKESTATIC, BRIDGE,
                                                replacement.bridgeMethod, replacement.bridgeDescriptor, false);
                                    }
                                }

                                // Java method references hide the call in the invokedynamic bootstrap
                                // instead of an ordinary invoke instruction. Rewriting only known
                                // owner/name/descriptor pairs keeps this generic and evidence-driven.
                                BridgeDiagnostics.guardPath(className, loader, protectionDomain,
                                        name, descriptor, handle.getOwner(), handle.getName(), handle.getDesc(),
                                        replacement.routeFamily, replacement.guard, "rewritten",
                                        "rewritten: invokedynamic method reference handle routed through bridge");
                                changed.set(true);
                                replacements.incrementAndGet();
                            }
                            super.visitInvokeDynamicInsn(indyName, indyDescriptor,
                                    bootstrapMethodHandle, rewrittenArguments);
                        }
                    };
                }
            }, 0);

            if (!changed.get()) return null;
            BridgeDiagnostics.rawDirectUnsafeTransformed(className, loader, replacements.get());
            return writer.toByteArray();
        } catch (Throwable throwable) {
            BridgeDiagnostics.transformError(className.replace('/', '.'), throwable);
            return null;
        }
    }

    private static Replacement replacement(int opcode, String owner, String name, String descriptor) {
        if (opcode != Opcodes.INVOKEINTERFACE && opcode != Opcodes.INVOKEVIRTUAL) return null;

        OptionalReplacement central = centralReplacement(owner, name, descriptor);
        if (central.replacement != null) {
            return central.replacement;
        }

        if (("org/bukkit/entity/Entity".equals(owner) || "org/bukkit/entity/Player".equals(owner))
                && "getNearbyEntities".equals(name)
                && "(DDD)Ljava/util/List;".equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "Entity#getNearbyEntities",
                    "entityGetNearbyEntities", "(" + ENTITY + "DDD)" + ENTITY_LIST,
                    "rewritten: entity scan can retry through the entity scheduler when Folia rejects the current thread");
        }

        if ("org/bukkit/entity/Player".equals(owner)) {
            if ("addPotionEffect".equals(name) && ("(" + POTION_EFFECT + ")Z").equals(descriptor)) {
                return new Replacement(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "playerAddPotionEffect", "(" + PLAYER + POTION_EFFECT + ")Z",
                        "rewritten: player potion effect mutation routes through the owning entity scheduler");
            }
            if ("removePotionEffect".equals(name) && ("(" + POTION_EFFECT_TYPE + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "playerRemovePotionEffect", "(" + PLAYER + POTION_EFFECT_TYPE + ")V",
                        "rewritten: player potion effect removal routes through the owning entity scheduler");
            }
            if ("getScoreboard".equals(name) && ("()" + SCOREBOARD).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftPlayer#scoreboard",
                        "playerGetScoreboard", "(" + PLAYER + ")" + SCOREBOARD,
                        "diagnostic: player-owned scoreboard read belongs to D_PLAYER_UI; no scheduler rewrite proven yet");
            }
            if ("setScoreboard".equals(name) && ("(" + SCOREBOARD + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftPlayer#scoreboard",
                        "playerSetScoreboard", "(" + PLAYER + SCOREBOARD + ")V",
                        "diagnostic: player-owned scoreboard assignment belongs to D_PLAYER_UI; no scheduler rewrite proven yet");
            }
            if ("openInventory".equals(name) && ("(" + INVENTORY + ")" + INVENTORY_VIEW).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                        "playerOpenInventory", "(" + PLAYER + INVENTORY + ")" + INVENTORY_VIEW,
                        "rewritten: player inventory open routes through the owning entity scheduler");
            }
            if ("closeInventory".equals(name) && "()V".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                        "playerCloseInventory", "(" + PLAYER + ")V",
                        "rewritten: player inventory close routes through the owning entity scheduler");
            }
        }

        if ("org/bukkit/entity/HumanEntity".equals(owner)) {
            if ("openInventory".equals(name) && ("(" + INVENTORY + ")" + INVENTORY_VIEW).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                        "humanOpenInventory", "(" + HUMAN_ENTITY + INVENTORY + ")" + INVENTORY_VIEW,
                        "rewritten: human inventory open routes through the owning entity scheduler");
            }
            if ("closeInventory".equals(name) && "()V".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                        "humanCloseInventory", "(" + HUMAN_ENTITY + ")V",
                        "rewritten: human inventory close routes through the owning entity scheduler");
            }
        }

        if ("org/bukkit/entity/LivingEntity".equals(owner)) {
            if ("addPotionEffect".equals(name) && ("(" + POTION_EFFECT + ")Z").equals(descriptor)) {
                return new Replacement(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "livingAddPotionEffect", "(" + LIVING_ENTITY + POTION_EFFECT + ")Z",
                        "rewritten: living-entity potion effect mutation routes through the owning entity scheduler");
            }
            if ("removePotionEffect".equals(name) && ("(" + POTION_EFFECT_TYPE + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "livingRemovePotionEffect", "(" + LIVING_ENTITY + POTION_EFFECT_TYPE + ")V",
                        "rewritten: living-entity potion effect removal routes through the owning entity scheduler");
            }
        }

        if ("org/bukkit/scoreboard/ScoreboardManager".equals(owner)
                && "getNewScoreboard".equals(name)
                && ("()" + SCOREBOARD).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardManager#unowned",
                    "scoreboardManagerGetNewScoreboard", "(" + SCOREBOARD_MANAGER + ")" + SCOREBOARD,
                    "rewritten: unowned scoreboard creation routes to the detached D_PLAYER_UI model on Folia");
        }

        if ("org/bukkit/scoreboard/Scoreboard".equals(owner)) {
            if ("getTeam".equals(name) && ("(" + STRING + ")" + TEAM).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboardGetTeam", "(" + SCOREBOARD + STRING + ")" + TEAM,
                        "diagnostic: scoreboard team lookup needs owner evidence before a packet/model shim");
            }
            if ("registerNewTeam".equals(name) && ("(" + STRING + ")" + TEAM).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboardRegisterNewTeam", "(" + SCOREBOARD + STRING + ")" + TEAM,
                        "diagnostic: scoreboard team creation needs owner evidence before a packet/model shim");
            }
            if ("getObjective".equals(name) && ("(" + STRING + ")" + OBJECTIVE).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboardGetObjective", "(" + SCOREBOARD + STRING + ")" + OBJECTIVE,
                        "rewritten: scoreboard objective lookup routes to the D_PLAYER_UI shim model");
            }
            if ("getObjective".equals(name) && ("(" + DISPLAY_SLOT + ")" + OBJECTIVE).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboardGetObjectiveForDisplaySlot", "(" + SCOREBOARD + DISPLAY_SLOT + ")" + OBJECTIVE,
                        "rewritten: scoreboard display-slot objective lookup routes to the D_PLAYER_UI shim model");
            }
            if ("getObjectives".equals(name) && "()Ljava/util/Set;".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboardGetObjectives", "(" + SCOREBOARD + ")Ljava/util/Set;",
                        "rewritten: scoreboard objective set read routes to the D_PLAYER_UI shim model");
            }
            if ("registerNewObjective".equals(name)) {
                Replacement objective = objectiveReplacement(descriptor);
                if (objective != null) return objective;
            }
        }

        if ("org/bukkit/scoreboard/Objective".equals(owner)) {
            if ("setDisplaySlot".equals(name) && ("(" + DISPLAY_SLOT + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveSetDisplaySlot", "(" + OBJECTIVE + DISPLAY_SLOT + ")V",
                        "rewritten: objective display-slot mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("setDisplayName".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveSetDisplayName", "(" + OBJECTIVE + STRING + ")V",
                        "rewritten: objective display-name mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("displayName".equals(name) && ("(" + COMPONENT + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveDisplayName", "(" + OBJECTIVE + COMPONENT + ")V",
                        "rewritten: objective component display-name mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("numberFormat".equals(name) && ("(" + NUMBER_FORMAT + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveNumberFormat", "(" + OBJECTIVE + NUMBER_FORMAT + ")V",
                        "rewritten: objective number-format mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("getScore".equals(name) && ("(" + STRING + ")" + SCORE).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveGetScore", "(" + OBJECTIVE + STRING + ")" + SCORE,
                        "rewritten: objective score lookup routes to the D_PLAYER_UI shim model");
            }
            if ("getScore".equals(name) && ("(" + OFFLINE_PLAYER + ")" + SCORE).equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objectiveGetScoreOfflinePlayer", "(" + OBJECTIVE + OFFLINE_PLAYER + ")" + SCORE,
                        "rewritten: objective offline-player score lookup routes to the D_PLAYER_UI shim model");
            }
        }

        if ("org/bukkit/scoreboard/Score".equals(owner)) {
            if ("setScore".equals(name) && "(I)V".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                        "scoreSetScore", "(" + SCORE + "I)V",
                        "rewritten: score value mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("getScore".equals(name) && "()I".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                        "scoreGetScore", "(" + SCORE + ")I",
                        "rewritten: score value read routes to the D_PLAYER_UI shim model");
            }
            if ("resetScore".equals(name) && "()V".equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                        "scoreResetScore", "(" + SCORE + ")V",
                        "rewritten: score reset stays inside the D_PLAYER_UI shim model");
            }
            if ("customName".equals(name) && ("(" + COMPONENT + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                        "scoreCustomName", "(" + SCORE + COMPONENT + ")V",
                        "rewritten: score custom-name mutation stays inside the D_PLAYER_UI shim model");
            }
            if ("numberFormat".equals(name) && ("(" + NUMBER_FORMAT + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                        "scoreNumberFormat", "(" + SCORE + NUMBER_FORMAT + ")V",
                        "rewritten: score number-format mutation stays inside the D_PLAYER_UI shim model");
            }
        }

        if ("org/bukkit/scoreboard/Team".equals(owner)) {
            Replacement teamDisplay = teamDisplayReplacement(name, descriptor);
            if (teamDisplay != null) return teamDisplay;
            if ("setOption".equals(name) && ("(" + TEAM_OPTION + TEAM_OPTION_STATUS + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                        "teamSetOption", "(" + TEAM + TEAM_OPTION + TEAM_OPTION_STATUS + ")V",
                        "diagnostic: scoreboard team option mutation is player UI/model state");
            }
            if ("addEntry".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                        "teamAddEntry", "(" + TEAM + STRING + ")V",
                        "diagnostic: scoreboard team entry mutation is player UI/model state");
            }
            if ("removeEntry".equals(name) && ("(" + STRING + ")Z").equals(descriptor)) {
                return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                        "teamRemoveEntry", "(" + TEAM + STRING + ")Z",
                        "diagnostic: scoreboard team entry mutation is player UI/model state");
            }
        }

        if ("org/bukkit/World".equals(owner) && "getChunkAt".equals(name)) {
            if (("(II)" + CHUNK).equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-read",
                        "worldGetChunkAt", "(" + WORLD + "II)" + CHUNK,
                        "rewritten: chunk read can retry on the owning region scheduler");
            }
            if (("(" + LOCATION + ")" + CHUNK).equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-read",
                        "worldGetChunkAt", "(" + WORLD + LOCATION + ")" + CHUNK,
                        "rewritten: location chunk read can retry on the owning region scheduler");
            }
            if (("(" + BLOCK + ")" + CHUNK).equals(descriptor)) {
                return new Replacement(RouteFamily.C_REGION_BLOCK, "CraftWorld#chunk-read",
                        "worldGetChunkAt", "(" + WORLD + BLOCK + ")" + CHUNK,
                        "rewritten: block-owned chunk read can retry on the owning region scheduler");
            }
        }

        if ("org/bukkit/World".equals(owner) && "getEntities".equals(name)
                && ("()" + ENTITY_LIST).equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                    "worldGetEntities", "(" + WORLD + ")" + ENTITY_LIST,
                    "rewritten: whole-world entity scan retries through experimental loaded-chunk split");
        }

        if ("org/bukkit/World".equals(owner) && "getNearbyEntities".equals(name)
                && ("(" + LOCATION + "DDD)" + COLLECTION).equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                    "worldGetNearbyEntities", "(" + WORLD + LOCATION + "DDD)" + COLLECTION,
                    "rewritten: bounded location scan can retry on the candidate owning region and keep failures loud");
        }

        if ("org/bukkit/World".equals(owner) && "getEntitiesByClass".equals(name)) {
            if (("(" + CLASS + ")" + COLLECTION).equals(descriptor)) {
                return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                        "worldGetEntitiesByClass", "(" + WORLD + CLASS + ")" + COLLECTION,
                        "rewritten: typed world entity scan retries through experimental loaded-chunk split");
            }
            if (("(" + CLASS_ARRAY + ")" + COLLECTION).equals(descriptor)) {
                return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                        "worldGetEntitiesByClass", "(" + WORLD + CLASS_ARRAY + ")" + COLLECTION,
                        "rewritten: typed world entity scan retries through experimental loaded-chunk split");
            }
        }

        if ("org/bukkit/World".equals(owner) && "getEntitiesByClasses".equals(name)
                && ("(" + CLASS_ARRAY + ")" + COLLECTION).equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                    "worldGetEntitiesByClasses", "(" + WORLD + CLASS_ARRAY + ")" + COLLECTION,
                    "rewritten: typed world entity scan retries through experimental loaded-chunk split");
        }

        if ("org/bukkit/World".equals(owner)
                && "getLoadedChunks".equals(name)
                && ("()" + CHUNK_ARRAY).equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#loaded-chunks",
                    "worldGetLoadedChunks", "(" + WORLD + ")" + CHUNK_ARRAY,
                    "rewritten: loaded chunk index can retry on the global scheduler, then feed split scans");
        }

        if ("org/bukkit/Chunk".equals(owner)
                && "getEntities".equals(name)
                && ("()" + ENTITY_ARRAY).equals(descriptor)) {
            return new Replacement(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftChunk#entity-scan",
                    "chunkGetEntities", "(" + CHUNK + ")" + ENTITY_ARRAY,
                    "rewritten: chunk-owned entity scan can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner)
                && "loadChunk".equals(name)
                && "(II)V".equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-load-unload",
                    "worldLoadChunk", "(" + WORLD + "II)V",
                    "rewritten: chunk load can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner)
                && "refreshChunk".equals(name)
                && "(II)Z".equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-load-unload",
                    "worldRefreshChunk", "(" + WORLD + "II)Z",
                    "rewritten: chunk refresh can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "dropItem".equals(name)
                && ("(" + LOCATION + ITEM_STACK + ")" + ITEM).equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#item-spawn",
                    "worldDropItem", "(" + WORLD + LOCATION + ITEM_STACK + ")" + ITEM,
                    "rewritten: location item spawn can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "dropItemNaturally".equals(name)
                && ("(" + LOCATION + ITEM_STACK + ")" + ITEM).equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#item-spawn",
                    "worldDropItemNaturally", "(" + WORLD + LOCATION + ITEM_STACK + ")" + ITEM,
                    "rewritten: location item spawn can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "spawnEntity".equals(name)
                && ("(" + LOCATION + ENTITY_TYPE + ")" + ENTITY).equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#entity-spawn",
                    "worldSpawnEntity", "(" + WORLD + LOCATION + ENTITY_TYPE + ")" + ENTITY,
                    "rewritten: location entity spawn can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "generateTree".equals(name)
                && ("(" + LOCATION + TREE_TYPE + ")Z").equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#world-mutation",
                    "worldGenerateTree", "(" + WORLD + LOCATION + TREE_TYPE + ")Z",
                    "rewritten: location tree generation can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "strikeLightning".equals(name)
                && ("(" + LOCATION + ")" + LIGHTNING).equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                    "worldStrikeLightning", "(" + WORLD + LOCATION + ")" + LIGHTNING,
                    "rewritten: location lightning effect can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "strikeLightningEffect".equals(name)
                && ("(" + LOCATION + ")" + LIGHTNING).equals(descriptor)) {
            return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                    "worldStrikeLightningEffect", "(" + WORLD + LOCATION + ")" + LIGHTNING,
                    "rewritten: location lightning effect can retry on the owning region scheduler");
        }

        if ("org/bukkit/World".equals(owner) && "createExplosion".equals(name)) {
            if (("(" + LOCATION + "F)Z").equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                        "worldCreateExplosion", "(" + WORLD + LOCATION + "F)Z",
                        "rewritten: location explosion can retry on the owning region scheduler");
            }
            if ("(DDDF)Z".equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                        "worldCreateExplosion", "(" + WORLD + "DDDF)Z",
                        "rewritten: coordinate explosion can retry on the owning region scheduler");
            }
        }

        if ("org/bukkit/World".equals(owner) && "playSound".equals(name)) {
            if (("(" + LOCATION + SOUND + "FF)V").equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                        "worldPlaySound", "(" + WORLD + LOCATION + SOUND + "FF)V",
                        "rewritten: location sound can retry on the owning region scheduler");
            }
            if (("(" + LOCATION + STRING + "FF)V").equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                        "worldPlaySound", "(" + WORLD + LOCATION + STRING + "FF)V",
                        "rewritten: location sound can retry on the owning region scheduler");
            }
            if (("(" + LOCATION + SOUND + SOUND_CATEGORY + "FF)V").equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                        "worldPlaySound", "(" + WORLD + LOCATION + SOUND + SOUND_CATEGORY + "FF)V",
                        "rewritten: location sound can retry on the owning region scheduler");
            }
            if (("(" + LOCATION + STRING + SOUND_CATEGORY + "FF)V").equals(descriptor)) {
                return new Replacement(RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                        "worldPlaySound", "(" + WORLD + LOCATION + STRING + SOUND_CATEGORY + "FF)V",
                        "rewritten: location sound can retry on the owning region scheduler");
            }
        }

        if ("org/bukkit/block/Block".equals(owner)) {
            if ("getType".equals(name) && ("()" + MATERIAL).equals(descriptor)) {
                return new Replacement(RouteFamily.C_REGION_BLOCK, "CraftBlock#block-read",
                        "blockGetType", "(" + BLOCK + ")" + MATERIAL,
                        "rewritten: block type read can route through the owning region scheduler");
            }
            if ("getBlockData".equals(name) && ("()" + BLOCK_DATA).equals(descriptor)) {
                return new Replacement(RouteFamily.C_REGION_BLOCK, "CraftBlock#block-read",
                        "blockGetBlockData", "(" + BLOCK + ")" + BLOCK_DATA,
                        "rewritten: block data read can route through the owning region scheduler");
            }
            if ("setType".equals(name) && ("(" + MATERIAL + ")V").equals(descriptor)) {
                return new Replacement(RouteFamily.C_REGION_BLOCK, "CraftBlock#block-mutation",
                        "blockSetType", "(" + BLOCK + MATERIAL + ")V",
                        "rewritten: block type mutation can route through the owning region scheduler");
            }
        }

        return null;
    }

    private static OptionalReplacement centralReplacement(String owner, String name, String descriptor) {
        return RouteRuleRegistry.matchExact(owner, name, descriptor)
                .filter(RouteRuleRegistry.RouteRule::rewrites)
                .map(rule -> new OptionalReplacement(new Replacement(rule.routeFamily(), rule.guard(),
                        rule.bridgeMethod(), rule.bridgeDescriptor(),
                        "rewritten: " + rule.note()
                                + "; policy=" + rule.returnPolicy()
                                + "; status=" + rule.status())))
                .orElseGet(() -> new OptionalReplacement(null));
    }

    private static Replacement teamDisplayReplacement(String name, String descriptor) {
        if ("setDisplayName".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetDisplayName", "(" + TEAM + STRING + ")V",
                    "rewritten: team display-name mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("displayName".equals(name) && ("(" + COMPONENT + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamDisplayName", "(" + TEAM + COMPONENT + ")V",
                    "rewritten: team component display-name mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("setPrefix".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetPrefix", "(" + TEAM + STRING + ")V",
                    "rewritten: team prefix mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("prefix".equals(name) && ("(" + COMPONENT + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamPrefix", "(" + TEAM + COMPONENT + ")V",
                    "rewritten: team component prefix mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("setSuffix".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetSuffix", "(" + TEAM + STRING + ")V",
                    "rewritten: team suffix mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("suffix".equals(name) && ("(" + COMPONENT + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSuffix", "(" + TEAM + COMPONENT + ")V",
                    "rewritten: team component suffix mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("setColor".equals(name) && ("(" + CHAT_COLOR + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetColor", "(" + TEAM + CHAT_COLOR + ")V",
                    "rewritten: team ChatColor mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("color".equals(name) && ("(" + NAMED_TEXT_COLOR + ")V").equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamColor", "(" + TEAM + NAMED_TEXT_COLOR + ")V",
                    "rewritten: team NamedTextColor mutation stays inside the D_PLAYER_UI shim model");
        }
        if ("setAllowFriendlyFire".equals(name) && "(Z)V".equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetAllowFriendlyFire", "(" + TEAM + "Z)V",
                    "rewritten: team friendly-fire flag stays inside the D_PLAYER_UI shim model");
        }
        if ("setCanSeeFriendlyInvisibles".equals(name) && "(Z)V".equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                    "teamSetCanSeeFriendlyInvisibles", "(" + TEAM + "Z)V",
                    "rewritten: team friendly-invisibles flag stays inside the D_PLAYER_UI shim model");
        }
        return null;
    }

    private static Replacement objectiveReplacement(String descriptor) {
        if (("(" + STRING + STRING + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveLegacy", "(" + SCOREBOARD + STRING + STRING + ")" + OBJECTIVE,
                    "rewritten: objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + STRING + STRING + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveLegacyDisplayName",
                    "(" + SCOREBOARD + STRING + STRING + STRING + ")" + OBJECTIVE,
                    "rewritten: objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + STRING + COMPONENT + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveComponent",
                    "(" + SCOREBOARD + STRING + STRING + COMPONENT + ")" + OBJECTIVE,
                    "rewritten: component objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + STRING + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveComponentRender",
                    "(" + SCOREBOARD + STRING + STRING + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                    "rewritten: component/render objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + CRITERIA + COMPONENT + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveCriteriaComponent",
                    "(" + SCOREBOARD + STRING + CRITERIA + COMPONENT + ")" + OBJECTIVE,
                    "rewritten: Criteria objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + CRITERIA + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveCriteriaComponentRender",
                    "(" + SCOREBOARD + STRING + CRITERIA + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                    "rewritten: Criteria/render objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + CRITERIA + STRING + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveCriteriaDisplayName",
                    "(" + SCOREBOARD + STRING + CRITERIA + STRING + ")" + OBJECTIVE,
                    "rewritten: Criteria/string objective creation routes to the D_PLAYER_UI shim model");
        }
        if (("(" + STRING + CRITERIA + STRING + RENDER_TYPE + ")" + OBJECTIVE).equals(descriptor)) {
            return new Replacement(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                    "scoreboardRegisterNewObjectiveCriteriaDisplayNameRender",
                    "(" + SCOREBOARD + STRING + CRITERIA + STRING + RENDER_TYPE + ")" + OBJECTIVE,
                    "rewritten: Criteria/string/render objective creation routes to the D_PLAYER_UI shim model");
        }
        return null;
    }

    private static boolean canRewriteInvokedynamic(String indyDescriptor, String bridgeDescriptor) {
        String capturedReceiver = firstParameterDescriptor(indyDescriptor);
        if (capturedReceiver == null) return true;
        String bridgeReceiver = firstParameterDescriptor(bridgeDescriptor);
        return capturedReceiver.equals(bridgeReceiver);
    }

    private static String firstParameterDescriptor(String methodDescriptor) {
        if (methodDescriptor == null || !methodDescriptor.startsWith("(")) return null;
        int index = 1;
        if (index >= methodDescriptor.length() || methodDescriptor.charAt(index) == ')') return null;
        int start = index;
        while (index < methodDescriptor.length() && methodDescriptor.charAt(index) == '[') {
            index++;
        }
        if (index >= methodDescriptor.length()) return null;
        char type = methodDescriptor.charAt(index);
        if (type == 'L') {
            int end = methodDescriptor.indexOf(';', index);
            return end < 0 ? null : methodDescriptor.substring(start, end + 1);
        }
        return methodDescriptor.substring(start, index + 1);
    }

    private static int handleOpcode(int tag) {
        return switch (tag) {
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            default -> -1;
        };
    }

    private record Replacement(RouteFamily routeFamily, String guard, String bridgeMethod,
                               String bridgeDescriptor, String reason) {
    }

    private record OptionalReplacement(Replacement replacement) {
    }
}
