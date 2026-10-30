package dev.xeaf.farlandsorigin;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Port of the Fabric mod's {@code RadiusCheck} to Minecraft 26.3's rewritten
 * density function system.
 *
 * Same behaviour as before: 1.0 once {@code max(|x|, |z|)} reaches the
 * configured switch distance, 0.0 otherwise. What changed is only how that
 * is expressed. 26.3 replaced the old {@code compute(FunctionContext)} model
 * (and {@code DensityFunction.SimpleFunction} / {@code KeyDispatchDataCodec},
 * which no longer exist) with a split between a *function* (pure description:
 * codec, range, which axes it depends on) and a *sampler* compiled from it
 * ({@link DensitySampler}), and moved everything to {@code float}.
 */
public final class RadiusCheck implements DensityFunction {
    public static final RadiusCheck INSTANCE = new RadiusCheck();
    public static final MapCodec<RadiusCheck> CODEC = MapCodec.unit(INSTANCE);

    private static final Interval RANGE = Interval.of(0.0F, 1.0F);
    private static final DensitySampler SAMPLER = new Sampler();

    private RadiusCheck() {}

    @Override
    public DensitySampler compileSampler(DensityFunction.CompileContext context) {
        return SAMPLER;
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this; // no child functions
    }

    @Override
    public Interval range() {
        return RANGE;
    }

    /** Depends on X and Z only, never on Y - lets the compiler slice it per column. */
    @Override
    public int domainAxes() {
        return AXIS_X | AXIS_Z;
    }

    @Override
    public MapCodec<RadiusCheck> codec() {
        return CODEC;
    }

    private static final class Sampler implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return Math.max(Math.abs(blockX), Math.abs(blockZ)) >= FarlandsOriginState.SWITCH_DISTANCE ? 1.0F : 0.0F;
        }
    }
}
