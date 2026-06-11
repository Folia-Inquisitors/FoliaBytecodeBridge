package dev.foliabytecodebridge;

/**
 * Server-internal compatibility categories.
 *
 * <p>These are deliberately separate from {@link RouteFamily}. Route families
 * answer "which Folia owner owns this Bukkit/Paper API call?" NMS compat
 * families answer "what kind of server-internal shape did this plugin assume?"
 * Keeping the maps separate prevents internal server adapters from looking like
 * ordinary Bukkit ownership routes.</p>
 */
public enum NmsCompatFamily {
    NMS_VERSION_COMPAT("NMS_VERSION_COMPAT",
            "missing or renamed server-internal fields, methods, or classes"),
    SERVER_TICK_COUNTER("SERVER_TICK_COUNTER",
            "synthetic MinecraftServer tick counter compatibility"),
    NMS_EXECUTOR_CONTEXT("NMS_EXECUTOR_CONTEXT",
            "server/chunk executor paths that may require regionized world data"),
    NMS_CHUNK_ACCESS("NMS_CHUNK_ACCESS",
            "server-internal chunk/world access that needs an owner context"),
    NMS_TICK_STATE("NMS_TICK_STATE",
            "server-internal tick state assumptions"),
    NMS_UNSUPPORTED("NMS_UNSUPPORTED",
            "detected NMS shape without a compatibility contract yet");

    private final String label;
    private final String description;

    NmsCompatFamily(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
