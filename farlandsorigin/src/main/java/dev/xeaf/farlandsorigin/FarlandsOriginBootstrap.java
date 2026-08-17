package dev.xeaf.farlandsorigin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

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
 * call in {@code FarlandsOrigin#onInitialize}, just reached through
 * reflection instead of a normal registration call, since
 * {@code BuiltInRegistries.DENSITY_FUNCTION_TYPE} is already frozen by the
 * time any Paper plugin code runs (see {@link NmsRegistryUnfreezer}).
 */
public final class FarlandsOriginBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        // Safe default before config.yml is actually read in onEnable() -
        // this also happens to be early enough to cover the (unlikely, but
        // possible on some server setups) case where worldgen starts before
        // plugins finish enabling. onEnable() overwrites this with the real
        // configured value once config.yml has loaded.
        FarlandsOriginState.reload(FarlandsOriginState.BASE_DISTANCE);

        NmsRegistryUnfreezer.forceRegister(
                BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Identifier.fromNamespaceAndPath("farlandsorigin", "radius_check"),
                RadiusCheck.CODEC
        );
        context.getLogger().info("Registered farlandsorigin:radius_check density function type.");

        // Must also happen here, before the datapack scan that (per this
        // project's own server logs) follows shortly after bootstrap()
        // returns - see FarlandsOriginDatapackInstaller for how/why.
        FarlandsOriginDatapackInstaller.install(context);
    }
}
