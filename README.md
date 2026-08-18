# FarlandsOrigin - Paper/Folia port

Port of [ThePotatoArchivist's ClassicFarlands](https://github.com/ThePotatoArchivist/ClassicFarlands)
(MIT licensed - read `LICENSE` file) from Fabric to Paper/Folia,
targeting Minecraft 26.1 / 26.2.

## WARNING: Made with AI (cuz idk how to use Java)
## PLEASE SUPPORT [THE ORIGINAL DEVELOPER](https://github.com/ThePotatoArchivist)

I am a random dev, and you should really not trust random stuff you find on the Internet. Take a look at the source code which is only a few hundred lines long. (click on the GitHub "view source" link on the side). And if you prefer, compile the project yourself with `mvn clean package` (requires Java JDK 21+ and Maven installed).

**Read this whole file before deploying.** This is not a routine "recompile
against a different API" port. Fabric gives mods three things Paper's plugin
API does not: an init hook that runs before Mojang's registries freeze,
Mixin bytecode injection into internal worldgen classes, and generic
JSON-patching of datapack files at load time. Everything below exists to
replace those three things without either of them.

## What changed vs. the Fabric mod, and why

| Fabric mod piece | Paper port | Why |
|---|---|---|
| `Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ...)` in `onInitialize` | `FarlandsOriginBootstrap` (a `PluginBootstrap`) + `NmsRegistryUnfreezer`, reflection | Paper plugins load *after* `BuiltInRegistries` is already frozen; there's no Paper API for this registry at all (checked against the current `RegistryEvents` javadocs - it only covers enchantments/entity variants/dialogs/etc., not worldgen). |
| `RadiusCheck.java` | Ported verbatim | Plain vanilla `DensityFunction` code, nothing Fabric-specific. |
| `BlendedNoiseMixin` / `PerlinNoiseMixin` (SpongePowered Mixin) | `FarlandsOriginAgent` + `BlendedNoiseTransformer` / `PerlinNoiseTransformer` (raw ASM via a `java.lang.instrument` agent) | Modern SpongePowered Mixin (the version mixinextras/`@ModifyExpressionValue` needs) has no supported standalone mode outside a mod loader - it's built around an `IMixinService` tied to a specific launch platform. Rather than ship an unverifiable Mixin bootstrap sequence, I did the same two bytecode edits directly with ASM, which *is* fully standalone-capable. |
| Mixson (generic runtime JSON patching of `noise_settings`) | `FarlandsOriginDatapackInstaller` (auto, at bootstrap) / `tools/build_datapack.py` (manual fallback) + companion datapack | No equivalent exists outside Fabric's resource-reload events. Datapacks can only override a whole registry file, not patch one field - so instead of guessing at vanilla's `noise_settings.json` content for a Minecraft version I have no way to verify from here, this reads it from **the running server's own classpath** (or, manually, your server jar) and patches only `final_density`, the same way Mixson did, just at install time instead of every load. |
| Quilt Config (`FarlandsOriginConfig`) | `FarlandsOriginState` + `config.yml` | Kaleido/Quilt Config isn't available on Paper; swapped for a plain Bukkit config. Same `distance` option, same arithmetic. |

## Install

0. **Generate a Gradle wrapper first, if you don't have one.** Use Gradle
   9.1.0 or newer - Gradle only added support for *running on* JDK 25 in
   9.1.0 (older Gradle, including the whole 8.x line, fails with
   "Unsupported class file major version 69" on a JDK 25 system):
   ```
   cd farlandsorigin
   gradle wrapper --gradle-version 9.7
   ```
   From then on use `./gradlew` instead of `gradle` for every command below.
   **Build with JDK 25**, not just "any recent LTS" - Paper 26.2's dev bundle
   itself requires a 25+ runtime to compile against (`build.gradle`'s
   `options.release`/`sourceCompatibility` are already set to 25 to match;
   don't lower them without also lowering `minecraft_version`, or you'll hit
   "Dependency resolution is looking for a library compatible with JVM
   runtime version X, but paper-api ... is only compatible with ... 25").

1. **Set your exact target version** in `gradle.properties` -
   `minecraft_version=26.2` (or `26.1.2`) resolves to the *latest* published
   build for that version via `.build.+`; pin it to a specific build (e.g.
   `26.2.build.102`) once you've picked one, so a later rebuild doesn't
   silently jump to a newer Paper build underneath you.
2. **Build.** `./gradlew shadowJar` - needs network access, since it pulls
   the paperweight dev bundle for `minecraft_version` from Paper's Maven the
   first time. Output: `build/libs/farlandsorigin-<mod_version>.jar`.
   No separate "reobf" step - Paper 26.1+ ships fully unobfuscated
   (Mojang mappings all the way down), so this jar already *is* what the
   server runs; there's nothing to remap.
3. Drop that jar in `plugins/` on your Folia (or Paper) server.
4. **Add the java agent flag.** In your start script, *before* `-jar
   paper.jar` (works identically for Folia's jar):
   ```
   java -javaagent:plugins/farlandsorigin-<mod_version>.jar <your normal flags> -jar folia-<version>.jar nogui
   ```
   This is not optional and can't be set from inside the plugin - agents are
   a JVM-level concept, fixed at process launch, before any plugin code runs.
5. **Start the server.** As of this version, the companion datapack is
   generated and installed automatically - `FarlandsOriginBootstrap` reads
   your server's own vanilla `noise_settings.json` files straight off the
   classpath (the same way it already reads real NMS classes) and writes
   the datapack into `<level-name>/datapacks/farlandsorigin/`
   before that folder gets scanned, so there's no manual jar-hunting or
   script-running step needed for the common case. Check the log for:
   - `Wrote Far Lands override for overworld.` (and `amplified`,
     `large_biomes`)
   - `FarlandsOrigin datapack auto-installed at ...`

   If you don't see those - e.g. your server uses a non-default `plugins`
   folder location, so the installer's server-root guess is wrong - fall
   back to the manual path instead:
   ```
   python3 farlandsorigin/tools/build_datapack.py path/to/your/server.jar farlandsorigin
   cp datapack/farlandsorigin/data/farlandsorigin/worldgen/density_function/old_overworld_noise.json \
      farlandsorigin/data/farlandsorigin/worldgen/density_function/
   cp -r farlandsorigin <world-folder>/datapacks/
   ```
   (the script auto-detects Mojang's bundler-jar format too, so any server
   jar you point it at - launcher stub, raw Mojang jar, already-extracted
   one - works)

   Either way (auto or manual), this has to be in place *before* a world is
   first created/loaded - Far Lands worldgen registries are baked once at
   server boot and won't retroactively apply to chunks generated earlier,
   same limitation the original mod has.
6. Check the rest of the log for:
   - `Registered farlandsorigin:radius_check density function type.`
   - `[FarlandsOrigin] BlendedNoise.compute: patched 2 call site(s).`
   - `[FarlandsOrigin] PerlinNoise.wrap: patched to identity function.`
   - `FarlandsOrigin agent attached; Far Lands generation active at distance ...`

   If any of these are missing or show 0 patched sites, Far Lands generation
   is **not** active - see Troubleshooting.

## Configuration

`plugins/FarlandsOrigin/config.yml`:
```yaml
distance: 12550824
```
Same option and arithmetic as the Fabric mod's config.

## Folia notes

Nothing in this port does per-region scheduling, so there was nothing to
port to Folia's threaded-regions model specifically - worldgen density
functions run on whichever thread is generating a given region regardless of
Folia vs. Paper, and none of this plugin's code touches Bukkit's
single-threaded APIs (no entities, no block updates, no scheduling). The one
thing to double check on Folia specifically is that `PluginBootstrap` timing
relative to registry freezing is the same as on Paper (Folia is a Paper
fork, so it should be, but verify on your Folia build).

## Known limitations / what to verify before trusting this in production

This was written and reviewed without the ability to compile or run it
against a real Paper/Folia 26.1/26.2 server (no network or NMS jars
available in the environment that produced it). Specifically unverified:

- **`NmsRegistryUnfreezer.FROZEN_FIELD = "frozen"`** - the field name inside
  vanilla's `MappedRegistry` holding its frozen state. If this throws
  `NoSuchFieldException` at boot, inspect `MappedRegistry` in your version
  (e.g. via a decompiler on the Paper/NMS jar) and update the constant.
- **`BlendedNoiseTransformer`/`PerlinNoiseTransformer` target signatures** -
  built from the method/class names your own Fabric mod's mixins already
  target (`BlendedNoise.compute`, `PerlinNoise.wrap`, `FunctionContext.blockX/
  blockZ`), which are Mojang's official mapped names and should carry over
  identically to Paper's mojmap runtime. Both transformers log how many call
  sites they patched at startup specifically so a mismatch is loud, not
  silent - if you see the "found 0 ... to patch" warnings, decompile the
  actual class in your version and adjust.
- **`build_datapack.py` / `FarlandsOriginDatapackInstaller`'s reference-vs-inline
  detection for `final_density`** - based on the pattern already visible in
  your mod's own `old_overworld_noise.json` (which references sibling
  entries like `minecraft:overworld/sloped_cheese` by ID), so `final_density`
  is very likely also a plain string reference in modern vanilla data - but
  both handle the inline-object case too, just in case.
- **`FarlandsOriginDatapackInstaller`'s server-root detection** - uses the
  server process's working directory (`Path.of("").toAbsolutePath()`),
  which is where `server.properties`/`world/`/`plugins/` all live relative
  to for a normally-launched server (`java -jar ... folia.jar` run from
  inside the server folder). An earlier version of this tried to derive it
  from `context.getDataDirectory()` instead and threw a
  `NullPointerException` on a real server - that path turned out to be
  relative, not absolute, so walking up two parents overshot past the root.
  If you launch the server with a working directory that ISN'T the actual
  server folder (unusual, but possible with some wrapper scripts/process
  managers), this will still get it wrong - same symptom as before ("Wrote
  Far Lands override..." logs appearing but generation not actually
  changing) and same fix (fall back to the manual script, which sidesteps
  this by taking the world's datapacks path from you directly).
- **`pack_format` in `pack.mcmeta`** - auto-detected from `version.json` on
  the classpath (or in the server jar, for the manual script) if present;
  verify against your server's actual vanilla datapack if generation
  refuses to load with a pack-format warning/error.
- **Java agent self-sufficiency** - `Launch-Agent-Class`/`Premain-Class` are
  both set in the manifest so the same jar works whether attached via
  `-javaagent:` (recommended, used above) or (if you ever need it) a
  self-attach at runtime; only the `-javaagent:` path has been reasoned
  through carefully here.

None of these are "this definitely won't work" - they're the concrete,
falsifiable things to check on a real test server before relying on this,
listed instead of glossed over.

## Fixed during real-server testing (kept here as a record)

Confirmed against a live Folia 26.1.2 server:

- **ASM couldn't parse JDK 25 class files at all** (`Unsupported class file
  major version 69`) - ASM added Java 25 support in 9.8; anything earlier
  fails outright. Pinned to `asm_version=9.8`.
- **`ClassWriter`'s default `getCommonSuperClass` can't see NMS types** -
  it resolves via the agent's own classloader by default, which has no
  visibility into Folia's server classes. Fixed with
  `TargetLoaderClassWriter`, which resolves against the classloader that's
  actually loading the class being transformed.
- **A plain `invokestatic` from `BlendedNoise` to a separate helper class
  failed with `NoClassDefFoundError`** - the JVM resolves that symbolic
  reference using BlendedNoise's own defining classloader, and
  Paperclip/Folia's loader doesn't have this plugin's jar on its search
  path. Fixed by having `BlendedNoiseTransformer` copy the helper's
  compiled field+method bytecode directly onto `BlendedNoise` itself
  (see that class's Javadoc), so nothing needs cross-classloader
  resolution at runtime. Configuration crosses the same boundary via JVM
  system properties (`farlandsorigin.coordinateDistance` /
  `farlandsorigin.baseDistance`) rather than a shared class's static
  fields, for the same reason.
