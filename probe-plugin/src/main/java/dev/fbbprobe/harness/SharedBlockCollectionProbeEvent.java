package dev.fbbprobe.harness;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

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

    public SharedBlockCollectionProbeEvent(String trigger, String context, List<Block> blocks) {
        this.trigger = trigger;
        this.context = context;
        this.blocks = blocks;
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
}
