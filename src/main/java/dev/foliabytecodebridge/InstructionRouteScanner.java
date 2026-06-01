package dev.foliabytecodebridge;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only ASM inventory for exact bytecode route evidence.
 *
 * <p>This is intentionally not a transformer. It answers whether ASM gives us
 * better owner/name/descriptor evidence before we promote any new rewrite path.
 */
public final class InstructionRouteScanner {

    private InstructionRouteScanner() {
    }

    public static RouteReport scan(byte[] classBytes, String sourceName) {
        List<RouteHit> hits = new ArrayList<>();
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
                        InstructionRouteRegistry.match(opcode, owner, name, descriptor)
                                .ifPresent(route -> hits.add(new RouteHit(className, methodName, methodDescriptor,
                                        owner, name, descriptor, route.routeFamily(), route.guard(),
                                        route.note(), false)));
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        for (Object argument : bootstrapMethodArguments) {
                            if (!(argument instanceof Handle handle)) continue;
                            InstructionRouteRegistry.match(handleOpcode(handle.getTag()),
                                            handle.getOwner(), handle.getName(), handle.getDesc())
                                    .ifPresent(route -> hits.add(new RouteHit(className, methodName, methodDescriptor,
                                            handle.getOwner(), handle.getName(), handle.getDesc(),
                                            route.routeFamily(), route.guard(), route.note(), true)));
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return new RouteReport(sourceName, hits);
    }

    private static int handleOpcode(int tag) {
        return switch (tag) {
            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
            default -> -1;
        };
    }

    public record RouteHit(String className, String methodName, String methodDescriptor,
                           String owner, String name, String descriptor,
                           RouteFamily routeFamily, String guard, String note,
                           boolean methodReference) {
        public String key() {
            return owner + "#" + name + descriptor;
        }
    }

    public static final class RouteReport {
        private final String sourceName;
        private final List<RouteHit> hits;
        private final Map<RouteFamily, Integer> counts;

        private RouteReport(String sourceName, List<RouteHit> hits) {
            this.sourceName = sourceName;
            this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
            this.counts = new EnumMap<>(RouteFamily.class);
            for (RouteHit hit : hits) {
                counts.merge(hit.routeFamily(), 1, Integer::sum);
            }
        }

        public String sourceName() {
            return sourceName;
        }

        public List<RouteHit> hits() {
            return hits;
        }

        public int count(RouteFamily routeFamily) {
            return counts.getOrDefault(routeFamily, 0);
        }

        public boolean contains(RouteFamily routeFamily, String owner, String name, String descriptor) {
            for (RouteHit hit : hits) {
                if (hit.routeFamily() == routeFamily
                        && hit.owner().equals(owner)
                        && hit.name().equals(name)
                        && hit.descriptor().equals(descriptor)) {
                    return true;
                }
            }
            return false;
        }

        public String summary() {
            return "source=" + sourceName
                    + " hits=" + hits.size()
                    + " A_ENTITY=" + count(RouteFamily.A_ENTITY)
                    + " B_REGION_LOCATION=" + count(RouteFamily.B_REGION_LOCATION)
                    + " C_REGION_BLOCK=" + count(RouteFamily.C_REGION_BLOCK)
                    + " D_PLAYER_UI=" + count(RouteFamily.D_PLAYER_UI)
                    + " G_WORLD_SCAN_SPLIT=" + count(RouteFamily.G_WORLD_SCAN_SPLIT)
                    + " S_GLOBAL=" + count(RouteFamily.S_GLOBAL);
        }
    }
}
