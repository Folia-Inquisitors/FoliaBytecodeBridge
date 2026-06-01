package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Read-only map of members exposed by the running server jars.
 *
 * <p>This feeds `NMS_VERSION_COMPAT` diagnostics. It should answer "what does
 * the current server jar expose?" before any adapter rewrites a plugin's
 * server-internal field or method access.</p>
 */
public final class ServerMemberMap {

    private ServerMemberMap() {
    }

    public static Optional<Result> inspectServerRoot(Path serverRoot, NmsCompatModel.Failure failure,
                                                     int maxCandidates) throws IOException {
        if (serverRoot == null || !Files.isDirectory(serverRoot)) return Optional.empty();
        String entryName = failure.owner().replace('.', '/') + ".class";
        for (Path jar : serverJars(serverRoot)) {
            Optional<Result> result = inspectJar(jar, entryName, failure, maxCandidates);
            if (result.isPresent()) return result;
        }
        return Optional.of(Result.notFound(PrivacySanitizer.path(serverRoot), failure));
    }

    public static Result inspectClassBytes(String source, byte[] classBytes, NmsCompatModel.Failure failure,
                                           int maxCandidates) {
        ClassMembers members = readMembers(classBytes);
        boolean exact = members.contains(failure.kind(), failure.name(), failure.descriptor());
        List<Member> candidates = members.candidates(failure.kind(), failure.name(), maxCandidates);
        return new Result(source, failure.owner(), failure.kind(), failure.name(),
                failure.descriptor(), true, exact, candidates);
    }

    private static Optional<Result> inspectJar(Path jarPath, String entryName,
                                               NmsCompatModel.Failure failure,
                                               int maxCandidates) throws IOException {
        if (!Files.isRegularFile(jarPath)) return Optional.empty();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) return Optional.empty();
            return Optional.of(inspectClassBytes(PrivacySanitizer.path(jarPath), readBytes(jar, entry), failure, maxCandidates));
        }
    }

    private static List<Path> serverJars(Path serverRoot) throws IOException {
        Set<Path> jars = new LinkedHashSet<>();
        addIfJar(jars, serverRoot.resolve("versions"));
        addIfJar(jars, serverRoot.resolve("cache"));
        addIfJar(jars, serverRoot.resolve("libraries"));
        Path launcher = serverRoot.resolve("folia.jar");
        if (Files.isRegularFile(launcher)) jars.add(launcher);
        return List.copyOf(jars);
    }

    private static void addIfJar(Set<Path> jars, Path root) throws IOException {
        if (!Files.exists(root)) return;
        if (Files.isRegularFile(root) && root.getFileName().toString().endsWith(".jar")) {
            jars.add(root);
            return;
        }
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
    }

    private static ClassMembers readMembers(byte[] classBytes) {
        ClassMembers members = new ClassMembers();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                members.fields.add(new Member("field", name, descriptor));
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                members.methods.add(new Member("method", name, descriptor));
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return members;
    }

    private static byte[] readBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    public record Result(String source, String owner, String kind, String expectedName,
                         String expectedDescriptor, boolean classFound, boolean exactMatch,
                         List<Member> candidates) {
        static Result notFound(String source, NmsCompatModel.Failure failure) {
            return new Result(source, failure.owner(), failure.kind(), failure.name(),
                    failure.descriptor(), false, false, List.of());
        }

        public String toEvidenceLine() {
            return "[FBB member-map] category=" + NmsCompatModel.CATEGORY
                    + " owner=" + owner
                    + " kind=" + kind
                    + " expected=" + expectedName + ":" + expectedDescriptor
                    + " classFound=" + classFound
                    + " exactMatch=" + exactMatch
                    + " source=" + quote(PrivacySanitizer.text(source))
                    + " candidates=" + quote(formatCandidates())
                    + " action=adapter-research"
                    + " next=" + (exactMatch
                    ? "investigate-classloader-or-obfuscation-mapping"
                    : "map-safe-equivalent-before-bytecode-adapter");
        }

        private String formatCandidates() {
            if (candidates.isEmpty()) return "";
            List<String> values = new ArrayList<>();
            for (Member candidate : candidates) {
                values.add(candidate.name() + ":" + candidate.descriptor());
            }
            return String.join("|", values);
        }

        private static String quote(String value) {
            if (value == null || value.isBlank()) return "\"\"";
            return "\"" + PrivacySanitizer.text(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    public record Member(String kind, String name, String descriptor) {
    }

    private static final class ClassMembers {
        private final List<Member> fields = new ArrayList<>();
        private final List<Member> methods = new ArrayList<>();

        private boolean contains(String kind, String name, String descriptor) {
            return members(kind).stream()
                    .anyMatch(member -> member.name().equals(name)
                            && ("unknown".equals(descriptor) || member.descriptor().equals(descriptor)));
        }

        private List<Member> candidates(String kind, String expectedName, int maxCandidates) {
            String needle = expectedName.toLowerCase(Locale.ROOT);
            return members(kind).stream()
                    .sorted(Comparator
                            .comparingInt((Member member) -> score(needle, member.name()))
                            .thenComparing(Member::name)
                            .thenComparing(Member::descriptor))
                    .limit(Math.max(1, maxCandidates))
                    .toList();
        }

        private List<Member> members(String kind) {
            return "method".equals(kind) ? methods : fields;
        }

        private static int score(String expected, String candidateName) {
            String candidate = candidateName.toLowerCase(Locale.ROOT);
            if (candidate.equals(expected)) return 0;
            if (candidate.contains(expected) || expected.contains(candidate)) return 1;
            if (candidate.contains("tick") && expected.contains("tick")) return 2;
            if (candidate.contains("current") || candidate.contains("time")) return 3;
            return 10;
        }
    }
}
