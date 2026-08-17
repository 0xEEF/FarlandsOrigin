package dev.xeaf.farlandsorigin;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import static java.lang.Math.max;

/**
 * Verbatim port of the Fabric mod's {@code RadiusCheck}. No changes needed:
 * this is plain vanilla-API code (implements {@link DensityFunction}), not
 * anything Fabric- or Paper-specific, so it works unmodified once the plugin
 * is on Paper's Mojang-mapped runtime classpath (paperweight-userdev dev
 * classpath / Paper's own runtime remapping).
 */
public final class RadiusCheck implements DensityFunction.SimpleFunction {
    public static final RadiusCheck INSTANCE = new RadiusCheck();
    public static final MapCodec<RadiusCheck> CODEC = MapCodec.unit(INSTANCE);
    public static final KeyDispatchDataCodec<RadiusCheck> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);

    private RadiusCheck() {}

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        return max(Math.abs(pos.blockX()), Math.abs(pos.blockZ())) >= FarlandsOriginState.SWITCH_DISTANCE ? 1.0 : 0.0;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC_HOLDER;
    }
}
