package dev.xeaf.farlandsorigin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Equivalent of the Fabric mod's BlendedNoiseMixin. Where Mixin's
 * {@code @ModifyExpressionValue} inserts a call to a helper method right
 * after each matched {@code FunctionContext.blockX()}/{@code blockZ()}
 * invocation, this does the same thing manually with ASM - with one added
 * complication a mod loader doesn't have to deal with.
 *
 * A first version of this called out to a separate helper class
 * ({@code invokestatic FarlandsOriginCoordinateHelper.adjustCoordinate}).
 * That failed on real Folia/Paperclip with {@code NoClassDefFoundError}:
 * the JVM resolves that symbolic reference using BlendedNoise's OWN
 * defining classloader, which doesn't see this plugin's jar on its
 * classpath (Paperclip's loader isolation). So instead, this reads
 * {@link FarlandsOriginCoordinateHelper}'s *compiled bytecode* and copies
 * its fields and method directly onto BlendedNoise's own {@link ClassNode}
 * before redirecting the blockX/blockZ call sites to it - after this
 * transform, BlendedNoise is fully self-contained, with nothing left that
 * needs cross-classloader resolution.
 */
final class BlendedNoiseTransformer {
    private static final String FUNCTION_CONTEXT_OWNER =
            "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HELPER_RESOURCE = "FarlandsOriginCoordinateHelper.class";
    private static final String HELPER_METHOD_NAME = "farlandsorigin$adjustCoordinate";

    private BlendedNoiseTransformer() {}

    static byte[] transform(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode target = new ClassNode();
        reader.accept(target, 0);

        ClassNode helper = readHelperClassNode();
        String oldOwner = helper.name; // ".../FarlandsOriginCoordinateHelper"
        String newOwner = target.name; // ".../BlendedNoise"

        // Copy the three synthetic fields (initialized, coordinateDistance,
        // baseDistance) straight across - names are namespaced with
        // "farlandsorigin$" specifically so they can't collide with any
        // real BlendedNoise field.
        for (FieldNode field : helper.fields) {
            target.fields.add(new FieldNode(field.access, field.name, field.desc, field.signature, null));
        }

        // Copy the adjustCoordinate method, rewriting any GETSTATIC/PUTSTATIC
        // that referenced the helper class's own fields to point at the
        // fields we just copied onto BlendedNoise instead.
        MethodNode helperMethod = helper.methods.stream()
                .filter(m -> HELPER_METHOD_NAME.equals(m.name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        HELPER_METHOD_NAME + " not found in compiled FarlandsOriginCoordinateHelper - " +
                        "did the source method get renamed?"));

        MethodNode copiedMethod = new MethodNode(
                helperMethod.access, helperMethod.name, helperMethod.desc, helperMethod.signature, null);
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn : helperMethod.instructions) {
            if (insn instanceof LabelNode label) labelMap.put(label, new LabelNode());
        }
        InsnList copiedInsns = new InsnList();
        for (AbstractInsnNode insn : helperMethod.instructions) {
            AbstractInsnNode cloned = insn.clone(labelMap);
            if (cloned instanceof FieldInsnNode fieldInsn && oldOwner.equals(fieldInsn.owner)) {
                fieldInsn.owner = newOwner;
            }
            copiedInsns.add(cloned);
        }
        copiedMethod.instructions = copiedInsns;
        copiedMethod.maxStack = helperMethod.maxStack;
        copiedMethod.maxLocals = helperMethod.maxLocals;
        target.methods.add(copiedMethod);

        // Now do the actual call-site redirect: every blockX()/blockZ() in
        // compute() gets its result passed through the method we just added.
        int patched = 0;
        for (MethodNode method : target.methods) {
            if (!"compute".equals(method.name)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                boolean isCoordinateGetter =
                        FUNCTION_CONTEXT_OWNER.equals(call.owner)
                        && ("blockX".equals(call.name) || "blockZ".equals(call.name))
                        && "()I".equals(call.desc);
                if (!isCoordinateGetter) continue;

                MethodInsnNode wrapCall = new MethodInsnNode(
                        Opcodes.INVOKESTATIC, newOwner, HELPER_METHOD_NAME, "(I)I", false);
                method.instructions.insert(call, wrapCall);
                patched++;
            }
        }

        if (patched == 0) {
            System.err.println("[FarlandsOrigin] BlendedNoise.compute: found 0 blockX()/blockZ() call sites " +
                    "to patch. Coordinate wrapping is NOT active - the target method signature likely " +
                    "changed in this Minecraft version. See BlendedNoiseTransformer.");
            return null;
        }
        System.out.println("[FarlandsOrigin] BlendedNoise.compute: patched " + patched + " call site(s).");

        ClassWriter writer = new TargetLoaderClassWriter(
                reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, targetLoader);
        target.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode readHelperClassNode() {
        String path = "dev/xeaf/farlandsorigin/" + HELPER_RESOURCE;
        try (InputStream in = BlendedNoiseTransformer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Could not find " + path + " on the agent's own classpath - " +
                        "the plugin jar is missing its own compiled classes, which shouldn't happen.");
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
