package dev.xeaf.farlandsorigin;

/**
 * Ported logic from the Fabric mod's {@code FarlandsOrigin.adjustCoordinate}.
 *
 * This class is NOT called directly from injected bytecode - an earlier
 * version of this port did that (a plain {@code invokestatic} from
 * BlendedNoise's own bytecode to this class), and it failed at runtime with
 * {@code NoClassDefFoundError} on real Folia/Paperclip: the injected call
 * gets resolved using BlendedNoise's OWN defining classloader, not this
 * plugin's, and Paperclip's server-jar loader doesn't see this jar on its
 * classpath.
 *
 * Instead, {@link BlendedNoiseTransformer} reads this class's *compiled
 * bytecode* at agent-install time and copies its fields + method directly
 * into BlendedNoise's own class file (see that class for details), so at
 * runtime there is no cross-class/cross-loader reference left to resolve at
 * all. This source file exists to be compiled by Gradle into correct,
 * verifiable bytecode that BlendedNoiseTransformer then splices in - think
 * of it as a bytecode template, not a class that's ever loaded/run as-is
 * inside the plugin's own classloader.
 *
 * Deliberately self-contained (no reference to {@link FarlandsOriginState}
 * or any other of our classes, and no static field initializers, which
 * would need a {@code <clinit>} to also get copied over - a boolean
 * "have I read the properties yet" flag sidesteps that entirely, since its
 * default value of {@code false} is exactly what we want) for the same
 * reason: after splicing, it must not need to resolve anything beyond
 * {@code java.lang.*}, which every classloader can always see.
 * Configuration crosses the loader boundary via JVM-wide system properties
 * instead of a shared class's static fields - see
 * {@link FarlandsOriginState#reload}, which sets the same properties this
 * reads.
 */
final class FarlandsOriginCoordinateHelper {
    private FarlandsOriginCoordinateHelper() {}

    // Copied onto BlendedNoise itself by BlendedNoiseTransformer, which
    // rewrites the field owner in the copied method's bytecode to match -
    // see there. Default values (false / 0 / 0) are exactly what we want,
    // so no <clinit> needs to be copied along with these.
    private static boolean farlandsorigin$initialized;
    private static int farlandsorigin$coordinateDistance;
    private static int farlandsorigin$baseDistance;

    /**
     * Lazily reads and caches {@code farlandsorigin.coordinateDistance} /
     * {@code farlandsorigin.baseDistance} on first call. This resolves
     * correctly even though BlendedNoise's class initializer runs very
     * early (during {@code BuiltInRegistries.bootStrap()}, before our
     * plugin's config has loaded): actual worldgen calls (and therefore the
     * first real invocation of this method) don't happen until well after
     * {@code FarlandsOrigin#onEnable} has already set the properties
     * to their final, config.yml-derived values.
     */
    private static int farlandsorigin$adjustCoordinate(int coordinate) {
        if (!farlandsorigin$initialized) {
            farlandsorigin$coordinateDistance = Integer.parseInt(
                    System.getProperty("farlandsorigin.coordinateDistance", "12550822"));
            farlandsorigin$baseDistance = Integer.parseInt(
                    System.getProperty("farlandsorigin.baseDistance", "12550824"));
            farlandsorigin$initialized = true;
        }
        int coordinateDistance = farlandsorigin$coordinateDistance;
        if (coordinate > coordinateDistance)
            return coordinate + farlandsorigin$baseDistance - coordinateDistance;
        if (coordinate < -coordinateDistance)
            return coordinate - farlandsorigin$baseDistance + coordinateDistance;
        return coordinate;
    }
}


