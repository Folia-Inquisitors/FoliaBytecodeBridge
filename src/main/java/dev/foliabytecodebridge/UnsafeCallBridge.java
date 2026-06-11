package dev.foliabytecodebridge;

import org.bukkit.Chunk;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.event.Event;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class UnsafeCallBridge {

    private static final AtomicInteger UNSAFE_CALLS = new AtomicInteger();
    private static final Map<String, DetachedScoreboardModel> PLAYER_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<String, DeferredChunkModel> DEFERRED_CHUNKS = new ConcurrentHashMap<>();
    private static final Map<String, Material> BLOCK_TYPE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BlockData> BLOCK_DATA_CACHE = new ConcurrentHashMap<>();
    private static volatile Plugin bridgePlugin;

    private UnsafeCallBridge() {
    }

    /**
     * Public bootstrap boundary used by the Bukkit plugin entrypoint.
     *
     * <p>Runtime helpers may be parent-loaded from the Java-agent helper jar, so
     * this cannot rely on package-private access from the plugin classloader.</p>
     */
    public static void setBridgePlugin(Plugin plugin) {
        bridgePlugin = plugin;
        BridgePluginResolver.setBridgePlugin(plugin, "UnsafeCallBridge#setBridgePlugin");
    }

    public static boolean bukkitDispatchCommand(CommandSender sender, String command) {
        return dispatchCommand("Bukkit#dispatchCommand(CommandSender,String)", null, sender, command);
    }

    public static boolean serverDispatchCommand(Server server, CommandSender sender, String command) {
        return dispatchCommand("Server#dispatchCommand(CommandSender,String)", server, sender, command);
    }

    public static void pluginManagerCallEvent(PluginManager pluginManager, Event event) {
        SyntheticEventDispatchBridge.callEvent(pluginManager, event);
    }

    public static Location entityGetLocation(Entity entity) {
        return guarded("Entity#getLocation", "entity", "entity-scheduler-read", entityReadDetail(entity),
                () -> entity.getLocation());
    }

    public static World entityGetWorld(Entity entity) {
        return guarded("Entity#getWorld", "entity", "entity-scheduler-read", entityReadDetail(entity),
                () -> entity.getWorld());
    }

    public static boolean entityTeleport(Entity entity, Location location) {
        String sourceApi = "Entity#teleport(Location)";
        String nextAction = "entity-scheduler-teleport-async";
        return guarded(sourceApi, "entity", nextAction,
                entityDetail(entity) + " target=" + locationDetail(location)
                        + " cause=" + defaultTeleportCause()
                        + " shim=" + shimName(),
                () -> {
                    if (!isFolia()) {
                        return entity.teleport(location);
                    }
                    watchTeleportFuture(sourceApi, "entity", nextAction,
                            entity.teleportAsync(location, defaultTeleportCause()));
                    return true;
                });
    }

    public static boolean entityTeleport(Entity entity, Location location, TeleportCause cause) {
        String sourceApi = "Entity#teleport(Location,TeleportCause)";
        String nextAction = "entity-scheduler-teleport-async";
        return guarded(sourceApi, "entity", nextAction,
                entityDetail(entity) + " target=" + locationDetail(location)
                        + " cause=" + safeTeleportCause(cause)
                        + " shim=" + shimName(),
                () -> {
                    if (!isFolia()) {
                        return entity.teleport(location, cause);
                    }
                    watchTeleportFuture(sourceApi, "entity", nextAction,
                            entity.teleportAsync(location, safeTeleportCause(cause)));
                    return true;
                });
    }

    public static CompletableFuture<Boolean> entityTeleportAsync(Entity entity, Location location, TeleportCause cause) {
        return guardedFuture("Entity#teleportAsync(Location,TeleportCause)", "entity",
                "entity-scheduler-teleport-async",
                entityDetail(entity) + " target=" + locationDetail(location) + " cause=" + cause,
                () -> entity.teleportAsync(location, cause));
    }

    public static CompletableFuture<Boolean> staticTeleportAsync(Entity entity, Location location, TeleportCause cause,
                                                                 String owner, String methodName, String descriptor) {
        String api = owner.replace('/', '.') + "#" + methodName + "(Entity,Location,TeleportCause)";
        return guardedFuture(api, "entity",
                "entity-scheduler-teleport-async",
                entityDetail(entity) + " target=" + locationDetail(location) + " cause=" + cause
                        + " bytecodeOwner=" + owner.replace('/', '.')
                        + " descriptor=" + descriptor
                        + " shim=Entity#teleportAsync",
                () -> entity.teleportAsync(location, cause));
    }

    public static void entitySetVelocity(Entity entity, Vector velocity) {
        guardedVoid("Entity#setVelocity", "entity", "entity-scheduler-mutation", entityDetail(entity),
                () -> entity.setVelocity(velocity));
    }

    public static List<Entity> entityGetNearbyEntities(Entity entity, double x, double y, double z) {
        String sourceApi = "Entity#getNearbyEntities";
        String family = "entity-scan";
        String nextAction = "entity-scheduler-scan";
        String detail = entityDetail(entity) + " x=" + x + " y=" + y + " z=" + z;
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route");
        try {
            if (!isFolia()) {
                return entity.getNearbyEntities(x, y, z);
            }

            boolean owned = false;
            boolean ownerCheckFailed = false;
            try {
                owned = isOwnedByCurrentRegion(entity);
            } catch (Throwable throwable) {
                ownerCheckFailed = true;
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                                + throwable.getClass().getName() + ": " + throwable.getMessage());
            }

            if (owned || ownerCheckFailed) {
                return entity.getNearbyEntities(x, y, z);
            }

            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=entity-location-bounded-split"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\"");
                return splitNearbyEntitiesForEntity(sourceApi, detail, entity, x, y, z);
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-entity-scheduler reason=proven-live-route");
            return callEntityScheduler(sourceApi, family, nextAction, detail, entity,
                    () -> entity.getNearbyEntities(x, y, z));
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    public static void entityRemove(Entity entity) {
        guardedVoid("Entity#remove", "entity", "entity-scheduler-mutation", entityDetail(entity),
                () -> entity.remove());
    }

    public static Location playerGetLocation(Player player) {
        return guarded("Player#getLocation", "player", "entity-scheduler-read", playerReadDetail(player),
                () -> player.getLocation());
    }

    public static World playerGetWorld(Player player) {
        return guarded("Player#getWorld", "player", "entity-scheduler-read", playerReadDetail(player),
                () -> player.getWorld());
    }

    public static boolean playerTeleport(Player player, Location location) {
        String sourceApi = "Player#teleport(Location)";
        String nextAction = "entity-scheduler-teleport-async";
        return guarded(sourceApi, "player", nextAction,
                playerDetail(player) + " target=" + locationDetail(location)
                        + " cause=" + defaultTeleportCause()
                        + " shim=" + shimName(),
                () -> {
                    if (!isFolia()) {
                        return player.teleport(location);
                    }
                    watchTeleportFuture(sourceApi, "player", nextAction,
                            player.teleportAsync(location, defaultTeleportCause()));
                    return true;
                });
    }

    public static boolean playerTeleport(Player player, Location location, TeleportCause cause) {
        String sourceApi = "Player#teleport(Location,TeleportCause)";
        String nextAction = "entity-scheduler-teleport-async";
        return guarded(sourceApi, "player", nextAction,
                playerDetail(player) + " target=" + locationDetail(location)
                        + " cause=" + safeTeleportCause(cause)
                        + " shim=" + shimName(),
                () -> {
                    if (!isFolia()) {
                        return player.teleport(location, cause);
                    }
                    watchTeleportFuture(sourceApi, "player", nextAction,
                            player.teleportAsync(location, safeTeleportCause(cause)));
                    return true;
                });
    }

    public static void playerSetGameMode(Player player, GameMode gameMode) {
        guardedVoid("Player#setGameMode", "player", "entity-scheduler-mutation",
                playerDetail(player) + " mode=" + gameMode,
                () -> player.setGameMode(gameMode));
    }

    public static void humanSetGameMode(HumanEntity human, GameMode gameMode) {
        guardedVoid("HumanEntity#setGameMode", "player", "entity-scheduler-mutation",
                humanDetail(human) + " mode=" + gameMode,
                () -> human.setGameMode(gameMode));
    }

    public static void playerSetVelocity(Player player, Vector velocity) {
        guardedVoid("Player#setVelocity", "player", "entity-scheduler-mutation", playerDetail(player),
                () -> player.setVelocity(velocity));
    }

    public static void playerPlaySound(Player player, Location location, Sound sound, float volume, float pitch) {
        guardedVoid("Player#playSound(Location,Sound,float,float)", "player", "entity-scheduler-audio",
                playerDetail(player) + " location=" + locationDetail(location) + " sound=" + sound,
                () -> player.playSound(location, sound, volume, pitch));
    }

    public static void playerPlaySound(Player player, Location location, String sound, float volume, float pitch) {
        guardedVoid("Player#playSound(Location,String,float,float)", "player", "entity-scheduler-audio",
                playerDetail(player) + " location=" + locationDetail(location) + " sound=" + sound,
                () -> player.playSound(location, sound, volume, pitch));
    }

    public static void playerPlaySound(Player player, Location location, Sound sound, SoundCategory category,
                                       float volume, float pitch) {
        guardedVoid("Player#playSound(Location,Sound,SoundCategory,float,float)", "player",
                "entity-scheduler-audio",
                playerDetail(player) + " location=" + locationDetail(location) + " sound=" + sound
                        + " category=" + category,
                () -> player.playSound(location, sound, category, volume, pitch));
    }

    public static void playerPlaySound(Player player, Location location, String sound, SoundCategory category,
                                       float volume, float pitch) {
        guardedVoid("Player#playSound(Location,String,SoundCategory,float,float)", "player",
                "entity-scheduler-audio",
                playerDetail(player) + " location=" + locationDetail(location) + " sound=" + sound
                        + " category=" + category,
                () -> player.playSound(location, sound, category, volume, pitch));
    }

    public static InventoryView playerOpenInventory(Player player, Inventory inventory) {
        String sourceApi = "Player#openInventory";
        String family = "player";
        String nextAction = "entity-scheduler-ui";
        String detail = playerDetail(player) + " inventory=" + inventoryDetail(inventory);
        return preemptiveEntityOnFolia(sourceApi, family, nextAction, detail, player,
                () -> player.openInventory(inventory),
                () -> callEntityScheduler(sourceApi, family, nextAction, detail, player,
                        () -> player.openInventory(inventory)));
    }

    public static void playerCloseInventory(Player player) {
        String sourceApi = "Player#closeInventory";
        String family = "player";
        String nextAction = "entity-scheduler-ui";
        String detail = playerDetail(player);
        preemptiveEntityOnFolia(sourceApi, family, nextAction, detail, player,
                () -> {
                    player.closeInventory();
                    return null;
                },
                () -> callEntityScheduler(sourceApi, family, nextAction, detail, player,
                        () -> {
                            player.closeInventory();
                            return null;
                        }));
    }

    public static InventoryView humanOpenInventory(HumanEntity human, Inventory inventory) {
        String sourceApi = "HumanEntity#openInventory";
        String family = "player";
        String nextAction = "entity-scheduler-ui";
        String detail = humanDetail(human) + " inventory=" + inventoryDetail(inventory);
        Entity owner = (Entity) human;
        return preemptiveEntityOnFolia(sourceApi, family, nextAction, detail, owner,
                () -> human.openInventory(inventory),
                () -> callEntityScheduler(sourceApi, family, nextAction, detail, owner,
                        () -> human.openInventory(inventory)));
    }

    public static void humanCloseInventory(HumanEntity human) {
        String sourceApi = "HumanEntity#closeInventory";
        String family = "player";
        String nextAction = "entity-scheduler-ui";
        String detail = humanDetail(human);
        Entity owner = (Entity) human;
        preemptiveEntityOnFolia(sourceApi, family, nextAction, detail, owner,
                () -> {
                    human.closeInventory();
                    return null;
                },
                () -> callEntityScheduler(sourceApi, family, nextAction, detail, owner,
                        () -> {
                            human.closeInventory();
                            return null;
                        }));
    }

    public static Scoreboard scoreboardManagerGetNewScoreboard(ScoreboardManager manager) {
        String sourceApi = "ScoreboardManager#getNewScoreboard";
        String family = "scoreboard";
        String nextAction = "scoreboard-detached-model-create";
        String detail = "owner=detached model=scoreboard-shim manager="
                + (manager == null ? "null" : manager.getClass().getName());
        if (!isFolia()) {
            return guarded(sourceApi, family, nextAction, detail + " policy=direct-non-folia",
                    () -> manager.getNewScoreboard());
        }

        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=shim-model action=modeled result=detached-scoreboard");
        return DetachedScoreboardModel.create();
    }

    public static Scoreboard playerGetScoreboard(Player player) {
        if (isFolia()) {
            String sourceApi = "Player#getScoreboard";
            String family = "scoreboard";
            String nextAction = "scoreboard-player-owned-model-read";
            DetachedScoreboardModel detached = playerScoreboardModel(player);
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    playerDetail(player) + " " + detached.detail()
                            + " policy=shim-model owner=player action=modeled result=player-scoreboard");
            return detached.proxy();
        }
        return guarded("Player#getScoreboard", "scoreboard", "scoreboard-player-owned-read",
                playerDetail(player) + " model=player-owned", () -> player.getScoreboard());
    }

    public static void playerSetScoreboard(Player player, Scoreboard scoreboard) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Player#setScoreboard";
            String family = "scoreboard";
            String nextAction = "scoreboard-player-owned-assign";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            rememberPlayerScoreboard(player, detached);
            // Folia hard-disables Bukkit board assignment. For bridge-owned boards we preserve the
            // legacy call by retaining the model as the player's active bridge board; a later packet
            // application layer can render this state without touching CraftScoreboardManager#setPlayerBoard.
            markUnsafeCall(sourceApi, route, family, nextAction,
                    playerDetail(player) + " " + detached.detail()
                            + " policy=shim-model owner=player action=model-retained"
                            + " result=assigned reason=folia-hard-unsupported-player-board-assign");
            return;
        }
        // Folia currently hard-disables CraftScoreboardManager#setPlayerBoard.
        // This wrapper is evidence-only until a packet/model scoreboard route exists.
        guardedVoid("Player#setScoreboard", "scoreboard", "scoreboard-hard-unsupported-player-board-assign",
                playerDetail(player) + " " + scoreboardDetail(scoreboard),
                () -> player.setScoreboard(scoreboard));
    }

    public static Team scoreboardGetTeam(Scoreboard scoreboard, String name) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Scoreboard#getTeam(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-team-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Team team = detached.getTeam(name);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " team=" + safeText(name)
                            + " policy=shim-model action=modeled result="
                            + (team == null ? "null-team" : "team"));
            return team;
        }
        return guarded("Scoreboard#getTeam(String)", "scoreboard", "scoreboard-model-team-read",
                scoreboardDetail(scoreboard) + " team=" + safeText(name),
                () -> scoreboard.getTeam(name));
    }

    public static Team scoreboardRegisterNewTeam(Scoreboard scoreboard, String name) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Scoreboard#registerNewTeam(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-team-create";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Team team = detached.registerNewTeam(name);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " team=" + safeText(name)
                            + " policy=shim-model action=modeled result=team");
            return team;
        }
        // Folia 26.1.x hard-disables Bukkit scoreboard team creation in CraftScoreboard.
        // Keep this routed through the bridge for exact bytecode evidence, but do not
        // imply that a scheduler retry can make this API safe.
        return guarded("Scoreboard#registerNewTeam(String)", "scoreboard", "scoreboard-hard-unsupported-team-create",
                scoreboardDetail(scoreboard) + " team=" + safeText(name),
                () -> scoreboard.registerNewTeam(name));
    }

    public static Objective scoreboardGetObjective(Scoreboard scoreboard, String name) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Scoreboard#getObjective(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Objective objective = detached.getObjective(name);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " objective=" + safeText(name)
                            + " policy=shim-model action=modeled result="
                            + (objective == null ? "null-objective" : "objective"));
            return objective;
        }
        return guarded("Scoreboard#getObjective(String)", "scoreboard", "scoreboard-model-objective-read",
                scoreboardDetail(scoreboard) + " objective=" + safeText(name),
                () -> scoreboard.getObjective(name));
    }

    public static Objective scoreboardGetObjectiveForDisplaySlot(Scoreboard scoreboard, DisplaySlot slot) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Scoreboard#getObjective(DisplaySlot)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Objective objective = detached.getObjective(slot);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " slot=" + slot
                            + " policy=shim-model action=modeled result="
                            + (objective == null ? "null-objective" : "objective"));
            return objective;
        }
        return guarded("Scoreboard#getObjective(DisplaySlot)", "scoreboard", "scoreboard-model-objective-read",
                scoreboardDetail(scoreboard) + " slot=" + slot,
                () -> scoreboard.getObjective(slot));
    }

    public static Set<Objective> scoreboardGetObjectives(Scoreboard scoreboard) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String sourceApi = "Scoreboard#getObjectives";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Set<Objective> objectives = detached.getObjectives();
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " policy=shim-model action=modeled result=objectives count="
                            + objectives.size());
            return objectives;
        }
        return guarded("Scoreboard#getObjectives", "scoreboard", "scoreboard-model-objective-read",
                scoreboardDetail(scoreboard), scoreboard::getObjectives);
    }

    public static Objective scoreboardRegisterNewObjectiveLegacy(Scoreboard scoreboard, String name, String criteria) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteria, null, null,
                "Scoreboard#registerNewObjective(String,String)",
                () -> scoreboard.registerNewObjective(name, criteria));
    }

    public static Objective scoreboardRegisterNewObjectiveLegacyDisplayName(Scoreboard scoreboard, String name,
                                                                           String criteria, String displayName) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteria, displayName, null,
                "Scoreboard#registerNewObjective(String,String,String)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName));
    }

    public static Objective scoreboardRegisterNewObjectiveComponent(Scoreboard scoreboard, String name,
                                                                    String criteria, Component displayName) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteria, componentLabel(displayName), null,
                "Scoreboard#registerNewObjective(String,String,Component)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName));
    }

    public static Objective scoreboardRegisterNewObjectiveComponentRender(Scoreboard scoreboard, String name,
                                                                          String criteria, Component displayName,
                                                                          RenderType renderType) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteria, componentLabel(displayName), renderType,
                "Scoreboard#registerNewObjective(String,String,Component,RenderType)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName, renderType));
    }

    public static Objective scoreboardRegisterNewObjectiveCriteriaComponent(Scoreboard scoreboard, String name,
                                                                           Criteria criteria, Component displayName) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteriaLabel(criteria), componentLabel(displayName),
                renderType(criteria), "Scoreboard#registerNewObjective(String,Criteria,Component)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName));
    }

    public static Objective scoreboardRegisterNewObjectiveCriteriaComponentRender(Scoreboard scoreboard, String name,
                                                                                 Criteria criteria,
                                                                                 Component displayName,
                                                                                 RenderType renderType) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteriaLabel(criteria), componentLabel(displayName),
                renderType, "Scoreboard#registerNewObjective(String,Criteria,Component,RenderType)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName, renderType));
    }

    public static Objective scoreboardRegisterNewObjectiveCriteriaDisplayName(Scoreboard scoreboard, String name,
                                                                             Criteria criteria, String displayName) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteriaLabel(criteria), displayName,
                renderType(criteria), "Scoreboard#registerNewObjective(String,Criteria,String)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName));
    }

    public static Objective scoreboardRegisterNewObjectiveCriteriaDisplayNameRender(Scoreboard scoreboard, String name,
                                                                                   Criteria criteria,
                                                                                   String displayName,
                                                                                   RenderType renderType) {
        return scoreboardRegisterNewObjectiveModel(scoreboard, name, criteriaLabel(criteria), displayName, renderType,
                "Scoreboard#registerNewObjective(String,Criteria,String,RenderType)",
                () -> scoreboard.registerNewObjective(name, criteria, displayName, renderType));
    }

    public static void objectiveSetDisplaySlot(Objective objective, DisplaySlot slot) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            detached.setDisplaySlot(slot);
            String sourceApi = "Objective#setDisplaySlot(DisplaySlot)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " slot=" + slot
                            + " policy=shim-model action=modeled result=display-slot-set");
            return;
        }
        guardedVoid("Objective#setDisplaySlot(DisplaySlot)", "scoreboard", "scoreboard-model-objective-mutation",
                objectiveDetail(objective) + " slot=" + slot,
                () -> objective.setDisplaySlot(slot));
    }

    public static Score objectiveGetScore(Objective objective, String entry) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            String sourceApi = "Objective#getScore(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-score-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Score score = detached.getScore(entry);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " entry=" + safeText(entry)
                            + " policy=shim-model action=modeled result=score");
            return score;
        }
        return guarded("Objective#getScore(String)", "scoreboard", "scoreboard-model-score-read",
                objectiveDetail(objective) + " entry=" + safeText(entry),
                () -> objective.getScore(entry));
    }

    public static Score objectiveGetScoreOfflinePlayer(Objective objective, OfflinePlayer player) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            String entry = offlinePlayerLabel(player);
            String sourceApi = "Objective#getScore(OfflinePlayer)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-score-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Score score = detached.getScore(entry);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " entry=" + safeText(entry)
                            + " policy=shim-model action=modeled result=score");
            return score;
        }
        return guarded("Objective#getScore(OfflinePlayer)", "scoreboard", "scoreboard-model-score-read",
                objectiveDetail(objective) + " player=" + offlinePlayerLabel(player),
                () -> objective.getScore(player));
    }

    public static void objectiveSetDisplayName(Objective objective, String displayName) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            detached.setDisplayName(displayName);
            String sourceApi = "Objective#setDisplayName(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " displayName=" + safeText(displayName)
                            + " policy=shim-model action=modeled result=display-name-set");
            return;
        }
        guardedVoid("Objective#setDisplayName(String)", "scoreboard", "scoreboard-model-objective-mutation",
                objectiveDetail(objective) + " displayName=" + safeText(displayName),
                () -> objective.setDisplayName(displayName));
    }

    public static void scoreSetScore(Score score, int value) {
        DetachedScoreModel detached = detachedScoreModel(score);
        if (detached != null) {
            detached.setScore(value);
            String sourceApi = "Score#setScore(int)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-score-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " value=" + value
                            + " policy=shim-model action=modeled result=score-set");
            return;
        }
        guardedVoid("Score#setScore(int)", "scoreboard", "scoreboard-model-score-mutation",
                scoreDetail(score) + " value=" + value,
                () -> score.setScore(value));
    }

    public static int scoreGetScore(Score score) {
        DetachedScoreModel detached = detachedScoreModel(score);
        if (detached != null) {
            int value = detached.getScore();
            String sourceApi = "Score#getScore";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-score-read";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " policy=shim-model action=modeled result=score value=" + value);
            return value;
        }
        return guarded("Score#getScore", "scoreboard", "scoreboard-model-score-read",
                scoreDetail(score), score::getScore);
    }

    public static void scoreResetScore(Score score) {
        DetachedScoreModel detached = detachedScoreModel(score);
        if (detached != null) {
            detached.resetScore();
            String sourceApi = "Score#resetScore";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-score-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " policy=shim-model action=modeled result=score-reset");
            return;
        }
        guardedVoid("Score#resetScore", "scoreboard", "scoreboard-model-score-mutation",
                scoreDetail(score), score::resetScore);
    }

    public static void objectiveDisplayName(Objective objective, Component displayName) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            detached.displayName(displayName);
            markScoreboardModel("Objective#displayName(Component)", "scoreboard-model-objective-mutation",
                    detached.detail() + " displayName=" + componentLabel(displayName)
                            + " policy=shim-model action=modeled result=display-name-component-set");
            return;
        }
        guardedVoid("Objective#displayName(Component)", "scoreboard", "scoreboard-model-objective-mutation",
                objectiveDetail(objective) + " displayName=" + componentLabel(displayName),
                () -> objective.displayName(displayName));
    }

    public static void objectiveNumberFormat(Objective objective, NumberFormat format) {
        DetachedObjectiveModel detached = detachedObjectiveModel(objective);
        if (detached != null) {
            detached.numberFormat(format);
            markScoreboardModel("Objective#numberFormat(NumberFormat)", "scoreboard-model-objective-mutation",
                    detached.detail() + " numberFormat=" + numberFormatLabel(format)
                            + " policy=shim-model action=modeled result=number-format-set");
            return;
        }
        guardedVoid("Objective#numberFormat(NumberFormat)", "scoreboard", "scoreboard-model-objective-mutation",
                objectiveDetail(objective) + " numberFormat=" + numberFormatLabel(format),
                () -> objective.numberFormat(format));
    }

    public static void scoreCustomName(Score score, Component customName) {
        DetachedScoreModel detached = detachedScoreModel(score);
        if (detached != null) {
            detached.customName(customName);
            markScoreboardModel("Score#customName(Component)", "scoreboard-model-score-mutation",
                    detached.detail() + " customName=" + componentLabel(customName)
                            + " policy=shim-model action=modeled result=custom-name-set");
            return;
        }
        guardedVoid("Score#customName(Component)", "scoreboard", "scoreboard-model-score-mutation",
                scoreDetail(score) + " customName=" + componentLabel(customName),
                () -> score.customName(customName));
    }

    public static void scoreNumberFormat(Score score, NumberFormat format) {
        DetachedScoreModel detached = detachedScoreModel(score);
        if (detached != null) {
            detached.numberFormat(format);
            markScoreboardModel("Score#numberFormat(NumberFormat)", "scoreboard-model-score-mutation",
                    detached.detail() + " numberFormat=" + numberFormatLabel(format)
                            + " policy=shim-model action=modeled result=number-format-set");
            return;
        }
        guardedVoid("Score#numberFormat(NumberFormat)", "scoreboard", "scoreboard-model-score-mutation",
                scoreDetail(score) + " numberFormat=" + numberFormatLabel(format),
                () -> score.numberFormat(format));
    }

    public static void teamSetDisplayName(Team team, String displayName) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setDisplayName(displayName);
            markScoreboardModel("Team#setDisplayName(String)", "scoreboard-model-team-mutation",
                    detached.detail() + " displayName=" + safeText(displayName)
                            + " policy=shim-model action=modeled result=display-name-set");
            return;
        }
        guardedVoid("Team#setDisplayName(String)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " displayName=" + safeText(displayName), () -> team.setDisplayName(displayName));
    }

    public static void teamDisplayName(Team team, Component displayName) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.displayName(displayName);
            markScoreboardModel("Team#displayName(Component)", "scoreboard-model-team-mutation",
                    detached.detail() + " displayName=" + componentLabel(displayName)
                            + " policy=shim-model action=modeled result=display-name-component-set");
            return;
        }
        guardedVoid("Team#displayName(Component)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " displayName=" + componentLabel(displayName), () -> team.displayName(displayName));
    }

    public static void teamSetPrefix(Team team, String prefix) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setPrefix(prefix);
            markScoreboardModel("Team#setPrefix(String)", "scoreboard-model-team-mutation",
                    detached.detail() + " prefix=" + safeText(prefix)
                            + " policy=shim-model action=modeled result=prefix-set");
            return;
        }
        guardedVoid("Team#setPrefix(String)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " prefix=" + safeText(prefix), () -> team.setPrefix(prefix));
    }

    public static void teamPrefix(Team team, Component prefix) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.prefix(prefix);
            markScoreboardModel("Team#prefix(Component)", "scoreboard-model-team-mutation",
                    detached.detail() + " prefix=" + componentLabel(prefix)
                            + " policy=shim-model action=modeled result=prefix-component-set");
            return;
        }
        guardedVoid("Team#prefix(Component)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " prefix=" + componentLabel(prefix), () -> team.prefix(prefix));
    }

    public static void teamSetSuffix(Team team, String suffix) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setSuffix(suffix);
            markScoreboardModel("Team#setSuffix(String)", "scoreboard-model-team-mutation",
                    detached.detail() + " suffix=" + safeText(suffix)
                            + " policy=shim-model action=modeled result=suffix-set");
            return;
        }
        guardedVoid("Team#setSuffix(String)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " suffix=" + safeText(suffix), () -> team.setSuffix(suffix));
    }

    public static void teamSuffix(Team team, Component suffix) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.suffix(suffix);
            markScoreboardModel("Team#suffix(Component)", "scoreboard-model-team-mutation",
                    detached.detail() + " suffix=" + componentLabel(suffix)
                            + " policy=shim-model action=modeled result=suffix-component-set");
            return;
        }
        guardedVoid("Team#suffix(Component)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " suffix=" + componentLabel(suffix), () -> team.suffix(suffix));
    }

    public static void teamSetColor(Team team, ChatColor color) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setColor(color);
            markScoreboardModel("Team#setColor(ChatColor)", "scoreboard-model-team-mutation",
                    detached.detail() + " color=" + color
                            + " policy=shim-model action=modeled result=color-set");
            return;
        }
        guardedVoid("Team#setColor(ChatColor)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " color=" + color, () -> team.setColor(color));
    }

    public static void teamColor(Team team, NamedTextColor color) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.color(color);
            markScoreboardModel("Team#color(NamedTextColor)", "scoreboard-model-team-mutation",
                    detached.detail() + " color=" + color
                            + " policy=shim-model action=modeled result=named-color-set");
            return;
        }
        guardedVoid("Team#color(NamedTextColor)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " color=" + color, () -> team.color(color));
    }

    public static void teamSetAllowFriendlyFire(Team team, boolean value) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setAllowFriendlyFire(value);
            markScoreboardModel("Team#setAllowFriendlyFire(boolean)", "scoreboard-model-team-mutation",
                    detached.detail() + " value=" + value
                            + " policy=shim-model action=modeled result=friendly-fire-set");
            return;
        }
        guardedVoid("Team#setAllowFriendlyFire(boolean)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " value=" + value, () -> team.setAllowFriendlyFire(value));
    }

    public static void teamSetCanSeeFriendlyInvisibles(Team team, boolean value) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setCanSeeFriendlyInvisibles(value);
            markScoreboardModel("Team#setCanSeeFriendlyInvisibles(boolean)", "scoreboard-model-team-mutation",
                    detached.detail() + " value=" + value
                            + " policy=shim-model action=modeled result=friendly-invisibles-set");
            return;
        }
        guardedVoid("Team#setCanSeeFriendlyInvisibles(boolean)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " value=" + value, () -> team.setCanSeeFriendlyInvisibles(value));
    }

    public static void teamSetOption(Team team, Team.Option option, Team.OptionStatus status) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.setOption(option, status);
            String sourceApi = "Team#setOption(Option,OptionStatus)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-team-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " option=" + option + " status=" + status
                            + " policy=shim-model action=modeled result=option-set");
            return;
        }
        guardedVoid("Team#setOption(Option,OptionStatus)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " option=" + option + " status=" + status,
                () -> team.setOption(option, status));
    }

    public static void teamAddEntry(Team team, String entry) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            detached.addEntry(entry);
            String sourceApi = "Team#addEntry(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-team-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " entry=" + safeText(entry)
                            + " policy=shim-model action=modeled result=entry-added");
            return;
        }
        guardedVoid("Team#addEntry(String)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " entry=" + safeText(entry),
                () -> team.addEntry(entry));
    }

    public static boolean teamRemoveEntry(Team team, String entry) {
        DetachedTeamModel detached = detachedTeamModel(team);
        if (detached != null) {
            boolean removed = detached.removeEntry(entry);
            String sourceApi = "Team#removeEntry(String)";
            String family = "scoreboard";
            String nextAction = "scoreboard-model-team-mutation";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " entry=" + safeText(entry)
                            + " policy=shim-model action=modeled result=" + removed);
            return removed;
        }
        return guarded("Team#removeEntry(String)", "scoreboard", "scoreboard-model-team-mutation",
                teamDetail(team) + " entry=" + safeText(entry),
                () -> team.removeEntry(entry));
    }

    public static boolean playerAddPotionEffect(Player player, PotionEffect effect) {
        String sourceApi = "Player#addPotionEffect";
        String family = "player";
        String nextAction = "entity-scheduler-mutation";
        String detail = playerDetail(player) + " effect=" + potionEffectDetail(effect);
        return preemptiveEntityAcceptedBooleanOnFolia(sourceApi, family, nextAction, detail, player,
                () -> player.addPotionEffect(effect),
                () -> scheduleEntityVoid(sourceApi, family, nextAction, detail, player,
                        () -> player.addPotionEffect(effect)));
    }

    public static void playerRemovePotionEffect(Player player, PotionEffectType type) {
        String sourceApi = "Player#removePotionEffect";
        String family = "player";
        String nextAction = "entity-scheduler-mutation";
        String detail = playerDetail(player) + " effect=" + type;
        preemptiveEntityVoidOnFolia(sourceApi, family, nextAction, detail, player,
                () -> player.removePotionEffect(type),
                () -> scheduleEntityVoid(sourceApi, family, nextAction, detail, player,
                        () -> player.removePotionEffect(type)));
    }

    public static boolean livingAddPotionEffect(LivingEntity entity, PotionEffect effect) {
        String sourceApi = "LivingEntity#addPotionEffect";
        String family = "entity";
        String nextAction = "entity-scheduler-mutation";
        String detail = livingDetail(entity) + " effect=" + potionEffectDetail(effect);
        return preemptiveEntityAcceptedBooleanOnFolia(sourceApi, family, nextAction, detail, entity,
                () -> entity.addPotionEffect(effect),
                () -> scheduleEntityVoid(sourceApi, family, nextAction, detail, entity,
                        () -> entity.addPotionEffect(effect)));
    }

    public static void livingRemovePotionEffect(LivingEntity entity, PotionEffectType type) {
        String sourceApi = "LivingEntity#removePotionEffect";
        String family = "entity";
        String nextAction = "entity-scheduler-mutation";
        String detail = livingDetail(entity) + " effect=" + type;
        preemptiveEntityVoidOnFolia(sourceApi, family, nextAction, detail, entity,
                () -> entity.removePotionEffect(type),
                () -> scheduleEntityVoid(sourceApi, family, nextAction, detail, entity,
                        () -> entity.removePotionEffect(type)));
    }

    public static void playerHidePlayer(Player viewer, Plugin plugin, Player hidden) {
        guardedVoid("Player#hidePlayer(Plugin,Player)", "player", "entity-scheduler-visibility",
                playerDetail(viewer) + " hidden=" + playerDetail(hidden),
                () -> viewer.hidePlayer(plugin, hidden));
    }

    public static void playerShowPlayer(Player viewer, Plugin plugin, Player hidden) {
        guardedVoid("Player#showPlayer(Plugin,Player)", "player", "entity-scheduler-visibility",
                playerDetail(viewer) + " hidden=" + playerDetail(hidden),
                () -> viewer.showPlayer(plugin, hidden));
    }

    @SuppressWarnings("deprecation")
    public static void playerHidePlayerLegacy(Player viewer, Player hidden) {
        guardedVoid("Player#hidePlayer(Player)", "player", "entity-scheduler-visibility",
                playerDetail(viewer) + " hidden=" + playerDetail(hidden),
                () -> viewer.hidePlayer(hidden));
    }

    @SuppressWarnings("deprecation")
    public static void playerShowPlayerLegacy(Player viewer, Player hidden) {
        guardedVoid("Player#showPlayer(Player)", "player", "entity-scheduler-visibility",
                playerDetail(viewer) + " hidden=" + playerDetail(hidden),
                () -> viewer.showPlayer(hidden));
    }

    public static Block worldGetBlockAt(World world, int x, int y, int z) {
        return guarded("World#getBlockAt(int,int,int)", "region", "region-scheduler-by-block",
                worldDetail(world) + " x=" + x + " y=" + y + " z=" + z,
                () -> world.getBlockAt(x, y, z));
    }

    public static Block worldGetBlockAt(World world, Location location) {
        return guarded("World#getBlockAt(Location)", "region", "region-scheduler-by-location",
                worldDetail(world) + " location=" + locationDetail(location),
                () -> world.getBlockAt(location));
    }

    public static List<Entity> worldGetEntities(World world) {
        String sourceApi = "World#getEntities";
        String family = "world-scan";
        String nextAction = "split-scan-by-loaded-chunks";
        String detail = worldDetail(world) + " model=split-by-loaded-chunks";
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                () -> world.getEntities(),
                () -> splitWorldEntities(sourceApi, detail, world));
    }

    public static Chunk[] worldGetLoadedChunks(World world) {
        String sourceApi = "World#getLoadedChunks";
        String family = "world-scan";
        String nextAction = "world-loaded-chunk-index";
        String detail = worldDetail(world) + " model=loaded-chunk-index";
        return guardedWithFallback(sourceApi, family, nextAction, detail,
                () -> world.getLoadedChunks(),
                () -> callGlobalScheduler(sourceApi, family, nextAction,
                        detail + " fallback=global-scheduler-loaded-chunk-index",
                        () -> world.getLoadedChunks()));
    }

    public static Entity[] chunkGetEntities(Chunk chunk) {
        String sourceApi = "Chunk#getEntities";
        String family = "chunk-scan";
        String nextAction = "region-scheduler-by-chunk";
        String detail = chunkDetail(chunk);
        return guardedWithFallback(sourceApi, family, nextAction, detail,
                () -> chunk.getEntities(),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail,
                        chunk.getWorld(), chunk.getX(), chunk.getZ(),
                        () -> chunk.getEntities()));
    }

    public static Collection<Entity> worldGetNearbyEntities(World world, Location location,
                                                            double x, double y, double z) {
        String sourceApi = "World#getNearbyEntities(Location,double,double,double)";
        String family = "world-scan";
        String nextAction = "region-scheduler-by-location-bounded-scan";
        String detail = worldDetail(world) + " location=" + locationDetail(location)
                + " x=" + x + " y=" + y + " z=" + z
                + " model=bounded-split-by-loaded-chunks";
        // Bounded scans can cross chunk/region ownership even with a center Location. On Folia, prefer
        // the chunk split immediately so the direct CraftWorld guard is not the first thing plugins see.
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                () -> world.getNearbyEntities(location, x, y, z),
                () -> splitNearbyEntities(sourceApi, detail, world, location, x, y, z));
    }

    public static Collection<Entity> worldGetEntitiesByClasses(World world, Class<?>... classes) {
        String sourceApi = "World#getEntitiesByClasses";
        String family = "world-scan";
        String nextAction = "split-scan-by-loaded-chunks";
        String detail = worldDetail(world) + " classes=" + classList(classes)
                + " model=split-by-loaded-chunks";
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                () -> world.getEntitiesByClasses(classes),
                () -> filterEntities(splitWorldEntities(sourceApi, detail, world), classes));
    }

    @SafeVarargs
    public static <T extends Entity> Collection<T> worldGetEntitiesByClass(World world, Class<T>... classes) {
        String sourceApi = "World#getEntitiesByClass(Class...)";
        String family = "world-scan";
        String nextAction = "split-scan-by-loaded-chunks";
        String detail = worldDetail(world) + " classes=" + classList(classes)
                + " model=split-by-loaded-chunks";
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                () -> world.getEntitiesByClass(classes),
                () -> filterTypedEntities(splitWorldEntities(sourceApi, detail, world), classes));
    }

    public static <T extends Entity> Collection<T> worldGetEntitiesByClass(World world, Class<T> clazz) {
        String sourceApi = "World#getEntitiesByClass(Class)";
        String family = "world-scan";
        String nextAction = "split-scan-by-loaded-chunks";
        String detail = worldDetail(world) + " class=" + className(clazz)
                + " model=split-by-loaded-chunks";
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                () -> world.getEntitiesByClass(clazz),
                () -> filterTypedEntities(splitWorldEntities(sourceApi, detail, world), clazz));
    }

    public static Chunk worldGetChunkAt(World world, int x, int z) {
        String sourceApi = "World#getChunkAt(int,int)";
        String family = "region";
        String nextAction = "region-scheduler-by-chunk";
        String detail = worldDetail(world) + " x=" + x + " z=" + z;
        return preemptiveChunkOnFolia(sourceApi, family, nextAction, detail, world, x, z,
                () -> isOwnedByCurrentRegion(world, x, z),
                () -> world.getChunkAt(x, z),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, world, x, z,
                        () -> world.getChunkAt(x, z)));
    }

    public static Chunk worldGetChunkAt(World world, Location location) {
        String sourceApi = "World#getChunkAt(Location)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location);
        return preemptiveChunkOnFolia(sourceApi, family, nextAction, detail, world,
                chunkX(location), chunkZ(location),
                () -> isOwnedByCurrentRegion(location),
                () -> world.getChunkAt(location),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, location,
                        () -> world.getChunkAt(location)));
    }

    public static Chunk worldGetChunkAt(World world, Block block) {
        String sourceApi = "World#getChunkAt(Block)";
        String family = "region";
        String nextAction = "region-scheduler-by-block";
        String detail = worldDetail(world) + " " + blockDetail(block);
        return preemptiveChunkOnFolia(sourceApi, family, nextAction, detail, world,
                chunkX(block), chunkZ(block),
                () -> isOwnedByCurrentRegion(block),
                () -> world.getChunkAt(block),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail,
                        block.getWorld(), block.getX() >> 4, block.getZ() >> 4,
                        () -> world.getChunkAt(block)));
    }

    public static void worldLoadChunk(World world, int x, int z) {
        String sourceApi = "World#loadChunk(int,int)";
        String family = "region";
        String nextAction = "region-scheduler-by-chunk";
        String detail = worldDetail(world) + " x=" + x + " z=" + z;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(world, x, z),
                () -> {
                    world.loadChunk(x, z);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, world, x, z,
                        () -> world.loadChunk(x, z)));
    }

    public static boolean worldRefreshChunk(World world, int x, int z) {
        String sourceApi = "World#refreshChunk(int,int)";
        String family = "region";
        String nextAction = "region-scheduler-by-chunk";
        String detail = worldDetail(world) + " x=" + x + " z=" + z;
        return preemptiveRegionAcceptedBooleanOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(world, x, z),
                () -> world.refreshChunk(x, z),
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, world, x, z,
                        () -> world.refreshChunk(x, z)));
    }

    public static Item worldDropItem(World world, Location location, ItemStack item) {
        String sourceApi = "World#dropItem(Location,ItemStack)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " item=" + itemDetail(item);
        return preemptiveRegionOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.dropItem(location, item),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, location,
                        () -> world.dropItem(location, item)));
    }

    public static Item worldDropItemNaturally(World world, Location location, ItemStack item) {
        String sourceApi = "World#dropItemNaturally(Location,ItemStack)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " item=" + itemDetail(item);
        return preemptiveRegionOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.dropItemNaturally(location, item),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, location,
                        () -> world.dropItemNaturally(location, item)));
    }

    public static Entity worldSpawnEntity(World world, Location location, EntityType type) {
        String sourceApi = "World#spawnEntity(Location,EntityType)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " type=" + type;
        return preemptiveRegionOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.spawnEntity(location, type),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, location,
                        () -> world.spawnEntity(location, type)));
    }

    public static boolean worldGenerateTree(World world, Location location, TreeType type) {
        String sourceApi = "World#generateTree(Location,TreeType)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " tree=" + type;
        return preemptiveRegionOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.generateTree(location, type),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, location,
                        () -> world.generateTree(location, type)));
    }

    public static LightningStrike worldStrikeLightning(World world, Location location) {
        String sourceApi = "World#strikeLightning(Location)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location);
        return preemptiveLightningOnFolia(sourceApi, family, nextAction, detail, world, location,
                () -> world.strikeLightning(location));
    }

    public static LightningStrike worldStrikeLightningEffect(World world, Location location) {
        String sourceApi = "World#strikeLightningEffect(Location)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location);
        return preemptiveLightningOnFolia(sourceApi, family, nextAction, detail, world, location,
                () -> world.strikeLightningEffect(location));
    }

    public static boolean worldCreateExplosion(World world, Location location, float power) {
        String sourceApi = "World#createExplosion(Location,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " power=" + power;
        return preemptiveRegionAcceptedBooleanOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.createExplosion(location, power),
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.createExplosion(location, power)));
    }

    public static boolean worldCreateExplosion(World world, double x, double y, double z, float power) {
        Location location = new Location(world, x, y, z);
        String sourceApi = "World#createExplosion(double,double,double,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " power=" + power;
        return preemptiveRegionAcceptedBooleanOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> world.createExplosion(x, y, z, power),
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.createExplosion(x, y, z, power)));
    }

    public static void worldPlaySound(World world, Location location, Sound sound, float volume, float pitch) {
        String sourceApi = "World#playSound(Location,Sound,float,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " sound=" + sound;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> {
                    world.playSound(location, sound, volume, pitch);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.playSound(location, sound, volume, pitch)));
    }

    public static void worldPlaySound(World world, Location location, String sound, float volume, float pitch) {
        String sourceApi = "World#playSound(Location,String,float,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location) + " sound=" + sound;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> {
                    world.playSound(location, sound, volume, pitch);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.playSound(location, sound, volume, pitch)));
    }

    public static void worldPlaySound(World world, Location location, Sound sound, SoundCategory category,
                                      float volume, float pitch) {
        String sourceApi = "World#playSound(Location,Sound,SoundCategory,float,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location)
                + " sound=" + sound + " category=" + category;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> {
                    world.playSound(location, sound, category, volume, pitch);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.playSound(location, sound, category, volume, pitch)));
    }

    public static void worldPlaySound(World world, Location location, String sound, SoundCategory category,
                                      float volume, float pitch) {
        String sourceApi = "World#playSound(Location,String,SoundCategory,float,float)";
        String family = "region";
        String nextAction = "region-scheduler-by-location";
        String detail = worldDetail(world) + " location=" + locationDetail(location)
                + " sound=" + sound + " category=" + category;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(location),
                () -> {
                    world.playSound(location, sound, category, volume, pitch);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, location,
                        () -> world.playSound(location, sound, category, volume, pitch)));
    }

    public static void blockSetType(Block block, Material material) {
        String sourceApi = "Block#setType(Material)";
        String family = "region";
        String nextAction = "region-scheduler-by-block";
        String detail = blockDetail(block) + " material=" + material;
        preemptiveRegionVoidOnFolia(sourceApi, family, nextAction, detail,
                () -> isOwnedByCurrentRegion(block),
                () -> {
                    setBlockTypeAndRemember(block, material);
                },
                () -> scheduleRegionVoid(sourceApi, family, nextAction, detail, block.getLocation(),
                        () -> setBlockTypeAndRemember(block, material)));
    }

    public static Material blockGetType(Block block) {
        String sourceApi = "Block#getType";
        String family = "region";
        String nextAction = "region-scheduler-by-block";
        String detail = blockDetail(block);
        return preemptiveBlockMaterialOnFolia(sourceApi, family, nextAction, detail, block,
                () -> isOwnedByCurrentRegion(block),
                () -> readBlockTypeAndRemember(block),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, block.getLocation(),
                        () -> readBlockTypeAndRemember(block)));
    }

    public static BlockData blockGetBlockData(Block block) {
        String sourceApi = "Block#getBlockData";
        String family = "region";
        String nextAction = "region-scheduler-by-block";
        String detail = blockDetail(block);
        return preemptiveBlockDataOnFolia(sourceApi, family, nextAction, detail, block,
                () -> isOwnedByCurrentRegion(block),
                () -> readBlockDataAndRemember(block),
                () -> callRegionScheduler(sourceApi, family, nextAction, detail, block.getLocation(),
                        () -> readBlockDataAndRemember(block)));
    }

    public static Location blockGetLocation(Block block) {
        return guarded("Block#getLocation", "region", "region-scheduler-by-block", blockDetail(block),
                () -> block.getLocation());
    }

    public static int unsafeCallCount() {
        return UNSAFE_CALLS.get();
    }

    public static void resetUnsafeCallCount() {
        UNSAFE_CALLS.set(0);
    }

    private static boolean dispatchCommand(String sourceApi, Server server, CommandSender sender, String command) {
        String family = "global";
        String nextAction = "global-or-entity-command-dispatch";
        String detail = senderDetail(sender)
                + " " + commandDetail(command)
                + " scheduler=" + commandDispatchScheduler(sender)
                + " return=scheduled-true";
        return guarded(sourceApi, family, nextAction, detail, () -> {
            if (!isFolia()) {
                return directDispatchCommand(server, sender, command);
            }

            Plugin owner = bridgePlugin();
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            String scheduledFrom = BridgeDiagnostics.captureCaller();
            Runnable action = () -> {
                try {
                    directDispatchCommand(server, sender, command);
                } catch (Throwable throwable) {
                    BridgeDiagnostics.taskFailure(owner, route, scheduledFrom, throwable);
                    BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                    throw rethrow(throwable);
                }
            };
            scheduleCommandDispatch(sourceApi, route, family, nextAction, owner, sender, action);
            // A Folia scheduler hop cannot preserve Bukkit's synchronous boolean command result.
            // Returning true means "accepted for dispatch"; task failures still log loudly.
            return true;
        });
    }

    private static boolean directDispatchCommand(Server server, CommandSender sender, String command) {
        if (server != null) {
            return server.dispatchCommand(sender, command);
        }
        return Bukkit.dispatchCommand(sender, command);
    }

    private static void scheduleCommandDispatch(String sourceApi, RouteFamily route, String family, String nextAction,
                                                Plugin owner, CommandSender sender, Runnable action) {
        if (sender instanceof Entity) {
            Entity entity = (Entity) sender;
            Runnable retired = () -> BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction,
                    new IllegalStateException("entity scheduler retired before command dispatch"));
            Object scheduler = entityScheduler(entity);
            Consumer<Object> task = ignored -> action.run();
            invokeScheduler(scheduler, "run",
                    new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                    owner, task, retired);
            return;
        }

        Object scheduler = scheduler("getGlobalRegionScheduler");
        Consumer<Object> task = ignored -> action.run();
        invokeScheduler(scheduler, "run",
                new Class<?>[]{Plugin.class, Consumer.class},
                owner, task);
    }

    private static List<Entity> splitWorldEntities(String sourceApi, String detail, World world) throws Exception {
        String family = "world-scan";
        String nextAction = "split-scan-by-loaded-chunks";
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        Chunk[] chunks = loadedChunksForSplit(sourceApi, detail, world);
        if (chunks == null) {
            chunks = new Chunk[0];
        }
        List<Entity> entities = new ArrayList<>();

        // Experimental translation path for bytecode that only says "scan the world".
        // The bridge cannot infer the plugin's intended region, so it decomposes the
        // scan into chunk-owned reads and keeps every failed chunk visible in logs.
        for (Chunk chunk : chunks) {
            if (chunk == null) continue;
            Entity[] chunkEntities = chunkEntitiesForSplit(sourceApi, detail, chunk);
            if (chunkEntities == null) continue;
            for (Entity entity : chunkEntities) {
                if (entity != null) {
                    entities.add(entity);
                }
            }
        }

        BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                detail + " fallback=split-scan-complete chunks=" + chunks.length
                        + " resultSize=" + entities.size());
        return entities;
    }

    private static Collection<Entity> splitNearbyEntities(String sourceApi, String detail, World world,
                                                          Location center, double x, double y, double z) throws Exception {
        String family = "world-scan";
        String nextAction = "region-scheduler-by-location-bounded-scan";
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        Chunk[] chunks = loadedChunksForSplit(sourceApi, detail, world);
        if (chunks == null) {
            chunks = new Chunk[0];
        }
        List<Entity> matches = new ArrayList<>();
        int candidateChunks = 0;

        // A bounded scan still has return-value semantics, so the bridge collects synchronously after
        // scheduling each chunk-owned read. This keeps the bytecode path generic while preserving logs
        // for the exact chunks/region boundaries that need more work if Folia rejects a scan.
        for (Chunk chunk : chunks) {
            if (!chunkIntersectsBounds(chunk, center, x, z)) continue;
            candidateChunks++;
            Entity[] chunkEntities = chunkEntitiesForSplit(sourceApi, detail, chunk);
            if (chunkEntities == null) continue;
            for (Entity entity : chunkEntities) {
                if (entityWithinBounds(entity, center, x, y, z)) {
                    matches.add(entity);
                }
            }
        }

        BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                detail + " fallback=preemptive-bounded-split-scan"
                        + " chunks=" + chunks.length
                        + " candidateChunks=" + candidateChunks
                        + " resultSize=" + matches.size());
        return matches;
    }

    private static List<Entity> splitNearbyEntitiesForEntity(String sourceApi, String detail, Entity owner,
                                                             double x, double y, double z) throws Exception {
        Location center = owner.getLocation();
        World world = center == null ? null : center.getWorld();
        Collection<Entity> split = splitNearbyEntities(sourceApi,
                detail + " model=entity-location-bounded-split", world, center, x, y, z);
        List<Entity> matches = new ArrayList<>();
        for (Entity candidate : split) {
            if (!sameEntity(owner, candidate)) {
                matches.add(candidate);
            }
        }
        BridgeDiagnostics.unsafeCall(sourceApi, RouteFamily.G_WORLD_SCAN_SPLIT,
                "entity-scan", "entity-location-bounded-split",
                detail + " fallback=entity-location-bounded-split"
                        + " resultSize=" + matches.size()
                        + " ownerRemoved=true");
        return matches;
    }

    private static boolean sameEntity(Entity left, Entity right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        try {
            return left.getUniqueId().equals(right.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean chunkIntersectsBounds(Chunk chunk, Location center, double x, double z) {
        if (chunk == null || center == null) return false;
        double minX = center.getX() - Math.max(0.0D, x);
        double maxX = center.getX() + Math.max(0.0D, x);
        double minZ = center.getZ() - Math.max(0.0D, z);
        double maxZ = center.getZ() + Math.max(0.0D, z);
        int chunkMinX = chunk.getX() << 4;
        int chunkMinZ = chunk.getZ() << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;
        return maxX >= chunkMinX && minX <= chunkMaxX
                && maxZ >= chunkMinZ && minZ <= chunkMaxZ;
    }

    private static boolean entityWithinBounds(Entity entity, Location center, double x, double y, double z) {
        if (entity == null || center == null) return false;
        try {
            Location location = entity.getLocation();
            if (location == null || location.getWorld() != center.getWorld()) return false;
            return Math.abs(location.getX() - center.getX()) <= Math.max(0.0D, x)
                    && Math.abs(location.getY() - center.getY()) <= Math.max(0.0D, y)
                    && Math.abs(location.getZ() - center.getZ()) <= Math.max(0.0D, z);
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure("Entity#getLocation", RouteFamily.A_ENTITY,
                    "entity", "entity-scheduler-read", throwable);
            throw rethrow(throwable);
        }
    }

    private static Chunk[] loadedChunksForSplit(String parentApi, String parentDetail, World world) throws Exception {
        String sourceApi = "World#getLoadedChunks";
        String family = "world-scan";
        String nextAction = "world-loaded-chunk-index";
        String detail = worldDetail(world) + " parent=" + parentApi
                + " model=split-scan-loaded-chunk-index";
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        try {
            return world.getLoadedChunks();
        } catch (Throwable throwable) {
            if (!isFolia() || !isThreadGuardFailure(throwable)) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=global-scheduler-loaded-chunk-index"
                            + " parentDetail=[" + parentDetail + "]"
                            + " original=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=blocked-sync-return-avoided"
                                + " action=preserve-original-throw"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\""
                                + " parentDetail=[" + parentDetail + "]");
                throw rethrow(throwable);
            }
            return callGlobalScheduler(sourceApi, family, nextAction, detail,
                    () -> world.getLoadedChunks());
        }
    }

    private static Entity[] chunkEntitiesForSplit(String parentApi, String parentDetail, Chunk chunk) throws Exception {
        String sourceApi = "Chunk#getEntities";
        String family = "chunk-scan";
        String nextAction = "region-scheduler-by-chunk";
        String detail = chunkDetail(chunk) + " parent=" + parentApi
                + " parentDetail=[" + parentDetail + "]";
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        try {
            return chunk.getEntities();
        } catch (Throwable throwable) {
            if (!isFolia() || !isThreadGuardFailure(throwable)) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=split-scan-region-scheduler"
                            + " original=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=blocked-sync-return-avoided"
                                + " action=preserve-original-throw"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\""
                                + " parentDetail=[" + parentDetail + "]");
                throw rethrow(throwable);
            }
            return callRegionScheduler(sourceApi, family, nextAction, detail,
                    chunk.getWorld(), chunk.getX(), chunk.getZ(),
                    () -> chunk.getEntities());
        }
    }

    private static Collection<Entity> filterEntities(Collection<Entity> entities, Class<?>... classes) {
        List<Entity> matches = new ArrayList<>();
        if (entities == null || classes == null || classes.length == 0) {
            return matches;
        }
        for (Entity entity : entities) {
            if (matchesAnyClass(entity, classes)) {
                matches.add(entity);
            }
        }
        return matches;
    }

    @SafeVarargs
    private static <T extends Entity> Collection<T> filterTypedEntities(Collection<Entity> entities,
                                                                        Class<T>... classes) {
        List<T> matches = new ArrayList<>();
        if (entities == null || classes == null || classes.length == 0) {
            return matches;
        }
        for (Entity entity : entities) {
            if (matchesAnyClass(entity, classes)) {
                @SuppressWarnings("unchecked")
                T typed = (T) entity;
                matches.add(typed);
            }
        }
        return matches;
    }

    private static <T extends Entity> Collection<T> filterTypedEntities(Collection<Entity> entities, Class<T> clazz) {
        List<T> matches = new ArrayList<>();
        if (entities == null || clazz == null) {
            return matches;
        }
        for (Entity entity : entities) {
            if (clazz.isInstance(entity)) {
                matches.add(clazz.cast(entity));
            }
        }
        return matches;
    }

    private static boolean matchesAnyClass(Entity entity, Class<?>... classes) {
        if (entity == null || classes == null) return false;
        for (Class<?> clazz : classes) {
            if (clazz != null && clazz.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }

    private static <T> T callGlobalScheduler(String sourceApi, String family, String nextAction, String detail,
                                             ThrowingSupplier<T> supplier) throws Exception {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, supplier);
        invokeScheduler(scheduler("getGlobalRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, Consumer.class},
                owner, task);
        return waitForScheduled(sourceApi, route, family, nextAction, detail, future);
    }

    private static <T> T callEntityScheduler(String sourceApi, String family, String nextAction, String detail,
                                             Entity entity, ThrowingSupplier<T> supplier) throws Exception {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, supplier);
        Runnable retired = () -> future.completeExceptionally(
                new IllegalStateException("entity scheduler retired before " + sourceApi));
        invokeScheduler(entityScheduler(entity), "run",
                new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                owner, task, retired);
        return waitForScheduled(sourceApi, route, family, nextAction, detail, future);
    }

    private static void scheduleEntityVoid(String sourceApi, String family, String nextAction, String detail,
                                           Entity entity, ThrowingRunnable runnable) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, () -> {
                    runnable.run();
                    return null;
                });
        Runnable retired = () -> future.completeExceptionally(
                new IllegalStateException("entity scheduler retired before " + sourceApi));
        invokeScheduler(entityScheduler(entity), "run",
                new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                owner, task, retired);
    }

    private static <T> T callRegionScheduler(String sourceApi, String family, String nextAction, String detail,
                                             Location location, ThrowingSupplier<T> supplier) throws Exception {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, supplier);
        invokeScheduler(scheduler("getRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, Location.class, Consumer.class},
                owner, location, task);
        return waitForScheduled(sourceApi, route, family, nextAction, detail, future);
    }

    private static <T> CompletableFuture<T> scheduleRegionFuture(String sourceApi, String family, String nextAction,
                                                                 String detail, Location location,
                                                                 ThrowingSupplier<T> supplier) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, supplier);
        try {
            invokeScheduler(scheduler("getRegionScheduler"), "run",
                    new Class<?>[]{Plugin.class, Location.class, Consumer.class},
                    owner, location, task);
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction,
                    new IllegalStateException("deferred region schedule failed: " + detail, throwable));
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private static <T> T callRegionScheduler(String sourceApi, String family, String nextAction, String detail,
                                             World world, int chunkX, int chunkZ, ThrowingSupplier<T> supplier) throws Exception {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, supplier);
        invokeScheduler(scheduler("getRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, World.class, int.class, int.class, Consumer.class},
                owner, world, chunkX, chunkZ, task);
        return waitForScheduled(sourceApi, route, family, nextAction, detail, future);
    }

    private static void scheduleRegionVoid(String sourceApi, String family, String nextAction, String detail,
                                           Location location, ThrowingRunnable runnable) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, () -> {
                    runnable.run();
                    return null;
                });
        invokeScheduler(scheduler("getRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, Location.class, Consumer.class},
                owner, location, task);
    }

    private static void scheduleRegionVoid(String sourceApi, String family, String nextAction, String detail,
                                           World world, int chunkX, int chunkZ, ThrowingRunnable runnable) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Plugin owner = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        Consumer<Object> task = ignored -> completeScheduled(sourceApi, route, family, nextAction,
                owner, scheduledFrom, future, () -> {
                    runnable.run();
                    return null;
                });
        invokeScheduler(scheduler("getRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, World.class, int.class, int.class, Consumer.class},
                owner, world, chunkX, chunkZ, task);
    }

    private static <T> void completeScheduled(String sourceApi, RouteFamily route, String family, String nextAction,
                                              Plugin owner, String scheduledFrom, CompletableFuture<T> future,
                                              ThrowingSupplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable throwable) {
            BridgeDiagnostics.taskFailure(owner, route, scheduledFrom, throwable);
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            future.completeExceptionally(throwable);
        }
    }

    private static <T> T waitForScheduled(String sourceApi, RouteFamily route, String family, String nextAction,
                                          String detail, CompletableFuture<T> future) throws Exception {
        try {
            return future.get(5L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            if (isBridgeOrServerStopping()) {
                BridgeDiagnostics.scheduledFallbackAbandoned(sourceApi, route, family, nextAction, detail, exception);
                throw exception;
            }
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction,
                    new IllegalStateException("scheduled fallback did not complete: " + detail, exception));
            throw exception;
        }
    }

    private static <T> T guarded(String sourceApi, String family, String nextAction, String detail,
                                 ThrowingSupplier<T> supplier) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction, detail);
        try {
            return supplier.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static <T> T guardedWithFallback(String sourceApi, String family, String nextAction, String detail,
                                             ThrowingSupplier<T> direct,
                                             ThrowingSupplier<T> fallback) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction, detail);
        try {
            return direct.get();
        } catch (Throwable throwable) {
            if (!isFolia() || !isThreadGuardFailure(throwable)) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }

            // This is intentionally evidence-preserving: the original guard is logged,
            // then a narrow scheduler retry is attempted for bytecode shapes with an owner.
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=scheduler-after-thread-guard"
                            + " original=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=blocked-sync-return-avoided"
                                + " action=preserve-original-throw"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\"");
                throw rethrow(throwable);
            }
            try {
                return fallback.get();
            } catch (Throwable fallbackThrowable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, fallbackThrowable);
                throw rethrow(fallbackThrowable);
            }
        }
    }

    private static <T> T preemptiveOnFolia(String sourceApi, String family, String nextAction, String detail,
                                           ThrowingSupplier<T> directNonFolia,
                                           ThrowingSupplier<T> preemptiveFolia) {
        return preemptiveOnFolia(sourceApi, family, nextAction, detail,
                "preemptive-split-scan", directNonFolia, preemptiveFolia);
    }

    private static <T> T preemptiveOnFolia(String sourceApi, String family, String nextAction, String detail,
                                           String fallbackLabel,
                                           ThrowingSupplier<T> directNonFolia,
                                           ThrowingSupplier<T> preemptiveFolia) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        boolean folia = isFolia();
        String policyDetail = detail + (folia
                ? " policy=preemptive-safe reason=proven-live-route"
                : " policy=direct-non-folia");
        markUnsafeCall(sourceApi, route, family, nextAction, policyDetail);
        try {
            if (!folia) {
                return directNonFolia.get();
            }

            // Whole-world entity scans have no single region owner. Live probe evidence proved the
            // direct call only trips Folia's guard before the bridge splits by loaded chunk, so use
            // the split route immediately and keep a clear policy breadcrumb in the log.
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=" + fallbackLabel + " reason=proven-live-route");
            return preemptiveFolia.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static <T> T preemptiveRegionOnFolia(String sourceApi, String family, String nextAction, String detail,
                                                 ThrowingBooleanSupplier ownerCheck,
                                                 ThrowingSupplier<T> direct,
                                                 ThrowingSupplier<T> scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            // If the owner probe itself fails, preserve the original call and keep the
            // diagnostic. Scheduling without a known owner can hide the real bytecode path
            // behind a bridge-runtime failure, especially in smoke harnesses.
            if (owned || ownerCheckFailed) {
                return direct.get();
            }
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler reason=proven-live-route");
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=blocked-sync-return-avoided"
                                + " action=direct-preserve-original"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\"");
                return direct.get();
            }
            return scheduled.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static Material preemptiveBlockMaterialOnFolia(String sourceApi, String family, String nextAction,
                                                           String detail, Block block,
                                                           ThrowingBooleanSupplier ownerCheck,
                                                           ThrowingSupplier<Material> direct,
                                                           ThrowingSupplier<Material> scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.get();
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler reason=proven-live-route");
            if (isUnsafeScheduledReturnWaitThread()) {
                Material cached = cachedBlockType(block);
                if (cached != null) {
                    BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                            detail + " fallback=block-material-cache"
                                    + " policy=sync-return-model"
                                    + " result=hit material=" + cached
                                    + " reason=no-owner-thread-wait"
                                    + " thread=\"" + currentThreadName() + "\"");
                    return cached;
                }

                // Live claim-visualization evidence showed a common legacy shape:
                // BukkitScheduler#scheduleSyncDelayedTask is translated to the global
                // scheduler, then the delayed Runnable performs block-owned material
                // reads. A direct retry from global still fails Folia's owner guard, so
                // use a bounded owner-region read when the stack proves the current
                // context is Folia's global scheduler. Other region/entity owner
                // threads still avoid cross-owner waits and preserve the evidence.
                if (isFoliaGlobalSchedulerContext()) {
                    BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                            detail + " fallback=region-owned-sync-return"
                                    + " policy=bounded-region-wait"
                                    + " result=attempt"
                                    + " reason=global-scheduler-block-read-cache-miss"
                                    + " thread=\"" + currentThreadName() + "\"");
                    return scheduled.get();
                }

                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=block-material-cache"
                                + " policy=sync-return-model"
                                + " result=miss action=direct-preserve-original"
                                + " reason=no-safe-sync-return-without-prior-owned-read-or-global-context"
                                + " thread=\"" + currentThreadName() + "\"");
                return direct.get();
            }
            return scheduled.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static BlockData preemptiveBlockDataOnFolia(String sourceApi, String family, String nextAction,
                                                         String detail, Block block,
                                                         ThrowingBooleanSupplier ownerCheck,
                                                         ThrowingSupplier<BlockData> direct,
                                                         ThrowingSupplier<BlockData> scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.get();
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler reason=proven-live-route");
            if (isUnsafeScheduledReturnWaitThread()) {
                BlockData cached = cachedBlockData(block);
                if (cached != null) {
                    BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                            detail + " fallback=block-data-cache"
                                    + " policy=sync-return-model"
                                    + " result=hit data=" + cached.getAsString()
                                    + " reason=no-owner-thread-wait"
                                    + " thread=\"" + currentThreadName() + "\"");
                    return cached;
                }

                // Mirrors the Block#getType live route: legacy delayed scheduler
                // tasks can run on Folia's global scheduler and still perform a
                // synchronous block-owned read. From that specific owner context,
                // use a bounded region wait so the old sync return shape is
                // preserved without pretending the global thread owns the block.
                if (isFoliaGlobalSchedulerContext()) {
                    BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                            detail + " fallback=region-owned-sync-return"
                                    + " policy=bounded-region-wait"
                                    + " result=attempt"
                                    + " reason=global-scheduler-block-data-cache-miss"
                                    + " thread=\"" + currentThreadName() + "\"");
                    return scheduled.get();
                }

                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=block-data-cache"
                                + " policy=sync-return-model"
                                + " result=miss action=direct-preserve-original"
                                + " reason=no-safe-sync-return-without-prior-owned-read-or-global-context"
                                + " thread=\"" + currentThreadName() + "\"");
                return direct.get();
            }
            return scheduled.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static LightningStrike preemptiveLightningOnFolia(String sourceApi, String family, String nextAction,
                                                              String detail, World world, Location location,
                                                              ThrowingSupplier<LightningStrike> direct) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = isOwnedByCurrentRegion(location);
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.get();
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler reason=proven-live-route");
            if (!isUnsafeScheduledReturnWaitThread()) {
                return callRegionScheduler(sourceApi, family, nextAction, detail, location, direct);
            }

            // Lightning is an entity-returning side effect. From a foreign/global owner
            // thread, waiting for the target region can stall Folia, while direct retry is
            // the exact failure the bridge is meant to avoid. Return a small deferred Entity
            // proxy and keep the real scheduled task/failure evidence visible.
            CompletableFuture<LightningStrike> future = scheduleRegionFuture(sourceApi, family, nextAction,
                    detail, location, direct);
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=deferred-lightning-proxy"
                            + " policy=deferred-proxy-return"
                            + " result=proxy"
                            + " reason=return-object-route-no-safe-sync-wait"
                            + " thread=\"" + currentThreadName() + "\"");
            return DeferredLightningStrikeModel.create(sourceApi, route, family, nextAction, detail,
                    world, location, future).proxy();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static Chunk preemptiveChunkOnFolia(String sourceApi, String family, String nextAction, String detail,
                                                World world, int chunkX, int chunkZ,
                                                ThrowingBooleanSupplier ownerCheck,
                                                ThrowingSupplier<Chunk> direct,
                                                ThrowingSupplier<Chunk> scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.get();
            }
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler reason=proven-live-route");
            if (isUnsafeScheduledReturnWaitThread()) {
                Chunk indexed = loadedChunkFromIndex(world, chunkX, chunkZ);
                if (indexed != null) {
                    BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                            detail + " fallback=loaded-chunk-index-return"
                                    + " policy=sync-return-model"
                                    + " result=hit reason=no-owner-thread-wait");
                    return indexed;
                }
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=loaded-chunk-index-return"
                                + " policy=sync-return-model"
                                + " result=miss action=deferred-chunk-model"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\"");
                return deferredChunk(sourceApi, route, family, nextAction, detail, world, chunkX, chunkZ);
            }
            return scheduled.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static boolean preemptiveRegionAcceptedBooleanOnFolia(String sourceApi, String family, String nextAction,
                                                                  String detail,
                                                                  ThrowingBooleanSupplier ownerCheck,
                                                                  ThrowingBooleanSupplier direct,
                                                                  ThrowingRunnable scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.getAsBoolean();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.getAsBoolean();
            }

            // These Bukkit methods return a boolean even though their useful side effect is
            // region-owned. On a Folia owner thread there is no safe way to synchronously wait
            // for another region's real boolean without risking a tick stall, so the bridge
            // reports an accepted deferred execution and keeps task failures loud.
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler"
                            + " policy=deferred-accepted-boolean"
                            + " return=scheduled-true"
                            + " reason=boolean-route-no-safe-sync-return");
            scheduled.run();
            return true;
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static void preemptiveRegionVoidOnFolia(String sourceApi, String family, String nextAction, String detail,
                                                    ThrowingBooleanSupplier ownerCheck,
                                                    ThrowingRunnable direct,
                                                    ThrowingRunnable scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                direct.run();
                return;
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = ownerCheck != null && ownerCheck.getAsBoolean();
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-region-owned" : "schedule-owner-region")));
        try {
            if (owned || ownerCheckFailed) {
                direct.run();
                return;
            }

            // Void mutations/effects have no legacy return value to preserve. Schedule them
            // to the owning region and return immediately so the bridge does not park a Folia
            // owner thread. Task failures are still logged by completeScheduled().
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-region-scheduler"
                            + " policy=fire-and-forget-void"
                            + " reason=void-route-no-sync-return");
            scheduled.run();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static boolean preemptiveEntityAcceptedBooleanOnFolia(String sourceApi, String family, String nextAction,
                                                                  String detail, Entity entity,
                                                                  ThrowingBooleanSupplier direct,
                                                                  ThrowingRunnable scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.getAsBoolean();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = isOwnedByCurrentRegion(entity);
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-entity-owned" : "schedule-owner-entity")));
        try {
            if (owned || ownerCheckFailed) {
                return direct.getAsBoolean();
            }

            // Boolean entity mutations are side-effect routes. When called from a Folia
            // owner thread for a different entity, waiting for the real boolean can stall
            // the tick graph. Schedule the mutation and return accepted=true, keeping task
            // failures visible through completeScheduled().
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-entity-scheduler"
                            + " policy=deferred-accepted-boolean"
                            + " return=scheduled-true"
                            + " reason=boolean-route-no-safe-sync-return");
            scheduled.run();
            return true;
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static void preemptiveEntityVoidOnFolia(String sourceApi, String family, String nextAction,
                                                    String detail, Entity entity,
                                                    ThrowingRunnable direct,
                                                    ThrowingRunnable scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                direct.run();
                return;
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = isOwnedByCurrentRegion(entity);
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-entity-owned" : "schedule-owner-entity")));
        try {
            if (owned || ownerCheckFailed) {
                direct.run();
                return;
            }

            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-entity-scheduler"
                            + " policy=fire-and-forget-void"
                            + " reason=void-route-no-sync-return");
            scheduled.run();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static <T> T preemptiveEntityOnFolia(String sourceApi, String family, String nextAction, String detail,
                                                 Entity entity,
                                                 ThrowingSupplier<T> direct,
                                                 ThrowingSupplier<T> scheduled) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        if (!isFolia()) {
            markUnsafeCall(sourceApi, route, family, nextAction, detail + " policy=direct-non-folia");
            try {
                return direct.get();
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                throw rethrow(throwable);
            }
        }

        boolean owned = false;
        boolean ownerCheckFailed = false;
        try {
            owned = isOwnedByCurrentRegion(entity);
        } catch (Throwable throwable) {
            ownerCheckFailed = true;
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " ownerCheck=failed action=direct-preserve-original ownerCheckThrowable=" + throwable.getClass().getName()
                            + ": " + throwable.getMessage());
        }

        markUnsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=preemptive-safe reason=proven-live-route"
                        + " ownerCheck=" + (ownerCheckFailed ? "failed-direct-preserve-original"
                        : (owned ? "current-entity-owned" : "schedule-owner-entity")));
        try {
            // Entity-owned mutations are only safe on the owning entity thread. When the
            // ownership probe fails, preserve the original call so diagnostics still show
            // the exact plugin bytecode path rather than a bridge scheduling artifact.
            if (owned || ownerCheckFailed) {
                return direct.get();
            }
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " fallback=preemptive-entity-scheduler reason=proven-live-route");
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=blocked-sync-return-avoided"
                                + " action=direct-preserve-original"
                                + " reason=return-value-scheduler-wait-from-folia-thread"
                                + " thread=\"" + currentThreadName() + "\"");
                return direct.get();
            }
            return scheduled.get();
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static void guardedVoid(String sourceApi, String family, String nextAction, String detail,
                                    ThrowingRunnable runnable) {
        guarded(sourceApi, family, nextAction, detail, () -> {
            runnable.run();
            return null;
        });
    }

    private static CompletableFuture<Boolean> guardedFuture(String sourceApi, String family, String nextAction,
                                                            String detail,
                                                            ThrowingSupplier<CompletableFuture<Boolean>> supplier) {
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction, detail);
        try {
            CompletableFuture<Boolean> future = supplier.get();
            if (future == null) {
                return null;
            }
            return future.whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
                }
            });
        } catch (Throwable throwable) {
            BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            throw rethrow(throwable);
        }
    }

    private static void markUnsafeCall(String sourceApi, RouteFamily route, String family, String nextAction, String detail) {
        UNSAFE_CALLS.incrementAndGet();
        BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction, detail);
        BridgeDiagnostics.promotionCandidate(sourceApi, route, family, nextAction, detail,
                CompatibilityContext.current());
    }

    private static void markScoreboardModel(String sourceApi, String nextAction, String detail) {
        String family = "scoreboard";
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        markUnsafeCall(sourceApi, route, family, nextAction, detail);
    }

    private static RuntimeException rethrow(Throwable throwable) {
        UnsafeCallBridge.<RuntimeException>sneakyThrow(throwable);
        return new IllegalStateException("unreachable");
    }

    private static void watchTeleportFuture(String sourceApi, String family, String nextAction,
                                            CompletableFuture<Boolean> future) {
        if (future == null) return;
        RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
        future.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, throwable);
            }
        });
    }

    private static Plugin bridgePlugin() {
        Plugin plugin = bridgePlugin;
        if (plugin != null) return plugin;
        plugin = BridgePluginResolver.requirePlugin("unsafe-call scheduling");
        bridgePlugin = plugin;
        return plugin;
    }

    private static boolean isBridgeOrServerStopping() {
        Plugin plugin = bridgePlugin;
        if (plugin != null && !plugin.isEnabled()) {
            return true;
        }
        try {
            Server server = Bukkit.getServer();
            Method method = server.getClass().getMethod("isStopping");
            Object result = method.invoke(server);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Older/alternate Bukkit shapes do not expose isStopping. In that case
            // keep the original timeout/failure path so evidence is not softened.
        }
        String threadName = currentThreadName();
        return threadName.contains("RegionShutdownThread")
                || threadName.contains("Region shutdown thread");
    }

    private static Object scheduler(String getterName) {
        try {
            return Bukkit.class.getMethod(getterName).invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia scheduler is not available: " + getterName, exception);
        }
    }

    private static Object entityScheduler(Entity entity) {
        try {
            return entity.getClass().getMethod("getScheduler").invoke(entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Entity scheduler is not available", exception);
        }
    }

    private static Object invokeScheduler(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to call Folia scheduler method " + methodName, exception);
        }
    }

    private static boolean isFolia() {
        if (Boolean.getBoolean("foliabytecodebridge.forceNonFolia")) return false;
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            Bukkit.class.getMethod("getAsyncScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static boolean isUnsafeScheduledReturnWaitThread() {
        if (!isFolia()) return false;
        String threadName = currentThreadName();
        // Return-value bridge routes must not park a Folia tick/global/entity owner thread
        // while waiting for another owner to answer. That converts one unsafe legacy call
        // into a server stall. Async scheduler threads may still wait because they are not
        // region owners; region/global/entity tick threads preserve the original failure.
        return threadName.contains("Folia Region Scheduler Thread")
                || threadName.contains("TickRegionScheduler")
                || threadName.contains("TickRegions")
                || threadName.contains("Server thread");
    }

    private static boolean isFoliaGlobalSchedulerContext() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if ("io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler".equals(className)) {
                return true;
            }
            if ("io.papermc.paper.threadedregions.RegionizedServer".equals(className)
                    && "globalTick".equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static String currentThreadName() {
        return Thread.currentThread().getName();
    }

    private static boolean isOwnedByCurrentRegion(Location location) {
        // Null receivers/locations are not schedulable ownership problems. Let the original
        // Bukkit call throw its normal error instead of hiding it behind a bridge scheduler failure.
        return location == null || location.getWorld() == null || Bukkit.isOwnedByCurrentRegion(location);
    }

    private static boolean isOwnedByCurrentRegion(Block block) {
        return block == null || Bukkit.isOwnedByCurrentRegion(block);
    }

    private static boolean isOwnedByCurrentRegion(Entity entity) {
        if (entity == null) return true;
        try {
            Object result = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class).invoke(null, entity);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Entity ownership check is not available", exception);
        }
    }

    private static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return world == null || Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    private static int chunkX(Location location) {
        return location == null ? 0 : location.getBlockX() >> 4;
    }

    private static int chunkZ(Location location) {
        return location == null ? 0 : location.getBlockZ() >> 4;
    }

    private static int chunkX(Block block) {
        return block == null ? 0 : block.getX() >> 4;
    }

    private static int chunkZ(Block block) {
        return block == null ? 0 : block.getZ() >> 4;
    }

    private static Chunk loadedChunkFromIndex(World world, int chunkX, int chunkZ) {
        if (world == null) return null;
        try {
            Chunk[] chunks = world.getLoadedChunks();
            if (chunks == null) return null;
            for (Chunk chunk : chunks) {
                if (chunk != null && chunk.getX() == chunkX && chunk.getZ() == chunkZ) {
                    return chunk;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Material readBlockTypeAndRemember(Block block) {
        Material material = block.getType();
        rememberBlockType(block, material);
        return material;
    }

    private static void setBlockTypeAndRemember(Block block, Material material) {
        block.setType(material);
        rememberBlockType(block, material);
        forgetBlockData(block);
    }

    private static void rememberBlockType(Block block, Material material) {
        String key = blockKey(block);
        if (key == null || material == null) return;
        BLOCK_TYPE_CACHE.put(key, material);
    }

    private static Material cachedBlockType(Block block) {
        String key = blockKey(block);
        return key == null ? null : BLOCK_TYPE_CACHE.get(key);
    }

    private static BlockData readBlockDataAndRemember(Block block) {
        BlockData data = block.getBlockData();
        rememberBlockData(block, data);
        return data;
    }

    private static void rememberBlockData(Block block, BlockData data) {
        String key = blockKey(block);
        if (key == null || data == null) return;
        BLOCK_DATA_CACHE.put(key, data);
    }

    private static BlockData cachedBlockData(Block block) {
        String key = blockKey(block);
        return key == null ? null : BLOCK_DATA_CACHE.get(key);
    }

    private static void forgetBlockData(Block block) {
        String key = blockKey(block);
        if (key != null) {
            BLOCK_DATA_CACHE.remove(key);
        }
    }

    private static String blockKey(Block block) {
        if (block == null) return null;
        try {
            return worldKey(block.getWorld()) + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Chunk deferredChunk(String sourceApi, RouteFamily route, String family, String nextAction,
                                       String detail, World world, int chunkX, int chunkZ) {
        if (world == null) {
            BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                    detail + " policy=deferred-chunk-model result=null-world action=default-null");
            return null;
        }
        String key = worldKey(world) + ":" + chunkX + ":" + chunkZ;
        DeferredChunkModel model = DEFERRED_CHUNKS.computeIfAbsent(key,
                ignored -> DeferredChunkModel.create(world, chunkX, chunkZ));
        model.requestAsyncLoad(sourceApi, family, nextAction, detail);
        BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                detail + " policy=deferred-chunk-model"
                        + " action=async-preload-return-proxy"
                        + " result=proxy key=" + key);
        return model.proxy();
    }

    private static String worldKey(World world) {
        if (world == null) return "null-world";
        try {
            return world.getUID().toString();
        } catch (Throwable ignored) {
            try {
                return world.getName();
            } catch (Throwable ignoredAgain) {
                return world.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(world));
            }
        }
    }

    private static boolean isThreadGuardFailure(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("thread")
                || lower.contains("async")
                || lower.contains("region");
    }

    private static TeleportCause defaultTeleportCause() {
        return TeleportCause.PLUGIN;
    }

    private static TeleportCause safeTeleportCause(TeleportCause cause) {
        return cause == null ? defaultTeleportCause() : cause;
    }

    private static String shimName() {
        return isFolia() ? "teleportAsync" : "sync-teleport";
    }

    private static DetachedScoreboardModel detachedScoreboardModel(Scoreboard scoreboard) {
        if (scoreboard == null || !Proxy.isProxyClass(scoreboard.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(scoreboard);
        return handler instanceof DetachedScoreboardModel ? (DetachedScoreboardModel) handler : null;
    }

    private static DetachedObjectiveModel detachedObjectiveModel(Objective objective) {
        if (objective == null || !Proxy.isProxyClass(objective.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(objective);
        return handler instanceof DetachedObjectiveModel ? (DetachedObjectiveModel) handler : null;
    }

    private static DetachedScoreModel detachedScoreModel(Score score) {
        if (score == null || !Proxy.isProxyClass(score.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(score);
        return handler instanceof DetachedScoreModel ? (DetachedScoreModel) handler : null;
    }

    private static DetachedScoreboardModel playerScoreboardModel(Player player) {
        String key = playerScoreboardKey(player);
        return PLAYER_SCOREBOARDS.computeIfAbsent(key,
                ignored -> DetachedScoreboardModel.createModel("player=" + playerLabel(player)));
    }

    private static void rememberPlayerScoreboard(Player player, DetachedScoreboardModel model) {
        PLAYER_SCOREBOARDS.put(playerScoreboardKey(player), model);
    }

    private static Objective scoreboardRegisterNewObjectiveModel(Scoreboard scoreboard, String name, String criteria,
                                                                 String displayName, RenderType renderType,
                                                                 String sourceApi,
                                                                 ThrowingSupplier<Objective> directCall) {
        DetachedScoreboardModel detached = detachedScoreboardModel(scoreboard);
        if (detached != null) {
            String family = "scoreboard";
            String nextAction = "scoreboard-model-objective-create";
            RouteFamily route = RouteFamily.forUnsafeCall(family, nextAction);
            Objective objective = detached.registerNewObjective(name, criteria, displayName, renderType);
            markUnsafeCall(sourceApi, route, family, nextAction,
                    detached.detail() + " objective=" + safeText(name)
                            + " criteria=" + safeText(criteria)
                            + " policy=shim-model action=modeled result=objective");
            return objective;
        }
        return guarded(sourceApi, "scoreboard", "scoreboard-hard-unsupported-objective-create",
                scoreboardDetail(scoreboard) + " objective=" + safeText(name)
                        + " criteria=" + safeText(criteria),
                directCall);
    }

    private static String playerScoreboardKey(Player player) {
        if (player == null) return "null-player";
        try {
            return "uuid:" + player.getUniqueId();
        } catch (Throwable ignored) {
            try {
                return "name:" + player.getName();
            } catch (Throwable ignoredAgain) {
                return "identity:" + System.identityHashCode(player);
            }
        }
    }

    private static String playerLabel(Player player) {
        if (player == null) return "null";
        try {
            return safeText(player.getName());
        } catch (Throwable ignored) {
            return player.getClass().getName();
        }
    }

    private static String offlinePlayerLabel(OfflinePlayer player) {
        if (player == null) return "null-player";
        try {
            String name = player.getName();
            return name == null ? String.valueOf(player.getUniqueId()) : name;
        } catch (Throwable ignored) {
            return player.getClass().getName();
        }
    }

    private static String criteriaLabel(Criteria criteria) {
        if (criteria == null) return "null-criteria";
        try {
            return criteria.getName();
        } catch (Throwable ignored) {
            return criteria.toString();
        }
    }

    private static RenderType renderType(Criteria criteria) {
        if (criteria == null) return null;
        try {
            return criteria.getDefaultRenderType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String componentLabel(Component component) {
        return component == null ? null : component.toString();
    }

    private static String numberFormatLabel(NumberFormat format) {
        return format == null ? "null" : format.getClass().getName();
    }

    private static DetachedTeamModel detachedTeamModel(Team team) {
        if (team == null || !Proxy.isProxyClass(team.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(team);
        return handler instanceof DetachedTeamModel ? (DetachedTeamModel) handler : null;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0F;
        if (returnType == double.class) return 0D;
        if (returnType == char.class) return '\0';
        if (Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
        if (Collection.class.isAssignableFrom(returnType)) return Collections.emptyList();
        return null;
    }

    private static Object emptyOrDefaultValue(Class<?> returnType) {
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        return defaultValue(returnType);
    }

    private static final class DeferredLightningStrikeModel implements InvocationHandler {
        private final String sourceApi;
        private final RouteFamily route;
        private final String family;
        private final String nextAction;
        private final String detail;
        private final World world;
        private final Location location;
        private final CompletableFuture<LightningStrike> future;
        private final LightningStrike proxy;

        private DeferredLightningStrikeModel(String sourceApi, RouteFamily route, String family, String nextAction,
                                             String detail, World world, Location location,
                                             CompletableFuture<LightningStrike> future) {
            this.sourceApi = sourceApi;
            this.route = route;
            this.family = family;
            this.nextAction = nextAction;
            this.detail = detail;
            this.world = world;
            this.location = location == null ? null : location.clone();
            this.future = future;
            this.proxy = (LightningStrike) Proxy.newProxyInstance(LightningStrike.class.getClassLoader(),
                    new Class<?>[]{LightningStrike.class}, this);
        }

        static DeferredLightningStrikeModel create(String sourceApi, RouteFamily route, String family,
                                                   String nextAction, String detail, World world, Location location,
                                                   CompletableFuture<LightningStrike> future) {
            return new DeferredLightningStrikeModel(sourceApi, route, family, nextAction,
                    detail, world, location, future);
        }

        LightningStrike proxy() {
            return proxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) return "DeferredLightningStrikeModel{" + detail + "}";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if ("getWorld".equals(name) && method.getParameterCount() == 0) return world;
            if ("getLocation".equals(name) && method.getParameterCount() == 0) {
                return location == null ? null : location.clone();
            }
            if ("getType".equals(name) && method.getParameterCount() == 0) {
                return EntityType.LIGHTNING_BOLT;
            }

            LightningStrike actual = actualOrNull(method);
            if (actual == null) {
                return defaultValue(method.getReturnType());
            }
            try {
                return method.invoke(actual, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, cause);
                throw cause;
            }
        }

        private LightningStrike actualOrNull(Method method) throws Exception {
            if (future.isDone()) {
                try {
                    return future.get();
                } catch (Exception exception) {
                    BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction, exception);
                    throw exception;
                }
            }
            if (isUnsafeScheduledReturnWaitThread()) {
                BridgeDiagnostics.unsafeCall(sourceApi, route, family, nextAction,
                        detail + " fallback=deferred-lightning-proxy"
                                + " policy=deferred-proxy-return"
                                + " action=method-default"
                                + " method=" + method.getName()
                                + " reason=proxy-not-ready-no-owner-thread-wait"
                                + " thread=\"" + currentThreadName() + "\"");
                return null;
            }
            try {
                return future.get(5L, TimeUnit.SECONDS);
            } catch (Exception exception) {
                BridgeDiagnostics.unsafeFailure(sourceApi, route, family, nextAction,
                        new IllegalStateException("deferred lightning proxy did not complete: " + detail, exception));
                throw exception;
            }
        }
    }

    private static final class DeferredChunkModel implements InvocationHandler {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final Chunk proxy;
        private final CompletableFuture<Chunk> future = new CompletableFuture<>();
        private volatile boolean loadRequested;

        private DeferredChunkModel(World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.proxy = (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(),
                    new Class<?>[]{Chunk.class}, this);
        }

        static DeferredChunkModel create(World world, int chunkX, int chunkZ) {
            return new DeferredChunkModel(world, chunkX, chunkZ);
        }

        Chunk proxy() {
            return proxy;
        }

        void requestAsyncLoad(String sourceApi, String family, String nextAction, String parentDetail) {
            if (loadRequested) return;
            loadRequested = true;

            Chunk loaded = loadedChunkFromIndex(world, chunkX, chunkZ);
            if (loaded != null) {
                future.complete(loaded);
                BridgeDiagnostics.unsafeCall(sourceApi, RouteFamily.B_REGION_LOCATION, family, nextAction,
                        parentDetail + " policy=deferred-chunk-model"
                                + " action=loaded-index-after-miss"
                                + " result=chunk " + detail());
                return;
            }

            try {
                // Paper/Folia exposes async chunk loading as the safe way to ask for a
                // not-yet-loaded chunk without parking a region owner thread. Reflection keeps
                // this model compiling against API jars that move the overload around.
                Method method = World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
                @SuppressWarnings("unchecked")
                CompletableFuture<Chunk> async = (CompletableFuture<Chunk>) method.invoke(world, chunkX, chunkZ, true);
                async.whenComplete((chunk, throwable) -> {
                    if (throwable != null) {
                        BridgeDiagnostics.unsafeFailure(sourceApi, RouteFamily.B_REGION_LOCATION,
                                family, nextAction, unwrap(throwable));
                        future.completeExceptionally(unwrap(throwable));
                        return;
                    }
                    future.complete(chunk);
                    BridgeDiagnostics.unsafeCall(sourceApi, RouteFamily.B_REGION_LOCATION, family, nextAction,
                            parentDetail + " policy=deferred-chunk-model"
                                    + " action=async-preload-complete"
                                    + " result=" + (chunk == null ? "null-chunk" : "chunk")
                                    + " " + detail());
                });
            } catch (Throwable throwable) {
                Throwable unwrapped = unwrap(throwable);
                BridgeDiagnostics.unsafeFailure(sourceApi, RouteFamily.B_REGION_LOCATION,
                        family, nextAction, unwrapped);
                future.completeExceptionally(unwrapped);
            }
        }

        private Chunk resolved() {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                return future.getNow(null);
            }
            Chunk loaded = loadedChunkFromIndex(world, chunkX, chunkZ);
            if (loaded != null) {
                future.complete(loaded);
            }
            return loaded;
        }

        private String detail() {
            return worldDetail(world) + " x=" + chunkX + " z=" + chunkZ
                    + " chunkProxy=deferred@" + Integer.toHexString(System.identityHashCode(this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) return "DeferredChunkModel{" + detail() + "}";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if ("getX".equals(name)) return chunkX;
            if ("getZ".equals(name)) return chunkZ;
            if ("getWorld".equals(name)) return world;
            if ("getChunkKey".equals(name)) return Chunk.getChunkKey(chunkX, chunkZ);
            if ("isLoaded".equals(name)) return resolved() != null || worldBoolean("isChunkLoaded");
            if ("isGenerated".equals(name)) return worldBoolean("isChunkGenerated");
            if ("load".equals(name)) {
                requestAsyncLoad("Chunk#load", "region", "region-scheduler-by-chunk", detail());
                BridgeDiagnostics.unsafeCall("Chunk#load", RouteFamily.B_REGION_LOCATION,
                        "region", "region-scheduler-by-chunk",
                        detail() + " policy=deferred-chunk-model action=async-preload-requested return=true");
                return true;
            }
            if ("getBlock".equals(name) && args != null && args.length == 3) {
                int localX = ((Number) args[0]).intValue();
                int y = ((Number) args[1]).intValue();
                int localZ = ((Number) args[2]).intValue();
                return worldGetBlockAt(world, (chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
            }
            if ("getEntities".equals(name)) {
                Chunk chunk = resolved();
                if (chunk != null) {
                    return chunkGetEntities(chunk);
                }
                BridgeDiagnostics.unsafeCall("Chunk#getEntities", RouteFamily.G_WORLD_SCAN_SPLIT,
                        "chunk-scan", "region-scheduler-by-chunk",
                        detail() + " policy=deferred-chunk-model"
                                + " action=pending-result-default"
                                + " result=empty-array reason=chunk-not-loaded-yet");
                return new Entity[0];
            }

            Chunk chunk = resolved();
            if (chunk != null) {
                try {
                    return method.invoke(chunk, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }

            BridgeDiagnostics.unsafeCall("Chunk#" + name, RouteFamily.B_REGION_LOCATION,
                    "region", "deferred-chunk-model",
                    detail() + " policy=deferred-chunk-model"
                            + " action=pending-result-default"
                            + " method=" + name
                            + " returnType=" + method.getReturnType().getName()
                            + " reason=chunk-not-loaded-yet");
            return emptyOrDefaultValue(method.getReturnType());
        }

        private boolean worldBoolean(String methodName) {
            try {
                Method method = World.class.getMethod(methodName, int.class, int.class);
                return Boolean.TRUE.equals(method.invoke(world, chunkX, chunkZ));
            } catch (Throwable throwable) {
                BridgeDiagnostics.unsafeCall("World#" + methodName, RouteFamily.B_REGION_LOCATION,
                        "region", "deferred-chunk-model",
                        detail() + " policy=deferred-chunk-model"
                                + " action=world-boolean-failed"
                                + " throwable=" + unwrap(throwable).getClass().getName()
                                + ": " + unwrap(throwable).getMessage());
                return false;
            }
        }

        private static Throwable unwrap(Throwable throwable) {
            if (throwable instanceof InvocationTargetException
                    && ((InvocationTargetException) throwable).getCause() != null) {
                return ((InvocationTargetException) throwable).getCause();
            }
            if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
                return throwable.getCause();
            }
            return throwable;
        }
    }

    private static final class DetachedScoreboardModel implements InvocationHandler {
        private final Map<String, DetachedTeamModel> teams = new LinkedHashMap<>();
        private final Map<String, DetachedObjectiveModel> objectives = new LinkedHashMap<>();
        private final Map<DisplaySlot, DetachedObjectiveModel> displaySlots = new LinkedHashMap<>();
        private final String ownerLabel;
        private final Scoreboard proxy;

        private DetachedScoreboardModel(String ownerLabel) {
            this.ownerLabel = ownerLabel;
            this.proxy = (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                    new Class<?>[]{Scoreboard.class}, this);
        }

        static Scoreboard create() {
            return createModel("detached").proxy;
        }

        static DetachedScoreboardModel createModel(String ownerLabel) {
            return new DetachedScoreboardModel(ownerLabel);
        }

        Scoreboard proxy() {
            return proxy;
        }

        Team getTeam(String name) {
            DetachedTeamModel team = teams.get(name);
            return team == null ? null : team.proxy;
        }

        Team registerNewTeam(String name) {
            String key = safeText(name);
            DetachedTeamModel existing = teams.get(key);
            if (existing != null) return existing.proxy;
            DetachedTeamModel team = DetachedTeamModel.createModel(this, key);
            teams.put(key, team);
            return team.proxy;
        }

        Objective getObjective(String name) {
            DetachedObjectiveModel objective = objectives.get(name);
            return objective == null ? null : objective.proxy;
        }

        Objective getObjective(DisplaySlot slot) {
            DetachedObjectiveModel objective = displaySlots.get(slot);
            return objective == null ? null : objective.proxy;
        }

        Set<Objective> getObjectives() {
            java.util.LinkedHashSet<Objective> result = new java.util.LinkedHashSet<>();
            for (DetachedObjectiveModel objective : objectives.values()) {
                result.add(objective.proxy);
            }
            return Collections.unmodifiableSet(result);
        }

        Objective registerNewObjective(String name, String criteria, String displayName, RenderType renderType) {
            String key = safeText(name);
            DetachedObjectiveModel existing = objectives.get(key);
            if (existing != null) return existing.proxy;
            DetachedObjectiveModel objective = DetachedObjectiveModel.createModel(this, key,
                    safeText(criteria), displayName == null ? key : displayName, renderType);
            objectives.put(key, objective);
            return objective.proxy;
        }

        void setDisplaySlot(DetachedObjectiveModel objective, DisplaySlot slot) {
            displaySlots.values().removeIf(existing -> existing == objective);
            if (slot != null) {
                displaySlots.put(slot, objective);
            }
        }

        void removeObjective(DetachedObjectiveModel objective) {
            objectives.values().removeIf(existing -> existing == objective);
            displaySlots.values().removeIf(existing -> existing == objective);
        }

        Set<Score> scoresForEntry(String entry) {
            java.util.LinkedHashSet<Score> scores = new java.util.LinkedHashSet<>();
            for (DetachedObjectiveModel objective : objectives.values()) {
                DetachedScoreModel score = objective.getScoreModelIfPresent(entry);
                if (score != null) scores.add(score.proxy);
            }
            return Collections.unmodifiableSet(scores);
        }

        void resetScores(String entry) {
            for (DetachedObjectiveModel objective : objectives.values()) {
                objective.resetScore(entry);
            }
        }

        Set<String> entries() {
            java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
            for (DetachedTeamModel team : teams.values()) {
                entries.addAll(team.entrySnapshot());
            }
            for (DetachedObjectiveModel objective : objectives.values()) {
                entries.addAll(objective.scoreEntries());
            }
            return Collections.unmodifiableSet(entries);
        }

        String detail() {
            return "scoreboard=detached@" + Integer.toHexString(System.identityHashCode(this))
                    + " owner=" + ownerLabel
                    + " teams=" + teams.size()
                    + " objectives=" + objectives.size()
                    + " slots=" + displaySlots.size();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return detail();
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if ("getTeam".equals(name) && args != null && args.length == 1) {
                return getTeam((String) args[0]);
            }
            if ("registerNewTeam".equals(name) && args != null && args.length == 1) {
                return registerNewTeam((String) args[0]);
            }
            if ("getTeams".equals(name)) {
                java.util.LinkedHashSet<Team> result = new java.util.LinkedHashSet<>();
                for (DetachedTeamModel team : teams.values()) {
                    result.add(team.proxy);
                }
                return Collections.unmodifiableSet(result);
            }
            if ("getEntries".equals(name)) {
                return entries();
            }
            if ("getObjective".equals(name) && args != null && args.length == 1) {
                if (args[0] instanceof DisplaySlot) {
                    return getObjective((DisplaySlot) args[0]);
                }
                return getObjective((String) args[0]);
            }
            if ("getObjectives".equals(name)) {
                return getObjectives();
            }
            if ("registerNewObjective".equals(name) && args != null && args.length >= 2) {
                return registerNewObjective((String) args[0], objectiveCriteriaArg(args[1]),
                        args.length >= 3 ? objectiveDisplayArg(args[2]) : null,
                        args.length >= 4 && args[3] instanceof RenderType ? (RenderType) args[3] : null);
            }
            if ("getScores".equals(name) && args != null && args.length == 1) {
                return scoresForEntry(args[0] instanceof OfflinePlayer
                        ? offlinePlayerLabel((OfflinePlayer) args[0])
                        : safeText((String) args[0]));
            }
            if ("resetScores".equals(name) && args != null && args.length == 1) {
                resetScores(args[0] instanceof OfflinePlayer
                        ? offlinePlayerLabel((OfflinePlayer) args[0])
                        : safeText((String) args[0]));
                return null;
            }
            if ("clearSlot".equals(name) && args != null && args.length == 1) {
                displaySlots.remove(args[0]);
                return null;
            }

            BridgeDiagnostics.unsafeCall("Scoreboard#" + name, RouteFamily.D_PLAYER_UI,
                    "scoreboard", "scoreboard-detached-model-unsupported",
                    detail() + " policy=shim-model action=unsupported method=" + name
                            + " result=default-value");
            return defaultValue(method.getReturnType());
        }

        private static String objectiveCriteriaArg(Object arg) {
            if (arg instanceof Criteria) return criteriaLabel((Criteria) arg);
            return safeText((String) arg);
        }

        private static String objectiveDisplayArg(Object arg) {
            if (arg instanceof Component) return componentLabel((Component) arg);
            return safeText((String) arg);
        }
    }

    private static final class DetachedTeamModel implements InvocationHandler {
        private final DetachedScoreboardModel scoreboard;
        private final String name;
        private final java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        private final Map<Team.Option, Team.OptionStatus> options = new LinkedHashMap<>();
        private final Team proxy;
        private String displayName;
        private String prefix = "";
        private String suffix = "";
        private Component displayNameComponent;
        private Component prefixComponent;
        private Component suffixComponent;
        private ChatColor color;
        private NamedTextColor namedColor;
        private boolean allowFriendlyFire = true;
        private boolean canSeeFriendlyInvisibles;

        private DetachedTeamModel(DetachedScoreboardModel scoreboard, String name) {
            this.scoreboard = scoreboard;
            this.name = name;
            this.displayName = name;
            this.proxy = (Team) Proxy.newProxyInstance(Team.class.getClassLoader(),
                    new Class<?>[]{Team.class}, this);
        }

        static DetachedTeamModel createModel(DetachedScoreboardModel scoreboard, String name) {
            return new DetachedTeamModel(scoreboard, name);
        }

        void setOption(Team.Option option, Team.OptionStatus status) {
            if (option != null) {
                options.put(option, status);
            }
        }

        void setDisplayName(String displayName) {
            this.displayName = safeText(displayName);
        }

        void displayName(Component displayName) {
            this.displayNameComponent = displayName;
        }

        void setPrefix(String prefix) {
            this.prefix = safeText(prefix);
        }

        void prefix(Component prefix) {
            this.prefixComponent = prefix;
        }

        void setSuffix(String suffix) {
            this.suffix = safeText(suffix);
        }

        void suffix(Component suffix) {
            this.suffixComponent = suffix;
        }

        void setColor(ChatColor color) {
            this.color = color;
        }

        void color(NamedTextColor color) {
            this.namedColor = color;
        }

        void setAllowFriendlyFire(boolean allowFriendlyFire) {
            this.allowFriendlyFire = allowFriendlyFire;
        }

        void setCanSeeFriendlyInvisibles(boolean canSeeFriendlyInvisibles) {
            this.canSeeFriendlyInvisibles = canSeeFriendlyInvisibles;
        }

        void addEntry(String entry) {
            entries.add(safeText(entry));
        }

        boolean removeEntry(String entry) {
            return entries.remove(safeText(entry));
        }

        Set<String> entrySnapshot() {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(entries));
        }

        String detail() {
            return scoreboard.detail() + " team=" + name
                    + " entries=" + entries.size()
                    + " options=" + options.size();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("toString".equals(methodName)) return detail();
            if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
            if ("equals".equals(methodName)) return proxy == (args == null ? null : args[0]);
            if ("getName".equals(methodName)) return name;
            if ("getScoreboard".equals(methodName)) return scoreboard.proxy;
            if ("getDisplayName".equals(methodName)) return displayName;
            if ("setDisplayName".equals(methodName) && args != null && args.length == 1) {
                setDisplayName((String) args[0]);
                return null;
            }
            if ("displayName".equals(methodName)) {
                if (args != null && args.length == 1) {
                    displayName((Component) args[0]);
                    return null;
                }
                return displayNameComponent;
            }
            if ("getPrefix".equals(methodName)) return prefix;
            if ("setPrefix".equals(methodName) && args != null && args.length == 1) {
                setPrefix((String) args[0]);
                return null;
            }
            if ("prefix".equals(methodName)) {
                if (args != null && args.length == 1) {
                    prefix((Component) args[0]);
                    return null;
                }
                return prefixComponent;
            }
            if ("getSuffix".equals(methodName)) return suffix;
            if ("setSuffix".equals(methodName) && args != null && args.length == 1) {
                setSuffix((String) args[0]);
                return null;
            }
            if ("suffix".equals(methodName)) {
                if (args != null && args.length == 1) {
                    suffix((Component) args[0]);
                    return null;
                }
                return suffixComponent;
            }
            if ("getColor".equals(methodName)) return color;
            if ("setColor".equals(methodName) && args != null && args.length == 1) {
                setColor((ChatColor) args[0]);
                return null;
            }
            if ("hasColor".equals(methodName)) return color != null || namedColor != null;
            if ("color".equals(methodName)) {
                if (args != null && args.length == 1) {
                    color((NamedTextColor) args[0]);
                    return null;
                }
                return (TextColor) namedColor;
            }
            if ("allowFriendlyFire".equals(methodName)) return allowFriendlyFire;
            if ("setAllowFriendlyFire".equals(methodName) && args != null && args.length == 1) {
                setAllowFriendlyFire(Boolean.TRUE.equals(args[0]));
                return null;
            }
            if ("canSeeFriendlyInvisibles".equals(methodName)) return canSeeFriendlyInvisibles;
            if ("setCanSeeFriendlyInvisibles".equals(methodName) && args != null && args.length == 1) {
                setCanSeeFriendlyInvisibles(Boolean.TRUE.equals(args[0]));
                return null;
            }
            if ("getEntries".equals(methodName)) return Collections.unmodifiableSet(entries);
            if ("getSize".equals(methodName)) return entries.size();
            if ("hasEntry".equals(methodName) && args != null && args.length == 1) {
                return entries.contains(safeText((String) args[0]));
            }
            if ("setOption".equals(methodName) && args != null && args.length == 2) {
                setOption((Team.Option) args[0], (Team.OptionStatus) args[1]);
                return null;
            }
            if ("getOption".equals(methodName) && args != null && args.length == 1) {
                return options.get(args[0]);
            }
            if ("addEntry".equals(methodName) && args != null && args.length == 1) {
                addEntry((String) args[0]);
                return null;
            }
            if ("removeEntry".equals(methodName) && args != null && args.length == 1) {
                return removeEntry((String) args[0]);
            }

            BridgeDiagnostics.unsafeCall("Team#" + methodName, RouteFamily.D_PLAYER_UI,
                    "scoreboard", "scoreboard-detached-team-unsupported",
                    detail() + " policy=shim-model action=unsupported method=" + methodName
                            + " result=default-value");
            return defaultValue(method.getReturnType());
        }
    }

    private static final class DetachedObjectiveModel implements InvocationHandler {
        private final DetachedScoreboardModel scoreboard;
        private final String name;
        private final String criteria;
        private final Map<String, DetachedScoreModel> scores = new LinkedHashMap<>();
        private final Objective proxy;
        private String displayName;
        private Component displayNameComponent;
        private DisplaySlot displaySlot;
        private RenderType renderType;
        private NumberFormat numberFormat;
        private boolean autoUpdateDisplay = true;

        private DetachedObjectiveModel(DetachedScoreboardModel scoreboard, String name, String criteria,
                                       String displayName, RenderType renderType) {
            this.scoreboard = scoreboard;
            this.name = name;
            this.criteria = criteria;
            this.displayName = displayName;
            this.renderType = renderType == null ? RenderType.INTEGER : renderType;
            this.proxy = (Objective) Proxy.newProxyInstance(Objective.class.getClassLoader(),
                    new Class<?>[]{Objective.class}, this);
        }

        static DetachedObjectiveModel createModel(DetachedScoreboardModel scoreboard, String name, String criteria,
                                                  String displayName, RenderType renderType) {
            return new DetachedObjectiveModel(scoreboard, name, criteria, displayName, renderType);
        }

        void setDisplayName(String displayName) {
            this.displayName = safeText(displayName);
        }

        void displayName(Component displayName) {
            this.displayNameComponent = displayName;
        }

        void setDisplaySlot(DisplaySlot slot) {
            this.displaySlot = slot;
            scoreboard.setDisplaySlot(this, slot);
        }

        void numberFormat(NumberFormat format) {
            this.numberFormat = format;
        }

        Score getScore(String entry) {
            return scores.computeIfAbsent(safeText(entry), key -> DetachedScoreModel.createModel(this, key)).proxy;
        }

        DetachedScoreModel getScoreModelIfPresent(String entry) {
            return scores.get(safeText(entry));
        }

        void resetScore(String entry) {
            DetachedScoreModel score = scores.get(safeText(entry));
            if (score != null) score.resetScore();
        }

        Set<String> scoreEntries() {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(scores.keySet()));
        }

        String detail() {
            return scoreboard.detail() + " objective=" + name
                    + " criteria=" + criteria
                    + " scores=" + scores.size()
                    + " slot=" + displaySlot;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("toString".equals(methodName)) return detail();
            if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
            if ("equals".equals(methodName)) return proxy == (args == null ? null : args[0]);
            if ("getName".equals(methodName)) return name;
            if ("getDisplayName".equals(methodName)) return displayName;
            if ("displayName".equals(methodName)) {
                if (args != null && args.length == 1) {
                    displayName((Component) args[0]);
                    return null;
                }
                return displayNameComponent;
            }
            if ("setDisplayName".equals(methodName) && args != null && args.length == 1) {
                setDisplayName((String) args[0]);
                return null;
            }
            if ("getCriteria".equals(methodName)) return criteria;
            if ("isModifiable".equals(methodName)) return true;
            if ("getScoreboard".equals(methodName)) return scoreboard.proxy;
            if ("unregister".equals(methodName)) {
                scoreboard.removeObjective(this);
                return null;
            }
            if ("setDisplaySlot".equals(methodName) && args != null && args.length == 1) {
                setDisplaySlot((DisplaySlot) args[0]);
                return null;
            }
            if ("getDisplaySlot".equals(methodName)) return displaySlot;
            if ("setRenderType".equals(methodName) && args != null && args.length == 1) {
                renderType = (RenderType) args[0];
                return null;
            }
            if ("getRenderType".equals(methodName)) return renderType;
            if ("getScore".equals(methodName) && args != null && args.length == 1) {
                return getScore(args[0] instanceof OfflinePlayer
                        ? offlinePlayerLabel((OfflinePlayer) args[0])
                        : safeText((String) args[0]));
            }
            if ("willAutoUpdateDisplay".equals(methodName)) return autoUpdateDisplay;
            if ("setAutoUpdateDisplay".equals(methodName) && args != null && args.length == 1) {
                autoUpdateDisplay = Boolean.TRUE.equals(args[0]);
                return null;
            }
            if ("numberFormat".equals(methodName)) {
                if (args != null && args.length == 1) {
                    numberFormat((NumberFormat) args[0]);
                    return null;
                }
                return numberFormat;
            }

            BridgeDiagnostics.unsafeCall("Objective#" + methodName, RouteFamily.D_PLAYER_UI,
                    "scoreboard", "scoreboard-detached-objective-unsupported",
                    detail() + " policy=shim-model action=unsupported method=" + methodName
                            + " result=default-value");
            return defaultValue(method.getReturnType());
        }
    }

    private static final class DetachedScoreModel implements InvocationHandler {
        private final DetachedObjectiveModel objective;
        private final String entry;
        private final Score proxy;
        private Integer value;
        private boolean triggerable;
        private Component customName;
        private NumberFormat numberFormat;

        private DetachedScoreModel(DetachedObjectiveModel objective, String entry) {
            this.objective = objective;
            this.entry = entry;
            this.proxy = (Score) Proxy.newProxyInstance(Score.class.getClassLoader(),
                    new Class<?>[]{Score.class}, this);
        }

        static DetachedScoreModel createModel(DetachedObjectiveModel objective, String entry) {
            return new DetachedScoreModel(objective, entry);
        }

        int getScore() {
            return value == null ? 0 : value;
        }

        void setScore(int value) {
            this.value = value;
        }

        void resetScore() {
            value = null;
        }

        void customName(Component customName) {
            this.customName = customName;
        }

        void numberFormat(NumberFormat format) {
            this.numberFormat = format;
        }

        String detail() {
            return objective.detail() + " entry=" + entry + " set=" + (value != null);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("toString".equals(methodName)) return detail();
            if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
            if ("equals".equals(methodName)) return proxy == (args == null ? null : args[0]);
            if ("getEntry".equals(methodName)) return entry;
            if ("getObjective".equals(methodName)) return objective.proxy;
            if ("getScoreboard".equals(methodName)) return objective.scoreboard.proxy;
            if ("getScore".equals(methodName)) return getScore();
            if ("setScore".equals(methodName) && args != null && args.length == 1) {
                setScore((Integer) args[0]);
                return null;
            }
            if ("isScoreSet".equals(methodName)) return value != null;
            if ("resetScore".equals(methodName)) {
                resetScore();
                return null;
            }
            if ("isTriggerable".equals(methodName)) return triggerable;
            if ("setTriggerable".equals(methodName) && args != null && args.length == 1) {
                triggerable = Boolean.TRUE.equals(args[0]);
                return null;
            }
            if ("customName".equals(methodName)) {
                if (args != null && args.length == 1) {
                    customName((Component) args[0]);
                    return null;
                }
                return customName;
            }
            if ("numberFormat".equals(methodName)) {
                if (args != null && args.length == 1) {
                    numberFormat((NumberFormat) args[0]);
                    return null;
                }
                return numberFormat;
            }

            BridgeDiagnostics.unsafeCall("Score#" + methodName, RouteFamily.D_PLAYER_UI,
                    "scoreboard", "scoreboard-detached-score-unsupported",
                    detail() + " policy=shim-model action=unsupported method=" + methodName
                            + " result=default-value");
            return defaultValue(method.getReturnType());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Throwable;
    }

    private static String playerDetail(Player player) {
        if (player == null) return "player=null";
        try {
            return "player=" + player.getName();
        } catch (Throwable ignored) {
            return "player=" + player.getClass().getName();
        }
    }

    private static String playerReadDetail(Player player) {
        return playerDetail(player) + " policy=entity-owner-read-return"
                + " contract=entity-owner-read-return";
    }

    private static String humanDetail(HumanEntity human) {
        if (human == null) return "human=null";
        try {
            return "human=" + human.getName();
        } catch (Throwable ignored) {
            return "human=" + human.getClass().getName();
        }
    }

    private static String inventoryDetail(Inventory inventory) {
        if (inventory == null) return "inventory=null";
        try {
            return "inventoryType=" + inventory.getType() + " size=" + inventory.getSize();
        } catch (Throwable ignored) {
            return "inventory=" + inventory.getClass().getName();
        }
    }

    private static String livingDetail(LivingEntity entity) {
        if (entity == null) return "livingEntity=null";
        try {
            return "livingEntity=" + entity.getType() + ":" + entity.getUniqueId();
        } catch (Throwable ignored) {
            return "livingEntity=" + entity.getClass().getName();
        }
    }

    private static String entityDetail(Entity entity) {
        if (entity == null) return "entity=null";
        try {
            return "entity=" + entity.getType() + ":" + entity.getUniqueId();
        } catch (Throwable ignored) {
            return "entity=" + entity.getClass().getName();
        }
    }

    private static String entityReadDetail(Entity entity) {
        return entityDetail(entity) + " policy=entity-owner-read-return"
                + " contract=entity-owner-read-return";
    }

    private static String senderDetail(CommandSender sender) {
        if (sender == null) return "sender=null";
        try {
            return "sender=" + sender.getName() + " type=" + sender.getClass().getName();
        } catch (Throwable ignored) {
            return "sender=" + sender.getClass().getName();
        }
    }

    private static String commandDetail(String command) {
        if (command == null) return "command=null";
        String normalized = safeText(command);
        if (normalized.length() > 96) {
            normalized = normalized.substring(0, 96) + "...";
        }
        return "command=" + normalized;
    }

    private static String commandDispatchScheduler(CommandSender sender) {
        return sender instanceof Entity ? "entity-scheduler" : "global-region-scheduler";
    }

    private static String worldDetail(World world) {
        if (world == null) return "world=null";
        try {
            return "world=" + world.getName();
        } catch (Throwable ignored) {
            return "world=" + world.getClass().getName();
        }
    }

    private static String blockDetail(Block block) {
        if (block == null) return "block=null";
        try {
            return "block=" + worldDetail(block.getWorld()) + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        } catch (Throwable ignored) {
            return "block=" + block.getClass().getName();
        }
    }

    private static String chunkDetail(Chunk chunk) {
        if (chunk == null) return "chunk=null";
        try {
            return "chunk=" + worldDetail(chunk.getWorld()) + "," + chunk.getX() + "," + chunk.getZ();
        } catch (Throwable ignored) {
            return "chunk=" + chunk.getClass().getName();
        }
    }

    private static String locationDetail(Location location) {
        if (location == null) return "location=null";
        return worldDetail(location.getWorld()) + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private static String itemDetail(ItemStack item) {
        if (item == null) return "item=null";
        try {
            return item.getType() + "x" + item.getAmount();
        } catch (Throwable ignored) {
            return item.getClass().getName();
        }
    }

    private static String potionEffectDetail(PotionEffect effect) {
        if (effect == null) return "effect=null";
        try {
            return effect.getType() + " duration=" + effect.getDuration() + " amplifier=" + effect.getAmplifier();
        } catch (Throwable ignored) {
            return effect.getClass().getName();
        }
    }

    private static String scoreboardDetail(Scoreboard scoreboard) {
        if (scoreboard == null) return "scoreboard=null";
        return "scoreboard=" + scoreboard.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(scoreboard));
    }

    private static String teamDetail(Team team) {
        if (team == null) return "team=null";
        try {
            return "team=" + safeText(team.getName());
        } catch (Throwable ignored) {
            return "team=" + team.getClass().getName();
        }
    }

    private static String objectiveDetail(Objective objective) {
        if (objective == null) return "objective=null";
        try {
            return "objective=" + safeText(objective.getName());
        } catch (Throwable ignored) {
            return "objective=" + objective.getClass().getName();
        }
    }

    private static String scoreDetail(Score score) {
        if (score == null) return "score=null";
        try {
            return "score=" + safeText(score.getEntry());
        } catch (Throwable ignored) {
            return "score=" + score.getClass().getName();
        }
    }

    private static String safeText(String value) {
        return value == null ? "null" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String classList(Class<?>[] classes) {
        if (classes == null) return "classes=null";
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < classes.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(className(classes[index]));
        }
        return builder.append(']').toString();
    }

    private static String className(Class<?> clazz) {
        return clazz == null ? "null" : clazz.getName();
    }
}
