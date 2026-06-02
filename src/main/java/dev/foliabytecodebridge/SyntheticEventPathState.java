package dev.foliabytecodebridge;

/**
 * Debug state for one synthetic custom-event dispatch.
 *
 * <p>The wrapper keeps unknown listener logic serialized, but known owner
 * shapes can still exit to a Folia route. This state object is the compact
 * evidence record that explains which side of that decision an event took.</p>
 */
final class SyntheticEventPathState {

    private final String eventName;
    private final int listenerCount;
    private final boolean shared;
    private final String route;
    private final String ownerStatus;
    private final String ownerMethod;
    private final String ownerType;
    private final String laneStatus;
    private final String missReason;
    private final boolean routeExit;

    private SyntheticEventPathState(String eventName, int listenerCount, boolean shared,
                                    String route, String ownerStatus, String ownerMethod,
                                    String ownerType, String laneStatus, String missReason,
                                    boolean routeExit) {
        this.eventName = eventName;
        this.listenerCount = listenerCount;
        this.shared = shared;
        this.route = route;
        this.ownerStatus = ownerStatus;
        this.ownerMethod = ownerMethod;
        this.ownerType = ownerType;
        this.laneStatus = laneStatus;
        this.missReason = missReason;
        this.routeExit = routeExit;
    }

    static SyntheticEventPathState scanning(String eventName, int listenerCount) {
        return new SyntheticEventPathState(eventName, listenerCount, listenerCount > 1,
                "none", "scanning", "none", "none",
                "pending", "none", false);
    }

    SyntheticEventPathState routeExit(RouteFamily routeFamily, String ownerMethod, String ownerType,
                                      String path) {
        return new SyntheticEventPathState(eventName, listenerCount, shared,
                routeFamily.label(), "owner-found", ownerMethod, ownerType,
                path, "none", true);
    }

    SyntheticEventPathState serialized(String missReason) {
        return new SyntheticEventPathState(eventName, listenerCount, shared,
                "none", "owner-missed", "none", "none",
                "serialized-compatibility-lane", missReason, false);
    }

    String detail() {
        return "event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " shared=" + shared
                + " route=" + safe(route)
                + " ownerStatus=" + safe(ownerStatus)
                + " ownerMethod=" + safe(ownerMethod)
                + " ownerType=" + safe(ownerType)
                + " laneStatus=" + safe(laneStatus)
                + " routeExit=" + routeExit
                + " missReason=" + safe(missReason);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
