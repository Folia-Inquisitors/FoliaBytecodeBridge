package dev.foliabytecodebridge;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.utility.JavaModule;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;

import java.lang.instrument.Instrumentation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class FoliaBytecodeBridgeAgent {

    private static volatile boolean installed;
    private static final List<JarFile> APPENDED_SERVER_JARS = Collections.synchronizedList(new ArrayList<>());
    private static volatile File helperRuntimeJar;

    private FoliaBytecodeBridgeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        install(instrumentation, "JAVA_AGENT");
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        install(instrumentation, arguments == null || arguments.isBlank() ? "ATTACH" : arguments.trim());
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static synchronized void install(Instrumentation instrumentation) {
        install(instrumentation, "UNKNOWN");
    }

    public static synchronized void install(Instrumentation instrumentation, String mode) {
        if (installed) return;
        try {
            openJavaNetForRuntimeVisibility(instrumentation);
            appendBridgeHelperJarToSystemSearch(instrumentation);
            appendServerLibraries(instrumentation);
            ClassFileLocator serverFallbackLocator = serverFallbackLocator();
            if (BridgeConfig.metadataOverlayAll()) {
                // This transformer only touches Folia's plugin metadata gate. It is intentionally separate
                // from plugin bytecode rewrites so logs can distinguish "allowed to load" from "made safe".
                MetadataJarReporter.reportPluginDirectory(new File("plugins").toPath());
                instrumentation.addTransformer(new RawMetadataTransformer(), true);
                retransformMetadataGate(instrumentation);
            }
            // Built-in server-fired events do not pass through plugin bytecode calling PluginManager#callEvent.
            // This exact API boundary rewrite lets RegisteredListener callbacks enter the same synthetic
            // compatibility model without rewriting every plugin listener class.
            instrumentation.addTransformer(new RawRegisteredListenerTransformer(), true);
            retransformRegisteredListenerBoundary(instrumentation);
            // NMS compatibility shims are not route-family rewrites. They patch missing server-internal
            // member shapes before server classes load, so keep them separate from plugin bytecode routing.
            instrumentation.addTransformer(new NmsSyntheticMemberTransformer(), true);
            // Register the raw scheduler transformer first. It only needs JVM descriptors, so it can patch
            // inherited BukkitRunnable owners even when Byte Buddy's typed resolver later skips a class.
            instrumentation.addTransformer(new RawSchedulerTransformer(), true);
            // Register the raw teleport family scanner before typed substitutions. It handles static
            // PaperLib-style shaded helpers by descriptor and logs typed Bukkit teleport paths with the
            // bytecode owner so A_ENTITY evidence is not tied to one plugin package.
            instrumentation.addTransformer(new RawTeleportTransformer(), true);
            // Command dispatch is a server/global guard with a sender-specific Folia route. Keep it as its
            // own raw transformer so exact return-shape tradeoffs stay visible in the logs and docs.
            instrumentation.addTransformer(new RawServerCommandTransformer(), true);
            // Paper's MCUtil.MAIN_EXECUTOR is a legacy single-main-thread executor. On Folia this must be
            // treated as global/server-owned work, not as a direct MinecraftServer#execute call.
            instrumentation.addTransformer(new RawMcUtilExecutorTransformer(), true);
            // Some plugin adapters call MinecraftServer#execute(Runnable) directly instead of going through
            // MCUtil.MAIN_EXECUTOR. That is the same legacy server-executor assumption, so keep it as a
            // general S_GLOBAL NMS executor route with its own diagnostic path.
            instrumentation.addTransformer(new RawNmsServerExecutorTransformer(), true);
            // Direct region/entity reads need a raw fallback because some plugin loaders resolve enough
            // bytecode for ASM but not enough type metadata for the typed Byte Buddy substitution pass.
            instrumentation.addTransformer(new RawDirectUnsafeTransformer(), true);
            // Paper/Folia guard tracing is evidence-only. It records bytecode that is known to hit
            // AsyncCatcher/TickThread-style guards, but it does not rewrite until a safe route exists.
            instrumentation.addTransformer(new RawGuardTraceTransformer(), true);
            // Legacy "main thread" predicates need their own model. A false old Paper main-thread check
            // can be compatible on a Folia tick/region thread, but async worker pools must stay false.
            instrumentation.addTransformer(new RawLegacyMainThreadTransformer(), true);
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.PoolStrategy.Default.EXTENDED)
                    .with(AgentBuilder.LocationStrategy.ForClassLoader.STRONG.withFallbackTo(serverFallbackLocator))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    // Never rewrite server internals or the bridge itself. This transformer is only for plugin code.
                    // Live-server smoke showed Byte Buddy can trip on Folia/Moonrise invokedynamic metadata before
                    // plugins load, so server libraries stay out of the typed substitution path. The raw scheduler
                    // transformer below still catches exact plugin scheduler bytecode without touching bootstrap code.
                    .ignore(nameStartsWith("java.")
                            .or(nameStartsWith("javax."))
                            .or(nameStartsWith("jdk."))
                            .or(nameStartsWith("sun."))
                            .or(nameStartsWith("com.sun."))
                            .or(nameStartsWith("org.jcp."))
                            .or(nameStartsWith("net.bytebuddy."))
                            .or(nameStartsWith("org.bukkit."))
                            .or(nameStartsWith("io.papermc."))
                            .or(nameStartsWith("paperclip."))
                            .or(nameStartsWith("net.minecraft."))
                            .or(nameStartsWith("com.mojang."))
                            .or(nameStartsWith("ca.spottedleaf."))
                            .or(nameStartsWith("it.unimi.dsi."))
                            .or(nameStartsWith("joptsimple."))
                            .or(nameStartsWith("net.minecrell."))
                            .or(nameStartsWith("org.jline."))
                            .or(nameStartsWith("io.netty."))
                            .or(nameStartsWith("org.xml."))
                            .or(nameStartsWith("oshi."))
                            .or(nameStartsWith("org.joml."))
                            .or(nameStartsWith("com.lmax."))
                            .or(nameStartsWith("org.spongepowered."))
                            .or(nameStartsWith("org.yaml."))
                            .or(nameStartsWith("io.leangen."))
                            .or(nameStartsWith("com.mysql."))
                            .or(nameStartsWith("com.velocitypowered."))
                            .or(nameStartsWith("net.md_5."))
                            .or(nameStartsWith("com.destroystokyo."))
                            .or(nameStartsWith("co.aikar."))
                            .or(nameStartsWith("com.google."))
                            .or(nameStartsWith("net.kyori."))
                            .or(nameStartsWith("org.slf4j."))
                            .or(nameStartsWith("org.apache.logging."))
                            .or(nameStartsWith("org.jetbrains."))
                            .or(nameStartsWith("dev.foliabytecodebridge.")))
                    .type(not(isInterface()))
                    .transform(FoliaBytecodeBridgeAgent::transform)
                    .with(new DiagnosticListener())
                    .installOn(instrumentation);
            retransformLoadedPluginClasses(instrumentation, mode);
            installed = true;
            System.setProperty("foliabytecodebridge.agentInstalled", "true");
            System.setProperty("foliabytecodebridge.agentMode", mode);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unable to install Folia bytecode bridge", throwable);
        }
    }

    private static DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                    TypeDescription typeDescription,
                                                    ClassLoader classLoader,
                                                    JavaModule module,
                                                    ProtectionDomain protectionDomain) {
        try {
            if (!bukkitApiVisibleToAgent()) {
                BridgeDiagnostics.transformSkipped(typeDescription.getName(), classLoader, "bukkit-api-not-visible-yet");
                return builder;
            }

            TypedRouteCandidateReporter.CandidateReport candidateReport =
                    TypedRouteCandidateReporter.inspect(
                            TypedRouteCandidateReporter.TypeName.of(typeDescription.getName()), classLoader);
            boolean typedTransformCandidate = candidateReport.routeCandidate() || candidateReport.scanUnknown();
            BridgeDiagnostics.typedRouteCandidateScan(typeDescription.getName(), classLoader, candidateReport,
                    typedTransformCandidate ? "attempted" : "skipped-no-registered-route-candidate");
            if (!typedTransformCandidate) {
                BridgeDiagnostics.transformSkipped(typeDescription.getName(), classLoader,
                        "typed-prescan-no-registered-route-candidate");
                return builder;
            }
            BridgeRuntimeVisibility.ensureBridgeVisible(classLoader, typeDescription.getName());

            // Keep substitutions grouped by source API so the matrix in docs/TRANSFORM_MATRIX.md stays easy to audit.
            return builder
                    .visit(replaceBukkitSchedulerCalls().on(any()))
                    .visit(replaceBukkitRunnableCalls().on(any()))
                    .visit(replaceDirectUnsafeCalls().on(any()));
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Bridge methods are missing", exception);
        }
    }

    private static boolean bukkitApiVisibleToAgent() {
        return canLoad("org.bukkit.plugin.Plugin", FoliaBytecodeBridgeAgent.class.getClassLoader())
                && canLoad("org.bukkit.scheduler.BukkitScheduler", FoliaBytecodeBridgeAgent.class.getClassLoader())
                && canLoad("org.bukkit.entity.Player", FoliaBytecodeBridgeAgent.class.getClassLoader());
    }

    private static void openJavaNetForRuntimeVisibility(Instrumentation instrumentation) {
        try {
            Module javaBase = URLClassLoader.class.getModule();
            Module bridgeModule = FoliaBytecodeBridgeAgent.class.getModule();
            instrumentation.redefineModule(javaBase,
                    Set.of(),
                    Map.of(),
                    Map.of("java.net", Set.of(bridgeModule)),
                    Set.of(),
                    Map.of());
            BridgeDiagnostics.agentClasspath("runtime-visibility java.base/java.net opened-to-bridge-module");
        } catch (Throwable throwable) {
            BridgeDiagnostics.attachWarning("runtime-visibility result=module-open-failed throwable="
                    + throwable.getClass().getName() + ": " + throwable.getMessage());
        }
    }

    static URL bridgeHelperJarUrl() {
        try {
            File file = helperRuntimeJar;
            return file == null || !file.isFile() ? null : file.toURI().toURL();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendBridgeHelperJarToSystemSearch(Instrumentation instrumentation) {
        try {
            CodeSource source = FoliaBytecodeBridgeAgent.class.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) {
                BridgeDiagnostics.attachWarning(
                        "helper-visibility result=bridge-jar-missing action=append-system-search");
                return;
            }

            File bridgeJar = new File(location.toURI());
            if (!bridgeJar.isFile()) {
                BridgeDiagnostics.attachWarning(
                        "helper-visibility result=bridge-jar-not-file action=append-system-search jar="
                                + bridgeJar);
                return;
            }

            File helperJar = helperRuntimeJar(bridgeJar);
            if (helperJar == null || !helperJar.isFile()) {
                BridgeDiagnostics.attachWarning(
                        "helper-visibility result=helper-runtime-jar-missing action=append-system-search");
                return;
            }

            // Raw and typed transformers emit INVOKESTATIC calls into bridge helper classes. The system-visible
            // helper jar must not contain the Bukkit plugin main class or anonymous entrypoint helpers; otherwise
            // Bukkit may resolve plugin-owned classes from the wrong parent loader and split plugin bootstrap access.
            JarFile jarFile = new JarFile(helperJar);
            instrumentation.appendToSystemClassLoaderSearch(jarFile);
            APPENDED_SERVER_JARS.add(jarFile);
            BridgeDiagnostics.agentClasspath("helper-runtime-jar-appended-to-system-search=" + helperJar.getName());
        } catch (Throwable throwable) {
            BridgeDiagnostics.attachWarning("helper-visibility result=append-system-search-failed throwable="
                    + throwable.getClass().getName() + ": " + throwable.getMessage());
        }
    }

    private static File helperRuntimeJar(File bridgeJar) throws IOException {
        File existing = helperRuntimeJar;
        if (existing != null && existing.isFile()) return existing;

        File temp = File.createTempFile("folia-bytecode-bridge-helper-", ".jar");
        temp.deleteOnExit();
        try (JarFile input = new JarFile(bridgeJar);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp))) {
            Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!helperRuntimeEntry(name)) continue;
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream entryStream = input.getInputStream(entry)) {
                        entryStream.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
        helperRuntimeJar = temp;
        BridgeDiagnostics.agentClasspath("helper-runtime-jar-created=" + temp.getName()
                + " source=" + bridgeJar.getName()
                + " note=excludes-bukkit-plugin-entrypoint-classes");
        return temp;
    }

    private static boolean helperRuntimeEntry(String name) {
        if (name == null) return false;
        if (!name.startsWith("dev/foliabytecodebridge/") || !name.endsWith(".class")) return false;
        return !name.equals("dev/foliabytecodebridge/FoliaBytecodeBridgePlugin.class")
                && !name.startsWith("dev/foliabytecodebridge/FoliaBytecodeBridgePlugin$");
    }

    private static ClassFileLocator serverFallbackLocator() {
        List<ClassFileLocator> locators = new ArrayList<>();
        locators.add(ClassFileLocator.ForClassLoader.ofBootLoader());
        locators.add(ClassFileLocator.ForClassLoader.ofSystemLoader());

        String roots = System.getProperty("foliabytecodebridge.classpathRoots", "libraries;versions;cache");
        for (String root : roots.split(";")) {
            if (root.isBlank()) continue;
            collectJarLocators(new File(root.trim()), locators);
        }

        return new ClassFileLocator.Compound(locators);
    }

    private static void appendServerLibraries(Instrumentation instrumentation) {
        if (!Boolean.parseBoolean(System.getProperty("foliabytecodebridge.appendServerLibraries", "false"))) {
            return;
        }
        List<File> jars = new ArrayList<>();
        String roots = System.getProperty("foliabytecodebridge.classpathRoots", "libraries;versions;cache");
        Pattern appendPattern = Pattern.compile(System.getProperty("foliabytecodebridge.appendServerLibraryPattern",
                "(?i)(folia-api|paper-api|adventure-|examination-|guava-|failureaccess-|listenablefuture-|gson-|bungeecord-chat|annotations-).*\\.jar"));
        for (String root : roots.split(";")) {
            if (root.isBlank()) continue;
            collectJarFiles(new File(root.trim()), jars);
        }

        int appended = 0;
        for (File jar : jars) {
            if (!appendPattern.matcher(jar.getName()).matches()) {
                continue;
            }
            try {
                JarFile jarFile = new JarFile(jar);
                instrumentation.appendToSystemClassLoaderSearch(jarFile);
                APPENDED_SERVER_JARS.add(jarFile);
                appended++;
            } catch (IOException ignored) {
                // Optional or locked jars should not prevent the transformer from installing.
            }
        }
        BridgeDiagnostics.agentClasspath("server-jars-appended=" + appended);
    }

    private static void collectJarLocators(File file, List<ClassFileLocator> locators) {
        if (!file.exists()) return;
        if (file.isFile() && file.getName().endsWith(".jar")) {
            try {
                locators.add(ClassFileLocator.ForJarFile.of(file));
            } catch (IOException ignored) {
                // A missing optional jar should not prevent the bridge from installing.
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            collectJarLocators(child, locators);
        }
    }

    private static void collectJarFiles(File file, List<File> jars) {
        if (!file.exists()) return;
        if (file.isFile() && file.getName().endsWith(".jar")) {
            jars.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            collectJarFiles(child, jars);
        }
    }

    private static boolean canLoad(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void retransformMetadataGate(Instrumentation instrumentation) {
        if (!instrumentation.isRetransformClassesSupported()) return;
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!"org.bukkit.plugin.PluginDescriptionFile".equals(loadedClass.getName())) continue;
            if (!instrumentation.isModifiableClass(loadedClass)) return;
            try {
                instrumentation.retransformClasses(loadedClass);
            } catch (Throwable throwable) {
                BridgeDiagnostics.attachWarning("metadataOverlay=" + BridgeConfig.metadataOverlay()
                        + " retransformClass=" + loadedClass.getName()
                        + " result=failed throwable=" + throwable.getClass().getName()
                        + ": " + throwable.getMessage());
            }
            return;
        }
    }

    private static void retransformRegisteredListenerBoundary(Instrumentation instrumentation) {
        if (!BridgeConfig.syntheticListenerBoundary() || !instrumentation.isRetransformClassesSupported()) return;
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!"org.bukkit.plugin.RegisteredListener".equals(loadedClass.getName())) continue;
            if (!instrumentation.isModifiableClass(loadedClass)) return;
            try {
                instrumentation.retransformClasses(loadedClass);
            } catch (Throwable throwable) {
                BridgeDiagnostics.attachWarning("syntheticListenerBoundary=true"
                        + " retransformClass=" + loadedClass.getName()
                        + " result=failed throwable=" + throwable.getClass().getName()
                        + ": " + throwable.getMessage());
            }
            return;
        }
    }

    private static void retransformLoadedPluginClasses(Instrumentation instrumentation, String mode) {
        if (!instrumentation.isRetransformClassesSupported()) {
            BridgeDiagnostics.attachWarning("mode=" + mode
                    + " retransformSupported=false reason=jvm-does-not-support-retransform");
            return;
        }

        int candidates = 0;
        int attempted = 0;
        int failed = 0;
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!isLoadedPluginClassCandidate(instrumentation, loadedClass)) continue;
            candidates++;
            try {
                instrumentation.retransformClasses(loadedClass);
                attempted++;
            } catch (Throwable throwable) {
                failed++;
                BridgeDiagnostics.attachWarning("mode=" + mode
                        + " retransformClass=" + loadedClass.getName()
                        + " result=failed throwable=" + throwable.getClass().getName()
                        + ": " + throwable.getMessage());
            }
        }
        BridgeDiagnostics.attach("mode=" + mode
                + " retransformCandidates=" + candidates
                + " retransformAttempted=" + attempted
                + " retransformFailed=" + failed);
    }

    private static boolean isLoadedPluginClassCandidate(Instrumentation instrumentation, Class<?> loadedClass) {
        if (loadedClass == null
                || loadedClass.isArray()
                || loadedClass.isPrimitive()
                || loadedClass.getClassLoader() == null
                || !instrumentation.isModifiableClass(loadedClass)) {
            return false;
        }

        String internalName = loadedClass.getName().replace('.', '/');
        if (RawSchedulerTransformer.shouldIgnore(internalName)) return false;

        ProtectionDomain protectionDomain = loadedClass.getProtectionDomain();
        if (protectionDomain == null
                || protectionDomain.getCodeSource() == null
                || protectionDomain.getCodeSource().getLocation() == null) {
            return false;
        }

        String location = protectionDomain.getCodeSource().getLocation().toString().replace('\\', '/').toLowerCase();
        // Self-attach can happen after Paper has already created plugin instances. Retransform only jars under
        // /plugins/ so the raw scheduler transformer gets a second chance at already-loaded target plugin classes
        // without asking Byte Buddy to revisit server libraries again.
        return location.contains("/plugins/")
                && location.endsWith(".jar")
                && !location.endsWith("/foliabytecodebridge.jar");
    }

    private static MemberSubstitution replaceBukkitSchedulerCalls() throws NoSuchMethodException {
        // relaxed() lets a class pass through when it does not contain one of these exact call shapes.
        return MemberSubstitution.relaxed()
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTask"))
                        .and(takesArguments(Plugin.class, Runnable.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTask", BukkitScheduler.class, Plugin.class, Runnable.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTaskLater"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskLater", BukkitScheduler.class, Plugin.class, Runnable.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTaskTimer"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskTimer", BukkitScheduler.class, Plugin.class, Runnable.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTaskAsynchronously"))
                        .and(takesArguments(Plugin.class, Runnable.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskAsynchronously", BukkitScheduler.class, Plugin.class, Runnable.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTaskLaterAsynchronously"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskLaterAsynchronously", BukkitScheduler.class, Plugin.class, Runnable.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("runTaskTimerAsynchronously"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskTimerAsynchronously", BukkitScheduler.class, Plugin.class, Runnable.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleSyncDelayedTask"))
                        .and(takesArguments(Plugin.class, Runnable.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleSyncDelayedTask", BukkitScheduler.class, Plugin.class, Runnable.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleSyncDelayedTask"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleSyncDelayedTask", BukkitScheduler.class, Plugin.class, Runnable.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleAsyncDelayedTask"))
                        .and(takesArguments(Plugin.class, Runnable.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleAsyncDelayedTask", BukkitScheduler.class, Plugin.class, Runnable.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleAsyncDelayedTask"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleAsyncDelayedTask", BukkitScheduler.class, Plugin.class, Runnable.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleSyncRepeatingTask"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleSyncRepeatingTask", BukkitScheduler.class, Plugin.class, Runnable.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("scheduleAsyncRepeatingTask"))
                        .and(takesArguments(Plugin.class, Runnable.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "scheduleAsyncRepeatingTask", BukkitScheduler.class, Plugin.class, Runnable.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("cancelTask"))
                        .and(takesArguments(int.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "cancelTask", BukkitScheduler.class, int.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("cancelTasks"))
                        .and(takesArguments(Plugin.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "cancelTasks", BukkitScheduler.class, Plugin.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitScheduler"))
                        .and(named("callSyncMethod"))
                        .and(takesArguments(Plugin.class, Callable.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "callSyncMethod", BukkitScheduler.class, Plugin.class, Callable.class));
    }

    private static MemberSubstitution replaceBukkitRunnableCalls() throws NoSuchMethodException {
        // BukkitRunnable stores cancellation state internally on Bukkit. On Folia we mirror that state in SchedulerBridge.
        return MemberSubstitution.relaxed()
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTask"))
                        .and(takesArguments(Plugin.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTask", BukkitRunnable.class, Plugin.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTaskLater"))
                        .and(takesArguments(Plugin.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskLater", BukkitRunnable.class, Plugin.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTaskTimer"))
                        .and(takesArguments(Plugin.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskTimer", BukkitRunnable.class, Plugin.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTaskAsynchronously"))
                        .and(takesArguments(Plugin.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskAsynchronously", BukkitRunnable.class, Plugin.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTaskLaterAsynchronously"))
                        .and(takesArguments(Plugin.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskLaterAsynchronously", BukkitRunnable.class, Plugin.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("runTaskTimerAsynchronously"))
                        .and(takesArguments(Plugin.class, long.class, long.class)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "runTaskTimerAsynchronously", BukkitRunnable.class, Plugin.class, long.class, long.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("cancel"))
                        .and(takesArguments(0)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "cancel", BukkitRunnable.class))
                .method(isDeclaredBy(named("org.bukkit.scheduler.BukkitRunnable"))
                        .and(named("isCancelled"))
                        .and(takesArguments(0)))
                .replaceWith(SchedulerBridge.class.getMethod(
                        "isCancelled", BukkitRunnable.class));
    }

    private static MemberSubstitution replaceDirectUnsafeCalls() throws NoSuchMethodException {
        return MemberSubstitution.relaxed()
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("getLocation"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("entityGetLocation", Entity.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("getWorld"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("entityGetWorld", Entity.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("teleport"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("entityTeleport", Entity.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("teleport"))
                        .and(takesArguments(Location.class, TeleportCause.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "entityTeleport", Entity.class, Location.class, TeleportCause.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("teleportAsync"))
                        .and(takesArguments(Location.class, TeleportCause.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "entityTeleportAsync", Entity.class, Location.class, TeleportCause.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("setVelocity"))
                        .and(takesArguments(Vector.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("entitySetVelocity", Entity.class, Vector.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("getNearbyEntities"))
                        .and(takesArguments(double.class, double.class, double.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "entityGetNearbyEntities", Entity.class, double.class, double.class, double.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Entity"))
                        .and(named("remove"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("entityRemove", Entity.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("getLocation"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerGetLocation", Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("getWorld"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerGetWorld", Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("teleport"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerTeleport", Player.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("teleport"))
                        .and(takesArguments(Location.class, TeleportCause.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "playerTeleport", Player.class, Location.class, TeleportCause.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("setGameMode"))
                        .and(takesArguments(GameMode.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerSetGameMode", Player.class, GameMode.class))
                .method(isDeclaredBy(named("org.bukkit.entity.HumanEntity"))
                        .and(named("setGameMode"))
                        .and(takesArguments(GameMode.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("humanSetGameMode", HumanEntity.class, GameMode.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("setVelocity"))
                        .and(takesArguments(Vector.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerSetVelocity", Player.class, Vector.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, Sound.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "playerPlaySound", Player.class, Location.class, Sound.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, String.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "playerPlaySound", Player.class, Location.class, String.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, Sound.class, SoundCategory.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "playerPlaySound", Player.class, Location.class, Sound.class, SoundCategory.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, String.class, SoundCategory.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "playerPlaySound", Player.class, Location.class, String.class, SoundCategory.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("openInventory"))
                        .and(takesArguments(Inventory.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerOpenInventory", Player.class, Inventory.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("closeInventory"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerCloseInventory", Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.HumanEntity"))
                        .and(named("openInventory"))
                        .and(takesArguments(Inventory.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("humanOpenInventory", HumanEntity.class, Inventory.class))
                .method(isDeclaredBy(named("org.bukkit.entity.HumanEntity"))
                        .and(named("closeInventory"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("humanCloseInventory", HumanEntity.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("addPotionEffect"))
                        .and(takesArguments(PotionEffect.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerAddPotionEffect", Player.class, PotionEffect.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("removePotionEffect"))
                        .and(takesArguments(PotionEffectType.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerRemovePotionEffect", Player.class, PotionEffectType.class))
                .method(isDeclaredBy(named("org.bukkit.entity.LivingEntity"))
                        .and(named("addPotionEffect"))
                        .and(takesArguments(PotionEffect.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("livingAddPotionEffect", LivingEntity.class, PotionEffect.class))
                .method(isDeclaredBy(named("org.bukkit.entity.LivingEntity"))
                        .and(named("removePotionEffect"))
                        .and(takesArguments(PotionEffectType.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("livingRemovePotionEffect", LivingEntity.class, PotionEffectType.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("hidePlayer"))
                        .and(takesArguments(Plugin.class, Player.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerHidePlayer", Player.class, Plugin.class, Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("showPlayer"))
                        .and(takesArguments(Plugin.class, Player.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerShowPlayer", Player.class, Plugin.class, Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("hidePlayer"))
                        .and(takesArguments(Player.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerHidePlayerLegacy", Player.class, Player.class))
                .method(isDeclaredBy(named("org.bukkit.entity.Player"))
                        .and(named("showPlayer"))
                        .and(takesArguments(Player.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("playerShowPlayerLegacy", Player.class, Player.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getBlockAt"))
                        .and(takesArguments(int.class, int.class, int.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetBlockAt", World.class, int.class, int.class, int.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getBlockAt"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetBlockAt", World.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getEntities"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetEntities", World.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getLoadedChunks"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetLoadedChunks", World.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getNearbyEntities"))
                        .and(takesArguments(Location.class, double.class, double.class, double.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldGetNearbyEntities", World.class, Location.class, double.class, double.class, double.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getEntitiesByClasses"))
                        .and(takesArguments(Class[].class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldGetEntitiesByClasses", World.class, Class[].class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getEntitiesByClass"))
                        .and(takesArguments(Class[].class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldGetEntitiesByClass", World.class, Class[].class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getEntitiesByClass"))
                        .and(takesArguments(Class.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldGetEntitiesByClass", World.class, Class.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getChunkAt"))
                        .and(takesArguments(int.class, int.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetChunkAt", World.class, int.class, int.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getChunkAt"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetChunkAt", World.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("getChunkAt"))
                        .and(takesArguments(Block.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldGetChunkAt", World.class, Block.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("loadChunk"))
                        .and(takesArguments(int.class, int.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldLoadChunk", World.class, int.class, int.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("refreshChunk"))
                        .and(takesArguments(int.class, int.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("worldRefreshChunk", World.class, int.class, int.class))
                .method(isDeclaredBy(named("org.bukkit.Chunk"))
                        .and(named("getEntities"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("chunkGetEntities", Chunk.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("dropItem"))
                        .and(takesArguments(Location.class, ItemStack.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldDropItem", World.class, Location.class, ItemStack.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("dropItemNaturally"))
                        .and(takesArguments(Location.class, ItemStack.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldDropItemNaturally", World.class, Location.class, ItemStack.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("spawnEntity"))
                        .and(takesArguments(Location.class, EntityType.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldSpawnEntity", World.class, Location.class, EntityType.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("generateTree"))
                        .and(takesArguments(Location.class, TreeType.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldGenerateTree", World.class, Location.class, TreeType.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("strikeLightning"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldStrikeLightning", World.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("strikeLightningEffect"))
                        .and(takesArguments(Location.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldStrikeLightningEffect", World.class, Location.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("createExplosion"))
                        .and(takesArguments(Location.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldCreateExplosion", World.class, Location.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("createExplosion"))
                        .and(takesArguments(double.class, double.class, double.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldCreateExplosion", World.class, double.class, double.class, double.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, Sound.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldPlaySound", World.class, Location.class, Sound.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, String.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldPlaySound", World.class, Location.class, String.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, Sound.class, SoundCategory.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldPlaySound", World.class, Location.class, Sound.class, SoundCategory.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.World"))
                        .and(named("playSound"))
                        .and(takesArguments(Location.class, String.class, SoundCategory.class, float.class, float.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod(
                        "worldPlaySound", World.class, Location.class, String.class, SoundCategory.class, float.class, float.class))
                .method(isDeclaredBy(named("org.bukkit.block.Block"))
                        .and(named("setType"))
                        .and(takesArguments(Material.class)))
                .replaceWith(UnsafeCallBridge.class.getMethod("blockSetType", Block.class, Material.class))
                .method(isDeclaredBy(named("org.bukkit.block.Block"))
                        .and(named("getType"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("blockGetType", Block.class))
                .method(isDeclaredBy(named("org.bukkit.block.Block"))
                        .and(named("getBlockData"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("blockGetBlockData", Block.class))
                .method(isDeclaredBy(named("org.bukkit.block.Block"))
                        .and(named("getLocation"))
                        .and(takesArguments(0)))
                .replaceWith(UnsafeCallBridge.class.getMethod("blockGetLocation", Block.class))
                ;
    }

    private static final class DiagnosticListener extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            BridgeDiagnostics.transformed(typeDescription.getName(), classLoader);
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            String missingType = optionalDependencyMissingType(throwable);
            if (missingType != null) {
                BridgeDiagnostics.optionalDependencyTransformSkipped(typeName, classLoader,
                        missingType, optionalDependencyAsmSummary(typeName, classLoader));
                return;
            }
            if (byteBuddyTypeMetadataShape(throwable)) {
                BridgeDiagnostics.transformShapeSkipped(typeName, classLoader, throwable);
                return;
            }
            BridgeDiagnostics.transformError(typeName, throwable);
        }

        private boolean byteBuddyTypeMetadataShape(Throwable throwable) {
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                String message = current.getMessage();
                if (message != null && message.contains("Illegal type annotations")) {
                    return true;
                }
            }
            return false;
        }

        private String optionalDependencyMissingType(Throwable throwable) {
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                String message = current.getMessage();
                String prefix = "Cannot resolve type description for ";
                if (message != null && message.startsWith(prefix)) {
                    return message.substring(prefix.length()).trim();
                }
            }
            return null;
        }

        private String optionalDependencyAsmSummary(String typeName, ClassLoader classLoader) {
            // Byte Buddy needs referenced soft-dependency types for typed substitutions. ASM does not:
            // it can still inventory owner/name/descriptor calls from raw class bytes, so we keep route
            // evidence without faking the missing dependency plugin or masking real unsafe-call failures.
            String resource = typeName.replace('.', '/') + ".class";
            try (InputStream inputStream = classLoader == null
                    ? ClassLoader.getSystemResourceAsStream(resource)
                    : classLoader.getResourceAsStream(resource)) {
                if (inputStream == null) {
                    return "not-available";
                }
                InstructionRouteScanner.RouteReport report =
                        InstructionRouteScanner.scan(inputStream.readAllBytes(), typeName);
                return report.summary().replace(' ', ',');
            } catch (Throwable throwable) {
                return "scan-failed:" + throwable.getClass().getName() + ":" + throwable.getMessage();
            }
        }
    }
}
