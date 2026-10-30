package dev.xeaf.farlandsorigin;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Port of the Fabric mod's {@code BlendedNoiseMixin}, which passed the
 * X/Z block coordinates going into {@code BlendedNoise.compute} through
 * {@code ClassicFarlands.adjustCoordinate}.
 *
 * 26.3 removed {@code compute} and the per-call {@code FunctionContext}
 * entirely; {@code BlendedNoise} now compiles into a {@link DensitySampler}
 * (a tree of plain sampler objects) whose scaling of block coordinates
 * happens deep inside generic, shared classes ({@code NoiseFunction.Sampler}
 * is used by every noise in the game, not just terrain). Hooking those
 * would shift biome/cave noise as well, which the original never did.
 *
 * The cleaner equivalent: wrap the one sampler {@code BlendedNoise} returns
 * from {@code compileSampler(RandomSource)} and adjust the coordinates on
 * the way in. That is exactly the boundary the old mixin sat on - "adjust
 * blockX/blockZ once, then run the whole BlendedNoise computation on the
 * adjusted values" - and needs no knowledge of its internals.
 *
 * This class lives in the plugin's classloader. {@link BlendedNoiseTransformer}
 * makes NMS call into it through a plain {@code java.util.function.Function}
 * stored in a system property, which avoids NMS having to resolve any of
 * our classes by name (it can't - see that class).
 */
final class BlendedNoiseSampler implements DensitySampler {
    private final DensitySampler delegate;

    BlendedNoiseSampler(DensitySampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
        return delegate.sampleValue(context,
                FarlandsOriginState.adjustCoordinate(blockX), blockY, FarlandsOriginState.adjustCoordinate(blockZ));
    }

    @Override
    public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
        int distance = FarlandsOriginState.COORDINATE_DISTANCE;

        // adjustCoordinate is the identity whenever |coordinate| <= distance.
        // Everywhere that's true for the whole volume, keep vanilla's fast
        // bulk sampling path; it's only within reach of the Far Lands that
        // coordinates actually get shifted, and a shift that kicks in
        // partway through a volume can't be expressed as a uniform
        // start/step, so sample point-by-point there instead.
        if (volume.minBlockX() >= -distance && volume.maxBlockX() <= distance
                && volume.minBlockZ() >= -distance && volume.maxBlockZ() <= distance) {
            delegate.sampleVolume(context, outputBuffer, volume);
            return;
        }

        DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
    }
}
