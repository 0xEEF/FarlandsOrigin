package dev.xeaf.farlandsorigin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Replacement for the Fabric mod's BlendedNoiseMixin on Minecraft 26.3.
 *
 * Minecraft 26.2 and earlier: {@code BlendedNoise.compute(FunctionContext)}
 * read {@code blockX()/blockZ()}, and the mixin passed each result through
 * {@code adjustCoordinate}. 26.3 deleted that whole model (see
 * {@link BlendedNoiseSampler}), so the equivalent hook point is now the
 * single place {@code BlendedNoise} hands out its compiled sampler:
 * {@code compileSampler(RandomSource)}. Before each {@code areturn} in that
 * method this inserts a call that wraps the sampler in a
 * {@link BlendedNoiseSampler}.
 *
 * The wrapping call is a method copied onto BlendedNoise from the compiled
 * template {@link BlendedNoiseHook} (not a call into this plugin directly -
 * see that class for the classloader reason).
 *
 * The other half of the old PerlinNoiseMixin ({@code wrap} -> identity, which
 * is what un-fixes the precision at huge coordinates and creates the Far
 * Lands) no longer needs bytecode at all: 26.3 ships its own switch for it,
 * {@code SharedConstants.DEBUG_ENABLE_FARLANDS}, which
 * {@link FarlandsOriginAgent} turns on.
 */
final class BlendedNoiseTransformer {
    static final String BRIDGE_PROPERTY = "farlandsorigin.samplerBridge";
    static final String PATCHED_PROPERTY = "farlandsorigin.blendedNoisePatched";

    private static final String OWN_PACKAGE_PREFIX = "dev/xeaf/farlandsorigin/";
    private static final String SAMPLER_DESC = "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySampler;";
    private static final String TARGET_METHOD = "compileSampler";
    private static final String TARGET_DESC = "(Lnet/minecraft/util/RandomSource;)" + SAMPLER_DESC;
    private static final String WRAP_NAME = "farlands$wrapSampler";
    private static final String WRAP_DESC = "(" + SAMPLER_DESC + ")" + SAMPLER_DESC;
    private static final String HELPER_RESOURCE = OWN_PACKAGE_PREFIX + "BlendedNoiseHook.class";

    private BlendedNoiseTransformer() {}

    static byte[] transform(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode target = new ClassNode();
        reader.accept(target, 0);

        MethodNode compileSampler = null;
        for (MethodNode method : target.methods) {
            if (TARGET_METHOD.equals(method.name) && TARGET_DESC.equals(method.desc)) {
                compileSampler = method;
                break;
            }
        }
        if (compileSampler == null) {
            System.err.println("[FarlandsOrigin] BlendedNoise." + TARGET_METHOD + TARGET_DESC + " not found. " +
                    "Terrain coordinate adjustment is NOT active - Minecraft probably changed this method. " +
                    "See BlendedNoiseTransformer.");
            return null;
        }

        int patched = 0;
        for (AbstractInsnNode insn : compileSampler.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            compileSampler.instructions.insertBefore(insn,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, target.name, WRAP_NAME, WRAP_DESC, false));
            patched++;
        }
        if (patched == 0) {
            System.err.println("[FarlandsOrigin] BlendedNoise." + TARGET_METHOD + " has no return to hook. " +
                    "Terrain coordinate adjustment is NOT active. See BlendedNoiseTransformer.");
            return null;
        }

        target.methods.add(copyHelperMethod());

        System.out.println("[FarlandsOrigin] BlendedNoise." + TARGET_METHOD + ": hooked " + patched + " return site(s).");
        System.setProperty(PATCHED_PROPERTY, "true");

        ClassWriter writer = new TargetLoaderClassWriter(
                reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, targetLoader);
        target.accept(writer);
        return writer.toByteArray();
    }

    /** Copies {@link BlendedNoiseHook}'s compiled wrap method into a fresh MethodNode. */
    private static MethodNode copyHelperMethod() {
        ClassNode helper = new ClassNode();
        try (InputStream in = BlendedNoiseTransformer.class.getClassLoader().getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Could not find " + HELPER_RESOURCE + " on the agent's own " +
                        "classpath - the plugin jar is missing its own compiled classes.");
            }
            new ClassReader(in.readAllBytes()).accept(helper, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + HELPER_RESOURCE, e);
        }

        MethodNode source = helper.methods.stream()
                .filter(m -> WRAP_NAME.equals(m.name) && WRAP_DESC.equals(m.desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        WRAP_NAME + WRAP_DESC + " not found in compiled BlendedNoiseHook - was it renamed?"));

        MethodNode copy = new MethodNode(source.access, source.name, source.desc, source.signature, null);

        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : source.instructions) {
            if (insn instanceof LabelNode label) labels.put(label, new LabelNode());
        }
        InsnList instructions = new InsnList();
        for (AbstractInsnNode insn : source.instructions) {
            AbstractInsnNode cloned = insn.clone(labels);
            requireNoOwnClassReference(cloned);
            instructions.add(cloned);
        }
        copy.instructions = instructions;
        return copy;
    }

    /**
     * The spliced method runs inside BlendedNoise, whose classloader cannot
     * see this plugin. Any reference to one of our own classes would blow up
     * with NoClassDefFoundError in the middle of world generation - catch it
     * here, at agent time, where it fails loudly and early instead.
     */
    private static void requireNoOwnClassReference(AbstractInsnNode insn) {
        String reference = null;
        if (insn instanceof MethodInsnNode m) reference = m.owner;
        else if (insn instanceof FieldInsnNode f) reference = f.owner;
        else if (insn instanceof TypeInsnNode t) reference = t.desc;
        else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) reference = type.getInternalName();

        if (reference != null && reference.startsWith(OWN_PACKAGE_PREFIX)) {
            throw new IllegalStateException("BlendedNoiseHook." + WRAP_NAME + " references " + reference +
                    ", which BlendedNoise's classloader cannot resolve. The hook may only use JDK and " +
                    "Minecraft classes.");
        }
    }
}
