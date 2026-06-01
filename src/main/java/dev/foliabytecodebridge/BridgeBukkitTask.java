package dev.foliabytecodebridge;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class BridgeBukkitTask implements BukkitTask {

    private final int taskId;
    private final Plugin owner;
    private final boolean sync;
    private final Object handle;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    BridgeBukkitTask(int taskId, Plugin owner, boolean sync, Object handle) {
        this.taskId = taskId;
        this.owner = owner;
        this.sync = sync;
        this.handle = handle;
    }

    @Override
    public int getTaskId() {
        return taskId;
    }

    @Override
    public Plugin getOwner() {
        return owner;
    }

    @Override
    public boolean isSync() {
        return sync;
    }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        try {
            // Folia scheduled tasks expose cancel(), but the exact type is API-owned, so keep the wrapper decoupled.
            Method cancel = handle.getClass().getMethod("cancel");
            cancel.invoke(handle);
        } catch (ReflectiveOperationException ignored) {
        } finally {
            SchedulerBridge.forget(taskId);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    boolean isBridgeCancelled() {
        return cancelled.get();
    }
}
