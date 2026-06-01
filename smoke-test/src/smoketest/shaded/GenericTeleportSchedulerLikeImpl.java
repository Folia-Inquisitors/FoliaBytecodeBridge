package smoketest.shaded;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.concurrent.CompletableFuture;

public final class GenericTeleportSchedulerLikeImpl implements GenericTeleportSchedulerLike {

    @Override
    public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location, TeleportCause cause) {
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }
}
