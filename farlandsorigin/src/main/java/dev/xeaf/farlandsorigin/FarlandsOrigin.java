package dev.xeaf.farlandsorigin;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin Bukkit-facing half of the port. The actual Far Lands behaviour is
 * driven by {@link FarlandsOriginState} (computed as early as possible, in
 * {@link FarlandsOriginBootstrap}) plus the Mixin classes in {@code mixin/},
 * which are woven into the server jar by {@link FarlandsOriginAgent} before
 * any world loads. This class exists mainly to give admins a normal plugin
 * to reload config through, and to log whether the agent actually attached.
 *
 * Safe on Folia: this class touches no per-world/per-region state and
 * schedules nothing, so there is nothing here that needs a region scheduler.
 */
public final class FarlandsOrigin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FarlandsOriginState.reload(getConfig().getInt("distance", FarlandsOriginState.BASE_DISTANCE));

        if (!FarlandsOriginAgent.isAttached()) {
            getSLF4JLogger().error(
                    "FarlandsOrigin' java agent did not attach. Far Lands coordinate wrapping " +
                    "(the BlendedNoise/PerlinNoise mixins) will NOT be active - you will get vanilla " +
                    "terrain instead of classic Far Lands. Add -javaagent:plugins/{} to your server " +
                    "start flags and restart. The custom density-function type and radius check will " +
                    "still work without it, but the coordinate-wrap trick that makes Far Lands look " +
                    "the way it did in Beta 1.7 requires the agent.",
                    getFile().getName()
            );
        } else {
            getSLF4JLogger().info("FarlandsOrigin agent attached; Far Lands generation active at distance {}.",
                    FarlandsOriginState.COORDINATE_DISTANCE);
        }
    }

    @Override
    public void onDisable() {
        // Nothing to tear down: registry + mixin state is process-lifetime,
        // matching the original mod (it never supported unloading either).
    }
}
