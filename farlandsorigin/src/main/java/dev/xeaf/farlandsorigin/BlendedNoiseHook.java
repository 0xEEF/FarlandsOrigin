package dev.xeaf.farlandsorigin;

import java.util.function.Function;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * NOT a class that is ever loaded or called at runtime. This is a bytecode
 * template: {@link BlendedNoiseTransformer} reads this class's compiled
 * bytes at agent-install time and copies {@code farlands$wrapSampler} onto
 * {@code BlendedNoise} itself, then inserts a call to it before each return
 * of {@code BlendedNoise.compileSampler(RandomSource)}.
 *
 * Why a template instead of a plain call into our own classes: the JVM
 * resolves a method call inside BlendedNoise using BlendedNoise's own
 * defining classloader, and on Folia/Paperclip that loader cannot see this
 * plugin's classes (a direct call failed with NoClassDefFoundError on 26.2).
 * So the spliced method must reference only what every loader can see:
 * JDK classes and the server's own classes. It reaches back into the plugin
 * through a plain {@link Function} object published in a JVM-wide system
 * property by {@link FarlandsOriginBootstrap} - the interface is a JDK type,
 * and the object's implementation lives in the plugin's classloader, which
 * is allowed to see NMS (that direction always worked).
 *
 * Hard rule for this file: do not reference any class from
 * {@code dev.xeaf.farlandsorigin} in the method below (the transformer
 * refuses to splice it if you do, so it fails at startup instead of with a
 * NoClassDefFoundError mid-worldgen). Use a string literal, not a shared
 * constant, for the property key.
 */
final class BlendedNoiseHook {
    private BlendedNoiseHook() {}

    @SuppressWarnings("unchecked")
    private static DensitySampler farlands$wrapSampler(DensitySampler sampler) {
        Object bridge = System.getProperties().get("farlandsorigin.samplerBridge");
        if (bridge instanceof Function) {
            return (DensitySampler) ((Function<Object, Object>) bridge).apply(sampler);
        }
        return sampler; // plugin not loaded (yet): behave like vanilla
    }
}
