package dev.xeaf.farlandsorigin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter#getCommonSuperClass} defaults to resolving types via
 * {@code Class.forName(name, false, this.getClass().getClassLoader())} -
 * i.e. using whatever classloader loaded ASM/our agent. That works fine for
 * a normal build tool, but breaks here: we're transforming NMS classes
 * (BlendedNoise, PerlinNoise) that reference OTHER NMS types
 * (PositionalRandomFactory, etc.) our agent's own classloader has never
 * heard of, since it's a completely different classloader from whatever
 * Paperclip/Folia uses to load the actual server jar.
 *
 * Fix: resolve against the classloader that's *actually loading the class
 * we're transforming* - the one handed to us in
 * {@link java.lang.instrument.ClassFileTransformer#transform}.
 */
final class TargetLoaderClassWriter extends ClassWriter {
    private final ClassLoader targetLoader;

    TargetLoaderClassWriter(ClassReader reader, int flags, ClassLoader targetLoader) {
        super(reader, flags);
        this.targetLoader = targetLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        ClassLoader loader = targetLoader != null ? targetLoader : getClass().getClassLoader();
        try {
            Class<?> class1 = Class.forName(type1.replace('/', '.'), false, loader);
            Class<?> class2 = Class.forName(type2.replace('/', '.'), false, loader);
            if (class1.isAssignableFrom(class2)) return type1;
            if (class2.isAssignableFrom(class1)) return type2;
            if (class1.isInterface() || class2.isInterface()) return "java/lang/Object";
            Class<?> c = class1;
            do {
                c = c.getSuperclass();
            } while (!c.isAssignableFrom(class2));
            return c.getName().replace('.', '/');
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not resolve common superclass of " + type1 + " and " + type2 +
                    " via target loader " + loader, e);
        }
    }
}
