package smoketest.shaded;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.concurrent.CompletableFuture;

public final class PaperLibLike {

    private PaperLibLike() {
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location, TeleportCause cause) {
        return entity.teleportAsync(location, cause);
    }

    public static CompletableFuture<Boolean> teleportAsync(Player player, Location location, TeleportCause cause) {
        return player.teleportAsync(location, cause);
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location,
                                                           TeleportCause cause, boolean unsupportedShape) {
        return entity.teleportAsync(location, cause);
    }
}
