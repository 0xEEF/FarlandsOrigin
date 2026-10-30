package dev.xeaf.farlandsorigin;

import net.minecraft.SharedConstants;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin Bukkit-facing half of the port. The actual Far Lands behaviour comes
 * from three places that all run before this class does:
 * {@link FarlandsOriginAgent} (Mojang's Far Lands switch + the BlendedNoise
 * hook), {@link FarlandsOriginBootstrap} (the {@code radius_check} density
 * function type and the auto-installed datapack), and the classes they wire
 * up. This class exists to reload config and to tell the admin, with real
 * checks rather than guesses, whether all of it is actually active.
 *
 * Safe on Folia: this class touches no per-world/per-region state and
 * schedules nothing, so there is nothing here that needs a region scheduler.
 */
public final class FarlandsOrigin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FarlandsOriginState.reload(getConfig().getInt("distance", FarlandsOriginState.BASE_DISTANCE));

        // SharedConstants.DEBUG_ENABLE_FARLANDS is the game's own flag, so
        // this reads back the real state of the precision-fix revert instead
        // of just assuming the agent's system property took effect.
        boolean agent = FarlandsOriginAgent.isAttached();
        boolean precisionFixReverted = SharedConstants.DEBUG_ENABLE_FARLANDS;
        boolean terrainNoiseHooked = Boolean.getBoolean(BlendedNoiseTransformer.PATCHED_PROPERTY);

        if (!agent) {
            getSLF4JLogger().error("The FarlandsOrigin java agent did not attach, so Far Lands terrain will " +
                    "NOT generate (you get vanilla terrain). Add -javaagent:plugins/{} to your server start " +
                    "flags, before -jar, and restart.", getFile().getName());
        } else if (!precisionFixReverted) {
            getSLF4JLogger().error("The agent attached but Minecraft's Far Lands flag " +
                    "(SharedConstants.DEBUG_ENABLE_FARLANDS) is off, so the noise precision fix is still " +
                    "active and Far Lands will not form. Something set MC_DEBUG_ENABLE_FARLANDS=false (or " +
                    "MC_DEBUG_ENABLED=false) before the game started.");
        } else if (!terrainNoiseHooked) {
            getSLF4JLogger().error("The agent attached but could not hook BlendedNoise (see the " +
                    "\"[FarlandsOrigin]\" lines earlier in the log). Far Lands will still form, but the " +
                    "configured distance won't be honoured exactly.");
        } else {
            getSLF4JLogger().info("FarlandsOrigin active: Far Lands generate from {} blocks out.",
                    FarlandsOriginState.SWITCH_DISTANCE);
        }
    }

    @Override
    public void onDisable() {
        // Nothing to tear down: registry + agent state is process-lifetime,
        // matching the original mod (it never supported unloading either).
    }
}
