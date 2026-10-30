package dev.xeaf.farlandsorigin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Automatic equivalent of {@code tools/build_datapack.py} - same logic,
 * run from inside the server process at bootstrap time, so there is no
 * script to run and no server jar to hunt down.
 *
 * It works because the plugin is loaded BY the running server: its
 * classloader can read the server's own bundled vanilla data files as plain
 * classpath resources ({@code data/minecraft/worldgen/...}), the same way it
 * already resolves real NMS classes.
 *
 * What it writes into {@code <level-name>/datapacks/farlandsorigin/}:
 * <ul>
 *   <li>{@code density_function/old_overworld_noise.json} - vanilla's own
 *       {@code overworld/final_density} with the underground (cave) branch
 *       of its {@code sloped_cheese} range-choice swapped for the plain
 *       {@code base_3d_noise}. That is exactly the edit the original mod
 *       author made by hand for every Minecraft version; doing it here from
 *       the running server's data means it can't go stale when Mojang
 *       restructures {@code final_density} (26.3 did - the old hand-copied
 *       JSON stopped being valid: {@code argument1/argument2} became
 *       {@code left/right}, {@code y_clamped_gradient} became
 *       {@code gradient}, a {@code lerp} type appeared, ...). A bundled
 *       copy is only the fallback if the vanilla file can't be transformed.</li>
 *   <li>{@code final_density_<preset>} wrappers that switch between that and
 *       the preset's own vanilla {@code final_density} via {@link RadiusCheck}.</li>
 *   <li>Overrides of {@code noise_settings/<preset>.json} pointing at the
 *       wrapper (datapacks can only replace a registry file whole, not patch
 *       one field, so the rest is copied straight from the server's own copy).</li>
 * </ul>
 *
 * Runs during {@link FarlandsOriginBootstrap#bootstrap}, which executes
 * before the server scans {@code <world>/datapacks/}.
 *
 * If anything here fails or logs a warning, the manual
 * {@code tools/build_datapack.py} path in the README still works.
 */
final class FarlandsOriginDatapackInstaller {
    private static final String NAMESPACE = "farlandsorigin";
    private static final List<String> PRESETS = List.of("overworld", "amplified", "large_biomes");
    private static final String NOISE_SETTINGS_PREFIX = "data/minecraft/worldgen/noise_settings/";
    private static final String VANILLA_FINAL_DENSITY =
            "data/minecraft/worldgen/density_function/overworld/final_density.json";
    private static final String SLOPED_CHEESE = "minecraft:overworld/sloped_cheese";
    private static final String BASE_3D_NOISE = "minecraft:overworld/base_3d_noise";
    private static final String BUNDLED_OLD_NOISE_RESOURCE =
            "data/farlandsorigin/worldgen/density_function/old_overworld_noise.json";

    private FarlandsOriginDatapackInstaller() {}

    static void install(BootstrapContext context) {
        try {
            Path serverRoot = findServerRoot();
            String levelName = readLevelName(serverRoot);
            Path datapackDir = serverRoot.resolve(levelName).resolve("datapacks").resolve("farlandsorigin");
            Path namespaceDensityDir = datapackDir.resolve("data").resolve(NAMESPACE)
                    .resolve("worldgen").resolve("density_function");
            Path noiseSettingsDir = datapackDir.resolve("data").resolve("minecraft")
                    .resolve("worldgen").resolve("noise_settings");
            Files.createDirectories(namespaceDensityDir);
            Files.createDirectories(noiseSettingsDir);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            writeOldOverworldNoise(context, namespaceDensityDir.resolve("old_overworld_noise.json"), gson);

            boolean wroteAny = false;
            for (String preset : PRESETS) {
                String resourcePath = NOISE_SETTINGS_PREFIX + preset + ".json";
                JsonObject settings = readJsonResource(resourcePath);
                if (settings == null) {
                    context.getLogger().warn("Could not find " + resourcePath + " on the classpath - " +
                            "skipping " + preset + ". (Falls back fine if the other presets still work; " +
                            "see tools/build_datapack.py to install manually if this preset matters to you.)");
                    continue;
                }

                JsonObject router = settings.getAsJsonObject("noise_router");
                if (router == null || !router.has("final_density")) {
                    context.getLogger().warn(resourcePath + " has no noise_router.final_density - skipping " + preset);
                    continue;
                }

                JsonElement originalFinalDensity = router.get("final_density");
                String whenOutOfRange;
                if (originalFinalDensity.isJsonPrimitive()) {
                    whenOutOfRange = originalFinalDensity.getAsString();
                } else {
                    String extractedName = "vanilla_final_density_" + preset;
                    writeJson(namespaceDensityDir.resolve(extractedName + ".json"), originalFinalDensity, gson);
                    whenOutOfRange = NAMESPACE + ":" + extractedName;
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "minecraft:range_choice");
                JsonObject input = new JsonObject();
                input.addProperty("type", NAMESPACE + ":radius_check");
                wrapper.add("input", input);
                wrapper.addProperty("min_inclusive", 1);
                wrapper.addProperty("max_exclusive", 2);
                wrapper.addProperty("when_in_range", NAMESPACE + ":old_overworld_noise");
                wrapper.addProperty("when_out_of_range", whenOutOfRange);

                String wrapperName = "final_density_" + preset;
                writeJson(namespaceDensityDir.resolve(wrapperName + ".json"), wrapper, gson);

                router.addProperty("final_density", NAMESPACE + ":" + wrapperName);
                writeJson(noiseSettingsDir.resolve(preset + ".json"), settings, gson);
                context.getLogger().info("Wrote Far Lands override for " + preset + ".");
                wroteAny = true;
            }

            if (!wroteAny) {
                context.getLogger().error("Could not auto-generate any noise_settings overrides - Far Lands " +
                        "generation will NOT work as installed. This usually means Mojang restructured worldgen " +
                        "data paths in this Minecraft version. Try tools/build_datapack.py manually against your " +
                        "server jar for a more detailed error, and please report this version mismatch.");
                return;
            }

            writeJson(datapackDir.resolve("pack.mcmeta"), buildPackMeta(), gson);

            context.getLogger().info("FarlandsOrigin datapack auto-installed at " + datapackDir +
                    " (only affects chunks generated from now on - see README for existing-world caveats).");
        } catch (IOException e) {
            context.getLogger().error("Failed to auto-install the FarlandsOrigin datapack - Far Lands generation " +
                    "will NOT work until this is fixed. You can install it manually instead: see " +
                    "tools/build_datapack.py in the README.", e);
        }
    }

    /**
     * Builds {@code old_overworld_noise.json} from vanilla's own
     * {@code overworld/final_density}: find the range-choice on
     * {@code overworld/sloped_cheese} and replace its {@code when_out_of_range}
     * (vanilla's cave carving) with {@code overworld/base_3d_noise}.
     */
    private static void writeOldOverworldNoise(BootstrapContext context, Path destination, Gson gson) throws IOException {
        JsonObject vanilla = readJsonResource(VANILLA_FINAL_DENSITY);
        if (vanilla != null && replaceUndergroundBranch(vanilla)) {
            writeJson(destination, vanilla, gson);
            context.getLogger().info("Derived old_overworld_noise from this server's own overworld/final_density.");
            return;
        }

        context.getLogger().warn("Could not derive old_overworld_noise from " + VANILLA_FINAL_DENSITY +
                " (its structure no longer has a range_choice on " + SLOPED_CHEESE + "?) - falling back to the " +
                "copy bundled in this plugin, which matches Minecraft 26.3 and may not match a newer version.");
        copyBundledResource(BUNDLED_OLD_NOISE_RESOURCE, destination);
    }

    /** Depth-first search for the sloped_cheese range_choice; edits it in place. */
    private static boolean replaceUndergroundBranch(JsonElement node) {
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (isSlopedCheeseRangeChoice(object)) {
                object.addProperty("when_out_of_range", BASE_3D_NOISE);
                return true;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (replaceUndergroundBranch(entry.getValue())) return true;
            }
        } else if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            for (JsonElement element : array) {
                if (replaceUndergroundBranch(element)) return true;
            }
        }
        return false;
    }

    private static boolean isSlopedCheeseRangeChoice(JsonObject object) {
        return "minecraft:range_choice".equals(stringOrNull(object.get("type")))
                && SLOPED_CHEESE.equals(stringOrNull(object.get("input")))
                && object.has("when_out_of_range");
    }

    private static String stringOrNull(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Since pack format 82 a pack declares {@code min_format}/{@code max_format}
     * instead of {@code pack_format} (the game rejects the old field for such
     * versions). Asked of the game itself rather than parsed out of
     * version.json, whose shape changed too ({@code data} became
     * {@code data_major}/{@code data_minor}).
     */
    private static JsonObject buildPackMeta() throws IOException {
        int major = detectDataFormat();

        JsonObject pack = new JsonObject();
        pack.addProperty("description", "FarlandsOrigin - Far Lands worldgen overrides (auto-installed)");
        pack.addProperty("min_format", major);
        pack.addProperty("max_format", major);

        JsonObject packMeta = new JsonObject();
        packMeta.add("pack", pack);
        return packMeta;
    }

    private static int detectDataFormat() throws IOException {
        try {
            return SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
        } catch (RuntimeException e) {
            // Fall back to version.json's pack_version.data_major.
            JsonObject versionJson = readJsonResource("version.json");
            if (versionJson != null && versionJson.has("pack_version")) {
                JsonObject packVersion = versionJson.getAsJsonObject("pack_version");
                if (packVersion.has("data_major")) return packVersion.get("data_major").getAsInt();
            }
            throw new IOException("Could not determine this server's data pack format", e);
        }
    }

    /**
     * The server's working directory IS the server root - it's where
     * server.properties, world/, and plugins/ all live relative to. (Don't
     * derive it from {@code context.getDataDirectory()}: that is a RELATIVE
     * path, so walking up its parents overshoots and yields null.)
     */
    private static Path findServerRoot() {
        return Path.of("").toAbsolutePath();
    }

    private static String readLevelName(Path serverRoot) throws IOException {
        Path propsFile = serverRoot.resolve("server.properties");
        if (!Files.exists(propsFile)) return "world";
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile)) {
            props.load(in);
        }
        return props.getProperty("level-name", "world");
    }

    private static JsonObject readJsonResource(String path) {
        try (InputStream in = FarlandsOriginDatapackInstaller.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyBundledResource(String resourcePath, Path dest) throws IOException {
        try (InputStream in = FarlandsOriginDatapackInstaller.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Bundled resource " + resourcePath + " not found in this plugin's own jar - " +
                        "the build is broken (missing src/main/resources file).");
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeJson(Path path, JsonElement element, Gson gson) throws IOException {
        Files.writeString(path, gson.toJson(element), StandardCharsets.UTF_8);
    }
}
