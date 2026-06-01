package dev.foliabytecodebridge;

public final class RepeatDiagnosticsSmoke {

    private RepeatDiagnosticsSmoke() {
    }

    public static void emitRepeatSummaryEvidence() {
        for (int index = 0; index < 100; index++) {
            BridgeDiagnostics.unsafeCall("Smoke#repeatDiagnostic", RouteFamily.C_REGION_BLOCK,
                    "region", "repeat-summary-smoke",
                    "policy=preemptive-safe reason=repeat-summary-smoke index=" + index);
        }
    }
}
