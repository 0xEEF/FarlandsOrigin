package dev.xeaf.farlandsorigin;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Entry point when the plugin jar is loaded with {@code -javaagent:}. This is
 * what makes the Far Lands coordinate-wrap trick possible at all: Paper's
 * plugin API has no hook into {@code BlendedNoise}/{@code PerlinNoise}'s
 * internal sampling math, so the only way to change it (short of shipping a
 * hand-patched server jar) is bytecode instrumentation.
 *
 * Required server start flag:
 * {@code -javaagent:plugins/FarlandsOrigin.jar}
 *
 * Add it BEFORE {@code -jar paper.jar ...} on the command line / in your
 * launch script, since agents must be specified as their own JVM flag, not a
 * plugin option - Paper has no way to attach an agent for you after the JVM
 * has already started.
 */
public final class FarlandsOriginAgent {
    private static volatile boolean attached = false;

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
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                     ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                try {
                    if ("net/minecraft/world/level/levelgen/synth/BlendedNoise".equals(className)) {
                        return BlendedNoiseTransformer.transform(classfileBuffer, loader);
                    }
                    if ("net/minecraft/world/level/levelgen/synth/PerlinNoise".equals(className)) {
                        return PerlinNoiseTransformer.transform(classfileBuffer, loader);
                    }
                } catch (Throwable t) {
                    // Fail loudly but don't take the server down mid-transform;
                    // FarlandsOrigin#onEnable logs isAttached() state and
                    // whoever's watching the console will see this stack trace
                    // either way.
                    System.err.println("[FarlandsOrigin] Failed to transform " + className + ":");
                    t.printStackTrace();
                }
                return null; // no change
            }
        }, false);
        attached = true;

        // -javaagent: loads THIS class via one classloader, and Paper loads
        // the plugin jar (including a SEPARATE copy of this exact class)
        // via its own PluginClassLoader - two different Class objects, two
        // independent copies of the `attached` field. FarlandsOrigin's
        // onEnable() calling isAttached() on its own copy would always see
        // the untouched `false` default, even though the agent (running as
        // a totally different copy of this class) attached successfully -
        // same cross-classloader trap as FarlandsOriginCoordinateHelper,
        // same fix: a JVM-wide system property instead of a static field.
        System.setProperty("farlandsorigin.agentAttached", "true");
    }

    public static boolean isAttached() {
        return attached || Boolean.parseBoolean(System.getProperty("farlandsorigin.agentAttached", "false"));
    }
}
