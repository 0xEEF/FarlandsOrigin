package dev.xeaf.farlandsorigin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/**
 * Automatic equivalent of {@code tools/build_datapack.py} - same logic,
 * ported to Java and run from inside the server process itself at bootstrap
 * time, instead of a separate script the admin has to run by hand against
 * an external server jar.
 *
 * This works (where an admin manually locating "the right jar" was fiddly -
 * Paperclip stubs, nested bundler jars, etc.) because the plugin is loaded
 * BY the running server, so its classloader can already read the server's
 * own bundled vanilla data files as plain classpath resources
 * ({@code data/minecraft/worldgen/noise_settings/*.json}) - the same way it
 * already resolves real NMS classes like
 * {@code net.minecraft.core.registries.BuiltInRegistries} directly, proven
 * to work in {@link FarlandsOriginBootstrap}. No bundler-jar drilling
 * needed here: whichever jar the JVM itself loaded classes from is already
 * exactly what's on this classloader's resource path.
 *
 * Runs during {@link FarlandsOriginBootstrap#bootstrap}, which is
 * confirmed (both by Paper's docs and by this project's own server logs -
 * "Registered farlandsorigin:radius_check..." consistently prints before
 * "Found new data pack ..." during startup) to run before the server scans
 * <world>/datapacks/ from disk. Writing our files there during bootstrap
 * means that scan picks them up moments later in the same startup.
 *
 * If anything here fails or logs a warning, the manual
 * {@code tools/build_datapack.py} path documented in the README still
 * works as a fallback - this doesn't replace it, just makes the common
 * case a no-manual-steps default.
 */
final class FarlandsOriginDatapackInstaller {
    private static final String NAMESPACE = "farlandsorigin";
    private static final List<String> PRESETS = List.of("overworld", "amplified", "large_biomes");
    private static final String NOISE_SETTINGS_PREFIX = "data/minecraft/worldgen/noise_settings/";
    private static final String BUNDLED_OLD_NOISE_RESOURCE =
            "data/farlandsorigin/worldgen/density_function/old_overworld_noise.json";

    private FarlandsOriginDatapackInstaller() {}

    static void install(BootstrapContext context) {
        try {
            Path serverRoot = findServerRoot(context);
            String levelName = readLevelName(serverRoot);
            Path datapackDir = serverRoot.resolve(levelName).resolve("datapacks").resolve("farlandsorigin");
            Path namespaceDensityDir = datapackDir.resolve("data").resolve(NAMESPACE)
                    .resolve("worldgen").resolve("density_function");
            Path noiseSettingsDir = datapackDir.resolve("data").resolve("minecraft")
                    .resolve("worldgen").resolve("noise_settings");
            Files.createDirectories(namespaceDensityDir);
            Files.createDirectories(noiseSettingsDir);

            copyBundledResource(BUNDLED_OLD_NOISE_RESOURCE, namespaceDensityDir.resolve("old_overworld_noise.json"));

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            int packFormat = detectPackFormat(gson);

            boolean wroteAny = false;
            for (String preset : PRESETS) {
                String resourcePath = NOISE_SETTINGS_PREFIX + preset + ".json";
                JsonObject settings = readJsonResource(resourcePath, gson);
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

            JsonObject packMeta = new JsonObject();
            JsonObject pack = new JsonObject();
            pack.addProperty("description", "FarlandsOrigin - Far Lands worldgen overrides (auto-installed)");
            pack.addProperty("pack_format", packFormat);
            packMeta.add("pack", pack);
            writeJson(datapackDir.resolve("pack.mcmeta"), packMeta, gson);

            context.getLogger().info("FarlandsOrigin datapack auto-installed at " + datapackDir +
                    " (only affects chunks generated from now on - see README for existing-world caveats).");
        } catch (IOException e) {
            context.getLogger().error("Failed to auto-install the FarlandsOrigin datapack - Far Lands generation " +
                    "will NOT work until this is fixed. You can install it manually instead: see " +
                    "tools/build_datapack.py in the README.", e);
        }
    }

    /**
     * {@code context.getDataDirectory()} returns a path RELATIVE to the
     * server's working directory (e.g. just {@code plugins/FarlandsOrigin},
     * not an absolute filesystem path) - walking up two parents from that
     * hits the root of the relative path and returns null, which is exactly
     * what happened here on a real server (NullPointerException in
     * readLevelName). The server's working directory itself IS the server
     * root - it's where server.properties, world/, and plugins/ all
     * live relative to, since that's how the JVM was launched (`java -jar
     * .../folia.jar` run from inside the server folder) - so use that
     * directly instead of walking up from the plugin's own data directory.
     */
    private static Path findServerRoot(BootstrapContext context) {
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

    private static int detectPackFormat(Gson gson) {
        JsonObject versionJson = readJsonResource("version.json", gson);
        if (versionJson != null) {
            JsonObject packVersion = versionJson.getAsJsonObject("pack_version");
            if (packVersion != null && packVersion.has("data")) {
                return packVersion.get("data").getAsInt();
            }
        }
        return 71; // matches tools/build_datapack.py's fallback
    }

    private static JsonObject readJsonResource(String path, Gson gson) {
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
