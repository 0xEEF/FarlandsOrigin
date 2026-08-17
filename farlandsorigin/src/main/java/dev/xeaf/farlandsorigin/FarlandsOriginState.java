package dev.xeaf.farlandsorigin;

/**
 * Direct port of the Fabric mod's {@code ClassicFarlandsConfig}. Fabric's
 * version used a Quilt Config (Kaleido) file; Paper plugins don't have that
 * dependency available, so this reads a plain Bukkit config.yml instead.
 *
 * This is deliberately static/global rather than an instance the plugin
 * holds, because the Mixin classes in {@code mixin/} run inside vanilla
 * worldgen code that has no reference to the plugin instance - matching how
 * the original mod's mixins read {@code FarlandsOriginConfig} statics too.
 */
public final class FarlandsOriginState {
    private FarlandsOriginState() {}

    /** Distance (in blocks) from 0,0 classic Minecraft's Far Lands began at. */
    public static final int BASE_DISTANCE = 12_550_824;

    /** Current configured switch-over distance, reloadable via config.yml. */
    public static volatile int COORDINATE_DISTANCE = BASE_DISTANCE;

    /**
     * The radius-check threshold. Kept as its own field, one less than
     * COORDINATE_DISTANCE, to exactly match the original mod's arithmetic
     * (see FarlandsOrigin#onInitialize in the source Fabric mod).
     */
    public static volatile int SWITCH_DISTANCE = BASE_DISTANCE;

    /**
     * Ported verbatim from {@code FarlandsOrigin.onInitialize}'s
     * SERVER_STARTING handler:
     * <pre>
     * COORDINATE_DISTANCE = 4 * (CONFIG.distance / 4) - 2;
     * SWITCH_DISTANCE     = 4 * (CONFIG.distance / 4) - 3;
     * </pre>
     * {@code configuredDistance} is config.yml's {@code distance} (default
     * BASE_DISTANCE), read by {@link FarlandsOrigin#onEnable()}.
     */
    public static void reload(int configuredDistance) {
        COORDINATE_DISTANCE = 4 * (configuredDistance / 4) - 2;
        SWITCH_DISTANCE = 4 * (configuredDistance / 4) - 3;

        // BlendedNoiseTransformer splices FarlandsOriginCoordinateHelper's
        // logic directly into BlendedNoise's own bytecode (see that class
        // for why), which reads its config through these JVM-wide system
        // properties rather than this class's fields - system properties
        // are visible across classloader boundaries, ordinary static fields
        // on a class living in this plugin's classloader are not
        // guaranteed to be (and on Folia/Paperclip, in practice, are not).
        System.setProperty("farlandsorigin.coordinateDistance", String.valueOf(COORDINATE_DISTANCE));
        System.setProperty("farlandsorigin.baseDistance", String.valueOf(BASE_DISTANCE));
    }
}
