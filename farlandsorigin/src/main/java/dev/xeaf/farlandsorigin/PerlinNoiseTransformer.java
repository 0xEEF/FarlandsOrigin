package dev.xeaf.farlandsorigin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Equivalent of the Fabric mod's PerlinNoiseMixin:
 * <pre>
 * {@literal @}WrapMethod(method = "wrap")
 * private static double revertPrecisionFix(double x, Operation&lt;Double&gt; original) {
 *     return x;
 * }
 * </pre>
 * i.e. entirely skip whatever {@code wrap(double)} normally does and just
 * return its input unchanged - this un-does the floating point precision fix
 * vanilla applies at extreme coordinates, which is what makes the classic
 * Beta 1.7-style Far Lands corruption reappear instead of the smoothed-out
 * modern terrain.
 *
 * This replaces the entire method body with {@code return arg0;}, so it
 * works regardless of {@code wrap}'s exact original implementation - there's
 * nothing version-specific about the *contents* we're removing, only the
 * method's name/descriptor need to still match.
 */
final class PerlinNoiseTransformer {
    private PerlinNoiseTransformer() {}

    static byte[] transform(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean patched = false;
        for (MethodNode method : classNode.methods) {
            // wrap(double): double - a static helper, so the double param is
            // local var slot 0 (double occupies two slots but that doesn't
            // change the *index* of the first local).
            if (!"wrap".equals(method.name) || !"(D)D".equals(method.desc)) continue;
            if ((method.access & Opcodes.ACC_STATIC) == 0) continue;

            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.DLOAD, 0));
            replacement.add(new InsnNode(Opcodes.DRETURN));

            method.instructions = replacement;
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            patched = true;
        }

        if (!patched) {
            System.err.println("[FarlandsOrigin] PerlinNoise: no static wrap(double):double method found " +
                    "to patch. The precision-fix revert is NOT active - the target method signature likely " +
                    "changed in this Minecraft version. See PerlinNoiseTransformer.");
            return null;
        }
        System.out.println("[FarlandsOrigin] PerlinNoise.wrap: patched to identity function.");

        ClassWriter writer = new TargetLoaderClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, targetLoader);
        classNode.accept(writer);
        return writer.toByteArray();
    }
}
