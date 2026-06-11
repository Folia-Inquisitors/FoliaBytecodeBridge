package dev.foliabytecodebridge;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Best-effort owner extraction for NMS/server-internal runnables.
 *
 * <p>This scanner is intentionally conservative. It promotes only concrete
 * owner clues that can be scheduled on Folia, such as Bukkit locations,
 * entities, chunks, or a ServerLevel + BlockPos/ChunkPos pair. Everything else
 * stays in the NMS compatibility model as no-owner-contract evidence.</p>
 */
final class NmsOwnerExtractor {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_FIELDS_PER_OBJECT = 48;

    private NmsOwnerExtractor() {
    }

    static Scan scan(Object root) {
        if (root == null) {
            return Scan.miss("root-null", "none");
        }
        Scanner scanner = new Scanner();
        scanner.visit(root, 0, "root:" + root.getClass().getName());
        return scanner.owner == null
                ? Scan.miss(scanner.missReason(), scanner.clueTrail())
                : Scan.found(scanner.owner, scanner.clueTrail());
    }

    record Scan(boolean found, Owner owner, String missReason, String clueTrail) {
        static Scan found(Owner owner, String clueTrail) {
            return new Scan(true, owner, "none", clueTrail);
        }

        static Scan miss(String reason, String clueTrail) {
            return new Scan(false, null, reason, clueTrail);
        }
    }

    record Owner(String kind, Location location, World world, int chunkX, int chunkZ,
                 String detail) {
        static Owner location(Location location, String source) {
            return new Owner("location", location, location.getWorld(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4,
                    source + " world=" + worldName(location.getWorld())
                            + " block=" + location.getBlockX() + "," + location.getBlockY()
                            + "," + location.getBlockZ());
        }

        static Owner entity(Entity entity, String source) {
            Location location = entity.getLocation();
            return new Owner("entity", location, location.getWorld(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4,
                    source + " entity=" + entity.getClass().getName()
                            + " world=" + worldName(location.getWorld()));
        }

        static Owner chunk(World world, int chunkX, int chunkZ, String source) {
            return new Owner("chunk", null, world, chunkX, chunkZ,
                    source + " world=" + worldName(world)
                            + " chunk=" + chunkX + "," + chunkZ);
        }

        boolean hasLocation() {
            return location != null;
        }

        boolean hasChunk() {
            return world != null;
        }

        private static String worldName(World world) {
            if (world == null) return "unknown";
            try {
                return world.getName();
            } catch (Throwable ignored) {
                return world.getClass().getName();
            }
        }
    }

    private static final class Scanner {
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private Owner owner;
        private World world;
        private Integer blockX;
        private Integer blockY;
        private Integer blockZ;
        private Integer chunkX;
        private Integer chunkZ;
        private String firstMiss = "no-compatible-owner-field";
        private final List<String> clues = new ArrayList<>();

        void visit(Object value, int depth, String path) {
            if (owner != null || value == null || depth > MAX_DEPTH || seen.contains(value)) return;
            seen.add(value);

            if (captureDirect(value, path)) return;
            captureNmsClue(value, path);
            if (owner != null) return;

            if (shouldStop(value.getClass())) return;
            captureObjectFieldClues(value, path);
            if (owner != null) return;

            int fields = 0;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (fields++ >= MAX_FIELDS_PER_OBJECT || owner != null) return;
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Object child = readField(field, value);
                    if (child == null) continue;
                    visit(child, depth + 1, path + "." + field.getName());
                }
            }
        }

        String clueTrail() {
            if (clues.isEmpty()) return "none";
            return String.join(";", clues);
        }

        String missReason() {
            if (world != null && blockX != null && blockY != null && blockZ != null) {
                return "world-and-blockpos-found-but-location-build-failed";
            }
            if (world != null && chunkX != null && chunkZ != null) {
                return "world-and-chunkpos-found-but-owner-build-failed";
            }
            if (world != null) return "world-found-without-position";
            if (blockX != null || chunkX != null) return "position-found-without-world";
            return firstMiss;
        }

        private boolean captureDirect(Object value, String path) {
            if (value instanceof Location location && location.getWorld() != null) {
                owner = Owner.location(location, path);
                return true;
            }
            if (value instanceof Block block) {
                owner = Owner.location(block.getLocation(), path + "#Block#getLocation");
                return true;
            }
            if (value instanceof Entity entity) {
                owner = Owner.entity(entity, path + "#Entity#getLocation");
                return true;
            }
            if (value instanceof org.bukkit.Chunk chunk) {
                owner = Owner.chunk(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                        path + "#Chunk#getWorld/getX/getZ");
                return true;
            }
            if (value instanceof World candidate) {
                world = candidate;
                clue(path, "world", "bukkit-world");
                tryBuildFromClues(path + "#World");
            }
            return owner != null;
        }

        private void captureNmsClue(Object value, String path) {
            Class<?> type = value.getClass();
            String name = type.getName();
            if (name.equals("net.minecraft.core.BlockPos")) {
                blockX = intMethod(value, "getX").orElse(blockX);
                blockY = intMethod(value, "getY").orElse(blockY == null ? 64 : blockY);
                blockZ = intMethod(value, "getZ").orElse(blockZ);
                clue(path, "block-pos", "x=" + blockX + ",y=" + blockY + ",z=" + blockZ);
                tryBuildFromClues(path + "#BlockPos");
                return;
            }
            if (name.endsWith(".ChunkPos") || name.equals("net.minecraft.world.level.ChunkPos")) {
                chunkX = intFieldOrMethod(value, "x", "x").orElse(chunkX);
                chunkZ = intFieldOrMethod(value, "z", "z").orElse(chunkZ);
                clue(path, "chunk-pos", "x=" + chunkX + ",z=" + chunkZ);
                tryBuildFromClues(path + "#ChunkPos");
                return;
            }

            Object bukkitWorld = invokeNoArg(value, "getWorld");
            if (bukkitWorld instanceof World candidate) {
                world = candidate;
                clue(path, "world", "method=getWorld type=" + value.getClass().getName());
                tryBuildFromClues(path + "#getWorld");
            }

            Object pos = invokeNoArg(value, "getPos");
            if (pos != null && pos != value) {
                captureNmsClue(pos, path + "#getPos");
            }
        }

        private void captureObjectFieldClues(Object value, String path) {
            Class<?> valueType = value.getClass();
            List<IntCandidate> localInts = new ArrayList<>();
            World localWorld = null;
            String localWorldPath = null;

            int fields = 0;
            for (Class<?> type = valueType; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (fields++ >= MAX_FIELDS_PER_OBJECT || owner != null) return;
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Object child = readField(field, value);
                    if (child == null) continue;
                    String fieldPath = path + "." + field.getName();

                    if (child instanceof Number number && isIntLike(field.getType())) {
                        localInts.add(new IntCandidate(field.getName(), number.intValue(), fieldPath));
                        clue(fieldPath, "int-candidate", "field=" + field.getName()
                                + " type=" + field.getType().getName()
                                + " value=" + number.intValue());
                        continue;
                    }

                    World childWorld = worldFromCandidate(child);
                    if (childWorld != null) {
                        localWorld = childWorld;
                        localWorldPath = fieldPath;
                        world = childWorld;
                        clue(fieldPath, "world-candidate", "type=" + child.getClass().getName());
                    }

                    captureCoordinatePair(child, fieldPath);
                }
            }

            // Common lambda shape for server-internal executor work:
            // captured ServerLevel + two captured ints. When the same runnable
            // object carries both clues, treat the ints as a chunk pair. This
            // is intentionally limited to the local runnable object so random
            // nested counters do not become owner context.
            if (owner == null && world != null && chunkX == null && chunkZ == null
                    && localWorld != null && localInts.size() == 2) {
                chunkX = localInts.get(0).value();
                chunkZ = localInts.get(1).value();
                clue(path, "captured-int-pair", "worldField=" + localWorldPath
                        + " xField=" + localInts.get(0).fieldName()
                        + " zField=" + localInts.get(1).fieldName()
                        + " chunk=" + chunkX + "," + chunkZ);
                tryBuildFromClues(path + "#captured-world-plus-two-ints");
            }
        }

        private void captureCoordinatePair(Object value, String path) {
            if (owner != null || value == null) return;
            String className = value.getClass().getName();
            if (!looksLikeCoordinatePair(className)) return;

            Optional<Integer> x = firstInt(value, "x", "chunkX", "chunkXCoord", "getX", "getChunkX");
            Optional<Integer> z = firstInt(value, "z", "chunkZ", "chunkZCoord", "getZ", "getChunkZ");
            if (x.isPresent() && z.isPresent()) {
                chunkX = x.get();
                chunkZ = z.get();
                clue(path, "coordinate-pair", "type=" + className + " chunk=" + chunkX + "," + chunkZ);
                tryBuildFromClues(path + "#coordinate-pair");
            }
        }

        private void tryBuildFromClues(String source) {
            if (owner != null || world == null) return;
            if (blockX != null && blockY != null && blockZ != null) {
                owner = Owner.location(new Location(world, blockX, blockY, blockZ), source);
                return;
            }
            if (chunkX != null && chunkZ != null) {
                owner = Owner.chunk(world, chunkX, chunkZ, source);
            }
        }

        private void clue(String path, String kind, String detail) {
            if (clues.size() >= 24) return;
            clues.add(kind + "@" + path + "[" + detail + "]");
        }
    }

    private record IntCandidate(String fieldName, int value, String path) {
    }

    private static boolean shouldStop(Class<?> type) {
        String name = type.getName();
        return type.isPrimitive()
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.google.")
                || name.startsWith("org.slf4j.");
    }

    private static Object readField(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static World worldFromCandidate(Object value) {
        if (value instanceof World world) return world;
        Object bukkitWorld = invokeNoArg(value, "getWorld");
        return bukkitWorld instanceof World world ? world : null;
    }

    private static boolean isIntLike(Class<?> type) {
        return type == int.class
                || type == Integer.class
                || type == short.class
                || type == Short.class
                || type == byte.class
                || type == Byte.class;
    }

    private static boolean looksLikeCoordinatePair(String className) {
        String lower = className.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("chunkpos")
                || lower.endsWith("intpair")
                || lower.contains("chunkcoord")
                || lower.contains("chunkpos");
    }

    private static Optional<Integer> firstInt(Object instance, String... names) {
        for (String name : names) {
            Optional<Integer> value = intFieldOrMethod(instance, name, name);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    private static Object invokeNoArg(Object instance, String methodName) {
        try {
            Method method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Optional<Integer> intMethod(Object instance, String methodName) {
        try {
            Method method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object result = method.invoke(instance);
            return result instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> intFieldOrMethod(Object instance, String fieldName, String methodName) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object result = field.get(instance);
            if (result instanceof Number number) {
                return Optional.of(number.intValue());
            }
        } catch (Throwable ignored) {
        }
        return intMethod(instance, methodName);
    }
}
