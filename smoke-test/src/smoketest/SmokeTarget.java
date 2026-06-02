package smoketest;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import smoketest.shaded.GenericTeleportHelperLike;
import smoketest.shaded.GenericTeleportSchedulerLike;
import smoketest.shaded.GenericTeleportSchedulerLikeImpl;
import smoketest.shaded.PaperLibLike;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

final class SmokeTarget {

    private SmokeTarget() {
    }

    static void runSchedulerCalls(BukkitScheduler scheduler, Plugin plugin) {
        scheduler.runTask(plugin, (Runnable) () -> {
        });
        scheduler.runTaskLater(plugin, (Runnable) () -> {
        }, 2L);
        scheduler.runTaskTimer(plugin, (Runnable) () -> {
        }, 1L, 20L);
        scheduler.runTaskAsynchronously(plugin, (Runnable) () -> {
        });
        scheduler.runTaskLaterAsynchronously(plugin, (Runnable) () -> {
        }, 3L);
        scheduler.runTaskTimerAsynchronously(plugin, (Runnable) () -> {
        }, 4L, 40L);
        scheduler.scheduleSyncDelayedTask(plugin, (Runnable) () -> {
        });
        scheduler.scheduleSyncDelayedTask(plugin, (Runnable) () -> {
        }, 5L);
        scheduler.scheduleSyncRepeatingTask(plugin, (Runnable) () -> {
        }, 1L, 20L);
        scheduler.scheduleAsyncDelayedTask(plugin, (Runnable) () -> {
        });
        scheduler.scheduleAsyncDelayedTask(plugin, (Runnable) () -> {
        }, 6L);
        scheduler.scheduleAsyncRepeatingTask(plugin, (Runnable) () -> {
        }, 7L, 70L);
        scheduler.callSyncMethod(plugin, (Callable<String>) () -> "ok");
        scheduler.cancelTask(123);
        scheduler.cancelTasks(plugin);
    }

    static void runBukkitRunnableCalls(Plugin plugin) {
        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
            }
        };
        runnable.runTask(plugin);
        runnable.runTaskLater(plugin, 1L);
        runnable.runTaskTimer(plugin, 1L, 20L);
        runnable.runTaskAsynchronously(plugin);
        runnable.runTaskLaterAsynchronously(plugin, 2L);
        runnable.runTaskTimerAsynchronously(plugin, 3L, 30L);
        runnable.isCancelled();
        runnable.cancel();
    }

    static boolean runStaticCommandDispatch(CommandSender sender) {
        return Bukkit.dispatchCommand(sender, "fbb_smoke_unknown");
    }

    static boolean runServerCommandDispatch(Server server, CommandSender sender) {
        return server.dispatchCommand(sender, "fbb_smoke_unknown");
    }

    static void runCustomEventDispatch(PluginManager pluginManager) {
        pluginManager.callEvent(new SmokeSharedEvent());
    }

    static void runMethodReferenceUnsafeCalls(World world) {
        Supplier<java.util.List<org.bukkit.entity.Entity>> supplier = world::getEntities;
        try {
            supplier.get();
        } catch (NullPointerException expected) {
        }
    }

    @SuppressWarnings("deprecation")
    static void runUnsafeCalls(Player player, Plugin plugin, World world, Block block) {
        try {
            player.getLocation();
        } catch (NullPointerException expected) {
        }
        try {
            player.getWorld();
        } catch (NullPointerException expected) {
        }
        try {
            player.teleport(new Location(world, 1, 2, 3));
        } catch (NullPointerException expected) {
        }
        try {
            player.teleport(new Location(world, 1, 2, 3), TeleportCause.COMMAND);
        } catch (NullPointerException expected) {
        }
        try {
            player.teleportAsync(new Location(world, 1, 2, 3), TeleportCause.COMMAND);
        } catch (NullPointerException expected) {
        }
        try {
            PaperLibLike.teleportAsync(player, new Location(world, 1, 2, 3), TeleportCause.COMMAND);
        } catch (NullPointerException expected) {
        }
        try {
            PaperLibLike.teleportAsync(player, new Location(world, 1, 2, 3), TeleportCause.COMMAND, true);
        } catch (NullPointerException expected) {
        }
        new GenericTeleportHelperLike().teleportAsync(player, new Location(world, 1, 2, 3));
        GenericTeleportSchedulerLike teleportScheduler = new GenericTeleportSchedulerLikeImpl();
        teleportScheduler.teleportAsync(player, new Location(world, 1, 2, 3));
        teleportScheduler.teleportAsync(player, new Location(world, 1, 2, 3), TeleportCause.COMMAND);
        try {
            player.setVelocity(new Vector(0, 0, 0));
        } catch (NullPointerException expected) {
        }
        try {
            player.getNearbyEntities(1, 2, 3);
        } catch (NullPointerException expected) {
        }
        try {
            player.remove();
        } catch (NullPointerException expected) {
        }
        try {
            player.playSound(new Location(world, 1, 2, 3), (Sound) null, 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            player.playSound(new Location(world, 1, 2, 3), "minecraft:block.note_block.pling", 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            player.playSound(new Location(world, 1, 2, 3), (Sound) null, (SoundCategory) null, 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            player.playSound(new Location(world, 1, 2, 3), "minecraft:block.note_block.pling", (SoundCategory) null, 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            player.closeInventory();
        } catch (NullPointerException expected) {
        }
        try {
            player.setGameMode(GameMode.SURVIVAL);
        } catch (NullPointerException expected) {
        }
        try {
            player.addPotionEffect(null);
        } catch (NullPointerException expected) {
        }
        try {
            player.removePotionEffect(null);
        } catch (NullPointerException expected) {
        }
        try {
            player.openInventory((Inventory) null);
        } catch (NullPointerException expected) {
        }
        try {
            player.getScoreboard();
        } catch (NullPointerException expected) {
        }
        try {
            player.setScoreboard(player.getScoreboard());
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            scoreboard.getTeam("FBBSmoke");
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            scoreboard.registerNewTeam("FBBSmoke");
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team team = scoreboard.registerNewTeam("FBBSmokeOption");
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            team.setPrefix("[FBB]");
            team.setSuffix("Smoke");
            team.setDisplayName("FBB Smoke Team");
            team.setColor(ChatColor.GREEN);
            team.setAllowFriendlyFire(false);
            team.setCanSeeFriendlyInvisibles(true);
            team.prefix(Component.text("[FBB]"));
            team.suffix(Component.text("Smoke"));
            team.displayName(Component.text("FBB Smoke Team"));
            team.color(NamedTextColor.GREEN);
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team team = scoreboard.registerNewTeam("FBBSmokeAdd");
            team.addEntry("SmokePlayer");
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team team = scoreboard.registerNewTeam("FBBSmokeRemove");
            team.removeEntry("SmokePlayer");
        } catch (NullPointerException expected) {
        }
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Objective objective = scoreboard.registerNewObjective("FBBSmokeObj", "dummy", "FBB Smoke");
            objective.displayName(Component.text("FBB Smoke"));
            objective.numberFormat(NumberFormat.blank());
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            Score score = objective.getScore("SmokePlayer");
            score.customName(Component.text("Smoke Player"));
            score.numberFormat(NumberFormat.blank());
            score.setScore(7);
            score.getScore();
            scoreboard.getObjective("FBBSmokeObj");
            scoreboard.getObjective(DisplaySlot.SIDEBAR);
            scoreboard.getObjectives();
        } catch (NullPointerException expected) {
        }
        try {
            player.hidePlayer(plugin, player);
        } catch (NullPointerException expected) {
        }
        try {
            player.showPlayer(plugin, player);
        } catch (NullPointerException expected) {
        }
        try {
            player.hidePlayer(player);
        } catch (NullPointerException expected) {
        }
        try {
            player.showPlayer(player);
        } catch (NullPointerException expected) {
        }
        try {
            world.getBlockAt(1, 2, 3);
        } catch (NullPointerException expected) {
        }
        try {
            world.getBlockAt(new Location(world, 4, 5, 6));
        } catch (NullPointerException expected) {
        }
        try {
            world.getEntities();
        } catch (NullPointerException expected) {
        }
        try {
            world.getLoadedChunks();
        } catch (NullPointerException expected) {
        }
        try {
            world.getNearbyEntities(new Location(world, 1, 2, 3), 4, 5, 6);
        } catch (NullPointerException expected) {
        }
        try {
            world.getEntitiesByClass(Player.class);
        } catch (NullPointerException expected) {
        }
        try {
            world.getEntitiesByClass(Player.class, Player.class);
        } catch (NullPointerException expected) {
        }
        try {
            world.getEntitiesByClasses(Player.class);
        } catch (NullPointerException expected) {
        }
        try {
            world.getChunkAt(7, 8);
        } catch (NullPointerException expected) {
        }
        try {
            world.refreshChunk(7, 8);
        } catch (NullPointerException expected) {
        }
        try {
            block.getChunk().getEntities();
        } catch (NullPointerException expected) {
        }
        try {
            world.dropItem(new Location(world, 1, 2, 3), (ItemStack) null);
        } catch (NullPointerException expected) {
        }
        try {
            world.dropItemNaturally(new Location(world, 1, 2, 3), (ItemStack) null);
        } catch (NullPointerException expected) {
        }
        try {
            world.spawnEntity(new Location(world, 1, 2, 3), EntityType.ARMOR_STAND);
        } catch (NullPointerException expected) {
        }
        try {
            world.generateTree(new Location(world, 1, 2, 3), (TreeType) null);
        } catch (NullPointerException expected) {
        }
        try {
            world.strikeLightning(new Location(world, 1, 2, 3));
        } catch (NullPointerException expected) {
        }
        try {
            world.strikeLightningEffect(new Location(world, 1, 2, 3));
        } catch (NullPointerException expected) {
        }
        try {
            world.createExplosion(new Location(world, 1, 2, 3), 0F);
        } catch (NullPointerException expected) {
        }
        try {
            world.createExplosion(1D, 2D, 3D, 0F);
        } catch (NullPointerException expected) {
        }
        try {
            world.playSound(new Location(world, 1, 2, 3), (Sound) null, 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            world.playSound(new Location(world, 1, 2, 3), "minecraft:block.note_block.pling", 1F, 1F);
        } catch (NullPointerException expected) {
        }
        try {
            block.getType();
        } catch (NullPointerException expected) {
        }
        try {
            block.getBlockData();
        } catch (NullPointerException expected) {
        }
        try {
            block.setType(Material.STONE);
        } catch (NullPointerException expected) {
        }
        try {
            block.getLocation();
        } catch (NullPointerException expected) {
        }
    }

    @SuppressWarnings("deprecation")
    static void runPluginShapeUnsafeCalls(Player player, Plugin plugin) {
        Inventory inventory = null;
        ItemStack item = null;
        Location location = player.getLocation();
        World world = player.getWorld();
        Chunk chunk = world.getChunkAt(location);

        player.playSound(location, (Sound) null, 1F, 1F);
        player.closeInventory();
        player.openInventory(inventory);
        ((HumanEntity) player).openInventory(inventory);
        ((HumanEntity) player).closeInventory();
        player.setScoreboard(player.getScoreboard());
        Scoreboard scoreboard = player.getScoreboard();
        Team team = scoreboard.registerNewTeam("FBBSmokeShape");
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team.addEntry(player.getName());
        team.removeEntry(player.getName());

        world.dropItemNaturally(player.getLocation(), item);
        world.dropItem(location, item);
        world.spawnEntity(location, EntityType.ARMOR_STAND);
        world.getNearbyEntities(location, 16D, 16D, 16D);
        world.getEntitiesByClass(Player.class);
        world.getEntitiesByClasses(Player.class);
        chunk.getEntities();
        world.generateTree(location, (TreeType) null);
        world.strikeLightning(location);
        world.strikeLightningEffect(location);
        world.createExplosion(location, 0F);
    }

    static void runScoreboardManagerShapeCalls(ScoreboardManager manager, Player player) {
        Scoreboard scoreboard = manager.getNewScoreboard();
        Team team = scoreboard.registerNewTeam("FBBSmokeDetached");
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team.addEntry(player.getName());
        team.removeEntry(player.getName());
        player.setScoreboard(scoreboard);
    }

    public static final class SmokeSharedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeNoOwnerEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public String reason() {
            return "no-owner-getter";
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeEntityOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final Entity entity;

        public SmokeEntityOwnedEvent(Entity entity) {
            this.entity = entity;
        }

        public Entity getEntity() {
            return entity;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeMultiBlockCollectionOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final List<Block> blocks;

        public SmokeMultiBlockCollectionOwnedEvent(List<Block> blocks) {
            this.blocks = blocks;
        }

        public List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeSingleBlockOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final Block block;

        public SmokeSingleBlockOwnedEvent(Block block) {
            this.block = block;
        }

        public Block getBlock() {
            return block;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeDelegatedBlockOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final Event originalEvent;

        public SmokeDelegatedBlockOwnedEvent(Event originalEvent) {
            this.originalEvent = originalEvent;
        }

        public Event getOriginalEvent() {
            return originalEvent;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeBlockCollectionOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final List<Block> blocks;

        public SmokeBlockCollectionOwnedEvent(List<Block> blocks) {
            this.blocks = blocks;
        }

        public List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class SmokeLocationOwnedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        private final Location location;

        public SmokeLocationOwnedEvent(Location location) {
            this.location = location;
        }

        public Location getLocation() {
            return location;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
