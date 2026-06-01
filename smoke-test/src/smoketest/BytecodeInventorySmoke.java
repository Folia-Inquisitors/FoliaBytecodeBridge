package smoketest;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class BytecodeInventorySmoke {

    private static final Probe[] REQUIRED = {
            new Probe("bukkitrunnable-runTaskAsync", null, "runTaskAsynchronously",
                    "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;"),
            new Probe("bukkitrunnable-runTaskTimerAsync", null, "runTaskTimerAsynchronously",
                    "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;"),
            new Probe("scheduler-runTaskAsync", "org/bukkit/scheduler/BukkitScheduler", "runTaskAsynchronously",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;"),
            new Probe("scheduler-runTaskLaterAsync", "org/bukkit/scheduler/BukkitScheduler", "runTaskLaterAsynchronously",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lorg/bukkit/scheduler/BukkitTask;"),
            new Probe("scheduler-runTaskTimerAsync", "org/bukkit/scheduler/BukkitScheduler", "runTaskTimerAsynchronously",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;"),
            new Probe("scheduler-scheduleSyncDelayed", "org/bukkit/scheduler/BukkitScheduler", "scheduleSyncDelayedTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I"),
            new Probe("scheduler-scheduleSyncDelayedLong", "org/bukkit/scheduler/BukkitScheduler", "scheduleSyncDelayedTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)I"),
            new Probe("scheduler-scheduleSyncRepeating", "org/bukkit/scheduler/BukkitScheduler", "scheduleSyncRepeatingTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I"),
            new Probe("scheduler-scheduleAsyncDelayed", "org/bukkit/scheduler/BukkitScheduler", "scheduleAsyncDelayedTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I"),
            new Probe("scheduler-scheduleAsyncDelayedLong", "org/bukkit/scheduler/BukkitScheduler", "scheduleAsyncDelayedTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)I"),
            new Probe("scheduler-scheduleAsyncRepeating", "org/bukkit/scheduler/BukkitScheduler", "scheduleAsyncRepeatingTask",
                    "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I"),
            new Probe("player-getLocation", "org/bukkit/entity/Player", "getLocation",
                    "()Lorg/bukkit/Location;"),
            new Probe("player-teleport-cause", "org/bukkit/entity/Player", "teleport",
                    "(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z"),
            new Probe("player-playSound", "org/bukkit/entity/Player", "playSound",
                    "(Lorg/bukkit/Location;Lorg/bukkit/Sound;FF)V"),
            new Probe("player-closeInventory", "org/bukkit/entity/Player", "closeInventory", "()V"),
            new Probe("player-openInventory", "org/bukkit/entity/Player", "openInventory",
                    "(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView;"),
            new Probe("world-dropItemNaturally", "org/bukkit/World", "dropItemNaturally",
                    "(Lorg/bukkit/Location;Lorg/bukkit/inventory/ItemStack;)Lorg/bukkit/entity/Item;"),
            new Probe("world-strikeLightning", "org/bukkit/World", "strikeLightning",
                    "(Lorg/bukkit/Location;)Lorg/bukkit/entity/LightningStrike;"),
            new Probe("world-generateTree", "org/bukkit/World", "generateTree",
                    "(Lorg/bukkit/Location;Lorg/bukkit/TreeType;)Z"),
            new Probe("shaded-paperlib-teleportAsync-fixture", "com/earth2me/essentials/paperlib/PaperLib", "teleportAsync",
                    "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Ljava/util/concurrent/CompletableFuture;")
    };

    private static final Probe[] KNOWN_GAPS = {
            new Probe("world-spawnEntity", "org/bukkit/World", "spawnEntity",
                    "(Lorg/bukkit/Location;Lorg/bukkit/entity/EntityType;)Lorg/bukkit/entity/Entity;")
    };

    private BytecodeInventorySmoke() {
    }

    static Result scan(Path[] jars) {
        if (jars.length == 0) return Result.empty();
        try {
            Map<String, Integer> exactCounts = new TreeMap<>();
            Map<String, Integer> looseCounts = new TreeMap<>();
            int classCount = 0;
            for (Path jar : jars) {
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalStateException("Deep smoke jar does not exist: " + jar);
                }
                classCount += scanJar(jar, exactCounts, looseCounts);
            }
            Map<String, Integer> requiredHits = collect(REQUIRED, exactCounts, looseCounts);
            List<String> missingRequired = missing(REQUIRED, requiredHits);
            Map<String, Integer> knownGapHits = collect(KNOWN_GAPS, exactCounts, looseCounts);
            return new Result(jars.length, classCount, requiredHits, missingRequired, knownGapHits);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan plugin bytecode", exception);
        }
    }

    private static int scanJar(Path path, Map<String, Integer> exactCounts, Map<String, Integer> looseCounts)
            throws IOException {
        int classCount = 0;
        try (JarFile jarFile = new JarFile(path.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                classCount++;
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    new ClassReader(inputStream).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name,
                                                            String descriptor, boolean isInterface) {
                                    increment(exactCounts, exact(owner, name, descriptor));
                                    increment(looseCounts, loose(name, descriptor));
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return classCount;
    }

    private static List<String> missing(Probe[] probes, Map<String, Integer> hits) {
        List<String> missing = new ArrayList<>();
        for (Probe probe : probes) {
            if (hits.getOrDefault(probe.label, 0) <= 0) {
                missing.add(probe.label);
            }
        }
        return missing;
    }

    private static Map<String, Integer> collect(Probe[] probes, Map<String, Integer> exactCounts,
                                                Map<String, Integer> looseCounts) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (Probe probe : probes) {
            String key = probe.owner == null ? loose(probe.name, probe.descriptor)
                    : exact(probe.owner, probe.name, probe.descriptor);
            int count = (probe.owner == null ? looseCounts : exactCounts).getOrDefault(key, 0);
            hits.put(probe.label, count);
        }
        return hits;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static String exact(String owner, String name, String descriptor) {
        return owner + "#" + name + descriptor;
    }

    private static String loose(String name, String descriptor) {
        return name + descriptor;
    }

    static final class Result {
        private final int jarCount;
        private final int classCount;
        private final Map<String, Integer> requiredHits;
        private final List<String> missingRequired;
        private final Map<String, Integer> knownGapHits;

        private Result(int jarCount, int classCount, Map<String, Integer> requiredHits,
                       List<String> missingRequired, Map<String, Integer> knownGapHits) {
            this.jarCount = jarCount;
            this.classCount = classCount;
            this.requiredHits = requiredHits;
            this.missingRequired = missingRequired;
            this.knownGapHits = knownGapHits;
        }

        static Result empty() {
            return new Result(0, 0, Map.of(), List.of(), Map.of());
        }

        String summarySuffix() {
            if (jarCount == 0) return "";
            return " bytecodeJars=" + jarCount
                    + " bytecodeClasses=" + classCount
                    + " bytecodeRequiredHits=" + requiredHits.values().stream().mapToInt(Integer::intValue).sum()
                    + " bytecodeMissing=" + missingRequired
                    + " knownGapHits=" + knownGapHits;
        }
    }

    private static final class Probe {
        private final String label;
        private final String owner;
        private final String name;
        private final String descriptor;

        private Probe(String label, String owner, String name, String descriptor) {
            this.label = label;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return label + "=" + (owner == null ? "*" : owner) + "#" + name + descriptor;
        }
    }
}
