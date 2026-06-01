package dev.fbbprobe.harness;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Harmless event used only to learn how Folia treats PluginManager#callEvent
 * from probe contexts. It has no listeners by default and carries no server
 * state, so failures point at the event dispatch path rather than gameplay.
 */
public final class GlobalModelProbeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
