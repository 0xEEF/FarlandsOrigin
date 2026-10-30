package dev.xeaf.farlandsorigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the Fabric mod's {@code ClassicFarlandsConfig} + the arithmetic in
 * its {@code ClassicFarlands} class. Fabric used a Quilt Config (Kaleido)
 * file; Paper plugins don't have that, so this reads a plain config.yml.
 *
 * Deliberately static/global rather than an instance the plugin holds: the
 * density function / sampler code that reads it runs deep inside vanilla
 * worldgen and has no reference to the plugin instance. Both of those
 * ({@link RadiusCheck} and {@link BlendedNoiseSampler}) live in this
 * plugin's classloader, next to this class, so plain static fields are
 * shared correctly (the 26.2 build needed a system-property bridge here
 * because it injected bytecode into NMS that read this state; see
 * {@link BlendedNoiseTransformer} for why that is no longer necessary).
 */
public final class FarlandsOriginState {
    private FarlandsOriginState() {}

    /** Distance (in blocks) from 0,0 at which classic Minecraft's Far Lands began. */
    public static final int BASE_DISTANCE = 12_550_824;

    /** Where coordinates start being shifted for BlendedNoise (see {@link #adjustCoordinate}). */
    public static volatile int COORDINATE_DISTANCE = 4 * (BASE_DISTANCE / 4) - 2;

    /** Where the radius check flips from modern to classic terrain noise. */
    public static volatile int SWITCH_DISTANCE = 4 * (BASE_DISTANCE / 4) - 3;

    private static final Pattern DISTANCE_LINE = Pattern.compile("^\\s*distance\\s*:\\s*(\\d+)\\s*(#.*)?$");

    /**
     * Ported verbatim from {@code ClassicFarlands.onInitialize}'s
     * SERVER_STARTING handler:
     * <pre>
     * COORDINATE_DISTANCE = 4 * (CONFIG.distance / 4) - 2;
     * SWITCH_DISTANCE     = 4 * (CONFIG.distance / 4) - 3;
     * </pre>
     */
    public static void reload(int configuredDistance) {
        COORDINATE_DISTANCE = 4 * (configuredDistance / 4) - 2;
        SWITCH_DISTANCE = 4 * (configuredDistance / 4) - 3;
    }

    /**
     * Ported verbatim from {@code ClassicFarlands.adjustCoordinate}: shifts
     * block coordinates beyond {@link #COORDINATE_DISTANCE} so the terrain
     * noise reaches its Far Lands threshold exactly at the configured
     * distance (for the default distance this is a +/-2 block nudge).
     */
    public static int adjustCoordinate(int coordinate) {
        int coordinateDistance = COORDINATE_DISTANCE;
        if (coordinate > coordinateDistance)
            return coordinate + BASE_DISTANCE - coordinateDistance;
        if (coordinate < -coordinateDistance)
            return coordinate - BASE_DISTANCE + coordinateDistance;
        return coordinate;
    }

    /**
     * Reads {@code distance:} straight out of config.yml without any Bukkit
     * API, so it can be applied from {@link FarlandsOriginBootstrap} - which
     * runs before Bukkit's config machinery exists and before spawn chunks
     * are generated. Falls back to the default on any problem.
     */
    static int readConfiguredDistance(Path configFile) {
        try {
            if (Files.isRegularFile(configFile)) {
                for (String line : Files.readAllLines(configFile)) {
                    Matcher matcher = DISTANCE_LINE.matcher(line);
                    if (matcher.matches()) return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through to default
        }
        return BASE_DISTANCE;
    }
}
