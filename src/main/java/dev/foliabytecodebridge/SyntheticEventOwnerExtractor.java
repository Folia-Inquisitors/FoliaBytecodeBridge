package dev.foliabytecodebridge;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds an obvious owner for custom synthetic events.
 *
 * <p>This is intentionally conservative. The bridge only promotes a shared
 * event path to an owner route when the event exposes a normal zero-argument
 * getter for an entity, block, block collection, or location owner. Unknown
 * event shapes stay inside the synthetic compatibility model and continue
 * producing diagnostics.</p>
 */
final class SyntheticEventOwnerExtractor {

    private static final Set<String> ENTITY_GETTER_NAMES = Set.of(
            "getEntity",
            "getPlayer",
            "getActor",
            "getTarget",
            "getTargetEntity",
            "getDamager",
            "getSource"
    );

    private static final Set<String> BLOCK_GETTER_NAMES = Set.of(
            "getBlock",
            "getClickedBlock",
            "getPlacedBlock",
            "getBrokenBlock",
            "getSourceBlock"
    );

    private static final Set<String> BLOCK_COLLECTION_GETTER_NAMES = Set.of(
            "getBlocks",
            "getAffectedBlocks"
    );

    private static final Set<String> LOCATION_GETTER_NAMES = Set.of(
            "getLocation",
            "getTo",
            "getFrom",
            "getTargetLocation"
    );

    private static final Set<String> ORIGINAL_EVENT_GETTER_NAMES = Set.of(
            "getOriginalEvent"
    );

    private static final Set<String> READ_ONLY_GETTER_NAMES = Set.of(
            "isReadOnly",
            "readOnly",
            "isReadOnlyEvent"
    );

    private SyntheticEventOwnerExtractor() {
    }

    static OwnerScan scan(Event event) {
        List<OwnerMiss> misses = new ArrayList<>();
        List<MultiRegionObservation> multiRegionObservations = new ArrayList<>();
        if (event == null) {
            misses.add(new OwnerMiss("event", "none", "null-event", "event=null"));
            return new OwnerScan(null, null, null, misses, multiRegionObservations);
        }

        EntityOwner entityOwner = entityOwner(event, misses);
        if (entityOwner != null) {
            return new OwnerScan(entityOwner, null, null, misses, multiRegionObservations);
        }

        BlockOwner blockOwner = blockOwner(event, misses, multiRegionObservations);
        if (blockOwner != null) {
            return new OwnerScan(null, blockOwner, null, misses, multiRegionObservations);
        }

        LocationOwner locationOwner = locationOwner(event, misses);
        if (locationOwner != null) {
            return new OwnerScan(null, null, locationOwner, misses, multiRegionObservations);
        }

        OwnerScan originalEventScan = originalEventOwnerScan(event, misses, multiRegionObservations);
        if (originalEventScan != null && originalEventScan.hasOwner()) {
            return originalEventScan;
        }
        return new OwnerScan(null, null, null, misses, multiRegionObservations);
    }

    static EntityOwner entityOwner(Event event) {
        if (event == null) return null;
        for (String name : ENTITY_GETTER_NAMES) {
            EntityOwner owner = entityOwner(event, name);
            if (owner != null) return owner;
        }
        return null;
    }

    static BlockOwner blockOwner(Event event) {
        if (event == null) return null;
        for (String name : BLOCK_GETTER_NAMES) {
            BlockOwner owner = singleBlockOwner(event, name);
            if (owner != null) return owner;
        }
        for (String name : BLOCK_COLLECTION_GETTER_NAMES) {
            BlockOwner owner = blockCollectionOwner(event, name);
            if (owner != null) return owner;
        }
        return null;
    }

    static LocationOwner locationOwner(Event event) {
        if (event == null) return null;
        for (String name : LOCATION_GETTER_NAMES) {
            LocationOwner owner = locationOwner(event, name);
            if (owner != null) return owner;
        }
        return null;
    }

    private static EntityOwner entityOwner(Event event, List<OwnerMiss> misses) {
        boolean sawCandidate = false;
        for (String name : ENTITY_GETTER_NAMES) {
            EntityOwner owner = entityOwner(event, name, misses);
            sawCandidate = sawCandidate || owner != null || hasMethodNamed(event, name);
            if (owner != null) return owner;
        }
        if (!sawCandidate) {
            misses.add(new OwnerMiss("entity", String.join("|", ENTITY_GETTER_NAMES),
                    "no-compatible-owner-getter", "expected-zero-arg-Entity-getter"));
        }
        return null;
    }

    private static BlockOwner blockOwner(Event event, List<OwnerMiss> misses,
                                         List<MultiRegionObservation> multiRegionObservations) {
        boolean sawCandidate = false;
        for (String name : BLOCK_GETTER_NAMES) {
            BlockOwner owner = singleBlockOwner(event, name, misses);
            sawCandidate = sawCandidate || owner != null || hasMethodNamed(event, name);
            if (owner != null) return owner;
        }
        for (String name : BLOCK_COLLECTION_GETTER_NAMES) {
            BlockOwner owner = blockCollectionOwner(event, name, misses, multiRegionObservations);
            sawCandidate = sawCandidate || owner != null || hasMethodNamed(event, name);
            if (owner != null) return owner;
        }
        if (!sawCandidate) {
            misses.add(new OwnerMiss("block", String.join("|", BLOCK_GETTER_NAMES)
                    + "|" + String.join("|", BLOCK_COLLECTION_GETTER_NAMES),
                    "no-compatible-owner-getter", "expected-zero-arg-Block-or-Collection<Block>-getter"));
        }
        return null;
    }

    private static LocationOwner locationOwner(Event event, List<OwnerMiss> misses) {
        boolean sawCandidate = false;
        for (String name : LOCATION_GETTER_NAMES) {
            LocationOwner owner = locationOwner(event, name, misses);
            sawCandidate = sawCandidate || owner != null || hasMethodNamed(event, name);
            if (owner != null) return owner;
        }
        if (!sawCandidate) {
            misses.add(new OwnerMiss("location", String.join("|", LOCATION_GETTER_NAMES),
                    "no-compatible-owner-getter", "expected-zero-arg-Location-getter"));
        }
        return null;
    }

    private static OwnerScan originalEventOwnerScan(Event event, List<OwnerMiss> misses,
                                                    List<MultiRegionObservation> multiRegionObservations) {
        // Some custom events are only wrappers. If they expose the original
        // Bukkit event, borrow ownership from that original event instead of
        // guessing from wrapper-only data such as World.
        for (String name : ORIGINAL_EVENT_GETTER_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (method.getParameterCount() != 0) {
                    misses.add(new OwnerMiss("delegate", name, "unsupported-owner-shape",
                            "parameters=" + method.getParameterCount()));
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    misses.add(new OwnerMiss("delegate", name, "unsupported-owner-shape", "static=true"));
                    continue;
                }
                if (!Event.class.isAssignableFrom(method.getReturnType())) {
                    misses.add(new OwnerMiss("delegate", name, "unsupported-owner-shape",
                            "return=" + method.getReturnType().getName()));
                    continue;
                }
                try {
                    Object value = method.invoke(event);
                    if (!(value instanceof Event originalEvent)) {
                        misses.add(new OwnerMiss("delegate", name, "getter-returned-null-or-wrong-type",
                                "value=" + valueType(value)));
                        continue;
                    }
                    if (originalEvent == event) {
                        misses.add(new OwnerMiss("delegate", name, "self-reference",
                                "event=" + event.getClass().getName()));
                        continue;
                    }
                    OwnerScan scan = directOwnerScan(originalEvent, "delegate:" + name + ".",
                            misses, multiRegionObservations);
                    if (scan.hasOwner()) return scan;
                    misses.add(new OwnerMiss("delegate", name, "original-event-owner-missed",
                            "event=" + originalEvent.getClass().getName()));
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    misses.add(new OwnerMiss("delegate", name, "getter-failed",
                            exception.getClass().getName() + ":" + safeMessage(exception.getMessage())));
                }
            }
        }
        return null;
    }

    private static OwnerScan directOwnerScan(Event event, String methodPrefix, List<OwnerMiss> misses,
                                             List<MultiRegionObservation> multiRegionObservations) {
        EntityOwner entityOwner = directEntityOwner(event, methodPrefix, misses);
        if (entityOwner != null) {
            return new OwnerScan(entityOwner, null, null, misses, multiRegionObservations);
        }
        BlockOwner blockOwner = directBlockOwner(event, methodPrefix, misses, multiRegionObservations);
        if (blockOwner != null) {
            return new OwnerScan(null, blockOwner, null, misses, multiRegionObservations);
        }
        LocationOwner locationOwner = directLocationOwner(event, methodPrefix, misses);
        if (locationOwner != null) {
            return new OwnerScan(null, null, locationOwner, misses, multiRegionObservations);
        }
        return new OwnerScan(null, null, null, misses, multiRegionObservations);
    }

    private static EntityOwner directEntityOwner(Event event, String methodPrefix, List<OwnerMiss> misses) {
        for (String name : ENTITY_GETTER_NAMES) {
            EntityOwner owner = entityOwner(event, name, misses);
            if (owner != null) {
                return new EntityOwner(owner.entity(), methodPrefix + owner.methodName());
            }
        }
        return null;
    }

    private static BlockOwner directBlockOwner(Event event, String methodPrefix, List<OwnerMiss> misses,
                                               List<MultiRegionObservation> multiRegionObservations) {
        for (String name : BLOCK_GETTER_NAMES) {
            BlockOwner owner = singleBlockOwner(event, name, misses);
            if (owner != null) {
                return new BlockOwner(owner.block(), methodPrefix + owner.methodName(), owner.blockCount());
            }
        }
        for (String name : BLOCK_COLLECTION_GETTER_NAMES) {
            BlockOwner owner = blockCollectionOwner(event, name, misses, multiRegionObservations);
            if (owner != null) {
                return new BlockOwner(owner.block(), methodPrefix + owner.methodName(), owner.blockCount());
            }
        }
        return null;
    }

    private static LocationOwner directLocationOwner(Event event, String methodPrefix, List<OwnerMiss> misses) {
        for (String name : LOCATION_GETTER_NAMES) {
            LocationOwner owner = locationOwner(event, name, misses);
            if (owner != null) {
                return new LocationOwner(owner.location(), methodPrefix + owner.methodName());
            }
        }
        return null;
    }

    private static EntityOwner entityOwner(Event event, String methodName) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!Entity.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object value = method.invoke(event);
                if (value instanceof Entity entity) {
                    return new EntityOwner(entity, method.getName());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Getter failures are evidence that this is not yet a proven
                // owner shape. Leave the event in the synthetic lane.
            }
        }
        return null;
    }

    private static EntityOwner entityOwner(Event event, String methodName, List<OwnerMiss> misses) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) {
                misses.add(new OwnerMiss("entity", methodName, "unsupported-owner-shape",
                        "parameters=" + method.getParameterCount()));
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                misses.add(new OwnerMiss("entity", methodName, "unsupported-owner-shape", "static=true"));
                continue;
            }
            if (!Entity.class.isAssignableFrom(method.getReturnType())) {
                misses.add(new OwnerMiss("entity", methodName, "unsupported-owner-shape",
                        "return=" + method.getReturnType().getName()));
                continue;
            }
            try {
                Object value = method.invoke(event);
                if (value instanceof Entity entity) {
                    return new EntityOwner(entity, method.getName());
                }
                misses.add(new OwnerMiss("entity", methodName, "getter-returned-null-or-wrong-type",
                        "value=" + valueType(value)));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                misses.add(new OwnerMiss("entity", methodName, "getter-failed",
                        exception.getClass().getName() + ":" + safeMessage(exception.getMessage())));
            }
        }
        return null;
    }

    private static BlockOwner singleBlockOwner(Event event, String methodName) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!Block.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object value = method.invoke(event);
                if (value instanceof Block block) {
                    return new BlockOwner(block, method.getName(), 1);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Getter failures mean this event shape is not proven yet.
            }
        }
        return null;
    }

    private static BlockOwner singleBlockOwner(Event event, String methodName, List<OwnerMiss> misses) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape",
                        "parameters=" + method.getParameterCount()));
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape", "static=true"));
                continue;
            }
            if (!Block.class.isAssignableFrom(method.getReturnType())) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape",
                        "return=" + method.getReturnType().getName()));
                continue;
            }
            try {
                Object value = method.invoke(event);
                if (value instanceof Block block) {
                    return new BlockOwner(block, method.getName(), 1);
                }
                misses.add(new OwnerMiss("block", methodName, "getter-returned-null-or-wrong-type",
                        "value=" + valueType(value)));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                misses.add(new OwnerMiss("block", methodName, "getter-failed",
                        exception.getClass().getName() + ":" + safeMessage(exception.getMessage())));
            }
        }
        return null;
    }

    private static BlockOwner blockCollectionOwner(Event event, String methodName) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!Collection.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object value = method.invoke(event);
                if (!(value instanceof Collection<?> blocks) || blocks.isEmpty()) continue;
                Block first = null;
                int count = 0;
                for (Object item : blocks) {
                    if (!(item instanceof Block block)) {
                        first = null;
                        break;
                    }
                    if (first == null) {
                        first = block;
                    } else if (!sameChunk(first, block)) {
                        // Multi-region block collections need a split/model pass
                        // before they are safe to promote out of the lane.
                        first = null;
                        break;
                    }
                    count++;
                }
                if (first != null) {
                    return new BlockOwner(first, method.getName(), count);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Leave unproven collection shapes in the synthetic lane.
            }
        }
        return null;
    }

    private static BlockOwner blockCollectionOwner(Event event, String methodName, List<OwnerMiss> misses,
                                                   List<MultiRegionObservation> multiRegionObservations) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape",
                        "parameters=" + method.getParameterCount()));
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape", "static=true"));
                continue;
            }
            if (!Collection.class.isAssignableFrom(method.getReturnType())) {
                misses.add(new OwnerMiss("block", methodName, "unsupported-owner-shape",
                        "return=" + method.getReturnType().getName()));
                continue;
            }
            try {
                Object value = method.invoke(event);
                if (!(value instanceof Collection<?> blocks)) {
                    misses.add(new OwnerMiss("block", methodName, "getter-returned-null-or-wrong-type",
                            "value=" + valueType(value)));
                    continue;
                }
                if (blocks.isEmpty()) {
                    misses.add(new OwnerMiss("block", methodName, "empty-collection", "size=0"));
                    continue;
                }
                Block first = null;
                int count = 0;
                boolean accepted = true;
                Map<String, Block> ownerAnchors = new LinkedHashMap<>();
                for (Object item : blocks) {
                    if (!(item instanceof Block block)) {
                        misses.add(new OwnerMiss("block", methodName, "non-block-collection-item",
                                "item=" + valueType(item)));
                        accepted = false;
                        break;
                    }
                    if (first == null) {
                        first = block;
                    }
                    ownerAnchors.putIfAbsent(ownerKey(block), block);
                    count++;
                }
                if (accepted && ownerAnchors.size() > 1) {
                    misses.add(new OwnerMiss("block", methodName, "multi-region-collection",
                            "size=" + blocks.size()));
                    accepted = false;
                }
                if (!accepted && !ownerAnchors.isEmpty()) {
                    boolean readOnly = readOnlyEvent(event);
                    // Phase 1/2 of the multi-region model is evidence-first:
                    // unknown collections stay serialized, explicit read-only
                    // shapes may run a read split without replaying listeners.
                    multiRegionObservations.add(new MultiRegionObservation("block", methodName,
                            RouteFamily.C_REGION_BLOCK, ownerAnchors.size(), blocks.size(),
                            readOnly ? "read-only" : "read-or-write-unknown",
                            String.join("|", ownerAnchors.keySet()), readOnly,
                            List.copyOf(ownerAnchors.values())));
                }
                if (accepted && first != null) {
                    return new BlockOwner(first, method.getName(), count);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                misses.add(new OwnerMiss("block", methodName, "getter-failed",
                        exception.getClass().getName() + ":" + safeMessage(exception.getMessage())));
            }
        }
        return null;
    }

    private static LocationOwner locationOwner(Event event, String methodName) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!Location.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object value = method.invoke(event);
                if (value instanceof Location location) {
                    return new LocationOwner(location, method.getName());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Leave unproven location getters in the synthetic lane.
            }
        }
        return null;
    }

    private static LocationOwner locationOwner(Event event, String methodName, List<OwnerMiss> misses) {
        for (Method method : event.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 0) {
                misses.add(new OwnerMiss("location", methodName, "unsupported-owner-shape",
                        "parameters=" + method.getParameterCount()));
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                misses.add(new OwnerMiss("location", methodName, "unsupported-owner-shape", "static=true"));
                continue;
            }
            if (!Location.class.isAssignableFrom(method.getReturnType())) {
                misses.add(new OwnerMiss("location", methodName, "unsupported-owner-shape",
                        "return=" + method.getReturnType().getName()));
                continue;
            }
            try {
                Object value = method.invoke(event);
                if (value instanceof Location location) {
                    if (location.getWorld() == null) {
                        misses.add(new OwnerMiss("location", methodName, "location-world-null",
                                "world=null"));
                        continue;
                    }
                    return new LocationOwner(location, method.getName());
                }
                misses.add(new OwnerMiss("location", methodName, "getter-returned-null-or-wrong-type",
                        "value=" + valueType(value)));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                misses.add(new OwnerMiss("location", methodName, "getter-failed",
                        exception.getClass().getName() + ":" + safeMessage(exception.getMessage())));
            }
        }
        return null;
    }

    private static boolean sameChunk(Block first, Block other) {
        if (first == null || other == null) return false;
        if (first.getWorld() != other.getWorld()) return false;
        return (first.getX() >> 4) == (other.getX() >> 4)
                && (first.getZ() >> 4) == (other.getZ() >> 4);
    }

    private static String ownerKey(Block block) {
        if (block == null) return "unknown-block";
        try {
            String world = block.getWorld() == null ? "unknown-world" : block.getWorld().getName();
            return world + ":" + (block.getX() >> 4) + "," + (block.getZ() >> 4);
        } catch (RuntimeException exception) {
            return block.getClass().getName();
        }
    }

    private static boolean readOnlyEvent(Event event) {
        if (event == null) return false;
        for (String name : READ_ONLY_GETTER_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
                try {
                    return Boolean.TRUE.equals(method.invoke(event));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean hasMethodNamed(Event event, String methodName) {
        for (Method method : event.getClass().getMethods()) {
            if (methodName.equals(method.getName())) return true;
        }
        return false;
    }

    private static String valueType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "no-message";
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    record OwnerScan(EntityOwner entityOwner, BlockOwner blockOwner,
                     LocationOwner locationOwner, List<OwnerMiss> misses,
                     List<MultiRegionObservation> multiRegionObservations) {
        boolean hasOwner() {
            return entityOwner != null || blockOwner != null || locationOwner != null;
        }

        boolean hasMultiRegionObservation() {
            return multiRegionObservations != null && !multiRegionObservations.isEmpty();
        }

        String missSummary() {
            if (misses == null || misses.isEmpty()) return "none";
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < misses.size(); index++) {
                if (index > 0) builder.append(';');
                OwnerMiss miss = misses.get(index);
                builder.append(miss.ownerKind())
                        .append(':')
                        .append(miss.methodName())
                        .append(':')
                        .append(miss.reason())
                        .append(':')
                        .append(miss.detail());
            }
            return builder.toString();
        }
    }

    record OwnerMiss(String ownerKind, String methodName, String reason, String detail) {
    }

    record MultiRegionObservation(String ownerKind, String methodName, RouteFamily routeFamily,
                                  int ownerCount, int itemCount, String operationKind,
                                  String ownerSet, boolean readOnly, List<Block> ownerAnchors) {
        String detail() {
            return "ownerKind=" + ownerKind
                    + " method=" + methodName
                    + " route=" + routeFamily.label()
                    + " owners=" + ownerCount
                    + " items=" + itemCount
                    + " operation=" + operationKind
                    + " readOnly=" + readOnly
                    + " ownerSet=" + ownerSet;
        }
    }

    record EntityOwner(Entity entity, String methodName) {
    }

    record BlockOwner(Block block, String methodName, int blockCount) {
    }

    record LocationOwner(Location location, String methodName) {
    }
}
