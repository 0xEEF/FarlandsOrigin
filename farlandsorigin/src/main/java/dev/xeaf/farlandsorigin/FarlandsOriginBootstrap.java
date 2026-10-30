package dev.xeaf.farlandsorigin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Paper runs {@link PluginBootstrap#bootstrap} before datapack-driven
 * registries (which includes the {@code worldgen/noise_settings} and
 * {@code worldgen/density_function} entries our companion datapack ships)
 * are loaded and parsed. That parsing is what needs
 * {@code farlandsorigin:radius_check} to already exist as a known density
 * function type - if it isn't registered by then, the datapack's
 * {@code final_density} override will fail to parse and the world (or that
 * dimension's noise settings) will fail to load.
 *
 * This is the equivalent of the Fabric mod's
 * {@code Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ...)}
 * call in {@code ClassicFarlands#onInitialize}, just reached through
 * reflection instead of a normal registration call, since
 * {@code BuiltInRegistries.DENSITY_FUNCTION_TYPE} is already frozen by the
 * time any Paper plugin code runs (see {@link NmsRegistryUnfreezer}).
 */
public final class FarlandsOriginBootstrap implements PluginBootstrap {
    /** Class that only exists on 26.3+; 26.2 and earlier have net.minecraft.world.level.levelgen.DensityFunction. */
    private static final String MODERN_DENSITY_FUNCTION =
            "net.minecraft.world.level.levelgen.densityfunction.DensityFunction";

    @Override
    public void bootstrap(BootstrapContext context) {
        requireModernDensityFunctions();

        // Apply config.yml's distance right away. Spawn chunks are generated
        // before plugins enable, so waiting for onEnable() would be too late.
        Path dataDirectory = Path.of("").toAbsolutePath().resolve(context.getDataDirectory());
        FarlandsOriginState.reload(FarlandsOriginState.readConfiguredDistance(dataDirectory.resolve("config.yml")));

        NmsRegistryUnfreezer.forceRegister(
                BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Identifier.fromNamespaceAndPath("farlandsorigin", "radius_check"),
                RadiusCheck.CODEC
        );
        context.getLogger().info("Registered farlandsorigin:radius_check density function type.");

        // How the bytecode hook on BlendedNoise (see BlendedNoiseTransformer /
        // BlendedNoiseHook) reaches back into this plugin. It is deliberately a
        // plain java.util.function.Function in a JVM-wide system property:
        // BlendedNoise's classloader can't resolve any of our classes by name,
        // but it can call a JDK interface, and the object behind it is
        // allowed to see Minecraft's classes (the direction that always worked).
        Function<Object, Object> samplerBridge = sampler -> new BlendedNoiseSampler((DensitySampler) sampler);
        System.getProperties().put(BlendedNoiseTransformer.BRIDGE_PROPERTY, samplerBridge);

        // Must also happen here, before the datapack scan that follows shortly
        // after bootstrap() returns - see FarlandsOriginDatapackInstaller.
        FarlandsOriginDatapackInstaller.install(context);
    }

    private static void requireModernDensityFunctions() {
        try {
            Class.forName(MODERN_DENSITY_FUNCTION, false, FarlandsOriginBootstrap.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("This build of FarlandsOrigin needs Minecraft 26.3 or newer: 26.3 " +
                    "rewrote the density function system this plugin plugs into. On 26.2 or older, use a " +
                    "FarlandsOrigin 1.0.x build instead.", e);
        }
    }
}
