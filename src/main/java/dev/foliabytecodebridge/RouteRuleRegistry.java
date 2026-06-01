package dev.foliabytecodebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central route architecture map for exact owner/name/descriptor bytecode shapes.
 *
 * <p>The entries here are not a claim that a Bukkit call is universally safe.
 * They are the current experimental translation model: which Folia owner family
 * the call belongs to, what return-value compromise is being attempted, and
 * whether the bridge rewrites, models, or only observes the shape. Transformers,
 * ASM inventory, smoke tests, and docs should grow from this map instead of
 * accumulating new raw strings in separate files.</p>
 */
final class RouteRuleRegistry {

    enum ReturnPolicy {
        VOID_FIRE_AND_FORGET,
        ACCEPTED_BOOLEAN,
        SYNC_RETURN_DIRECT_OR_OWNER,
        SPLIT_AGGREGATE_RETURN,
        DEFERRED_PROXY_RETURN,
        SHIM_MODEL_RETURN,
        ASYNC_FUTURE,
        TRACE_ONLY
    }

    enum TranslationStatus {
        OBSERVED,
        EXPERIMENTAL_REWRITE,
        MODELED,
        TRACE_ONLY,
        MISSED
    }

    private static final String BLOCK = "Lorg/bukkit/block/Block;";
    private static final String CAUSE = "Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;";
    private static final String CHAT_COLOR = "Lorg/bukkit/ChatColor;";
    private static final String CHUNK = "Lorg/bukkit/Chunk;";
    private static final String CHUNK_ARRAY = "[Lorg/bukkit/Chunk;";
    private static final String CLASS = "Ljava/lang/Class;";
    private static final String CLASS_ARRAY = "[Ljava/lang/Class;";
    private static final String COLLECTION = "Ljava/util/Collection;";
    private static final String COMMAND_SENDER = "Lorg/bukkit/command/CommandSender;";
    private static final String COMPONENT = "Lnet/kyori/adventure/text/Component;";
    private static final String CRITERIA = "Lorg/bukkit/scoreboard/Criteria;";
    private static final String DISPLAY_SLOT = "Lorg/bukkit/scoreboard/DisplaySlot;";
    private static final String ENTITY = "Lorg/bukkit/entity/Entity;";
    private static final String ENTITY_ARRAY = "[Lorg/bukkit/entity/Entity;";
    private static final String ENTITY_LIST = "Ljava/util/List;";
    private static final String ENTITY_TYPE = "Lorg/bukkit/entity/EntityType;";
    private static final String HUMAN_ENTITY = "Lorg/bukkit/entity/HumanEntity;";
    private static final String INVENTORY = "Lorg/bukkit/inventory/Inventory;";
    private static final String INVENTORY_VIEW = "Lorg/bukkit/inventory/InventoryView;";
    private static final String ITEM = "Lorg/bukkit/entity/Item;";
    private static final String ITEM_STACK = "Lorg/bukkit/inventory/ItemStack;";
    private static final String LIGHTNING = "Lorg/bukkit/entity/LightningStrike;";
    private static final String LIVING_ENTITY = "Lorg/bukkit/entity/LivingEntity;";
    private static final String LOCATION = "Lorg/bukkit/Location;";
    private static final String MATERIAL = "Lorg/bukkit/Material;";
    private static final String NAMED_TEXT_COLOR = "Lnet/kyori/adventure/text/format/NamedTextColor;";
    private static final String NUMBER_FORMAT = "Lio/papermc/paper/scoreboard/numbers/NumberFormat;";
    private static final String OBJECTIVE = "Lorg/bukkit/scoreboard/Objective;";
    private static final String OFFLINE_PLAYER = "Lorg/bukkit/OfflinePlayer;";
    private static final String PLAYER = "Lorg/bukkit/entity/Player;";
    private static final String POTION_EFFECT = "Lorg/bukkit/potion/PotionEffect;";
    private static final String POTION_EFFECT_TYPE = "Lorg/bukkit/potion/PotionEffectType;";
    private static final String RENDER_TYPE = "Lorg/bukkit/scoreboard/RenderType;";
    private static final String SCORE = "Lorg/bukkit/scoreboard/Score;";
    private static final String SCOREBOARD = "Lorg/bukkit/scoreboard/Scoreboard;";
    private static final String SCOREBOARD_MANAGER = "Lorg/bukkit/scoreboard/ScoreboardManager;";
    private static final String SOUND = "Lorg/bukkit/Sound;";
    private static final String SOUND_CATEGORY = "Lorg/bukkit/SoundCategory;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String TEAM = "Lorg/bukkit/scoreboard/Team;";
    private static final String TEAM_OPTION = "Lorg/bukkit/scoreboard/Team$Option;";
    private static final String TEAM_OPTION_STATUS = "Lorg/bukkit/scoreboard/Team$OptionStatus;";
    private static final String TREE_TYPE = "Lorg/bukkit/TreeType;";
    private static final String WORLD = "Lorg/bukkit/World;";

    private static final List<RouteRule> RULES = new ArrayList<>();
    private static final Map<String, RouteRule> EXACT = new ConcurrentHashMap<>();
    private static final Map<String, RouteRule> RUNTIME_API = new ConcurrentHashMap<>();

    static {
        scheduler("org/bukkit/Bukkit", "dispatchCommand", "(" + COMMAND_SENDER + STRING + ")Z",
                "bukkitDispatchCommand", "(" + COMMAND_SENDER + STRING + ")Z",
                "CraftServer#dispatchCommand", ReturnPolicy.ACCEPTED_BOOLEAN,
                "command dispatch schedules through global/entity owner; return=scheduled-true");
        scheduler("org/bukkit/Server", "dispatchCommand", "(" + COMMAND_SENDER + STRING + ")Z",
                "serverDispatchCommand", "(Lorg/bukkit/Server;" + COMMAND_SENDER + STRING + ")Z",
                "CraftServer#dispatchCommand", ReturnPolicy.ACCEPTED_BOOLEAN,
                "server command dispatch schedules through global/entity owner; return=scheduled-true");
        runtime("MCUtil.MAIN_EXECUTOR#execute", "io/papermc/paper/util/MCUtil",
                "MAIN_EXECUTOR.execute", "(Ljava/lang/Runnable;)V",
                RouteFamily.S_GLOBAL, "MCUtil#main-executor",
                "mcUtilMainExecutorExecute", "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "Paper MCUtil main executor schedules through Folia global scheduler");
        runtime("MinecraftServer#execute", "net/minecraft/server/MinecraftServer",
                "execute", "(Ljava/lang/Runnable;)V",
                RouteFamily.S_GLOBAL, "MinecraftServer#server-executor",
                "minecraftServerExecute", "(Ljava/lang/Object;Ljava/lang/Runnable;)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "NMS MinecraftServer executor schedules through Folia global scheduler");

        add("org/bukkit/entity/Entity", "teleport", "(" + LOCATION + ")Z",
                RouteFamily.A_ENTITY, "CraftEntity#teleport",
                "entityTeleport", "(" + ENTITY + LOCATION + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "direct entity teleport submits teleportAsync and returns scheduled acceptance");
        add("org/bukkit/entity/Entity", "teleport", "(" + LOCATION + CAUSE + ")Z",
                RouteFamily.A_ENTITY, "CraftEntity#teleport",
                "entityTeleport", "(" + ENTITY + LOCATION + CAUSE + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "direct entity teleport with cause submits teleportAsync and returns scheduled acceptance");
        add("org/bukkit/entity/Player", "teleport", "(" + LOCATION + ")Z",
                RouteFamily.A_ENTITY, "CraftPlayer#teleport",
                "playerTeleport", "(" + PLAYER + LOCATION + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "direct player teleport submits teleportAsync and returns scheduled acceptance");
        add("org/bukkit/entity/Player", "teleport", "(" + LOCATION + CAUSE + ")Z",
                RouteFamily.A_ENTITY, "CraftPlayer#teleport",
                "playerTeleport", "(" + PLAYER + LOCATION + CAUSE + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "direct player teleport with cause submits teleportAsync and returns scheduled acceptance");

        add("org/bukkit/entity/Entity", "getNearbyEntities", "(DDD)" + ENTITY_LIST,
                RouteFamily.G_WORLD_SCAN_SPLIT, "Entity#getNearbyEntities",
                "entityGetNearbyEntities", "(" + ENTITY + "DDD)" + ENTITY_LIST,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "entity-origin scan uses entity owner or bounded split model");
        add("org/bukkit/entity/Player", "getNearbyEntities", "(DDD)" + ENTITY_LIST,
                RouteFamily.G_WORLD_SCAN_SPLIT, "Entity#getNearbyEntities",
                "entityGetNearbyEntities", "(" + ENTITY + "DDD)" + ENTITY_LIST,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "player-origin scan uses entity owner or bounded split model");

        add("org/bukkit/entity/Player", "addPotionEffect", "(" + POTION_EFFECT + ")Z",
                RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                "playerAddPotionEffect", "(" + PLAYER + POTION_EFFECT + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "player effect mutation owns receiver entity; wrong-owner return is scheduled-true");
        add("org/bukkit/entity/Player", "removePotionEffect", "(" + POTION_EFFECT_TYPE + ")V",
                RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                "playerRemovePotionEffect", "(" + PLAYER + POTION_EFFECT_TYPE + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "player effect removal owns receiver entity");
        add("org/bukkit/entity/LivingEntity", "addPotionEffect", "(" + POTION_EFFECT + ")Z",
                RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                "livingAddPotionEffect", "(" + LIVING_ENTITY + POTION_EFFECT + ")Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "living entity effect mutation owns receiver entity; wrong-owner return is scheduled-true");
        add("org/bukkit/entity/LivingEntity", "removePotionEffect", "(" + POTION_EFFECT_TYPE + ")V",
                RouteFamily.A_ENTITY, "CraftLivingEntity#effect-mutation",
                "livingRemovePotionEffect", "(" + LIVING_ENTITY + POTION_EFFECT_TYPE + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "living entity effect removal owns receiver entity");

        RouteRule playerOpenInventory = add("org/bukkit/entity/Player", "openInventory", "(" + INVENTORY + ")" + INVENTORY_VIEW,
                RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                "playerOpenInventory", "(" + PLAYER + INVENTORY + ")" + INVENTORY_VIEW,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "player inventory open routes through the owning entity scheduler");
        runtimeAlias("Player#openInventory", playerOpenInventory);
        RouteRule playerCloseInventory = add("org/bukkit/entity/Player", "closeInventory", "()V",
                RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                "playerCloseInventory", "(" + PLAYER + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "player inventory close routes through the owning entity scheduler");
        runtimeAlias("Player#closeInventory", playerCloseInventory);
        RouteRule humanOpenInventory = add("org/bukkit/entity/HumanEntity", "openInventory", "(" + INVENTORY + ")" + INVENTORY_VIEW,
                RouteFamily.D_PLAYER_UI, "CraftHumanEntity#open-container",
                "humanOpenInventory", "(" + HUMAN_ENTITY + INVENTORY + ")" + INVENTORY_VIEW,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "human inventory open routes through the receiver entity scheduler");
        runtimeAlias("HumanEntity#openInventory", humanOpenInventory);
        RouteRule humanCloseInventory = add("org/bukkit/entity/HumanEntity", "closeInventory", "()V",
                RouteFamily.D_PLAYER_UI, "CraftHumanEntity#close-container",
                "humanCloseInventory", "(" + HUMAN_ENTITY + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "human inventory close routes through the receiver entity scheduler");
        runtimeAlias("HumanEntity#closeInventory", humanCloseInventory);

        scoreboard("org/bukkit/entity/Player", "getScoreboard", "()" + SCOREBOARD,
                "playerGetScoreboard", "(" + PLAYER + ")" + SCOREBOARD, ReturnPolicy.SHIM_MODEL_RETURN,
                "player scoreboard read returns player-owned model on Folia");
        scoreboard("org/bukkit/entity/Player", "setScoreboard", "(" + SCOREBOARD + ")V",
                "playerSetScoreboard", "(" + PLAYER + SCOREBOARD + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "player scoreboard assignment retains bridge model state");
        scoreboard("org/bukkit/Bukkit", "getScoreboardManager", "()" + SCOREBOARD_MANAGER,
                null, null, ReturnPolicy.TRACE_ONLY,
                "scoreboard manager is an unowned UI/model factory; exact creation is modeled separately",
                "CraftScoreboardManager");
        scoreboard("org/bukkit/scoreboard/ScoreboardManager", "getNewScoreboard", "()" + SCOREBOARD,
                "scoreboardManagerGetNewScoreboard", "(" + SCOREBOARD_MANAGER + ")" + SCOREBOARD,
                ReturnPolicy.SHIM_MODEL_RETURN, "detached scoreboard creation returns D_PLAYER_UI model",
                "CraftScoreboardManager#unowned");
        scoreboard("org/bukkit/scoreboard/ScoreboardManager", "getMainScoreboard", "()" + SCOREBOARD,
                null, null, ReturnPolicy.TRACE_ONLY,
                "main scoreboard remains diagnostic until an owner/model policy is proven",
                "CraftScoreboardManager#main");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "getTeam", "(" + STRING + ")" + TEAM,
                "scoreboardGetTeam", "(" + SCOREBOARD + STRING + ")" + TEAM, ReturnPolicy.SHIM_MODEL_RETURN,
                "scoreboard team lookup uses bridge model when receiver is bridge-owned");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewTeam", "(" + STRING + ")" + TEAM,
                "scoreboardRegisterNewTeam", "(" + SCOREBOARD + STRING + ")" + TEAM, ReturnPolicy.SHIM_MODEL_RETURN,
                "scoreboard team creation uses bridge model when receiver is bridge-owned");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective", "(" + STRING + STRING + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveLegacy", "(" + SCOREBOARD + STRING + STRING + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "legacy objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + STRING + STRING + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveLegacyDisplayName",
                "(" + SCOREBOARD + STRING + STRING + STRING + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "legacy objective creation with display name routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + STRING + COMPONENT + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveComponent",
                "(" + SCOREBOARD + STRING + STRING + COMPONENT + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "component objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + STRING + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveComponentRender",
                "(" + SCOREBOARD + STRING + STRING + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "component/render objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + CRITERIA + COMPONENT + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveCriteriaComponent",
                "(" + SCOREBOARD + STRING + CRITERIA + COMPONENT + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "Criteria/component objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + CRITERIA + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveCriteriaComponentRender",
                "(" + SCOREBOARD + STRING + CRITERIA + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "Criteria/component/render objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + CRITERIA + STRING + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveCriteriaDisplayName",
                "(" + SCOREBOARD + STRING + CRITERIA + STRING + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "Criteria/string objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "registerNewObjective",
                "(" + STRING + CRITERIA + STRING + RENDER_TYPE + ")" + OBJECTIVE,
                "scoreboardRegisterNewObjectiveCriteriaDisplayNameRender",
                "(" + SCOREBOARD + STRING + CRITERIA + STRING + RENDER_TYPE + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "Criteria/string/render objective creation routes to the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "getObjective", "(" + STRING + ")" + OBJECTIVE,
                "scoreboardGetObjective", "(" + SCOREBOARD + STRING + ")" + OBJECTIVE, ReturnPolicy.SHIM_MODEL_RETURN,
                "scoreboard objective lookup uses bridge model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "getObjective", "(" + DISPLAY_SLOT + ")" + OBJECTIVE,
                "scoreboardGetObjectiveForDisplaySlot", "(" + SCOREBOARD + DISPLAY_SLOT + ")" + OBJECTIVE,
                ReturnPolicy.SHIM_MODEL_RETURN, "scoreboard objective display-slot lookup uses bridge model");
        scoreboard("org/bukkit/scoreboard/Scoreboard", "getObjectives", "()" + "Ljava/util/Set;",
                "scoreboardGetObjectives", "(" + SCOREBOARD + ")Ljava/util/Set;", ReturnPolicy.SHIM_MODEL_RETURN,
                "scoreboard objective set read uses bridge model");
        scoreboard("org/bukkit/scoreboard/Objective", "setDisplaySlot", "(" + DISPLAY_SLOT + ")V",
                "objectiveSetDisplaySlot", "(" + OBJECTIVE + DISPLAY_SLOT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "objective display slot mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Objective", "setDisplayName", "(" + STRING + ")V",
                "objectiveSetDisplayName", "(" + OBJECTIVE + STRING + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "objective display-name mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Objective", "displayName", "(" + COMPONENT + ")V",
                "objectiveDisplayName", "(" + OBJECTIVE + COMPONENT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "objective component display-name mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Objective", "numberFormat", "(" + NUMBER_FORMAT + ")V",
                "objectiveNumberFormat", "(" + OBJECTIVE + NUMBER_FORMAT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "objective number-format mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Objective", "getScore", "(" + STRING + ")" + SCORE,
                "objectiveGetScore", "(" + OBJECTIVE + STRING + ")" + SCORE, ReturnPolicy.SHIM_MODEL_RETURN,
                "objective score lookup uses bridge model");
        scoreboard("org/bukkit/scoreboard/Objective", "getScore", "(" + OFFLINE_PLAYER + ")" + SCORE,
                "objectiveGetScore", "(" + OBJECTIVE + OFFLINE_PLAYER + ")" + SCORE, ReturnPolicy.SHIM_MODEL_RETURN,
                "objective offline-player score lookup uses bridge model");
        scoreboard("org/bukkit/scoreboard/Score", "setScore", "(I)V",
                "scoreSetScore", "(" + SCORE + "I)V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "score value mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Score", "getScore", "()I",
                "scoreGetScore", "(" + SCORE + ")I", ReturnPolicy.SHIM_MODEL_RETURN,
                "score value read uses bridge model");
        scoreboard("org/bukkit/scoreboard/Score", "resetScore", "()V",
                "scoreResetScore", "(" + SCORE + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "score reset mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Score", "customName", "(" + COMPONENT + ")V",
                "scoreCustomName", "(" + SCORE + COMPONENT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "score custom-name mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Score", "numberFormat", "(" + NUMBER_FORMAT + ")V",
                "scoreNumberFormat", "(" + SCORE + NUMBER_FORMAT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "score number-format mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setOption", "(" + TEAM_OPTION + TEAM_OPTION_STATUS + ")V",
                "teamSetOption", "(" + TEAM + TEAM_OPTION + TEAM_OPTION_STATUS + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, "team option mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Team", "addEntry", "(" + STRING + ")V",
                "teamAddEntry", "(" + TEAM + STRING + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team entry add mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Team", "removeEntry", "(" + STRING + ")Z",
                "teamRemoveEntry", "(" + TEAM + STRING + ")Z", ReturnPolicy.SHIM_MODEL_RETURN,
                "team entry remove mutates bridge model");
        scoreboard("org/bukkit/scoreboard/Team", "setDisplayName", "(" + STRING + ")V",
                "teamSetDisplayName", "(" + TEAM + STRING + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team display-name mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "displayName", "(" + COMPONENT + ")V",
                "teamDisplayName", "(" + TEAM + COMPONENT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team component display-name mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setPrefix", "(" + STRING + ")V",
                "teamSetPrefix", "(" + TEAM + STRING + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team prefix mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "prefix", "(" + COMPONENT + ")V",
                "teamPrefix", "(" + TEAM + COMPONENT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team component prefix mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setSuffix", "(" + STRING + ")V",
                "teamSetSuffix", "(" + TEAM + STRING + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team suffix mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "suffix", "(" + COMPONENT + ")V",
                "teamSuffix", "(" + TEAM + COMPONENT + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team component suffix mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setColor", "(" + CHAT_COLOR + ")V",
                "teamSetColor", "(" + TEAM + CHAT_COLOR + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team ChatColor mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "color", "(" + NAMED_TEXT_COLOR + ")V",
                "teamColor", "(" + TEAM + NAMED_TEXT_COLOR + ")V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team NamedTextColor mutation stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setAllowFriendlyFire", "(Z)V",
                "teamSetAllowFriendlyFire", "(" + TEAM + "Z)V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team friendly-fire flag stays inside the D_PLAYER_UI shim model");
        scoreboard("org/bukkit/scoreboard/Team", "setCanSeeFriendlyInvisibles", "(Z)V",
                "teamSetCanSeeFriendlyInvisibles", "(" + TEAM + "Z)V", ReturnPolicy.VOID_FIRE_AND_FORGET,
                "team friendly-invisibles flag stays inside the D_PLAYER_UI shim model");

        add("org/bukkit/World", "getChunkAt", "(II)" + CHUNK,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-read",
                "worldGetChunkAt", "(" + WORLD + "II)" + CHUNK,
                ReturnPolicy.DEFERRED_PROXY_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "chunk read uses owner scheduler, loaded-index, or deferred proxy");
        add("org/bukkit/World", "getChunkAt", "(" + LOCATION + ")" + CHUNK,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-read",
                "worldGetChunkAt", "(" + WORLD + LOCATION + ")" + CHUNK,
                ReturnPolicy.DEFERRED_PROXY_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "location chunk read uses owner scheduler, loaded-index, or deferred proxy");
        add("org/bukkit/World", "getChunkAt", "(" + BLOCK + ")" + CHUNK,
                RouteFamily.C_REGION_BLOCK, "CraftWorld#chunk-read",
                "worldGetChunkAt", "(" + WORLD + BLOCK + ")" + CHUNK,
                ReturnPolicy.DEFERRED_PROXY_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "block-owned chunk read uses owner scheduler, loaded-index, or deferred proxy");
        add("org/bukkit/World", "getEntities", "()" + ENTITY_LIST,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                "worldGetEntities", "(" + WORLD + ")" + ENTITY_LIST,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "whole-world entity scan splits over loaded chunks");
        add("org/bukkit/World", "getNearbyEntities", "(" + LOCATION + "DDD)" + COLLECTION,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                "worldGetNearbyEntities", "(" + WORLD + LOCATION + "DDD)" + COLLECTION,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "bounded world scan splits over candidate loaded chunks");
        add("org/bukkit/World", "getEntitiesByClass", "(" + CLASS + ")" + COLLECTION,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                "worldGetEntitiesByClass", "(" + WORLD + CLASS + ")" + COLLECTION,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "typed world scan splits over loaded chunks");
        add("org/bukkit/World", "getEntitiesByClass", "(" + CLASS_ARRAY + ")" + COLLECTION,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                "worldGetEntitiesByClass", "(" + WORLD + CLASS_ARRAY + ")" + COLLECTION,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "typed varargs world scan splits over loaded chunks");
        add("org/bukkit/World", "getEntitiesByClasses", "(" + CLASS_ARRAY + ")" + COLLECTION,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#entity-scan",
                "worldGetEntitiesByClasses", "(" + WORLD + CLASS_ARRAY + ")" + COLLECTION,
                ReturnPolicy.SPLIT_AGGREGATE_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "typed multi-class world scan splits over loaded chunks");
        add("org/bukkit/World", "getLoadedChunks", "()" + CHUNK_ARRAY,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftWorld#loaded-chunks",
                "worldGetLoadedChunks", "(" + WORLD + ")" + CHUNK_ARRAY,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "loaded chunk index feeds split scans");
        add("org/bukkit/Chunk", "getEntities", "()" + ENTITY_ARRAY,
                RouteFamily.G_WORLD_SCAN_SPLIT, "CraftChunk#entity-scan",
                "chunkGetEntities", "(" + CHUNK + ")" + ENTITY_ARRAY,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "chunk entity scan owns chunk region");

        add("org/bukkit/World", "loadChunk", "(II)V",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-load-unload",
                "worldLoadChunk", "(" + WORLD + "II)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "chunk load schedules to owner region");
        add("org/bukkit/World", "refreshChunk", "(II)Z",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#chunk-load-unload",
                "worldRefreshChunk", "(" + WORLD + "II)Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "chunk refresh schedules to owner region and returns scheduled acceptance");
        add("org/bukkit/World", "dropItem", "(" + LOCATION + ITEM_STACK + ")" + ITEM,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#item-spawn",
                "worldDropItem", "(" + WORLD + LOCATION + ITEM_STACK + ")" + ITEM,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "location item spawn owns target region");
        add("org/bukkit/World", "dropItemNaturally", "(" + LOCATION + ITEM_STACK + ")" + ITEM,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#item-spawn",
                "worldDropItemNaturally", "(" + WORLD + LOCATION + ITEM_STACK + ")" + ITEM,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "natural location item spawn owns target region");
        add("org/bukkit/World", "spawnEntity", "(" + LOCATION + ENTITY_TYPE + ")" + ENTITY,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#entity-spawn",
                "worldSpawnEntity", "(" + WORLD + LOCATION + ENTITY_TYPE + ")" + ENTITY,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "location entity spawn owns target region");
        add("org/bukkit/World", "generateTree", "(" + LOCATION + TREE_TYPE + ")Z",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#world-mutation",
                "worldGenerateTree", "(" + WORLD + LOCATION + TREE_TYPE + ")Z",
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "tree generation owns target region");
        add("org/bukkit/World", "strikeLightning", "(" + LOCATION + ")" + LIGHTNING,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                "worldStrikeLightning", "(" + WORLD + LOCATION + ")" + LIGHTNING,
                ReturnPolicy.DEFERRED_PROXY_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "lightning effect owns target region; foreign owner threads return a deferred entity proxy");
        add("org/bukkit/World", "strikeLightningEffect", "(" + LOCATION + ")" + LIGHTNING,
                RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                "worldStrikeLightningEffect", "(" + WORLD + LOCATION + ")" + LIGHTNING,
                ReturnPolicy.DEFERRED_PROXY_RETURN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "lightning visual effect owns target region; foreign owner threads return a deferred entity proxy");
        add("org/bukkit/World", "createExplosion", "(" + LOCATION + "F)Z",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                "worldCreateExplosion", "(" + WORLD + LOCATION + "F)Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "location explosion schedules to owner region and returns scheduled acceptance");
        add("org/bukkit/World", "createExplosion", "(DDDF)Z",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#world-effect",
                "worldCreateExplosion", "(" + WORLD + "DDDF)Z",
                ReturnPolicy.ACCEPTED_BOOLEAN, TranslationStatus.EXPERIMENTAL_REWRITE,
                "coordinate explosion schedules to owner region and returns scheduled acceptance");
        add("org/bukkit/World", "playSound", "(" + LOCATION + SOUND + "FF)V",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                "worldPlaySound", "(" + WORLD + LOCATION + SOUND + "FF)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "location sound schedules to owner region");
        add("org/bukkit/World", "playSound", "(" + LOCATION + STRING + "FF)V",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                "worldPlaySound", "(" + WORLD + LOCATION + STRING + "FF)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "string location sound schedules to owner region");
        add("org/bukkit/World", "playSound", "(" + LOCATION + SOUND + SOUND_CATEGORY + "FF)V",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                "worldPlaySound", "(" + WORLD + LOCATION + SOUND + SOUND_CATEGORY + "FF)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "categorized location sound schedules to owner region");
        add("org/bukkit/World", "playSound", "(" + LOCATION + STRING + SOUND_CATEGORY + "FF)V",
                RouteFamily.B_REGION_LOCATION, "CraftWorld#sound",
                "worldPlaySound", "(" + WORLD + LOCATION + STRING + SOUND_CATEGORY + "FF)V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "categorized string location sound schedules to owner region");

        add("org/bukkit/block/Block", "getType", "()" + MATERIAL,
                RouteFamily.C_REGION_BLOCK, "CraftBlock#block-read",
                "blockGetType", "(" + BLOCK + ")" + MATERIAL,
                ReturnPolicy.SYNC_RETURN_DIRECT_OR_OWNER, TranslationStatus.EXPERIMENTAL_REWRITE,
                "block material read owns block region");
        add("org/bukkit/block/Block", "setType", "(" + MATERIAL + ")V",
                RouteFamily.C_REGION_BLOCK, "CraftBlock#block-mutation",
                "blockSetType", "(" + BLOCK + MATERIAL + ")V",
                ReturnPolicy.VOID_FIRE_AND_FORGET, TranslationStatus.EXPERIMENTAL_REWRITE,
                "block material mutation owns block region");
    }

    private RouteRuleRegistry() {
    }

    static Optional<RouteRule> matchExact(String owner, String name, String descriptor) {
        return Optional.ofNullable(EXACT.get(key(owner, name, descriptor)));
    }

    static Optional<RouteRule> matchRuntimeApi(String api) {
        return Optional.ofNullable(RUNTIME_API.get(api));
    }

    static List<RouteRule> rules() {
        return Collections.unmodifiableList(RULES);
    }

    private static void scheduler(String owner, String name, String descriptor, String bridgeMethod,
                                  String bridgeDescriptor, String guard, ReturnPolicy returnPolicy, String note) {
        add(owner, name, descriptor, RouteFamily.S_GLOBAL, guard, bridgeMethod, bridgeDescriptor,
                returnPolicy, TranslationStatus.EXPERIMENTAL_REWRITE, note);
    }

    private static void runtime(String api, String owner, String name, String descriptor,
                                RouteFamily routeFamily, String guard, String bridgeMethod,
                                String bridgeDescriptor, ReturnPolicy returnPolicy,
                                TranslationStatus status, String note) {
        RouteRule rule = add(owner, name, descriptor, routeFamily, guard, bridgeMethod,
                bridgeDescriptor, returnPolicy, status, note);
        RUNTIME_API.put(api, rule);
    }

    private static void runtimeAlias(String api, RouteRule rule) {
        RUNTIME_API.put(api, rule);
    }

    private static void scoreboard(String owner, String name, String descriptor, String bridgeMethod,
                                   String bridgeDescriptor, ReturnPolicy returnPolicy, String note) {
        scoreboard(owner, name, descriptor, bridgeMethod, bridgeDescriptor, returnPolicy, note, "CraftScoreboard#model");
    }

    private static void scoreboard(String owner, String name, String descriptor, String bridgeMethod,
                                   String bridgeDescriptor, ReturnPolicy returnPolicy, String note, String guard) {
        add(owner, name, descriptor, RouteFamily.D_PLAYER_UI, guard, bridgeMethod, bridgeDescriptor,
                returnPolicy, TranslationStatus.MODELED, note);
    }

    private static RouteRule add(String owner, String name, String descriptor, RouteFamily routeFamily,
                                 String guard, String bridgeMethod, String bridgeDescriptor,
                                 ReturnPolicy returnPolicy, TranslationStatus status, String note) {
        RouteRule rule = new RouteRule(owner, name, descriptor, routeFamily, guard, bridgeMethod,
                bridgeDescriptor, returnPolicy, status, note);
        RULES.add(rule);
        EXACT.put(key(owner, name, descriptor), rule);
        return rule;
    }

    private static String key(String owner, String name, String descriptor) {
        return owner + "#" + name + descriptor;
    }

    record RouteRule(String owner, String name, String descriptor, RouteFamily routeFamily, String guard,
                     String bridgeMethod, String bridgeDescriptor, ReturnPolicy returnPolicy,
                     TranslationStatus status, String note) {
        boolean rewrites() {
            return bridgeMethod != null && !bridgeMethod.isBlank()
                    && bridgeDescriptor != null && !bridgeDescriptor.isBlank()
                    && status != TranslationStatus.TRACE_ONLY
                    && status != TranslationStatus.MISSED
                    && status != TranslationStatus.OBSERVED;
        }

        String api() {
            return owner.replace('/', '.') + "#" + name;
        }
    }
}
