package dev.foliabytecodebridge;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Smoke coverage for the NMS compatibility model.
 *
 * <p>The smoke test does not prove arbitrary NMS safety. It proves the
 * architecture layer exists: owner clues are found, owner misses stay explicit,
 * and the owner-preserving lane keeps context active while work executes.</p>
 */
public final class NmsCompatibilityLaneSmoke {

    private NmsCompatibilityLaneSmoke() {
    }

    public static String assertNmsCompatibilityLaneAndOwnerModel(World world) {
        LocationOwnerRunnable ownerRunnable = new LocationOwnerRunnable(new Location(world, 32, 70, 48));
        NmsOwnerExtractor.Scan ownerScan = NmsOwnerExtractor.scan(ownerRunnable);
        if (!ownerScan.found()) {
            throw new IllegalStateException("Expected NMS owner scan to find location owner: "
                    + ownerScan.missReason());
        }
        if (!"location".equals(ownerScan.owner().kind())) {
            throw new IllegalStateException("Expected location owner, got " + ownerScan.owner().kind());
        }

        CapturedWorldAndIntsRunnable capturedPair =
                new CapturedWorldAndIntsRunnable(world, 7, -11);
        NmsOwnerExtractor.Scan capturedPairScan = NmsOwnerExtractor.scan(capturedPair);
        if (!capturedPairScan.found()) {
            throw new IllegalStateException("Expected captured world + two ints to become chunk owner: "
                    + capturedPairScan.missReason() + " clues=" + capturedPairScan.clueTrail());
        }
        if (!"chunk".equals(capturedPairScan.owner().kind())
                || capturedPairScan.owner().chunkX() != 7
                || capturedPairScan.owner().chunkZ() != -11) {
            throw new IllegalStateException("Expected chunk owner 7,-11, got "
                    + capturedPairScan.owner().detail());
        }

        NmsOwnerExtractor.Scan missScan = NmsOwnerExtractor.scan(new NoOwnerRunnable());
        if (missScan.found()) {
            throw new IllegalStateException("Expected no-owner NMS runnable to stay unowned");
        }

        NmsCompatibilityState state = NmsCompatibilityState
                .scanning("smoke-nms-executor", NmsCompatFamily.NMS_EXECUTOR_CONTEXT, "SMOKE_NMS_CONTEXT")
                .ownerFound(ownerScan.owner(), "owner-preserving-nms-lane");
        final boolean[] active = {false};
        NmsCompatibilityLane.runOwnerPreserving("smoke-nms-executor",
                "smoke-owner-preserving-lane", state, () -> {
                    active[0] = NmsCompatibilityLane.active()
                            && NmsCompatibilityContext.current() != null;
                });
        if (!active[0]) {
            throw new IllegalStateException("Expected NMS compatibility context to be active inside lane");
        }

        return "owner-found,captured-world-int-pair,owner-miss,lane-context";
    }

    private record LocationOwnerRunnable(Location location) implements Runnable {
        @Override
        public void run() {
        }
    }

    private static final class NoOwnerRunnable implements Runnable {
        @Override
        public void run() {
        }
    }

    /**
     * Mirrors the common executor-lambda shape: a captured world object and two
     * captured integer coordinates. The extractor may promote this only when
     * both clues live on the same runnable object.
     */
    private record CapturedWorldAndIntsRunnable(World world, int chunkX, int chunkZ) implements Runnable {
        @Override
        public void run() {
        }
    }
}
