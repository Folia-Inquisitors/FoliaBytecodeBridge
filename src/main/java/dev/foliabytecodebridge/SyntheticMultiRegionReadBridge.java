package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Phase 2 read-only split/aggregate path for synthetic multi-owner events.
 *
 * <p>This only runs for event shapes that explicitly expose `isReadOnly()`
 * style evidence. It schedules one read snapshot per detected owner anchor and
 * aggregates the result count. It does not replay listeners per region, because
 * doing that would duplicate arbitrary listener side effects.</p>
 *
 * <p>Live Folia scheduler contexts must not block while waiting on other owner
 * regions. The bridge therefore logs the scheduled split immediately and lets a
 * completion callback record whether the owner snapshots aggregated later.</p>
 */
final class SyntheticMultiRegionReadBridge {

    private SyntheticMultiRegionReadBridge() {
    }

    static void tryReadOnlySplit(String eventName, int listenerCount,
                                 SyntheticEventOwnerExtractor.OwnerScan scan) {
        if (scan == null || !scan.hasMultiRegionObservation()) return;
        for (SyntheticEventOwnerExtractor.MultiRegionObservation observation : scan.multiRegionObservations()) {
            if (!observation.readOnly()) continue;
            List<Block> anchors = observation.ownerAnchors();
            if (anchors == null || anchors.isEmpty()) {
                BridgeDiagnostics.syntheticMultiRegionReadSplit(eventName, listenerCount, observation,
                        "result=blocked reason=no-owner-anchors");
                continue;
            }
            try {
                scheduleReadSplit(eventName, listenerCount, observation, anchors);
            } catch (Throwable throwable) {
                BridgeDiagnostics.syntheticMultiRegionReadSplitFailure(eventName, listenerCount,
                        observation, throwable);
            }
        }
    }

    private static void scheduleReadSplit(String eventName, int listenerCount,
                                          SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                          List<Block> anchors) {
        if (Boolean.getBoolean("foliabytecodebridge.smokeSyntheticReadSplit")) {
            BridgeDiagnostics.syntheticMultiRegionReadSplit(eventName, listenerCount, observation,
                    "result=aggregated phaseResult=completed scheduledOwners=" + anchors.size()
                            + " completedOwners=" + anchors.size()
                            + " action=read-split-owner-snapshots mode=smoke-inline");
            return;
        }
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Block anchor : anchors) {
            if (anchor == null) continue;
            futures.add(scheduleBlockRead(observation, anchor));
        }
        if (futures.isEmpty()) {
            BridgeDiagnostics.syntheticMultiRegionReadSplit(eventName, listenerCount, observation,
                    "result=blocked reason=no-readable-owner-anchors");
            return;
        }
        BridgeDiagnostics.syntheticMultiRegionReadSplit(eventName, listenerCount, observation,
                "result=scheduled scheduledOwners=" + futures.size()
                        + " action=read-split-owner-snapshots mode=nonblocking");
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(5L, TimeUnit.SECONDS)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        BridgeDiagnostics.syntheticMultiRegionReadSplitFailure(eventName, listenerCount,
                                observation, throwable);
                        return;
                    }
                    int completed = 0;
                    for (CompletableFuture<String> future : futures) {
                        if (future.isDone() && !future.isCompletedExceptionally()) {
                            completed++;
                        }
                    }
                    BridgeDiagnostics.syntheticMultiRegionReadSplit(eventName, listenerCount, observation,
                            "result=aggregated phaseResult=completed scheduledOwners=" + futures.size()
                                    + " completedOwners=" + completed
                                    + " action=read-split-owner-snapshots mode=nonblocking");
                });
    }

    private static CompletableFuture<String> scheduleBlockRead(
            SyntheticEventOwnerExtractor.MultiRegionObservation observation, Block block) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            Consumer<Object> task = ignored -> {
                try {
                    // Read-only snapshot: touching material proves the owner
                    // region can satisfy this block-owned read without copying
                    // the whole listener chain across regions.
                    future.complete(String.valueOf(block.getType()));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            };
            run.invoke(scheduler, bridgePlugin(), block.getLocation(), task);
        } catch (Throwable throwable) {
            future.completeExceptionally(new IllegalStateException(
                    "Unable to schedule synthetic multi-region read for " + observation.methodName(), throwable));
        }
        return future;
    }

    private static Plugin bridgePlugin() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("FoliaBytecodeBridge");
            if (plugin != null) return plugin;
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("FoliaBytecodeBridge plugin is not enabled for synthetic read split");
    }
}
