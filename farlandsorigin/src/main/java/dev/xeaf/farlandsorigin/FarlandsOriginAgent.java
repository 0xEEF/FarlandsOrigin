package dev.xeaf.farlandsorigin;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Entry point when the plugin jar is loaded with {@code -javaagent:}.
 *
 * Does two things, both of which have to happen before the server's own
 * classes load, which is why this can't be done from inside the plugin:
 *
 * 1. Turns on Mojang's built-in Far Lands switch. Minecraft 26.3 added
 *    {@code SharedConstants.DEBUG_ENABLE_FARLANDS}, which makes
 *    {@code GradientNoise.wrap} (the precision fix the old PerlinNoiseMixin
 *    reverted) return its input unchanged. It is a {@code static final}
 *    read from system properties exactly once, when SharedConstants
 *    initializes - so the properties must be set before that, i.e. here.
 *    {@code MC_DEBUG_ENABLED} is only the master gate inside the game's
 *    {@code debugFlag()} helper (nothing else reads it), so enabling it
 *    does not switch on any other debug behaviour.
 *
 * 2. Registers the transformer that hooks BlendedNoise (see
 *    {@link BlendedNoiseTransformer}).
 *
 * Required server start flag:
 * {@code -javaagent:plugins/farlandsorigin-<version>.jar}
 * placed BEFORE {@code -jar}.
 */
public final class FarlandsOriginAgent {
    private static final String BLENDED_NOISE = "net/minecraft/world/level/levelgen/synth/BlendedNoise";

    private FarlandsOriginAgent() {}

    /** Called by the JVM itself if the manifest's Premain-Class points here. */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    /** Called instead of premain if the agent is attached to an already-running JVM. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        // Respect an explicit choice by the admin (e.g. -DMC_DEBUG_ENABLE_FARLANDS=false).
        setIfAbsent("MC_DEBUG_ENABLED", "true");
        setIfAbsent("MC_DEBUG_ENABLE_FARLANDS", "true");

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                     ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!BLENDED_NOISE.equals(className)) return null;
                try {
                    return BlendedNoiseTransformer.transform(classfileBuffer, loader);
                } catch (Throwable t) {
                    // Fail loudly but don't take the server down mid-transform:
                    // returning null leaves the class unmodified, and
                    // FarlandsOrigin#onEnable reports the missing patch.
                    System.err.println("[FarlandsOrigin] Failed to transform " + className + ":");
                    t.printStackTrace();
                    return null;
                }
            }
        }, false);

        // The plugin is loaded by a different classloader and gets its own copy
        // of this class, so a static field here would never be seen by it - a
        // JVM-wide system property is.
        System.setProperty("farlandsorigin.agentAttached", "true");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    public static boolean isAttached() {
        return Boolean.getBoolean("farlandsorigin.agentAttached");
    }
}
