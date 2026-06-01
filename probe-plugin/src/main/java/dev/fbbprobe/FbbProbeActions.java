package dev.fbbprobe;

import dev.fbbprobe.harness.ProbeActions;
import dev.fbbprobe.harness.ProbeRuntime;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class FbbProbeActions implements ProbeActions {

    @Override
    public void runStartupProbes(ProbeRuntime runtime) {
        if (Bukkit.getWorlds().isEmpty()) {
            runtime.probeBlocked("S_GLOBAL", "Bukkit#getWorlds",
                    "org.bukkit.Bukkit", "getWorlds", "()Ljava/util/List;",
                    "server-startup", "no-world-loaded");
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        Location location = world.getSpawnLocation();
        Block block = location.getBlock();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        // Startup probes deliberately avoid player-only APIs. They are the boot-time evidence layer:
        // server/world/chunk/unowned-scoreboard methods that many plugins touch during enable, async tasks,
        // map scans, or edits. Keep this bucket broad so live restarts produce evidence without requiring a join.
        runtime.probe("S_GLOBAL", "Bukkit#getWorlds", "org.bukkit.Bukkit", "getWorlds",
                "()Ljava/util/List;", Bukkit::getWorlds);
        runtime.probeGuard("S_GLOBAL", "Bukkit#dispatchCommand(CommandSender,String)",
                "org.bukkit.Bukkit", "dispatchCommand",
                "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z",
                "CraftServer#dispatchCommand",
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fbbprobe_internal_unknown"));
        runtime.probeGuard("D_PLAYER_UI", "Bukkit#getScoreboardManager",
                "org.bukkit.Bukkit", "getScoreboardManager",
                "()Lorg/bukkit/scoreboard/ScoreboardManager;",
                "CraftScoreboardManager#unowned", Bukkit::getScoreboardManager);
        runtime.probeGuard("D_PLAYER_UI", "ScoreboardManager#getNewScoreboard",
                "org.bukkit.scoreboard.ScoreboardManager", "getNewScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;",
                "CraftScoreboardManager#unowned", () -> Bukkit.getScoreboardManager().getNewScoreboard());
        runtime.probeGuard("D_PLAYER_UI", "ScoreboardManager#getMainScoreboard",
                "org.bukkit.scoreboard.ScoreboardManager", "getMainScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;",
                "CraftScoreboardManager#global-model", () -> Bukkit.getScoreboardManager().getMainScoreboard());
        runtime.probe("D_PLAYER_UI", "Scoreboard#registerNewObjective(String,String,String)",
                "org.bukkit.scoreboard.Scoreboard", "registerNewObjective",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lorg/bukkit/scoreboard/Objective;",
                () -> newStartupScoreboardObjective().getName());
        runtime.probe("D_PLAYER_UI", "Objective#setDisplaySlot(DisplaySlot)",
                "org.bukkit.scoreboard.Objective", "setDisplaySlot",
                "(Lorg/bukkit/scoreboard/DisplaySlot;)V",
                () -> newStartupScoreboardObjective().setDisplaySlot(DisplaySlot.SIDEBAR));
        runtime.probe("D_PLAYER_UI", "Objective#displayName(Component)",
                "org.bukkit.scoreboard.Objective", "displayName",
                "(Lnet/kyori/adventure/text/Component;)V",
                () -> newStartupScoreboardObjective().displayName(Component.text("FBB Probe")));
        runtime.probe("D_PLAYER_UI", "Score#setScore(int)",
                "org.bukkit.scoreboard.Score", "setScore", "(I)V",
                () -> newStartupScoreboardObjective().getScore("FBBProbeStartup").setScore(1));
        runtime.probe("D_PLAYER_UI", "Score#customName(Component)",
                "org.bukkit.scoreboard.Score", "customName",
                "(Lnet/kyori/adventure/text/Component;)V",
                () -> newStartupScoreboardObjective().getScore("FBBProbeStartup")
                        .customName(Component.text("FBB Probe")));

        runtime.probe("B_REGION_LOCATION", "World#getBlockAt(Location)", "org.bukkit.World", "getBlockAt",
                "(Lorg/bukkit/Location;)Lorg/bukkit/block/Block;", () -> world.getBlockAt(location));
        runtime.probe("C_REGION_BLOCK", "World#getBlockAt(int,int,int)", "org.bukkit.World", "getBlockAt",
                "(III)Lorg/bukkit/block/Block;",
                () -> world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(int,int)", "org.bukkit.World", "getChunkAt",
                "(II)Lorg/bukkit/Chunk;", () -> world.getChunkAt(chunkX, chunkZ));
        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(Location)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/Location;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(location));
        runtime.probe("C_REGION_BLOCK", "World#getChunkAt(Block)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/block/Block;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(block));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntities", "org.bukkit.World", "getEntities",
                "()Ljava/util/List;", () -> world.getEntities());
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getLoadedChunks", "org.bukkit.World", "getLoadedChunks",
                "()[Lorg/bukkit/Chunk;", () -> world.getLoadedChunks());
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getNearbyEntities(Location,double,double,double)",
                "org.bukkit.World", "getNearbyEntities", "(Lorg/bukkit/Location;DDD)Ljava/util/Collection;",
                () -> world.getNearbyEntities(location, 8.0D, 8.0D, 8.0D));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class)", "org.bukkit.World", "getEntitiesByClass",
                "(Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class...)", "org.bukkit.World", "getEntitiesByClass",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class, Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClasses(Class...)", "org.bukkit.World", "getEntitiesByClasses",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClasses(Player.class, Entity.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "Chunk#getEntities", "org.bukkit.Chunk", "getEntities",
                "()[Lorg/bukkit/entity/Entity;", () -> world.getChunkAt(location).getEntities());

        runtime.probeGuard("B_REGION_LOCATION", "World#loadChunk(int,int)",
                "org.bukkit.World", "loadChunk", "(II)V",
                "CraftWorld#chunk-load-unload", () -> world.loadChunk(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#isChunkLoaded(int,int)",
                "org.bukkit.World", "isChunkLoaded", "(II)Z",
                "chunk-context-baseline", () -> world.isChunkLoaded(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#refreshChunk(int,int)",
                "org.bukkit.World", "refreshChunk", "(II)Z",
                "CraftWorld#chunk-load-unload", () -> world.refreshChunk(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#playSound(Location,Sound,float,float)",
                "org.bukkit.World", "playSound", "(Lorg/bukkit/Location;Lorg/bukkit/Sound;FF)V",
                "CraftWorld#sound", () -> world.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.1F, 1.0F));
        runtime.probeGuard("B_REGION_LOCATION", "World#playSound(Location,String,float,float)",
                "org.bukkit.World", "playSound", "(Lorg/bukkit/Location;Ljava/lang/String;FF)V",
                "CraftWorld#sound", () -> world.playSound(location, "minecraft:block.note_block.pling", 0.1F, 1.0F));
        runtime.probe("B_REGION_LOCATION", "World#createExplosion(Location,float)", "org.bukkit.World", "createExplosion",
                "(Lorg/bukkit/Location;F)Z", () -> world.createExplosion(location, 0.0F));
        runtime.probe("B_REGION_LOCATION", "World#createExplosion(double,double,double,float)",
                "org.bukkit.World", "createExplosion", "(DDDF)Z",
                () -> world.createExplosion(location.getX(), location.getY(), location.getZ(), 0.0F));

        runtime.probeBlocked("A_ENTITY", "Player-only startup probes",
                "org.bukkit.entity.Player", "*", "player-required",
                "startup-no-player", "use first-join or /fbbprobe matrix safe for player/entity paths");
    }

    @Override
    public void runSafeProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();
        Block block = location.getBlock();

        runtime.probe("A_ENTITY", "Player#getLocation", "org.bukkit.entity.Player", "getLocation",
                "()Lorg/bukkit/Location;", player::getLocation);
        runtime.probe("A_ENTITY", "Player#getWorld", "org.bukkit.entity.Player", "getWorld",
                "()Lorg/bukkit/World;", player::getWorld);
        runtime.probe("A_ENTITY", "Player#teleport(Location)", "org.bukkit.entity.Player", "teleport",
                "(Lorg/bukkit/Location;)Z", () -> player.teleport(location));
        runtime.probe("A_ENTITY", "Player#teleport(Location,TeleportCause)", "org.bukkit.entity.Player", "teleport",
                "(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z",
                () -> player.teleport(location, TeleportCause.PLUGIN));
        runtime.probe("A_ENTITY", "Player#setVelocity(Vector)", "org.bukkit.entity.Player", "setVelocity",
                "(Lorg/bukkit/util/Vector;)V", () -> player.setVelocity(player.getVelocity()));
        runtime.probe("G_WORLD_SCAN_SPLIT", "Player#getNearbyEntities", "org.bukkit.entity.Player", "getNearbyEntities",
                "(DDD)Ljava/util/List;", () -> player.getNearbyEntities(1.0D, 1.0D, 1.0D));

        runtime.probe("B_REGION_LOCATION", "World#getBlockAt(Location)", "org.bukkit.World", "getBlockAt",
                "(Lorg/bukkit/Location;)Lorg/bukkit/block/Block;", () -> world.getBlockAt(location));
        runtime.probe("C_REGION_BLOCK", "World#getBlockAt(int,int,int)", "org.bukkit.World", "getBlockAt",
                "(III)Lorg/bukkit/block/Block;", () -> world.getBlockAt(block.getX(), block.getY(), block.getZ()));
        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(int,int)", "org.bukkit.World", "getChunkAt",
                "(II)Lorg/bukkit/Chunk;", () -> world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4));
        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(Location)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/Location;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(location));
        runtime.probe("C_REGION_BLOCK", "World#getChunkAt(Block)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/block/Block;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(block));
        runtime.probe("C_REGION_BLOCK", "Block#getLocation", "org.bukkit.block.Block", "getLocation",
                "()Lorg/bukkit/Location;", block::getLocation);
    }

    @Override
    public void runScanProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();

        // Folia-aware adapters usually route broad scans carefully instead of pretending a whole world
        // belongs to one thread. These probes keep scan evidence separate from simple current-region reads.
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntities", "org.bukkit.World", "getEntities",
                "()Ljava/util/List;", () -> world.getEntities());
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getNearbyEntities(Location,double,double,double)",
                "org.bukkit.World", "getNearbyEntities", "(Lorg/bukkit/Location;DDD)Ljava/util/Collection;",
                () -> world.getNearbyEntities(location, 8.0D, 8.0D, 8.0D));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class)", "org.bukkit.World", "getEntitiesByClass",
                "(Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class...)", "org.bukkit.World", "getEntitiesByClass",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class, Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClasses(Class...)", "org.bukkit.World", "getEntitiesByClasses",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClasses(Player.class, Entity.class));
        // Resolve the chunk inside the probe body so async/global failures are attributed to the
        // intended B_REGION_LOCATION -> G_WORLD_SCAN_SPLIT bytecode path instead of escaping as
        // an unclassified Location#getChunk setup failure.
        runtime.probe("G_WORLD_SCAN_SPLIT", "Chunk#getEntities", "org.bukkit.Chunk", "getEntities",
                "()[Lorg/bukkit/entity/Entity;", () -> world.getChunkAt(location).getEntities());
        runtime.probe("B_REGION_LOCATION", "World#getLoadedChunks", "org.bukkit.World", "getLoadedChunks",
                "()[Lorg/bukkit/Chunk;", () -> world.getLoadedChunks());
    }

    @Override
    public void runUiProbes(ProbeRuntime runtime, Player player) {
        HumanEntity human = player;
        Inventory inventory = Bukkit.createInventory(null, 9, "FBB Probe");
        runtime.probe("D_PLAYER_UI", "Player#openInventory", "org.bukkit.entity.Player", "openInventory",
                "(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView;",
                () -> player.openInventory(inventory));
        runtime.probe("D_PLAYER_UI", "Player#closeInventory", "org.bukkit.entity.Player", "closeInventory",
                "()V", () -> player.closeInventory());
        // Many plugin callsites compile against HumanEntity even when the runtime receiver is a Player.
        // Keep these owner-shape probes separate so bridge misses point at the exact bytecode owner.
        runtime.probe("D_PLAYER_UI", "HumanEntity#openInventory", "org.bukkit.entity.HumanEntity", "openInventory",
                "(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView;",
                () -> human.openInventory(inventory));
        runtime.probe("D_PLAYER_UI", "HumanEntity#closeInventory", "org.bukkit.entity.HumanEntity", "closeInventory",
                "()V", () -> human.closeInventory());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void runVisibilityProbes(ProbeRuntime runtime, Player player) {
        runtime.probe("F_PLAYER_VISIBILITY", "Player#hidePlayer(Plugin,Player)", "org.bukkit.entity.Player", "hidePlayer",
                "(Lorg/bukkit/plugin/Plugin;Lorg/bukkit/entity/Player;)V", () -> player.hidePlayer(runtime.plugin(), player));
        runtime.probe("F_PLAYER_VISIBILITY", "Player#showPlayer(Plugin,Player)", "org.bukkit.entity.Player", "showPlayer",
                "(Lorg/bukkit/plugin/Plugin;Lorg/bukkit/entity/Player;)V", () -> player.showPlayer(runtime.plugin(), player));
        runtime.probe("F_PLAYER_VISIBILITY", "Player#hidePlayer(Player)", "org.bukkit.entity.Player", "hidePlayer",
                "(Lorg/bukkit/entity/Player;)V", () -> player.hidePlayer(player));
        runtime.probe("F_PLAYER_VISIBILITY", "Player#showPlayer(Player)", "org.bukkit.entity.Player", "showPlayer",
                "(Lorg/bukkit/entity/Player;)V", () -> player.showPlayer(player));
    }

    @Override
    public void runEntityMutationProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        GameMode gameMode = player.getGameMode();
        Entity entity = player;
        HumanEntity human = player;
        LivingEntity living = player;

        runtime.probe("A_ENTITY", "Player#setGameMode(GameMode)", "org.bukkit.entity.Player", "setGameMode",
                "(Lorg/bukkit/GameMode;)V", () -> player.setGameMode(gameMode));
        runtime.probe("A_ENTITY", "HumanEntity#setGameMode(GameMode)", "org.bukkit.entity.HumanEntity", "setGameMode",
                "(Lorg/bukkit/GameMode;)V", () -> human.setGameMode(gameMode));
        runtime.probe("A_ENTITY", "Entity#setVelocity(Vector)", "org.bukkit.entity.Entity", "setVelocity",
                "(Lorg/bukkit/util/Vector;)V", () -> entity.setVelocity(entity.getVelocity()));
        runtime.probe("A_ENTITY", "Player#addPotionEffect(PotionEffect)",
                "org.bukkit.entity.Player", "addPotionEffect", "(Lorg/bukkit/potion/PotionEffect;)Z",
                () -> player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20, 0)));
        runtime.probe("A_ENTITY", "Player#removePotionEffect(PotionEffectType)",
                "org.bukkit.entity.Player", "removePotionEffect", "(Lorg/bukkit/potion/PotionEffectType;)V",
                () -> player.removePotionEffect(PotionEffectType.NIGHT_VISION));
        runtime.probe("A_ENTITY", "LivingEntity#addPotionEffect(PotionEffect)",
                "org.bukkit.entity.LivingEntity", "addPotionEffect", "(Lorg/bukkit/potion/PotionEffect;)Z",
                () -> living.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20, 0)));
        runtime.probe("A_ENTITY", "LivingEntity#removePotionEffect(PotionEffectType)",
                "org.bukkit.entity.LivingEntity", "removePotionEffect", "(Lorg/bukkit/potion/PotionEffectType;)V",
                () -> living.removePotionEffect(PotionEffectType.NIGHT_VISION));
        runtime.probe("A_ENTITY", "Player#playSound(Location,Sound,float,float)",
                "org.bukkit.entity.Player", "playSound", "(Lorg/bukkit/Location;Lorg/bukkit/Sound;FF)V",
                () -> player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.1F, 1.0F));
        runtime.probe("A_ENTITY", "Player#playSound(Location,String,float,float)",
                "org.bukkit.entity.Player", "playSound", "(Lorg/bukkit/Location;Ljava/lang/String;FF)V",
                () -> player.playSound(location, "minecraft:block.note_block.pling", 0.1F, 1.0F));
        runtime.probe("A_ENTITY", "Player#playSound(Location,Sound,SoundCategory,float,float)",
                "org.bukkit.entity.Player", "playSound",
                "(Lorg/bukkit/Location;Lorg/bukkit/Sound;Lorg/bukkit/SoundCategory;FF)V",
                () -> player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.1F, 1.0F));
        runtime.probe("A_ENTITY", "Player#setVelocity(Vector zero-copy)", "org.bukkit.entity.Player", "setVelocity",
                "(Lorg/bukkit/util/Vector;)V", () -> player.setVelocity(new Vector(
                        player.getVelocity().getX(), player.getVelocity().getY(), player.getVelocity().getZ())));
    }

    @Override
    public void runWorldEffectProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();
        Block block = location.getBlock();

        runtime.probe("C_REGION_BLOCK", "Block#setType(Material)", "org.bukkit.block.Block", "setType",
                "(Lorg/bukkit/Material;)V", () -> block.setType(block.getType()));
        runtime.probe("B_REGION_LOCATION", "World#strikeLightning(Location)", "org.bukkit.World", "strikeLightning",
                "(Lorg/bukkit/Location;)Lorg/bukkit/entity/LightningStrike;", () -> world.strikeLightning(location));
        runtime.probe("B_REGION_LOCATION", "World#strikeLightningEffect(Location)", "org.bukkit.World", "strikeLightningEffect",
                "(Lorg/bukkit/Location;)Lorg/bukkit/entity/LightningStrike;", () -> world.strikeLightningEffect(location));
        runtime.probe("B_REGION_LOCATION", "World#createExplosion(Location,float)", "org.bukkit.World", "createExplosion",
                "(Lorg/bukkit/Location;F)Z", () -> world.createExplosion(location, 0.0F));
        runtime.probe("B_REGION_LOCATION", "World#createExplosion(double,double,double,float)",
                "org.bukkit.World", "createExplosion", "(DDDF)Z",
                () -> world.createExplosion(location.getX(), location.getY(), location.getZ(), 0.0F));
        runtime.probeGuard("B_REGION_LOCATION", "World#playSound(Location,Sound,float,float)",
                "org.bukkit.World", "playSound", "(Lorg/bukkit/Location;Lorg/bukkit/Sound;FF)V",
                "CraftWorld#sound", () -> world.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.1F, 1.0F));
        runtime.probeGuard("B_REGION_LOCATION", "World#playSound(Location,String,float,float)",
                "org.bukkit.World", "playSound", "(Lorg/bukkit/Location;Ljava/lang/String;FF)V",
                "CraftWorld#sound", () -> world.playSound(location, "minecraft:block.note_block.pling", 0.1F, 1.0F));
    }

    @Override
    public void runChunkGuardProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        runtime.probeGuard("B_REGION_LOCATION", "World#loadChunk(int,int)",
                "org.bukkit.World", "loadChunk", "(II)V",
                "CraftWorld#chunk-load-unload", () -> world.loadChunk(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#isChunkLoaded(int,int)",
                "org.bukkit.World", "isChunkLoaded", "(II)Z",
                "chunk-context-baseline", () -> world.isChunkLoaded(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#refreshChunk(int,int)",
                "org.bukkit.World", "refreshChunk", "(II)Z",
                "CraftWorld#chunk-load-unload", () -> world.refreshChunk(chunkX, chunkZ));
    }

    @Override
    public void runServerGuardProbes(ProbeRuntime runtime, Player player) {
        runtime.probe("S_GLOBAL", "Bukkit#getOnlinePlayers",
                "org.bukkit.Bukkit", "getOnlinePlayers", "()Ljava/util/Collection;",
                Bukkit::getOnlinePlayers);
        runtime.probe("S_GLOBAL", "Bukkit#getPlayer(UUID)",
                "org.bukkit.Bukkit", "getPlayer", "(Ljava/util/UUID;)Lorg/bukkit/entity/Player;",
                () -> Bukkit.getPlayer(player.getUniqueId()));
        runtime.probeGuard("S_GLOBAL", "Bukkit#dispatchCommand(CommandSender,String)",
                "org.bukkit.Bukkit", "dispatchCommand",
                "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z",
                "CraftServer#dispatchCommand",
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fbbprobe_internal_unknown"));
    }

    @Override
    public void runScoreboardGuardProbes(ProbeRuntime runtime, Player player) {
        // Scoreboard probes are intentionally split between unowned manager calls and player-owned model calls.
        // The compatible-plugin references usually avoid getNewScoreboard() or bind team changes to a player context.
        runtime.probeGuard("D_PLAYER_UI", "Bukkit#getScoreboardManager",
                "org.bukkit.Bukkit", "getScoreboardManager",
                "()Lorg/bukkit/scoreboard/ScoreboardManager;",
                "CraftScoreboardManager#unowned", Bukkit::getScoreboardManager);
        runtime.probeGuard("D_PLAYER_UI", "ScoreboardManager#getNewScoreboard",
                "org.bukkit.scoreboard.ScoreboardManager", "getNewScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;",
                "CraftScoreboardManager#unowned", () -> Bukkit.getScoreboardManager().getNewScoreboard());
        runtime.probeGuard("D_PLAYER_UI", "ScoreboardManager#getMainScoreboard",
                "org.bukkit.scoreboard.ScoreboardManager", "getMainScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;",
                "CraftScoreboardManager#global-model", () -> Bukkit.getScoreboardManager().getMainScoreboard());

        runtime.probe("D_PLAYER_UI", "Player#getScoreboard",
                "org.bukkit.entity.Player", "getScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;", player::getScoreboard);
        runtime.probe("D_PLAYER_UI", "Player#setScoreboard",
                "org.bukkit.entity.Player", "setScoreboard",
                "(Lorg/bukkit/scoreboard/Scoreboard;)V",
                () -> player.setScoreboard(player.getScoreboard()));

        Scoreboard scoreboard = player.getScoreboard();
        runtime.probe("D_PLAYER_UI", "Scoreboard#getTeam(String)",
                "org.bukkit.scoreboard.Scoreboard", "getTeam",
                "(Ljava/lang/String;)Lorg/bukkit/scoreboard/Team;",
                () -> scoreboard.getTeam("FBBProbe"));
        runtime.probe("D_PLAYER_UI", "Scoreboard#getTeams",
                "org.bukkit.scoreboard.Scoreboard", "getTeams",
                "()Ljava/util/Set;", scoreboard::getTeams);
        runtime.probe("D_PLAYER_UI", "Scoreboard#getEntries",
                "org.bukkit.scoreboard.Scoreboard", "getEntries",
                "()Ljava/util/Set;", scoreboard::getEntries);
        runtime.probe("D_PLAYER_UI", "Scoreboard#getObjectives",
                "org.bukkit.scoreboard.Scoreboard", "getObjectives",
                "()Ljava/util/Set;", scoreboard::getObjectives);
        runtime.probe("D_PLAYER_UI", "Scoreboard#getObjective(String)",
                "org.bukkit.scoreboard.Scoreboard", "getObjective",
                "(Ljava/lang/String;)Lorg/bukkit/scoreboard/Objective;",
                () -> scoreboard.getObjective("FBBProbeObj"));
        runtime.probe("D_PLAYER_UI", "Scoreboard#registerNewTeam(String)",
                "org.bukkit.scoreboard.Scoreboard", "registerNewTeam",
                "(Ljava/lang/String;)Lorg/bukkit/scoreboard/Team;",
                () -> registerOrGetTeam(scoreboard, "FBBProbe"));
        runtime.probe("D_PLAYER_UI", "Scoreboard#registerNewObjective(String,String,String)",
                "org.bukkit.scoreboard.Scoreboard", "registerNewObjective",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lorg/bukkit/scoreboard/Objective;",
                () -> registerOrGetObjective(scoreboard, "FBBProbeObj"));
        Objective objective = scoreboard.getObjective("FBBProbeObj");
        if (objective == null) {
            blockObjectiveScoreProbes(runtime, "Scoreboard#registerNewObjective");
        } else {
            runtime.probe("D_PLAYER_UI", "Objective#setDisplaySlot(DisplaySlot)",
                    "org.bukkit.scoreboard.Objective", "setDisplaySlot",
                    "(Lorg/bukkit/scoreboard/DisplaySlot;)V",
                    () -> objective.setDisplaySlot(DisplaySlot.SIDEBAR));
            runtime.probe("D_PLAYER_UI", "Objective#getScore(String)",
                    "org.bukkit.scoreboard.Objective", "getScore",
                    "(Ljava/lang/String;)Lorg/bukkit/scoreboard/Score;",
                    () -> objective.getScore(player.getName()));
            Score score = objective.getScore(player.getName());
            runtime.probe("D_PLAYER_UI", "Score#setScore(int)",
                    "org.bukkit.scoreboard.Score", "setScore", "(I)V",
                    () -> score.setScore(1));
            runtime.probe("D_PLAYER_UI", "Score#getScore",
                    "org.bukkit.scoreboard.Score", "getScore", "()I", score::getScore);
            runtime.probe("D_PLAYER_UI", "Objective#displayName(Component)",
                    "org.bukkit.scoreboard.Objective", "displayName",
                    "(Lnet/kyori/adventure/text/Component;)V",
                    () -> objective.displayName(Component.text("FBB Probe")));
            runtime.probe("D_PLAYER_UI", "Objective#numberFormat(NumberFormat)",
                    "org.bukkit.scoreboard.Objective", "numberFormat",
                    "(Lio/papermc/paper/scoreboard/numbers/NumberFormat;)V",
                    () -> objective.numberFormat(NumberFormat.blank()));
            runtime.probe("D_PLAYER_UI", "Score#customName(Component)",
                    "org.bukkit.scoreboard.Score", "customName",
                    "(Lnet/kyori/adventure/text/Component;)V",
                    () -> score.customName(Component.text(player.getName())));
            runtime.probe("D_PLAYER_UI", "Score#numberFormat(NumberFormat)",
                    "org.bukkit.scoreboard.Score", "numberFormat",
                    "(Lio/papermc/paper/scoreboard/numbers/NumberFormat;)V",
                    () -> score.numberFormat(NumberFormat.blank()));
        }

        // Folia currently hard-disables Bukkit scoreboard team creation. Keep the failed creation
        // evidence, then avoid cascading Team#setOption/addEntry/removeEntry probes through the
        // same registerNewTeam guard because that mislabels the blocker in the live log.
        Team team = scoreboard.getTeam("FBBProbe");
        if (team == null) {
            blockTeamMutationProbes(runtime, "Scoreboard#registerNewTeam");
            return;
        }
        runtime.probe("D_PLAYER_UI", "Team#setOption(Option,OptionStatus)",
                "org.bukkit.scoreboard.Team", "setOption",
                "(Lorg/bukkit/scoreboard/Team$Option;Lorg/bukkit/scoreboard/Team$OptionStatus;)V",
                () -> team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER));
        runtime.probe("D_PLAYER_UI", "Team#setPrefix(String)",
                "org.bukkit.scoreboard.Team", "setPrefix", "(Ljava/lang/String;)V",
                () -> team.setPrefix("[FBB]"));
        runtime.probe("D_PLAYER_UI", "Team#setSuffix(String)",
                "org.bukkit.scoreboard.Team", "setSuffix", "(Ljava/lang/String;)V",
                () -> team.setSuffix("Probe"));
        runtime.probe("D_PLAYER_UI", "Team#setDisplayName(String)",
                "org.bukkit.scoreboard.Team", "setDisplayName", "(Ljava/lang/String;)V",
                () -> team.setDisplayName("FBB Probe Team"));
        runtime.probe("D_PLAYER_UI", "Team#setColor(ChatColor)",
                "org.bukkit.scoreboard.Team", "setColor", "(Lorg/bukkit/ChatColor;)V",
                () -> team.setColor(ChatColor.GREEN));
        runtime.probe("D_PLAYER_UI", "Team#setAllowFriendlyFire(boolean)",
                "org.bukkit.scoreboard.Team", "setAllowFriendlyFire", "(Z)V",
                () -> team.setAllowFriendlyFire(false));
        runtime.probe("D_PLAYER_UI", "Team#setCanSeeFriendlyInvisibles(boolean)",
                "org.bukkit.scoreboard.Team", "setCanSeeFriendlyInvisibles", "(Z)V",
                () -> team.setCanSeeFriendlyInvisibles(true));
        runtime.probe("D_PLAYER_UI", "Team#prefix(Component)",
                "org.bukkit.scoreboard.Team", "prefix", "(Lnet/kyori/adventure/text/Component;)V",
                () -> team.prefix(Component.text("[FBB]")));
        runtime.probe("D_PLAYER_UI", "Team#suffix(Component)",
                "org.bukkit.scoreboard.Team", "suffix", "(Lnet/kyori/adventure/text/Component;)V",
                () -> team.suffix(Component.text("Probe")));
        runtime.probe("D_PLAYER_UI", "Team#displayName(Component)",
                "org.bukkit.scoreboard.Team", "displayName", "(Lnet/kyori/adventure/text/Component;)V",
                () -> team.displayName(Component.text("FBB Probe Team")));
        runtime.probe("D_PLAYER_UI", "Team#color(NamedTextColor)",
                "org.bukkit.scoreboard.Team", "color",
                "(Lnet/kyori/adventure/text/format/NamedTextColor;)V",
                () -> team.color(NamedTextColor.GREEN));
        runtime.probe("D_PLAYER_UI", "Team#addEntry(String)",
                "org.bukkit.scoreboard.Team", "addEntry",
                "(Ljava/lang/String;)V",
                () -> team.addEntry(player.getName()));
        runtime.probe("D_PLAYER_UI", "Team#removeEntry(String)",
                "org.bukkit.scoreboard.Team", "removeEntry",
                "(Ljava/lang/String;)Z",
                () -> team.removeEntry(player.getName()));
    }

    private static Team registerOrGetTeam(Scoreboard scoreboard, String name) {
        Team team = scoreboard.getTeam(name);
        return team == null ? scoreboard.registerNewTeam(name) : team;
    }

    private static Objective registerOrGetObjective(Scoreboard scoreboard, String name) {
        Objective objective = scoreboard.getObjective(name);
        return objective == null ? scoreboard.registerNewObjective(name, "dummy", name) : objective;
    }

    private static Objective newStartupScoreboardObjective() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        return scoreboard.registerNewObjective("FBBProbeStartupObj", "dummy", "FBB Probe");
    }

    private static void blockObjectiveScoreProbes(ProbeRuntime runtime, String blockedBy) {
        String reason = "folia-hard-unsupported-scoreboard-objective-create";
        runtime.probeBlocked("D_PLAYER_UI", "Objective#setDisplaySlot(DisplaySlot)",
                "org.bukkit.scoreboard.Objective", "setDisplaySlot",
                "(Lorg/bukkit/scoreboard/DisplaySlot;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Objective#getScore(String)",
                "org.bukkit.scoreboard.Objective", "getScore",
                "(Ljava/lang/String;)Lorg/bukkit/scoreboard/Score;", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Score#setScore(int)",
                "org.bukkit.scoreboard.Score", "setScore", "(I)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Score#getScore",
                "org.bukkit.scoreboard.Score", "getScore", "()I", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Objective#displayName(Component)",
                "org.bukkit.scoreboard.Objective", "displayName",
                "(Lnet/kyori/adventure/text/Component;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Objective#numberFormat(NumberFormat)",
                "org.bukkit.scoreboard.Objective", "numberFormat",
                "(Lio/papermc/paper/scoreboard/numbers/NumberFormat;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Score#customName(Component)",
                "org.bukkit.scoreboard.Score", "customName",
                "(Lnet/kyori/adventure/text/Component;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Score#numberFormat(NumberFormat)",
                "org.bukkit.scoreboard.Score", "numberFormat",
                "(Lio/papermc/paper/scoreboard/numbers/NumberFormat;)V", blockedBy, reason);
    }

    private static void blockTeamMutationProbes(ProbeRuntime runtime, String blockedBy) {
        String reason = "folia-hard-unsupported-scoreboard-team-create";
        runtime.probeBlocked("D_PLAYER_UI", "Team#setOption(Option,OptionStatus)",
                "org.bukkit.scoreboard.Team", "setOption",
                "(Lorg/bukkit/scoreboard/Team$Option;Lorg/bukkit/scoreboard/Team$OptionStatus;)V",
                blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setPrefix(String)",
                "org.bukkit.scoreboard.Team", "setPrefix", "(Ljava/lang/String;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setSuffix(String)",
                "org.bukkit.scoreboard.Team", "setSuffix", "(Ljava/lang/String;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setDisplayName(String)",
                "org.bukkit.scoreboard.Team", "setDisplayName", "(Ljava/lang/String;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setColor(ChatColor)",
                "org.bukkit.scoreboard.Team", "setColor", "(Lorg/bukkit/ChatColor;)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setAllowFriendlyFire(boolean)",
                "org.bukkit.scoreboard.Team", "setAllowFriendlyFire", "(Z)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#setCanSeeFriendlyInvisibles(boolean)",
                "org.bukkit.scoreboard.Team", "setCanSeeFriendlyInvisibles", "(Z)V", blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#addEntry(String)",
                "org.bukkit.scoreboard.Team", "addEntry", "(Ljava/lang/String;)V",
                blockedBy, reason);
        runtime.probeBlocked("D_PLAYER_UI", "Team#removeEntry(String)",
                "org.bukkit.scoreboard.Team", "removeEntry", "(Ljava/lang/String;)Z",
                blockedBy, reason);
    }

    @Override
    public void runRecoveryPathProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();
        Block block = location.getBlock();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        // These are the routes that already recovered during live tests. Keep them together so
        // preemptive rewrites can be verified without hunting across several probe buckets.
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntities", "org.bukkit.World", "getEntities",
                "()Ljava/util/List;", () -> world.getEntities());
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class)", "org.bukkit.World", "getEntitiesByClass",
                "(Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClass(Class...)", "org.bukkit.World", "getEntitiesByClass",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClass(Player.class, Player.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getEntitiesByClasses(Class...)", "org.bukkit.World", "getEntitiesByClasses",
                "([Ljava/lang/Class;)Ljava/util/Collection;", () -> world.getEntitiesByClasses(Player.class, Entity.class));
        runtime.probe("G_WORLD_SCAN_SPLIT", "World#getLoadedChunks", "org.bukkit.World", "getLoadedChunks",
                "()[Lorg/bukkit/Chunk;", () -> world.getLoadedChunks());
        runtime.probe("G_WORLD_SCAN_SPLIT", "Chunk#getEntities", "org.bukkit.Chunk", "getEntities",
                "()[Lorg/bukkit/entity/Entity;", () -> world.getChunkAt(location).getEntities());

        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(int,int)", "org.bukkit.World", "getChunkAt",
                "(II)Lorg/bukkit/Chunk;", () -> world.getChunkAt(chunkX, chunkZ));
        runtime.probe("B_REGION_LOCATION", "World#getChunkAt(Location)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/Location;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(location));
        runtime.probe("C_REGION_BLOCK", "World#getChunkAt(Block)", "org.bukkit.World", "getChunkAt",
                "(Lorg/bukkit/block/Block;)Lorg/bukkit/Chunk;", () -> world.getChunkAt(block));
        runtime.probeGuard("B_REGION_LOCATION", "World#loadChunk(int,int)",
                "org.bukkit.World", "loadChunk", "(II)V",
                "CraftWorld#chunk-load-unload", () -> world.loadChunk(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#refreshChunk(int,int)",
                "org.bukkit.World", "refreshChunk", "(II)Z",
                "CraftWorld#chunk-load-unload", () -> world.refreshChunk(chunkX, chunkZ));
        runtime.probeGuard("B_REGION_LOCATION", "World#playSound(Location,Sound,float,float)",
                "org.bukkit.World", "playSound", "(Lorg/bukkit/Location;Lorg/bukkit/Sound;FF)V",
                "CraftWorld#sound", () -> world.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.1F, 1.0F));
        runtime.probe("B_REGION_LOCATION", "World#createExplosion(Location,float)", "org.bukkit.World", "createExplosion",
                "(Lorg/bukkit/Location;F)Z", () -> world.createExplosion(location, 0.0F));
    }

    @Override
    public void runDestructiveProbes(ProbeRuntime runtime, Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();

        runtime.probe("B_REGION_LOCATION", "World#dropItem(Location,ItemStack)",
                "org.bukkit.World", "dropItem", "(Lorg/bukkit/Location;Lorg/bukkit/inventory/ItemStack;)Lorg/bukkit/entity/Item;",
                () -> world.dropItem(location, new ItemStack(Material.STONE, 1)));
        runtime.probe("B_REGION_LOCATION", "World#dropItemNaturally(Location,ItemStack)",
                "org.bukkit.World", "dropItemNaturally", "(Lorg/bukkit/Location;Lorg/bukkit/inventory/ItemStack;)Lorg/bukkit/entity/Item;",
                () -> world.dropItemNaturally(location, new ItemStack(Material.STONE, 1)));
        runtime.probe("B_REGION_LOCATION", "World#spawnEntity(Location,EntityType)",
                "org.bukkit.World", "spawnEntity", "(Lorg/bukkit/Location;Lorg/bukkit/entity/EntityType;)Lorg/bukkit/entity/Entity;",
                () -> {
                    Entity entity = world.spawnEntity(location, EntityType.ARMOR_STAND);
                    entity.remove();
                });
        runtime.probe("B_REGION_LOCATION", "World#generateTree(Location,TreeType)",
                "org.bukkit.World", "generateTree", "(Lorg/bukkit/Location;Lorg/bukkit/TreeType;)Z",
                () -> world.generateTree(location, TreeType.TREE));
    }
}
