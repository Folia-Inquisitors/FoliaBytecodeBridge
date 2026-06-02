package dev.fbbprobe.harness;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Probe-only event for synthetic/shared event path evidence.
 *
 * <p>It does not touch gameplay state. Multiple probe listeners mutate and
 * observe this one event object so logs can show ordering, cancellation, and
 * shared event state without requiring a player join or a destructive action.</p>
 */
public final class SharedEventPathProbeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String trigger;
    private final String context;
    private final String bridgeRole;
    private final boolean entityOwnerExitProbe;
    private final boolean unknownOverlapProbe;
    private final boolean internalStateProbe;
    private final List<String> effects = new ArrayList<>();
    private boolean cancelled;

    public SharedEventPathProbeEvent(String trigger, String context, String bridgeRole) {
        this(trigger, context, bridgeRole, false, false);
    }

    public SharedEventPathProbeEvent(String trigger, String context, String bridgeRole,
                                     boolean entityOwnerExitProbe) {
        this(trigger, context, bridgeRole, entityOwnerExitProbe, false);
    }

    public SharedEventPathProbeEvent(String trigger, String context, String bridgeRole,
                                     boolean entityOwnerExitProbe, boolean unknownOverlapProbe) {
        this(trigger, context, bridgeRole, entityOwnerExitProbe, unknownOverlapProbe, false);
    }

    public SharedEventPathProbeEvent(String trigger, String context, String bridgeRole,
                                     boolean entityOwnerExitProbe, boolean unknownOverlapProbe,
                                     boolean internalStateProbe) {
        this.trigger = trigger;
        this.context = context;
        this.bridgeRole = bridgeRole;
        this.entityOwnerExitProbe = entityOwnerExitProbe;
        this.unknownOverlapProbe = unknownOverlapProbe;
        this.internalStateProbe = internalStateProbe;
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

    public String bridgeRole() {
        return bridgeRole;
    }

    public boolean entityOwnerExitProbe() {
        return entityOwnerExitProbe;
    }

    public boolean unknownOverlapProbe() {
        return unknownOverlapProbe;
    }

    public boolean internalStateProbe() {
        return internalStateProbe;
    }

    public void addEffect(String effect) {
        effects.add(effect);
    }

    public List<String> effects() {
        return Collections.unmodifiableList(effects);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
