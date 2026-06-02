package dev.fbbprobe.harness;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Probe-only event with a block collection owner shape.
 *
 * <p>The target bridge should only promote this event out of the synthetic
 * lane when every block belongs to the same region/chunk. Mixed-region
 * collections stay serialized and produce owner-miss evidence.</p>
 */
public final class SharedBlockCollectionProbeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String trigger;
    private final String context;
    private final List<Block> blocks;
    private final boolean readOnly;
    private final boolean mutation;
    private final String mutationKind;
    private final boolean prepareContract;
    private final boolean ownerApplyContract;
    private final boolean aggregateVerifyContract;
    private final boolean failPrepare;
    private final boolean failVerify;
    private final List<String> mutationEffects = new ArrayList<>();

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks) {
        this(trigger, context, blocks, false, false, "unspecified");
    }

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks,
                                           boolean readOnly) {
        this(trigger, context, blocks, readOnly, false, "unspecified");
    }

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks,
                                           boolean readOnly, boolean mutation, String mutationKind) {
        this(trigger, context, blocks, readOnly, mutation, mutationKind, false, false, false);
    }

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks,
                                           boolean readOnly, boolean mutation, String mutationKind,
                                           boolean prepareContract, boolean ownerApplyContract,
                                           boolean aggregateVerifyContract) {
        this(trigger, context, blocks, readOnly, mutation, mutationKind,
                prepareContract, ownerApplyContract, aggregateVerifyContract, false, false);
    }

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks,
                                           boolean readOnly, boolean mutation, String mutationKind,
                                           boolean prepareContract, boolean ownerApplyContract,
                                           boolean aggregateVerifyContract,
                                           boolean failPrepare, boolean failVerify) {
        this.trigger = trigger;
        this.context = context;
        this.blocks = blocks;
        this.readOnly = readOnly;
        this.mutation = mutation;
        this.mutationKind = mutationKind == null || mutationKind.isBlank() ? "unspecified" : mutationKind;
        this.prepareContract = prepareContract;
        this.ownerApplyContract = ownerApplyContract;
        this.aggregateVerifyContract = aggregateVerifyContract;
        this.failPrepare = failPrepare;
        this.failVerify = failVerify;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public String trigger() {
        return trigger;
    }

    public String context() {
        return context;
    }

    public List<Block> getBlocks() {
        return blocks == null ? Collections.emptyList() : Collections.unmodifiableList(blocks);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isMutation() {
        return mutation;
    }

    public String getMutationKind() {
        return mutationKind;
    }

    public boolean supportsPreparePhase() {
        return prepareContract;
    }

    public boolean supportsOwnerApplyPhase() {
        return ownerApplyContract;
    }

    public boolean supportsAggregateVerifyPhase() {
        return aggregateVerifyContract;
    }

    public boolean prepareMutation() {
        mutationEffects.add("prepare");
        return !failPrepare;
    }

    public boolean applyOwnerMutation(Block block) {
        mutationEffects.add("apply:" + ownerKey(block));
        return true;
    }

    public boolean verifyAggregateMutation(int scheduledOwners, int completedOwners) {
        mutationEffects.add("verify:" + scheduledOwners + "/" + completedOwners);
        return !failVerify && scheduledOwners == completedOwners;
    }

    public List<String> mutationEffects() {
        return Collections.unmodifiableList(mutationEffects);
    }

    private static String ownerKey(Block block) {
        if (block == null) return "unknown";
        String world = block.getWorld() == null ? "unknown-world" : block.getWorld().getName();
        return world + ":" + (block.getX() >> 4) + "," + (block.getZ() >> 4);
    }
}
