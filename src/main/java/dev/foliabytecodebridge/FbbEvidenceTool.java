package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Offline evidence planner for live Folia smoke tests.
 *
 * <p>This is intentionally read-only. It scans plugin jars for bytecode shapes
 * already known to the route registry, then compares that prediction with the
 * latest server log. A prediction is not a safety claim; it only tells us which
 * route families should produce evidence when the plugin executes that code.</p>
 */
public final class FbbEvidenceTool {

    private static final String[] FBB_TAGS = {
            "[FBB scheduler]",
            "[FBB unsafe-call]",
            "[FBB unsafe-failure]",
            "[FBB task-failure]",
            "[FBB model]",
            "[FBB model-summary]",
            "[FBB transform]",
            "[FBB guard-path]",
            "[FBB teleport-path]",
            "[FBB metadata]"
    };

    private FbbEvidenceTool() {
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options.help) {
            printHelp();
            return;
        }

        System.out.println("[FBB evidence] mode=preflight routeFamilies=" + RouteFamily.labels()
                + " routeRules=" + RouteRuleRegistry.rules().size());
        List<JarReport> reports = List.of();
        if (options.pluginsDir != null) {
            reports = scanPluginDirectory(options.pluginsDir, options.maxExamples, options.includeToolingJars);
            printPreflight(reports, options.maxExamples);
        }
        LogSummary logSummary = LogSummary.missing(options.logFile);
        if (options.logFile != null) {
            logSummary = printLogSummary(options.logFile, options.maxLogLines);
        }
        if (options.serverRoot != null && logSummary.exists) {
            printMemberMaps(options.serverRoot, logSummary.compatReports, options.maxExamples);
        }
        if (options.serverRoot != null && !reports.isEmpty()) {
            printSyntheticCandidateMap(options.serverRoot, options.paperRoot, reports, options.maxExamples);
        }
        if (!reports.isEmpty()) {
            printExpectedObserved(reports, logSummary);
        }
    }

    private static List<JarReport> scanPluginDirectory(Path pluginsDir, int maxExamples,
                                                       boolean includeToolingJars) throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            System.out.println("[FBB preflight-warning] pluginsDir=" + pluginsDir + " result=missing-directory");
            return List.of();
        }

        List<Path> jars;
        try (var stream = Files.list(pluginsDir)) {
            jars = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        }

        List<JarReport> reports = new ArrayList<>();
        for (Path jar : jars) {
            if (!includeToolingJars && isFbbToolingJar(jar.getFileName().toString())) {
                System.out.println("[FBB preflight-skip] jar=" + jar.getFileName()
                        + " reason=fbb-tooling use=--include-tooling-jars");
                continue;
            }
            try {
                reports.add(scanJar(jar, maxExamples));
            } catch (RuntimeException | IOException ex) {
                System.out.println("[FBB preflight-warning] jar=" + jar.getFileName()
                        + " result=scan-failed error=" + quote(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            }
        }
        return reports;
    }

    private static boolean isFbbToolingJar(String jarName) {
        String lower = jarName.toLowerCase(Locale.ROOT);
        return lower.equals("foliabytecodebridge.jar")
                || lower.equals("fbbprobe.jar")
                || lower.equals("fbbprobecontrol.jar");
    }

    private static JarReport scanJar(Path jarPath, int maxExamples) throws IOException {
        PluginMetadata metadata = PluginMetadata.unknown(jarPath.getFileName().toString());
        Map<RouteFamily, Integer> familyCounts = new EnumMap<>(RouteFamily.class);
        Map<String, MethodReport> methodReports = new TreeMap<>();
        Map<String, NmsMemberReport> nmsMemberReports = new TreeMap<>();
        Set<String> signals = new LinkedHashSet<>();
        AtomicInteger classCount = new AtomicInteger();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml == null) pluginYml = jar.getJarEntry("paper-plugin.yml");
            if (pluginYml != null) {
                metadata = PluginMetadata.parse(readText(jar, pluginYml), jarPath.getFileName().toString());
            }

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                classCount.incrementAndGet();
                byte[] bytes = readBytes(jar, entry);
                collectSignals(bytes, signals);
                InstructionRouteScanner.RouteReport report = InstructionRouteScanner.scan(bytes, entry.getName());
                for (InstructionRouteScanner.RouteHit hit : report.hits()) {
                    familyCounts.merge(hit.routeFamily(), 1, Integer::sum);
                    String key = hit.owner() + "#" + hit.name() + hit.descriptor();
        MethodReport method = methodReports.computeIfAbsent(key, ignored -> MethodReport.from(hit));
                    method.count++;
                    if (method.examples.size() < maxExamples) {
                        method.examples.add(hit.className() + "#" + hit.methodName() + hit.methodDescriptor());
                    }
                }
                collectDynamicTeleportCandidates(bytes, entry.getName(), methodReports, familyCounts, maxExamples);
                collectNmsMembers(bytes, entry.getName(), nmsMemberReports, maxExamples);
            }
        }

        return new JarReport(jarPath.getFileName().toString(), metadata, classCount.get(),
                familyCounts, methodReports, nmsMemberReports, signals);
    }

    private static void collectNmsMembers(byte[] classBytes, String sourceName,
                                          Map<String, NmsMemberReport> nmsMemberReports,
                                          int maxExamples) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String className = sourceName;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className = name == null ? sourceName : name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        recordNmsMember(className, methodName, methodDescriptor, owner, name, descriptor,
                                "field", nmsMemberReports, maxExamples);
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        recordNmsMember(className, methodName, methodDescriptor, owner, name, descriptor,
                                "method", nmsMemberReports, maxExamples);
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static void recordNmsMember(String className, String methodName, String methodDescriptor,
                                        String owner, String name, String descriptor, String kind,
                                        Map<String, NmsMemberReport> nmsMemberReports,
                                        int maxExamples) {
        if (!NmsCompatModel.isServerInternalOwner(owner)) return;
        String key = kind + ":" + owner + "#" + name + descriptor;
        NmsMemberReport report = nmsMemberReports.computeIfAbsent(key,
                ignored -> new NmsMemberReport(kind, owner, name, descriptor));
        report.count++;
        if (report.examples.size() < maxExamples) {
            report.examples.add(className + "#" + methodName + methodDescriptor);
        }
    }

    private static void collectDynamicTeleportCandidates(byte[] classBytes, String sourceName,
                                                         Map<String, MethodReport> methodReports,
                                                         Map<RouteFamily, Integer> familyCounts,
                                                         int maxExamples) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String className = sourceName;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className = name == null ? sourceName : name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        recordTeleportCandidate(className, methodName, methodDescriptor,
                                owner, name, descriptor, methodReports, familyCounts, maxExamples);
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        for (Object argument : bootstrapMethodArguments) {
                            if (argument instanceof Handle handle) {
                                recordTeleportCandidate(className, methodName, methodDescriptor,
                                        handle.getOwner(), handle.getName(), handle.getDesc(),
                                        methodReports, familyCounts, maxExamples);
                            }
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static void recordTeleportCandidate(String className, String methodName, String methodDescriptor,
                                                String owner, String name, String descriptor,
                                                Map<String, MethodReport> methodReports,
                                                Map<RouteFamily, Integer> familyCounts,
                                                int maxExamples) {
        if (!isTeleportCandidate(owner, name, descriptor)) return;

        String key = owner + "#" + name + descriptor;
        MethodReport method = methodReports.computeIfAbsent(key, ignored -> MethodReport.dynamicTeleport(owner, name, descriptor));
        method.count++;
        familyCounts.merge(RouteFamily.A_ENTITY, 1, Integer::sum);
        if (method.examples.size() < maxExamples) {
            method.examples.add(className + "#" + methodName + methodDescriptor);
        }
    }

    private static boolean isTeleportCandidate(String owner, String name, String descriptor) {
        if (!name.toLowerCase(Locale.ROOT).contains("teleport")) return false;
        if (!descriptor.contains("Lorg/bukkit/Location;")) return false;

        boolean directBukkitEntity = owner.startsWith("org/bukkit/entity/")
                && (descriptor.endsWith(")Z") || descriptor.endsWith(")Ljava/util/concurrent/CompletableFuture;"));
        boolean helperShape = descriptor.contains("Lorg/bukkit/entity/Entity;")
                || descriptor.contains("Lorg/bukkit/entity/Player;");
        return directBukkitEntity || helperShape;
    }

    private static void printPreflight(List<JarReport> reports, int maxExamples) {
        if (reports.isEmpty()) {
            System.out.println("[FBB preflight] result=no-plugin-jars");
            return;
        }

        for (JarReport report : reports) {
            String routeSummary = report.familyCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey().label() + "=" + entry.getValue())
                    .collect(Collectors.joining(","));
            if (routeSummary.isBlank()) routeSummary = "none";

            System.out.println("[FBB preflight-plugin] jar=" + report.jarName
                    + " plugin=" + quote(report.metadata.name)
                    + " version=" + quote(report.metadata.version)
                    + " main=" + quote(report.metadata.mainClass)
                    + " foliaSupported=" + report.metadata.foliaSupported
                    + " commands=" + quote(String.join(",", report.metadata.commands))
                    + " classes=" + report.classCount
                    + " routeHits=" + report.methodReports.values().stream().mapToInt(method -> method.count).sum()
                    + " nmsHits=" + report.nmsMemberReports.values().stream().mapToInt(member -> member.count).sum()
                    + " routes=" + quote(routeSummary)
                    + " signals=" + quote(String.join(",", report.signals)));

            report.methodReports.values().stream()
                    .sorted(Comparator.comparing((MethodReport method) -> method.routeFamily.label())
                            .thenComparing(method -> method.owner)
                            .thenComparing(method -> method.name)
                            .thenComparing(method -> method.descriptor))
                    .forEach(method -> printMethod(report.jarName, method, maxExamples));

            report.nmsMemberReports.values().stream()
                    .sorted(Comparator.comparing((NmsMemberReport member) -> member.owner)
                            .thenComparing(member -> member.name)
                            .thenComparing(member -> member.descriptor))
                    .forEach(member -> printNmsMember(report.jarName, member, maxExamples));
        }
    }

    private static void printMethod(String jarName, MethodReport method, int maxExamples) {
        Optional<RouteRuleRegistry.RouteRule> rule = RouteRuleRegistry.matchExact(method.owner, method.name, method.descriptor);
        String policy = rule.map(value -> value.returnPolicy().name()).orElse("registry-observed-only");
        String status = rule.map(value -> value.status().name()).orElse("registry-observed-only");
        String note = rule.map(RouteRuleRegistry.RouteRule::note).orElse(method.note);
        List<String> examples = method.examples.size() <= maxExamples
                ? method.examples
                : method.examples.subList(0, maxExamples);

        System.out.println("[FBB preflight-route] jar=" + jarName
                + " route=" + method.routeFamily.label()
                + " owner=" + method.owner.replace('/', '.')
                + " name=" + method.name
                + " descriptor=" + method.descriptor
                + " guard=" + quote(method.guard)
                + " confidence=" + method.confidence
                + " policy=" + policy
                + " status=" + status
                + " calls=" + method.count
                + " examples=" + quote(String.join(" | ", examples))
                + " note=" + quote(note));
    }

    private static void printNmsMember(String jarName, NmsMemberReport member, int maxExamples) {
        List<String> examples = member.examples.size() <= maxExamples
                ? member.examples
                : member.examples.subList(0, maxExamples);
        System.out.println("[FBB preflight-nms] jar=" + jarName
                + " category=" + NmsCompatModel.CATEGORY
                + " kind=" + member.kind
                + " owner=" + member.owner.replace('/', '.')
                + " name=" + member.name
                + " descriptor=" + member.descriptor
                + " calls=" + member.count
                + " examples=" + quote(String.join(" | ", examples))
                + " action=map-server-member-before-adapter"
                + " note=" + quote("server-internal reference; not a Folia ownership route unless a later stack trace proves a mismatch"));
    }

    private static LogSummary printLogSummary(Path logFile, int maxLogLines) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            System.out.println("[FBB log-summary] file=" + PrivacySanitizer.path(logFile) + " result=missing-file");
            return LogSummary.missing(logFile);
        }

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<NmsCompatModel.Report> compatReports = NmsCompatModel.fromLogLines(lines);
        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        for (String tag : FBB_TAGS) tagCounts.put(tag, 0);
        tagCounts.put("[FBB compat]", compatReports.size());
        Map<RouteFamily, Integer> routeCounts = new EnumMap<>(RouteFamily.class);
        List<String> interesting = new ArrayList<>();
        List<String> controlInteresting = new ArrayList<>();
        boolean fileLockFailure = false;
        int fbbLineCount = 0;
        int realErrorLines = 0;
        int expectedControlErrorLines = 0;

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (line.contains("locked a portion of the file")) fileLockFailure = true;
            boolean keep = false;
            for (String tag : FBB_TAGS) {
                if (line.contains(tag)) {
                    tagCounts.merge(tag, 1, Integer::sum);
                    keep = true;
                }
            }
            if (keep) fbbLineCount++;
            for (RouteFamily family : RouteFamily.values()) {
                if (line.contains("route=" + family.label()) || line.contains("routeFamily=" + family.label())) {
                    routeCounts.merge(family, 1, Integer::sum);
                }
            }
            boolean errorLine = importantErrorLine(line);
            boolean controlLine = controlProbeEvidence(lines, lineIndex);
            if (keep || errorLine) {
                if (controlLine) {
                    controlInteresting.add(line);
                    if (errorLine) expectedControlErrorLines++;
                } else {
                    interesting.add(line);
                    if (errorLine) realErrorLines++;
                }
            }
        }

        for (NmsCompatModel.Report report : compatReports) {
            interesting.add(report.toEvidenceLine());
        }

        String tags = tagCounts.entrySet().stream()
                .map(entry -> entry.getKey().replace("[FBB ", "").replace("]", "") + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        String routes = routeCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().label() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        if (routes.isBlank()) routes = "none";

        System.out.println("[FBB log-summary] file=" + PrivacySanitizer.path(logFile)
                + " lines=" + lines.size()
                + " tags=" + quote(tags)
                + " routes=" + quote(routes)
                + " fileLockFailure=" + fileLockFailure
                + " realErrorLines=" + realErrorLines
                + " expectedControlErrorLines=" + expectedControlErrorLines);

        System.out.println("[FBB control-summary] expectedBaselineLines=" + controlInteresting.size()
                + " expectedErrorLines=" + expectedControlErrorLines
                + " note=" + quote("FBBProbeControl is deliberately untransformed; these lines are raw Folia baseline evidence, not bridge rewrites"));

        int controlStart = Math.max(0, controlInteresting.size() - Math.max(5, maxLogLines / 4));
        for (int index = controlStart; index < controlInteresting.size(); index++) {
            System.out.println("[FBB control-evidence] " + PrivacySanitizer.text(controlInteresting.get(index)));
        }

        int start = Math.max(0, interesting.size() - maxLogLines);
        for (int index = start; index < interesting.size(); index++) {
            System.out.println("[FBB log-evidence] " + PrivacySanitizer.text(interesting.get(index)));
        }
        return new LogSummary(logFile, lines, tagCounts, routeCounts, fileLockFailure,
                fbbLineCount, true, compatReports);
    }

    private static boolean importantErrorLine(String line) {
        return line.contains("UnsupportedOperationException")
                || line.contains("NoSuchFieldError")
                || line.contains("NoSuchMethodError")
                || line.contains("ClassNotFoundException")
                || line.contains("NoClassDefFoundError")
                || line.contains("IncompatibleClassChangeError")
                || line.contains("/ERROR]")
                || line.contains("Command exception")
                || line.contains("Could not pass event")
                || line.contains("Cannot ");
    }

    private static boolean controlProbeEvidence(List<String> lines, int lineIndex) {
        if (controlProbeToken(lines.get(lineIndex))) return true;
        if (!importantErrorLine(lines.get(lineIndex))) return false;

        // Raw Folia stack traces often put the FBBProbeControl frame a few lines
        // after the ERROR header. Keep a small local window so the summary can
        // classify expected baseline explosions without hiding the stack itself.
        int start = Math.max(0, lineIndex - 4);
        int end = Math.min(lines.size(), lineIndex + 24);
        for (int index = start; index < end; index++) {
            if (controlProbeToken(lines.get(index))) return true;
        }
        return false;
    }

    private static boolean controlProbeToken(String line) {
        return line.contains("bridgeRole=control-untransformed")
                || line.contains("FBBProbeControl")
                || line.contains("fbbprobecontrol")
                || line.contains("probecontrol")
                || line.contains("dev.foliabytecodebridge.probecontrol");
    }

    private static void printMemberMaps(Path serverRoot, List<NmsCompatModel.Report> compatReports,
                                        int maxCandidates) throws IOException {
        if (compatReports.isEmpty()) {
            System.out.println("[FBB member-map] category=" + NmsCompatModel.CATEGORY
                    + " serverRoot=" + quote(PrivacySanitizer.path(serverRoot))
                    + " result=no-compat-failures");
            return;
        }
        for (NmsCompatModel.Report report : compatReports) {
            Optional<ServerMemberMap.Result> result = ServerMemberMap.inspectServerRoot(
                    serverRoot, report.failure(), maxCandidates);
            if (result.isPresent()) {
                System.out.println(PrivacySanitizer.text(result.get().toEvidenceLine()));
            } else {
                System.out.println("[FBB member-map] category=" + NmsCompatModel.CATEGORY
                        + " owner=" + report.failure().owner()
                        + " kind=" + report.failure().kind()
                        + " expected=" + report.failure().name() + ":" + report.failure().descriptor()
                        + " classFound=false source=" + quote(PrivacySanitizer.path(serverRoot))
                        + " candidates=\"\" action=adapter-research"
                        + " next=provide-server-root-or-extracted-server-jar");
            }
        }
    }

    private static void printSyntheticCandidateMap(Path serverRoot, Path paperRoot,
                                                   List<JarReport> reports, int maxCandidates) throws IOException {
        Map<String, SyntheticCandidate> candidates = new TreeMap<>();
        for (JarReport report : reports) {
            for (NmsMemberReport member : report.nmsMemberReports.values()) {
                String key = member.kind + ":" + member.owner + "#" + member.name + member.descriptor;
                SyntheticCandidate candidate = candidates.computeIfAbsent(key,
                        ignored -> new SyntheticCandidate(member.kind, member.owner, member.name, member.descriptor));
                candidate.calls += member.count;
                candidate.jars.add(report.jarName);
                for (String example : member.examples) {
                    if (candidate.examples.size() < maxCandidates) {
                        candidate.examples.add(report.jarName + "!" + example);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            System.out.println("[FBB synthetic-scan] category=" + NmsCompatModel.CATEGORY
                    + " result=no-server-internal-plugin-references");
            return;
        }

        int liveExactSuppressed = 0;
        int printed = 0;
        Map<String, Integer> classifications = new TreeMap<>();
        for (SyntheticCandidate candidate : candidates.values()) {
            NmsCompatModel.Failure failure = new NmsCompatModel.Failure(
                    "method".equals(candidate.kind) ? "NoSuchMethodError" : "NoSuchFieldError",
                    candidate.owner.replace('/', '.'), candidate.name, candidate.descriptor, candidate.kind,
                    "offline-plugin-reference-scan");
            Optional<ServerMemberMap.Result> live = ServerMemberMap.inspectServerRoot(serverRoot, failure, maxCandidates);
            SourceMemberEvidence paper = paperRoot == null
                    ? SourceMemberEvidence.notConfigured()
                    : inspectSourceRoot(paperRoot, candidate.owner, candidate.name);
            boolean liveClassFound = live.map(ServerMemberMap.Result::classFound).orElse(false);
            boolean liveExact = live.map(ServerMemberMap.Result::exactMatch).orElse(false);

            String classification = classifySyntheticCandidate(candidate, liveClassFound, liveExact, paper);
            classifications.merge(classification, 1, Integer::sum);
            if (liveExact) {
                liveExactSuppressed++;
                continue;
            }
            printed++;
            System.out.println("[FBB synthetic-candidate] category=" + NmsCompatModel.CATEGORY
                    + " owner=" + candidate.owner.replace('/', '.')
                    + " kind=" + candidate.kind
                    + " name=" + candidate.name
                    + " descriptor=" + candidate.descriptor
                    + " calls=" + candidate.calls
                    + " jars=" + quote(String.join(",", candidate.jars))
                    + " liveClassFound=" + liveClassFound
                    + " liveExact=" + liveExact
                    + " paperRoot=" + quote(PrivacySanitizer.path(paperRoot))
                    + " paperClassFound=" + paper.classFound
                    + " paperNameFound=" + paper.nameFound
                    + " paperSource=" + quote(PrivacySanitizer.text(paper.source))
                    + " classification=" + classification
                    + " examples=" + quote(PrivacySanitizer.text(String.join(" | ", candidate.examples)))
                    + " next=" + quote(nextSyntheticStep(classification, candidate.kind)));
        }
        String summary = classifications.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        System.out.println("[FBB synthetic-summary] category=" + NmsCompatModel.CATEGORY
                + " candidates=" + candidates.size()
                + " printedGaps=" + printed
                + " liveExactSuppressed=" + liveExactSuppressed
                + " classifications=" + quote(summary));
    }

    private static String classifySyntheticCandidate(SyntheticCandidate candidate,
                                                     boolean liveClassFound,
                                                     boolean liveExact,
                                                     SourceMemberEvidence paper) {
        if (liveExact) return "no-synthetic-needed-live-member-exists";
        if (!liveClassFound && paper.classFound) return "owner-shape-mismatch-research-before-adapter";
        if (paper.nameFound && "field".equals(candidate.kind)) return "synthetic-field-candidate-map-equivalent-first";
        if (paper.nameFound) return "synthetic-method-risky-behavior-adapter-first";
        if (paper.configured && paper.classFound) return "member-not-in-paper-reference-map-equivalent-first";
        if (paper.configured) return "owner-not-found-in-paper-reference";
        return "reference-root-not-configured";
    }

    private static String nextSyntheticStep(String classification, String kind) {
        return switch (classification) {
            case "synthetic-field-candidate-map-equivalent-first" ->
                    "inspect Paper source and Folia equivalent field/method; add synthetic only if behavior is preserved";
            case "synthetic-method-risky-behavior-adapter-first" ->
                    "do not synthesize blindly; method bodies need a behavior adapter or ownership route model";
            case "owner-shape-mismatch-research-before-adapter" ->
                    "compare package/version mappings before adding any synthetic member";
            case "no-synthetic-needed-live-member-exists" ->
                    "if runtime still fails, investigate classloader, relocation, or descriptor mapping";
            case "reference-root-not-configured" ->
                    "rerun with --paper-root <extracted Paper source or reference root>";
            default ->
                    "keep as evidence until a runtime linkage failure or clear Paper-to-Folia equivalent appears";
        };
    }

    private static SourceMemberEvidence inspectSourceRoot(Path root, String owner, String memberName) throws IOException {
        if (root == null || !Files.exists(root)) return SourceMemberEvidence.missingRoot(root);
        if (Files.isRegularFile(root) && root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return inspectSourceZip(root, owner, memberName);
        }
        String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
        List<Path> matches = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals(simpleName + ".java")
                                || fileName.equals(simpleName + ".java.patch")
                                || fileName.equals(simpleName + ".patch");
                    })
                    .limit(20)
                    .forEach(matches::add);
        }
        if (matches.isEmpty()) return new SourceMemberEvidence(true, false, false, "");
        for (Path match : matches) {
            String text;
            try {
                text = Files.readString(match, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                continue;
            }
            if (text.contains(memberName)) {
                return new SourceMemberEvidence(true, true, true, PrivacySanitizer.path(match));
            }
        }
        return new SourceMemberEvidence(true, true, false,
                matches.stream().map(PrivacySanitizer::path).collect(Collectors.joining("|")));
    }

    private static SourceMemberEvidence inspectSourceZip(Path zipPath, String owner, String memberName) throws IOException {
        String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
        List<String> matches = new ArrayList<>();
        try (JarFile zip = new JarFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String fileName = Path.of(entry.getName()).getFileName().toString();
                if (!fileName.equals(simpleName + ".java")
                        && !fileName.equals(simpleName + ".java.patch")
                        && !fileName.equals(simpleName + ".patch")) {
                    continue;
                }
                matches.add(entry.getName());
                String text = new String(readBytes(zip, entry), StandardCharsets.UTF_8);
                if (text.contains(memberName)) {
                    return new SourceMemberEvidence(true, true, true,
                    PrivacySanitizer.path(zipPath) + "!" + entry.getName());
                }
                if (matches.size() >= 20) break;
            }
        }
        if (matches.isEmpty()) return new SourceMemberEvidence(true, false, false, PrivacySanitizer.path(zipPath));
        return new SourceMemberEvidence(true, true, false,
                matches.stream().map(name -> PrivacySanitizer.path(zipPath) + "!" + name).collect(Collectors.joining("|")));
    }

    private static void printExpectedObserved(List<JarReport> reports, LogSummary logSummary) {
        if (!logSummary.exists) {
            System.out.println("[FBB expected-summary] result=no-log predictions=" + countMethods(reports));
            return;
        }

        int exact = 0;
        int pluginFamily = 0;
        int familyOnly = 0;
        int notExercised = 0;
        int blocked = 0;

        for (JarReport report : reports) {
            for (MethodReport method : report.methodReports.values()) {
                Observation observation = findObservation(report, method, logSummary);
                switch (observation.status) {
                    case "observed-exact" -> exact++;
                    case "observed-plugin-family" -> pluginFamily++;
                    case "observed-family-only" -> familyOnly++;
                    case "server-start-blocked" -> blocked++;
                    default -> notExercised++;
                }
                System.out.println("[FBB expected-observed] jar=" + report.jarName
                        + " plugin=" + quote(report.metadata.name)
                        + " route=" + method.routeFamily.label()
                        + " owner=" + method.owner.replace('/', '.')
                        + " name=" + method.name
                        + " descriptor=" + method.descriptor
                        + " confidence=" + method.confidence
                        + " predictedCalls=" + method.count
                        + " observed=" + observation.status
                        + " evidence=" + quote(observation.evidence)
                        + " next=" + quote(observation.nextAction));
            }
        }

        System.out.println("[FBB expected-summary] predictions=" + countMethods(reports)
                + " observedExact=" + exact
                + " observedPluginFamily=" + pluginFamily
                + " observedFamilyOnly=" + familyOnly
                + " notExercised=" + notExercised
                + " serverStartBlocked=" + blocked
                + " fbbLogLines=" + logSummary.fbbLineCount);
    }

    private static Observation findObservation(JarReport report, MethodReport method, LogSummary logSummary) {
        if (logSummary.fileLockFailure && logSummary.fbbLineCount == 0) {
            return new Observation("server-start-blocked", "DirectoryLock/file lock before FBB runtime evidence",
                    "stop the duplicate/stale server process, boot once, then rerun evidence");
        }

        String routeNeedle = "route=" + method.routeFamily.label();
        String ownerDot = method.owner.replace('/', '.');
        String ownerInternal = method.owner;
        String apiNeedle = ownerDot + "#" + method.name;
        String ownerLogNeedle = "owner=" + ownerDot;
        String nameNeedle = "name=" + method.name;
        String descriptorNeedle = "descriptor=" + method.descriptor;
        String jarNeedle = report.jarName;
        String pluginNeedle = report.metadata.name;

        for (String line : logSummary.lines) {
            if (!line.contains(routeNeedle)) continue;
            boolean exactOwner = line.contains(apiNeedle)
                    || line.contains(ownerLogNeedle)
                    || line.contains(ownerDot)
                    || line.contains(ownerInternal);
            boolean exactMethod = line.contains(nameNeedle)
                    || line.contains("#" + method.name)
                    || line.contains("api=" + simpleApi(method));
            boolean exactDescriptor = line.contains(descriptorNeedle);
            if (exactOwner && exactMethod && (exactDescriptor || method.confidence.equals("dynamic-teleport-shape"))) {
                return new Observation("observed-exact", compact(line),
                        "compare rewrite/action fields; refine route only if action=missed or unsafe-failure appears");
            }
        }

        for (String line : logSummary.lines) {
            if (!line.contains(routeNeedle)) continue;
            boolean pluginMatch = line.contains(jarNeedle)
                    || (!pluginNeedle.isBlank() && line.contains(pluginNeedle))
                    || report.methodReports.values().stream()
                    .flatMap(value -> value.examples.stream())
                    .limit(20)
                    .anyMatch(example -> line.contains(exampleClassPrefix(example)));
            if (pluginMatch) {
                return new Observation("observed-plugin-family", compact(line),
                        "route family ran for this plugin; trigger narrower feature or inspect class/method evidence");
            }
        }

        for (String line : logSummary.lines) {
            if (line.contains(routeNeedle)) {
                return new Observation("observed-family-only", compact(line),
                        "same route family ran elsewhere; trigger this plugin path or add a focused probe");
            }
        }

        return new Observation("not-exercised", "no matching FBB route line in latest.log",
                "run the command/feature from plugin.yml or add startup probe for this method shape");
    }

    private static int countMethods(List<JarReport> reports) {
        return reports.stream().mapToInt(report -> report.methodReports.size()).sum();
    }

    private static String simpleApi(MethodReport method) {
        int slash = method.owner.lastIndexOf('/');
        String simpleOwner = slash >= 0 ? method.owner.substring(slash + 1) : method.owner;
        return simpleOwner + "#" + method.name;
    }

    private static String exampleClassPrefix(String example) {
        int hash = example.indexOf('#');
        String className = hash >= 0 ? example.substring(0, hash) : example;
        return className.replace('/', '.');
    }

    private static String compact(String line) {
        String compacted = line.replace('\t', ' ').replaceAll("\\s+", " ").trim();
        return compacted.length() <= 260 ? compacted : compacted.substring(0, 257) + "...";
    }

    private static void collectSignals(byte[] bytes, Set<String> signals) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        addSignal(text, signals, "org/bukkit/scheduler", "bukkit-scheduler");
        addSignal(text, signals, "io/papermc/paper/threadedregions/scheduler", "folia-scheduler");
        addSignal(text, signals, "org/bukkit/scoreboard", "scoreboard");
        addSignal(text, signals, "teleport", "teleport");
        addSignal(text, signals, "getNearbyEntities", "nearby-entities");
        addSignal(text, signals, "getEntities", "world-entity-scan");
        addSignal(text, signals, "org/bukkit/World", "world-api");
        addSignal(text, signals, "org/bukkit/entity", "entity-api");
        addSignal(text, signals, "org/bukkit/inventory", "inventory-api");
        addSignal(text, signals, "PaperLib", "paperlib-like");
        addSignal(text, signals, "net/minecraft/", "nms-internals");
        addSignal(text, signals, "org/bukkit/craftbukkit/", "craftbukkit-internals");
        addSignal(text, signals, "ca/spottedleaf/", "folia-internals");
    }

    private static void addSignal(String text, Set<String> signals, String needle, String signal) {
        if (text.contains(needle)) signals.add(signal);
    }

    private static String readText(JarFile jar, JarEntry entry) throws IOException {
        return new String(readBytes(jar, entry), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static String quote(String value) {
        if (value == null || value.isBlank()) return "\"\"";
        return "\"" + PrivacySanitizer.text(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void printHelp() {
        System.out.println("Usage: java -cp FoliaBytecodeBridge.jar "
                + FbbEvidenceTool.class.getName()
                + " [--plugins <plugins-dir>] [--log <latest.log>] [--server-root <server-root>]"
                + " [--paper-root <Paper source/reference root>] [--max-examples N] [--max-log-lines N]"
                + " [--include-tooling-jars]");
        System.out.println("This tool predicts route families, summarizes observed FBB log evidence, maps NMS compatibility failures to running server members, and lists synthetic-member candidates for review.");
    }

    private record JarReport(String jarName, PluginMetadata metadata, int classCount,
                             Map<RouteFamily, Integer> familyCounts,
                             Map<String, MethodReport> methodReports,
                             Map<String, NmsMemberReport> nmsMemberReports,
                             Set<String> signals) {
    }

    private static final class NmsMemberReport {
        private final String kind;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final List<String> examples = new ArrayList<>();
        private int count;

        private NmsMemberReport(String kind, String owner, String name, String descriptor) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    private static final class SyntheticCandidate {
        private final String kind;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final Set<String> jars = new LinkedHashSet<>();
        private final List<String> examples = new ArrayList<>();
        private int calls;

        private SyntheticCandidate(String kind, String owner, String name, String descriptor) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    private record SourceMemberEvidence(boolean configured, boolean classFound,
                                        boolean nameFound, String source) {
        private static SourceMemberEvidence notConfigured() {
            return new SourceMemberEvidence(false, false, false, "");
        }

        private static SourceMemberEvidence missingRoot(Path root) {
            return new SourceMemberEvidence(true, false, false,
                    PrivacySanitizer.path(root));
        }
    }

    private static final class MethodReport {
        private final RouteFamily routeFamily;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final String guard;
        private final String note;
        private final String confidence;
        private final List<String> examples = new ArrayList<>();
        private int count;

        private MethodReport(RouteFamily routeFamily, String owner, String name, String descriptor,
                             String guard, String note, String confidence) {
            this.routeFamily = routeFamily;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.guard = guard;
            this.note = note;
            this.confidence = confidence;
        }

        private static MethodReport from(InstructionRouteScanner.RouteHit hit) {
            String confidence = RouteRuleRegistry.matchExact(hit.owner(), hit.name(), hit.descriptor()).isPresent()
                    ? "exact-route-rule"
                    : "scanner-observed-shape";
            return new MethodReport(hit.routeFamily(), hit.owner(), hit.name(), hit.descriptor(),
                    hit.guard(), hit.note(), confidence);
        }

        private static MethodReport dynamicTeleport(String owner, String name, String descriptor) {
            return new MethodReport(RouteFamily.A_ENTITY, owner, name, descriptor,
                    "RawTeleportTransformer#dynamic-shape",
                    "teleport-like bytecode candidate; transformer decides exact rewrite, trace-only, or missed by owner/name/descriptor shape",
                    "dynamic-teleport-shape");
        }
    }

        private record LogSummary(Path path, List<String> lines, Map<String, Integer> tagCounts,
                              Map<RouteFamily, Integer> routeCounts, boolean fileLockFailure,
                              int fbbLineCount, boolean exists,
                              List<NmsCompatModel.Report> compatReports) {
        private static LogSummary missing(Path path) {
            return new LogSummary(path, List.of(), Map.of(), Map.of(), false, 0, false, List.of());
        }
    }

    private record Observation(String status, String evidence, String nextAction) {
    }

    private record PluginMetadata(String name, String version, String mainClass,
                                  String foliaSupported, List<String> commands) {
        private static PluginMetadata unknown(String jarName) {
            return new PluginMetadata(jarName, "unknown", "unknown", "unknown", List.of());
        }

        private static PluginMetadata parse(String yaml, String jarName) {
            Map<String, String> topLevel = new HashMap<>();
            Set<String> commands = new LinkedHashSet<>();
            String section = "";

            for (String rawLine : yaml.split("\\R")) {
                String line = rawLine.replace("\t", "    ");
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                int indent = countIndent(line);
                String trimmed = line.trim();
                if (indent == 0) {
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        String key = trimmed.substring(0, colon).trim();
                        String value = trimmed.substring(colon + 1).trim();
                        section = key;
                        if (!value.isBlank()) topLevel.put(key, stripYamlScalar(value));
                    }
                    continue;
                }
                if ("commands".equals(section) && indent <= 4 && trimmed.endsWith(":")) {
                    commands.add(trimmed.substring(0, trimmed.length() - 1).trim());
                }
            }

            return new PluginMetadata(
                    topLevel.getOrDefault("name", jarName),
                    topLevel.getOrDefault("version", "unknown"),
                    topLevel.getOrDefault("main", topLevel.getOrDefault("bootstrapper", "unknown")),
                    topLevel.getOrDefault("folia-supported", topLevel.getOrDefault("foliaSupported", "missing")),
                    List.copyOf(commands));
        }

        private static int countIndent(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') count++;
            return count;
        }

        private static String stripYamlScalar(String value) {
            String stripped = value.trim();
            if ((stripped.startsWith("\"") && stripped.endsWith("\""))
                    || (stripped.startsWith("'") && stripped.endsWith("'"))) {
                stripped = stripped.substring(1, stripped.length() - 1);
            }
            return stripped;
        }
    }

    private static final class Options {
        private Path pluginsDir;
        private Path logFile;
        private Path serverRoot;
        private Path paperRoot;
        private int maxExamples = 3;
        private int maxLogLines = 80;
        private boolean includeToolingJars;
        private boolean help;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--plugins" -> options.pluginsDir = Path.of(requireValue(args, ++index, arg));
                    case "--log" -> options.logFile = Path.of(requireValue(args, ++index, arg));
                    case "--server-root" -> options.serverRoot = Path.of(requireValue(args, ++index, arg));
                    case "--paper-root" -> options.paperRoot = Path.of(requireValue(args, ++index, arg));
                    case "--max-examples" -> options.maxExamples = Integer.parseInt(requireValue(args, ++index, arg));
                    case "--max-log-lines" -> options.maxLogLines = Integer.parseInt(requireValue(args, ++index, arg));
                    case "--include-tooling-jars" -> options.includeToolingJars = true;
                    case "--no-log" -> options.logFile = null;
                    case "--help", "-h", "/?" -> options.help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            return args[index];
        }
    }
}
