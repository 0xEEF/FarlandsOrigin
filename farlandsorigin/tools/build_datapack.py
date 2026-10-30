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
VANILLA_FINAL_DENSITY = "data/minecraft/worldgen/density_function/overworld/final_density.json"
SLOPED_CHEESE = "minecraft:overworld/sloped_cheese"
BASE_3D_NOISE = "minecraft:overworld/base_3d_noise"


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


def replace_underground_branch(node) -> bool:
    """
    Finds the range_choice on overworld/sloped_cheese inside vanilla's
    final_density and replaces its when_out_of_range (vanilla's cave carving)
    with overworld/base_3d_noise, in place. This is the edit the original mod
    author made by hand for every Minecraft version; doing it from the
    server's own data means it cannot go stale when Mojang restructures
    final_density (26.3 did: argument1/argument2 became left/right,
    y_clamped_gradient became gradient, a lerp type appeared, ...).
    """
    if isinstance(node, dict):
        if (node.get("type") == "minecraft:range_choice"
                and node.get("input") == SLOPED_CHEESE
                and "when_out_of_range" in node):
            node["when_out_of_range"] = BASE_3D_NOISE
            return True
        return any(replace_underground_branch(v) for v in list(node.values()))
    if isinstance(node, list):
        return any(replace_underground_branch(v) for v in node)
    return False


def detect_pack_major(zf, names):
    """
    Data pack format of this server. Since pack format 82 a pack declares
    min_format/max_format instead of pack_format. version.json used to have
    pack_version.data; it now has data_major/data_minor.
    """
    if "version.json" in names:
        try:
            pack_version = read_json_from_zip(zf, "version.json").get("pack_version", {})
            if "data_major" in pack_version:
                return pack_version["data_major"]
            if "data" in pack_version:
                return pack_version["data"]
        except Exception:
            pass
    return None


def build(jar_path: Path, out_dir: Path):
    zf = find_data_zip(jar_path)
    names = set(zf.namelist())

    pack_major = detect_pack_major(zf, names)
    if pack_major is None:
        sys.exit("Could not determine this server's data pack format from version.json - "
                 "refusing to write a pack.mcmeta that the game would reject.")

    density_dir = out_dir / "data" / NAMESPACE / "worldgen" / "density_function"
    noise_settings_dir = out_dir / "data" / "minecraft" / "worldgen" / "noise_settings"
    density_dir.mkdir(parents=True, exist_ok=True)
    noise_settings_dir.mkdir(parents=True, exist_ok=True)

    if VANILLA_FINAL_DENSITY not in names:
        sys.exit(f"{VANILLA_FINAL_DENSITY} not found - cannot derive old_overworld_noise.")
    old_noise = read_json_from_zip(zf, VANILLA_FINAL_DENSITY)
    if not replace_underground_branch(old_noise):
        sys.exit(f"No range_choice on {SLOPED_CHEESE} found in {VANILLA_FINAL_DENSITY}; Mojang "
                 f"restructured it. Cannot derive old_overworld_noise automatically for this version.")
    (density_dir / "old_overworld_noise.json").write_text(json.dumps(old_noise, indent=2))
    print("Derived old_overworld_noise from this server's own overworld/final_density.")

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
            "min_format": pack_major,
            "max_format": pack_major
        }
    }, indent=2))

    if not wrote_any:
        print("\nERROR: found the data jar but wrote 0 overrides - see the warnings above. "
              "The datapack this produced is incomplete and Far Lands generation will NOT "
              "work with it as-is.", file=sys.stderr)
        sys.exit(1)

    print(f"\nDone. Copy {out_dir} into <world>/datapacks/ and restart the server.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    jar_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("farlandsorigin")
    build(jar_path, out_dir)
