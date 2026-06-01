package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.Opcodes;

import java.util.Optional;

/**
 * Exact bytecode owner/name/descriptor map used by read-only ASM inventory
 * tooling. Keep this separate from runtime rewrites until a route has enough
 * smoke/live evidence to become a transformer rule.
 */
public final class InstructionRouteRegistry {

    private static final String BLOCK = "Lorg/bukkit/block/Block;";
    private static final String COMMAND_SENDER = "Lorg/bukkit/command/CommandSender;";
    private static final String COLLECTION = "Ljava/util/Collection;";
    private static final String DISPLAY_SLOT = "Lorg/bukkit/scoreboard/DisplaySlot;";
    private static final String ENTITY = "Lorg/bukkit/entity/Entity;";
    private static final String ENTITY_ARRAY = "[Lorg/bukkit/entity/Entity;";
    private static final String ENTITY_LIST = "Ljava/util/List;";
    private static final String INVENTORY = "Lorg/bukkit/inventory/Inventory;";
    private static final String INVENTORY_VIEW = "Lorg/bukkit/inventory/InventoryView;";
    private static final String LIVING_ENTITY = "Lorg/bukkit/entity/LivingEntity;";
    private static final String LOCATION = "Lorg/bukkit/Location;";
    private static final String MATERIAL = "Lorg/bukkit/Material;";
    private static final String OBJECTIVE = "Lorg/bukkit/scoreboard/Objective;";
    private static final String PLAYER = "Lorg/bukkit/entity/Player;";
    private static final String POTION_EFFECT = "Lorg/bukkit/potion/PotionEffect;";
    private static final String POTION_EFFECT_TYPE = "Lorg/bukkit/potion/PotionEffectType;";
    private static final String SCORE = "Lorg/bukkit/scoreboard/Score;";
    private static final String SCOREBOARD = "Lorg/bukkit/scoreboard/Scoreboard;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String TEAM = "Lorg/bukkit/scoreboard/Team;";
    private static final String TEAM_OPTION = "Lorg/bukkit/scoreboard/Team$Option;";
    private static final String TEAM_OPTION_STATUS = "Lorg/bukkit/scoreboard/Team$OptionStatus;";

    private InstructionRouteRegistry() {
    }

    public static Optional<InstructionRoute> match(int opcode, String owner, String name, String descriptor) {
        if (!isMethodCallOpcode(opcode)) {
            return Optional.empty();
        }

        InstructionRoute route = matchRoute(owner, name, descriptor);
        return Optional.ofNullable(route);
    }

    private static boolean isMethodCallOpcode(int opcode) {
        return opcode == Opcodes.INVOKEINTERFACE
                || opcode == Opcodes.INVOKEVIRTUAL
                || opcode == Opcodes.INVOKESTATIC;
    }

    private static InstructionRoute matchRoute(String owner, String name, String descriptor) {
        Optional<InstructionRoute> central = RouteRuleRegistry.matchExact(owner, name, descriptor)
                .map(rule -> route(rule.routeFamily(), rule.guard(),
                        rule.note() + "; policy=" + rule.returnPolicy() + "; status=" + rule.status()));
        if (central.isPresent()) {
            return central.get();
        }

        if ("org/bukkit/Bukkit".equals(owner)
                && "dispatchCommand".equals(name)
                && ("(" + COMMAND_SENDER + STRING + ")Z").equals(descriptor)) {
            return route(RouteFamily.S_GLOBAL, "CraftServer#dispatchCommand",
                    "command dispatch routes through global/entity scheduler");
        }
        if ("org/bukkit/Server".equals(owner)
                && "dispatchCommand".equals(name)
                && ("(" + COMMAND_SENDER + STRING + ")Z").equals(descriptor)) {
            return route(RouteFamily.S_GLOBAL, "CraftServer#dispatchCommand",
                    "server command dispatch routes through global/entity scheduler");
        }

        if ("org/bukkit/entity/Player".equals(owner)) {
            if ("addPotionEffect".equals(name) && ("(" + POTION_EFFECT + ")Z").equals(descriptor)) {
                return route(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "player effect mutation owns receiver entity");
            }
            if ("removePotionEffect".equals(name) && ("(" + POTION_EFFECT_TYPE + ")V").equals(descriptor)) {
                return route(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "player effect removal owns receiver entity");
            }
            if ("openInventory".equals(name) && ("(" + INVENTORY + ")" + INVENTORY_VIEW).equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                        "player UI open needs player-owned UI policy");
            }
            if ("closeInventory".equals(name) && "()V".equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                        "player UI close needs player-owned UI policy");
            }
            if ("getScoreboard".equals(name) && ("()" + SCOREBOARD).equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftPlayer#scoreboard",
                        "player-owned scoreboard read");
            }
            if ("setScoreboard".equals(name) && ("(" + SCOREBOARD + ")V").equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftPlayer#scoreboard",
                        "player-owned scoreboard assignment");
            }
            if ("getNearbyEntities".equals(name) && "(DDD)Ljava/util/List;".equals(descriptor)) {
                return route(RouteFamily.G_WORLD_SCAN_SPLIT, "Entity#getNearbyEntities",
                        "entity-origin scan");
            }
        }

        if ("org/bukkit/entity/HumanEntity".equals(owner)) {
            if ("openInventory".equals(name) && ("(" + INVENTORY + ")" + INVENTORY_VIEW).equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                        "human UI open needs player-owned UI policy");
            }
            if ("closeInventory".equals(name) && "()V".equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                        "human UI close needs player-owned UI policy");
            }
        }

        if ("org/bukkit/entity/LivingEntity".equals(owner)) {
            if ("addPotionEffect".equals(name) && ("(" + POTION_EFFECT + ")Z").equals(descriptor)) {
                return route(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "living entity effect mutation owns receiver entity");
            }
            if ("removePotionEffect".equals(name) && ("(" + POTION_EFFECT_TYPE + ")V").equals(descriptor)) {
                return route(RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                        "living entity effect removal owns receiver entity");
            }
        }

        if ("org/bukkit/entity/Entity".equals(owner)) {
            if ("getNearbyEntities".equals(name) && "(DDD)Ljava/util/List;".equals(descriptor)) {
                return route(RouteFamily.G_WORLD_SCAN_SPLIT, "Entity#getNearbyEntities",
                        "entity-origin scan");
            }
        }

        if ("org/bukkit/block/Block".equals(owner)) {
            if ("getType".equals(name) && ("()" + MATERIAL).equals(descriptor)) {
                return route(RouteFamily.C_REGION_BLOCK, "CraftBlock#block-read",
                        "block material read owns block region");
            }
            if ("setType".equals(name) && ("(" + MATERIAL + ")V").equals(descriptor)) {
                return route(RouteFamily.C_REGION_BLOCK, "CraftBlock#block-mutation",
                        "block material mutation owns block region");
            }
        }

        if ("org/bukkit/World".equals(owner)) {
            if ("getBlockAt".equals(name) && ("(" + LOCATION + ")" + BLOCK).equals(descriptor)) {
                return route(RouteFamily.B_REGION_LOCATION, "CraftWorld#block-read",
                        "location-owned block read");
            }
            if ("getBlockAt".equals(name) && ("(III)" + BLOCK).equals(descriptor)) {
                return route(RouteFamily.C_REGION_BLOCK, "CraftWorld#block-read",
                        "coordinate-owned block read");
            }
            if ("getEntities".equals(name) && "()Ljava/util/List;".equals(descriptor)) {
                return route(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                        "whole-world entity scan must split");
            }
            if ("getNearbyEntities".equals(name) && ("(" + LOCATION + "DDD)" + COLLECTION).equals(descriptor)) {
                return route(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                        "bounded world scan must split by region/chunk");
            }
            if ("getChunkAt".equals(name) && ("(" + LOCATION + ")Lorg/bukkit/Chunk;").equals(descriptor)) {
                return route(RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-read",
                        "location-owned chunk read");
            }
        }

        if ("org/bukkit/Chunk".equals(owner)
                && "getEntities".equals(name)
                && ("()" + ENTITY_ARRAY).equals(descriptor)) {
            return route(RouteFamily.G_WORLD_SCAN_SPLIT, "CraftChunk#entity-scan",
                    "chunk-owned entity scan");
        }

        if ("org/bukkit/scoreboard/ScoreboardManager".equals(owner)
                && "getNewScoreboard".equals(name)
                && ("()" + SCOREBOARD).equals(descriptor)) {
            return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardManager#unowned",
                    "detached scoreboard model creation");
        }
        if ("org/bukkit/scoreboard/Scoreboard".equals(owner)) {
            if ("registerNewTeam".equals(name) && ("(" + STRING + ")" + TEAM).equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboard team model creation");
            }
            if ("registerNewObjective".equals(name) && descriptor.startsWith("(" + STRING + STRING)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboard#model",
                        "scoreboard objective model creation");
            }
        }
        if ("org/bukkit/scoreboard/Objective".equals(owner)) {
            if ("setDisplaySlot".equals(name) && ("(" + DISPLAY_SLOT + ")V").equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objective display slot model mutation");
            }
            if ("getScore".equals(name) && ("(" + STRING + ")" + SCORE).equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardObjective#model",
                        "objective score model read");
            }
        }
        if ("org/bukkit/scoreboard/Score".equals(owner)
                && "setScore".equals(name)
                && "(I)V".equals(descriptor)) {
            return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardScore#model",
                    "score value model mutation");
        }
        if ("org/bukkit/scoreboard/Team".equals(owner)) {
            if ("setOption".equals(name) && ("(" + TEAM_OPTION + TEAM_OPTION_STATUS + ")V").equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                        "team option model mutation");
            }
            if ("addEntry".equals(name) && ("(" + STRING + ")V").equals(descriptor)) {
                return route(RouteFamily.D_PLAYER_UI, "CraftScoreboardTeam#model",
                        "team entry model mutation");
            }
        }

        return null;
    }

    private static InstructionRoute route(RouteFamily routeFamily, String guard, String note) {
        return new InstructionRoute(routeFamily, guard, note);
    }

    public record InstructionRoute(RouteFamily routeFamily, String guard, String note) {
    }
}
