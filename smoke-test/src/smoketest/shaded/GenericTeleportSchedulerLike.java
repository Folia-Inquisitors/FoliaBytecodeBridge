package smoketest.shaded;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.concurrent.CompletableFuture;

public interface GenericTeleportSchedulerLike {

    CompletableFuture<Boolean> teleportAsync(Entity entity, Location location);

    CompletableFuture<Boolean> teleportAsync(Entity entity, Location location, TeleportCause cause);
}
