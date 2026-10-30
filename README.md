# FarlandsOrigin - Paper/Folia port

Port of [ThePotatoArchivist's ClassicFarlands](https://github.com/ThePotatoArchivist/ClassicFarlands)
(MIT licensed - `LICENSE` carried over unmodified) from Fabric to Paper/Folia.

**This build targets Minecraft 26.3 and newer.** 26.3 rewrote Minecraft's
density function system, so it does not load on 26.2 or older - on those, keep
using a 1.0.x build (the bootstrapper detects this and stops with a clear
message instead of a `NoClassDefFoundError`).

**Read this file before deploying.** This is not a "recompile against another
API" port. Fabric gives mods things Paper's plugin API does not: bytecode
injection into worldgen classes, an init hook before registries are built, and
generic JSON-patching of datapack files at load time. Everything below
replaces those.

## What changed vs. the Fabric mod, and why

| Fabric mod piece | This port | Why |
|---|---|---|
| `Registry.register(DENSITY_FUNCTION_TYPE, ...)` in `onInitialize` | `FarlandsOriginBootstrap` (a Paper `PluginBootstrap`) | Paper has no API for this registry (`RegistryEvents` only covers enchantments/variants/dialogs/etc.). It works because Paper runs bootstrappers at the top of `Bootstrap.bootStrap()`, *before* `BuiltInRegistries.bootStrap()` fills and freezes the registries. `NmsRegistryUnfreezer` is only a safety net. |
| `RadiusCheck` | `RadiusCheck` - **rewritten for 26.3** | 26.3 replaced `compute(FunctionContext)`, `DensityFunction.SimpleFunction` and `KeyDispatchDataCodec` with function/sampler pairs (`compileSampler`, `DensitySampler`) and floats. Same behaviour, new shape. |
| `BlendedNoiseMixin` (adjust `blockX/blockZ` inside `BlendedNoise.compute`) | `BlendedNoiseTransformer` + `BlendedNoiseSampler` + `BlendedNoiseHook` | `compute` and per-call contexts no longer exist. `BlendedNoise` now hands out one compiled sampler from `compileSampler(RandomSource)`; the agent wraps *that* in a sampler that adjusts X/Z on the way in - the same boundary the mixin sat on. (Hooking the generic `NoiseFunction.Sampler` instead would also shift biome/cave noise, which the original never did.) |
| `PerlinNoiseMixin` (`wrap` -> identity, undoes the precision fix) | Mojang's own `SharedConstants.DEBUG_ENABLE_FARLANDS` | 26.3 ships a switch for exactly this. It's a `static final` read from system properties once, so the agent sets `MC_DEBUG_ENABLED`/`MC_DEBUG_ENABLE_FARLANDS` before the game starts. `MC_DEBUG_ENABLED` is only a gate inside the game's `debugFlag()` helper, so nothing else is switched on. No bytecode needed. |
| Mixson (generic runtime JSON patching) | `FarlandsOriginDatapackInstaller` (automatic) / `tools/build_datapack.py` (manual) | Datapacks can only replace a registry file whole. The installer reads the server's own vanilla JSON off the classpath and writes the datapack into `<level-name>/datapacks/farlandsorigin/` before the server scans that folder. |
| the hand-maintained `old_overworld_noise.json` | **derived at startup from vanilla's `overworld/final_density`** | The original author re-made this by hand every Minecraft version: swap the cave branch of the `sloped_cheese` range-choice for `base_3d_noise`. 26.3 changed the JSON format (`argument1/2` -> `left/right`, `y_clamped_gradient` -> `gradient`, new `lerp`, beardifier inlined), which broke the old copy. The installer now applies that same edit to the running server's own data, so it can't go stale. A 26.3 copy is bundled only as a fallback. |
| Quilt Config | `config.yml` | Not available on Paper. Same `distance` option and arithmetic. |

## Install

1. **Build** (needs network the first time, for the Paper dev bundle):
   ```
   cd farlandsorigin
   ./gradlew clean build
   ```
   Requirements: **JDK 25** (Paper's 26.x dev bundles require a 25+ runtime)
   and the bundled **Gradle 9.7** wrapper. Output:
   `build/libs/farlandsorigin-<version>.jar`. There is no reobf step - Paper
   26.1+ runs on Mojang's names directly.
2. Put the jar in `plugins/`.
3. **Add the java agent flag** to your start command, *before* `-jar`:
   ```
   java -javaagent:plugins/farlandsorigin-<version>.jar -Xmx2G -jar folia-26.3-<build>.jar nogui
   ```
   This cannot be done from inside the plugin: agents are fixed at JVM
   launch, before any server class loads. Without it you get vanilla terrain.
4. **Start the server.** A healthy start logs (roughly in this order):
   ```
   [FarlandsOrigin] Registered farlandsorigin:radius_check density function type.
   [FarlandsOrigin] Derived old_overworld_noise from this server's own overworld/final_density.
   [FarlandsOrigin] Wrote Far Lands override for overworld.   (and amplified, large_biomes)
   [FarlandsOrigin] FarlandsOrigin datapack auto-installed at ...
   [FarlandsOrigin] BlendedNoise.compileSampler: hooked 1 return site(s).
   ...
   [FarlandsOrigin] FarlandsOrigin active: Far Lands generate from 12550821 blocks out.
   ```
   The last line is a *real check*: it is only printed if the agent attached,
   Minecraft's own `DEBUG_ENABLE_FARLANDS` flag is actually on, and the
   BlendedNoise hook was applied. Otherwise you get a specific error saying
   which of the three is missing.
5. **Seeing it:** `/tp @s 12550830 100 0` in creative/spectator, in an area
   that has never been generated.

**Existing worlds:** the datapack and agent only affect chunks generated
*after* they're in place - chunks already on disk keep whatever they were
generated as. Test in a fresh world or unexplored coordinates.

### Manual datapack install (fallback)

If the automatic install logs a warning, or your server isn't launched from
its own folder (the installer treats the working directory as the server root):

```
python3 farlandsorigin/tools/build_datapack.py <any server jar or the server-26.3 zip> farlandsorigin
cp -r farlandsorigin <world-folder>/datapacks/
```
The script drills through Mojang's bundler-jar layers by itself. A ready-made
copy of the resulting datapack for 26.3 is in `datapack/farlandsorigin/` if
you'd rather not run Python.

## Configuration

`plugins/FarlandsOrigin/config.yml`:
```yaml
distance: 12550824
```
Applied at bootstrap (before spawn chunks generate), so changing it needs a
restart. The effective switch point is `4 * (distance / 4) - 3` blocks,
exactly as in the original (12550821 for the default).

## Troubleshooting

| Log line | Meaning |
|---|---|
| `This build of FarlandsOrigin needs Minecraft 26.3 or newer` | Running on 26.2 or older. Use a 1.0.x build. |
| `The FarlandsOrigin java agent did not attach` | Missing `-javaagent:` flag. |
| `Minecraft's Far Lands flag ... is off` | Something set `MC_DEBUG_ENABLE_FARLANDS=false` or `MC_DEBUG_ENABLED=false`, or `SharedConstants` was initialised before the agent ran. |
| `could not hook BlendedNoise` / `BlendedNoise.compileSampler ... not found` | Minecraft changed that method again. Far Lands still form, but the configured distance isn't honoured exactly. |
| `Could not derive old_overworld_noise ... falling back` | Vanilla's `final_density` no longer has a `sloped_cheese` range-choice. The bundled 26.3 copy is used, and may be wrong for newer versions. |
| `Could not auto-generate any noise_settings overrides` | Vanilla worldgen data paths moved. Use the manual script for a precise error. |

## Folia

Nothing here schedules anything or touches Bukkit's thread-bound APIs;
worldgen density functions run on whichever thread generates a region, on
Folia and Paper alike. `RadiusCheck` and `BlendedNoiseSampler` are stateless
apart from two `volatile` ints written once at startup.

## What was and wasn't verified

Verified in the environment that produced this version:
- All sources compile against the real Folia API signatures and ASM 9.9.1
  (Minecraft's own classes stubbed from the real 26.3 source).
- The `BlendedNoise` hook: run through a real JVM with `-Xverify:all`, under a
  classloader layout that reproduces the 26.2 crash (server loader that cannot
  see plugin classes). Both return paths wrapped, unrelated methods untouched,
  and X/Z adjustment matches `ClassicFarlands.adjustCoordinate` exactly,
  including the volume fast path / point-by-point fallback and a custom distance.
- The agent with a real `-javaagent:`: flags set, an admin's explicit
  `=false` respected, hook applied at class-load, failure degrades to vanilla.
- The datapack generation, end-to-end, against the real 26.3 data files
  (`tools/build_datapack.py`, same algorithm as the Java installer).
- Every NMS signature the plugin touches was checked against the 26.3 source.

**Not** verified here (needs a real 26.3 server): the Java installer itself
and the registry registration were type-checked but not executed against the
real game, and the datapack was not loaded by a real 26.3 server. Whether the
resulting terrain *looks* like authentic Far Lands is a visual judgement only
a real run can give. 26.3's noise code is float-based and was rewritten, so
the look may differ in detail from older versions.

One design note: the BlendedNoise hook reaches the plugin through a
`java.util.function.Function` stored in a JVM-wide system property
(`farlandsorigin.samplerBridge`). That is a non-`String` value in
`System.getProperties()`, which is fine for normal lookups but would break
code that blindly casts every property value to `String` (e.g.
`Properties.store`). None is known in Paper/Folia.

## Record of problems found on real servers

- **26.2: ASM couldn't parse JDK 25 class files** (`Unsupported class file
  major version 69`) - needs ASM 9.8+. Now pinned to 9.9.1 (what Folia ships).
- **26.2: `ClassWriter.getCommonSuperClass` couldn't see Minecraft types** - it
  resolves through the agent's classloader by default. `TargetLoaderClassWriter`
  resolves against the classloader that is loading the target class.
- **26.2: a plain `invokestatic` from `BlendedNoise` into plugin code failed
  with `NoClassDefFoundError`** - the JVM resolves it with BlendedNoise's own
  loader, which can't see the plugin. The injected method must reference only
  JDK and Minecraft classes (`BlendedNoiseTransformer` enforces this at agent
  time), and reaches the plugin through a JDK-typed bridge.
- **26.2: `context.getDataDirectory()` is relative**, so deriving the server
  root from its parents returned `null`. The working directory is used instead.
- **26.3: the whole density function system was rewritten** (see the table),
  and the datapack JSON format changed with it.
