package dev.foliabytecodebridge;

import java.io.InputStream;

/**
 * Read-only bytecode inventory before the Byte Buddy typed substitution pass.
 *
 * <p>This deliberately does not decide whether a class is transformed. It gives
 * us a cheap owner/name/descriptor snapshot before Byte Buddy resolves generic
 * and type-use metadata, which is where unrelated event/helper classes can
 * produce noisy inspection errors. The typed transform is still attempted so an
 * unknown-but-legitimate route shape is not hidden by a premature skip.</p>
 */
final class TypedRouteCandidateReporter {

    private static final String MARKER = "typed-route-prescan-v1";

    private TypedRouteCandidateReporter() {
    }

    static CandidateReport inspect(TypeName typeName, ClassLoader classLoader) {
        String resourceName = typeName.internalName() + ".class";
        try (InputStream inputStream = classLoader == null
                ? ClassLoader.getSystemResourceAsStream(resourceName)
                : classLoader.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                return CandidateReport.unknown(typeName, "class-resource-missing");
            }

            InstructionRouteScanner.RouteReport report =
                    InstructionRouteScanner.scan(inputStream.readAllBytes(), typeName.externalName());
            if (report.hits().isEmpty()) {
                return CandidateReport.noRouteCandidate(typeName, metadataCategory(typeName), report.summary());
            }
            return CandidateReport.routeCandidate(typeName, report.summary());
        } catch (Throwable throwable) {
            return CandidateReport.unknown(typeName,
                    "scan-failed:" + throwable.getClass().getName() + ":" + throwable.getMessage());
        }
    }

    private static String metadataCategory(TypeName typeName) {
        String name = typeName.externalName();
        if (name.endsWith("Event") || name.contains(".events.") || name.contains(".api.events.")) {
            return "TYPE_METADATA_ONLY";
        }
        return "NO_REGISTERED_ROUTE_CANDIDATE";
    }

    record TypeName(String externalName, String internalName) {
        static TypeName of(String externalName) {
            String safeExternal = externalName == null ? "unknown" : externalName;
            return new TypeName(safeExternal, safeExternal.replace('.', '/'));
        }
    }

    record CandidateReport(TypeName typeName, String action, String category, String reason) {
        static CandidateReport routeCandidate(TypeName typeName, String reason) {
            return new CandidateReport(typeName, "observe-route-candidate", "ROUTE_CANDIDATE", reason);
        }

        static CandidateReport noRouteCandidate(TypeName typeName, String category, String reason) {
            return new CandidateReport(typeName, "observe-no-route-candidate", category, reason);
        }

        static CandidateReport unknown(TypeName typeName, String reason) {
            return new CandidateReport(typeName, "observe-scan-unknown", "SCAN_UNKNOWN", reason);
        }

        String marker() {
            return MARKER;
        }
    }
}
