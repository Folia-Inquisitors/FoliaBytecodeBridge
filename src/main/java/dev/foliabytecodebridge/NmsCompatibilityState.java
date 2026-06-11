package dev.foliabytecodebridge;

/**
 * Compact evidence object for one NMS compatibility path.
 *
 * <p>This is the NMS sibling of the synthetic event path state. It records the
 * server-internal family, owner clue, lane status, and route-exit decision
 * without pretending the path is an ordinary Bukkit {@link RouteFamily}.</p>
 */
final class NmsCompatibilityState {

    private final String sourceApi;
    private final NmsCompatFamily family;
    private final String model;
    private final String ownerStatus;
    private final String ownerKind;
    private final String ownerDetail;
    private final String laneStatus;
    private final String action;
    private final String result;
    private final String missReason;
    private final boolean routeExit;

    private NmsCompatibilityState(String sourceApi, NmsCompatFamily family, String model,
                                  String ownerStatus, String ownerKind, String ownerDetail,
                                  String laneStatus, String action, String result,
                                  String missReason, boolean routeExit) {
        this.sourceApi = sourceApi;
        this.family = family;
        this.model = model;
        this.ownerStatus = ownerStatus;
        this.ownerKind = ownerKind;
        this.ownerDetail = ownerDetail;
        this.laneStatus = laneStatus;
        this.action = action;
        this.result = result;
        this.missReason = missReason;
        this.routeExit = routeExit;
    }

    static NmsCompatibilityState scanning(String sourceApi, NmsCompatFamily family, String model) {
        return new NmsCompatibilityState(sourceApi, family, model,
                "scanning", "none", "none", "pending",
                "scan-start", "scanning", "none", false);
    }

    NmsCompatibilityState ownerFound(NmsOwnerExtractor.Owner owner, String laneStatus) {
        return new NmsCompatibilityState(sourceApi, family, model,
                "owner-found", owner.kind(), owner.detail(), laneStatus,
                "route-exit", "owner-context-found", "none", true);
    }

    NmsCompatibilityState ownerMiss(String reason, String laneStatus) {
        return new NmsCompatibilityState(sourceApi, family, model,
                "no-owner-contract", "none", "none", laneStatus,
                "stay-serialized", "serialized-unproven", reason, false);
    }

    String sourceApi() {
        return sourceApi;
    }

    NmsCompatFamily family() {
        return family;
    }

    String model() {
        return model;
    }

    String ownerStatus() {
        return ownerStatus;
    }

    String ownerKind() {
        return ownerKind;
    }

    String ownerDetail() {
        return ownerDetail;
    }

    String laneStatus() {
        return laneStatus;
    }

    String action() {
        return action;
    }

    String result() {
        return result;
    }

    String missReason() {
        return missReason;
    }

    boolean routeExit() {
        return routeExit;
    }

    String detail() {
        return "api=" + safe(sourceApi)
                + " family=" + family.label()
                + " model=" + safe(model)
                + " ownerStatus=" + safe(ownerStatus)
                + " ownerKind=" + safe(ownerKind)
                + " ownerDetail=" + safe(ownerDetail)
                + " laneStatus=" + safe(laneStatus)
                + " action=" + safe(action)
                + " result=" + safe(result)
                + " routeExit=" + routeExit
                + " missReason=" + safe(missReason);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
