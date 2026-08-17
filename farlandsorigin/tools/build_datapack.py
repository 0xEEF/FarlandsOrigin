#!/usr/bin/env python3
"""
Builds the FarlandsOrigin companion datapack directly from your own Paper/
Folia server, instead of shipping a hand-copied (and potentially stale/wrong)
vanilla noise_settings.json in this repo.

Why this exists: to make Far Lands generation happen, `final_density` inside
`worldgen/noise_settings/{overworld,amplified,large_biomes}.json` needs to be
wrapped in a `range_choice` that switches to the old-style noise once you're
far enough from 0,0. Datapacks can only override a registry file *entirely*
(no partial JSON merge), so we need the exact, currently-correct content of
those files for YOUR server's exact Minecraft version - which is inside your
server's own jars already, so this script reads it from there directly
rather than us guessing at it.

Since Minecraft 1.18, Mojang's server download is a "bundler" jar - a thin
launcher whose real content (including all of vanilla's data/ files) is
packed inside a NESTED jar under META-INF/versions/, not at the top level.
Paperclip-based launchers (Paper/Folia) extract and patch that nested jar
into a `cache/` (or similar) folder on first run and run *that*. This script
handles both cases automatically: point it at whichever jar you've got -
the launcher stub, the raw Mojang bundler jar, or an already-extracted
server jar - and it'll drill down through any bundler layers to find the
actual game data.

Usage:
    python3 build_datapack.py /path/to/some-server.jar [output_dir]

Then copy the resulting folder into <your-world>/datapacks/ and restart.
"""
import io
import json
import sys
import zipfile
from pathlib import Path

NAMESPACE = "farlandsorigin"
PRESETS = ["overworld", "amplified", "large_biomes"]
NOISE_SETTINGS_PREFIX = "data/minecraft/worldgen/noise_settings/"


def read_json_from_zip(zf: zipfile.ZipFile, path: str):
    with zf.open(path) as f:
        return json.load(f)


def find_data_zip(jar_path: Path) -> zipfile.ZipFile:
    """
    Returns an open ZipFile that actually contains data/minecraft/... at its
    root, drilling through Mojang's bundler format (nested jars under
    META-INF/versions/) if needed. Tries breadth-first, a few levels deep,
    so it also copes with a bundler-jar-inside-a-bundler-jar in principle.
    """
    to_try = [("(top level) " + str(jar_path), jar_path.read_bytes())]
    seen_labels = set()

    while to_try:
        label, data = to_try.pop(0)
        if label in seen_labels:
            continue
        seen_labels.add(label)

        zf = zipfile.ZipFile(io.BytesIO(data))
        names = zf.namelist()

        if any(n.startswith(NOISE_SETTINGS_PREFIX) for n in names):
            print(f"Found vanilla worldgen data in: {label}")
            return zf

        # Bundler format: nested version jar(s) under META-INF/versions/.
        nested = [n for n in names if n.startswith("META-INF/versions/") and n.endswith(".jar")]
        for n in nested:
            print(f"'{label}' looks like a bundler jar - checking nested {n} ...")
            to_try.append((f"{label} -> {n}", zf.read(n)))

    raise SystemExit(
        f"Could not find data/minecraft/worldgen/noise_settings/ anywhere in {jar_path} "
        f"or any nested jar inside it. This isn't a server jar with vanilla data in it - "
        f"double check the path. If you're pointing at a Paperclip launcher stub "
        f"(the small jar you actually run), look for the larger, patched server jar it "
        f"extracts on first run (often in a 'cache' or 'versions' folder next to it) and "
        f"point this script at that instead."
    )


def build(jar_path: Path, out_dir: Path):
    zf = find_data_zip(jar_path)
    names = set(zf.namelist())

    pack_format = None
    if "version.json" in names:
        try:
            version_data = read_json_from_zip(zf, "version.json")
            pack_format = version_data.get("pack_version", {}).get("data")
        except Exception:
            pass
    if pack_format is None:
        print("WARNING: couldn't auto-detect data pack_format from version.json; "
              "defaulting to 71. Check your server's actual vanilla datapack "
              "pack.mcmeta (unzip the jar and look under data/) and fix "
              "pack.mcmeta by hand if generation refuses to load.", file=sys.stderr)
        pack_format = 71

    density_dir = out_dir / "data" / NAMESPACE / "worldgen" / "density_function"
    noise_settings_dir = out_dir / "data" / "minecraft" / "worldgen" / "noise_settings"
    density_dir.mkdir(parents=True, exist_ok=True)
    noise_settings_dir.mkdir(parents=True, exist_ok=True)

    wrote_any = False
    for preset in PRESETS:
        src_path = f"{NOISE_SETTINGS_PREFIX}{preset}.json"
        if src_path not in names:
            print(f"WARNING: {src_path} not found, skipping {preset}. "
                  f"(This preset may not exist in your version, or the path changed.)",
                  file=sys.stderr)
            continue

        settings = read_json_from_zip(zf, src_path)
        router = settings.get("noise_router")
        if router is None or "final_density" not in router:
            print(f"WARNING: {src_path} has no noise_router.final_density, "
                  f"skipping {preset}.", file=sys.stderr)
            continue

        original_final_density = router["final_density"]

        # If final_density is already a bare reference id (a string), we
        # can point straight at it. If it's inlined as an object, pull it
        # out into its own file so we're not duplicating a huge blob
        # inside noise_router.
        if isinstance(original_final_density, str):
            when_out_of_range = original_final_density
        else:
            extracted_name = f"vanilla_final_density_{preset}"
            (density_dir / f"{extracted_name}.json").write_text(
                json.dumps(original_final_density, indent=2))
            when_out_of_range = f"{NAMESPACE}:{extracted_name}"

        wrapper_name = f"final_density_{preset}"
        wrapper = {
            "type": "minecraft:range_choice",
            "input": {"type": f"{NAMESPACE}:radius_check"},
            "min_inclusive": 1,
            "max_exclusive": 2,
            "when_in_range": f"{NAMESPACE}:old_overworld_noise",
            "when_out_of_range": when_out_of_range,
        }
        (density_dir / f"{wrapper_name}.json").write_text(json.dumps(wrapper, indent=2))

        router["final_density"] = f"{NAMESPACE}:{wrapper_name}"
        (noise_settings_dir / f"{preset}.json").write_text(json.dumps(settings, indent=2))
        print(f"Wrote override for {preset}.")
        wrote_any = True

    (out_dir / "pack.mcmeta").write_text(json.dumps({
        "pack": {
            "description": "FarlandsOrigin - Far Lands worldgen overrides",
            "pack_format": pack_format
        }
    }, indent=2))

    if not wrote_any:
        print("\nERROR: found the data jar but wrote 0 overrides - see the warnings above. "
              "The datapack this produced is incomplete and Far Lands generation will NOT "
              "work with it as-is.", file=sys.stderr)
        sys.exit(1)

    print(f"\nDone. Copy {out_dir} into <world>/datapacks/, then also copy "
          f"data/farlandsorigin/worldgen/density_function/old_overworld_noise.json "
          f"from this repo's datapack/ folder into the same spot if it isn't there "
          f"already, and restart the server.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    jar_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("farlandsorigin")
    build(jar_path, out_dir)
