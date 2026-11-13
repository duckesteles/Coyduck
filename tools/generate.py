#!/usr/bin/env python3
import json
import shutil
from pathlib import Path

from PIL import Image

VANILLA = Path.home() / ".cache/coytextures/vanilla/assets/minecraft"
OLD = Path.home() / ".cache/coytextures/old"
EMPTY = "minecraft:coy_empty"
NO_SMOKE = ("explosion", "poof", "smoke", "totem_of_undying")


def first(value):
    choice = dict(value[0]) if isinstance(value, list) else dict(value)
    choice.pop("weight", None)
    return choice


def fixed_blockstates(out):
    count = 0
    for path in sorted((VANILLA / "blockstates").glob("*.json")):
        data = json.loads(path.read_text())
        changed = False
        for key, value in data.get("variants", {}).items():
            if isinstance(value, list):
                data["variants"][key] = first(value)
                changed = True
        for part in data.get("multipart", []):
            if isinstance(part.get("apply"), list):
                part["apply"] = first(part["apply"])
                changed = True
        if changed:
            target = out / "assets/minecraft/blockstates" / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(data, indent=2) + "\n")
            count += 1
    return count


def no_smoke(out):
    particles = out / "assets/minecraft/particles"
    particles.mkdir(parents=True, exist_ok=True)
    for name in NO_SMOKE:
        (particles / f"{name}.json").write_text(json.dumps({"textures": [EMPTY]}, indent=2) + "\n")
    texture = out / "assets/minecraft/textures/particle/coy_empty.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (16, 16), (0, 0, 0, 0)).save(texture)


def no_fire_overlay(out):
    texture = out / "assets/minecraft/textures/block/fire_1.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (16, 16), (0, 0, 0, 0)).save(texture)


OLD_TEXTURES = {
    "totem_of_undying.png": "item/totem_of_undying.png",
}


def old_textures(out):
    for source, path in OLD_TEXTURES.items():
        target = out / "assets/minecraft/textures" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(OLD / source, target)


def generate(out):
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    states = fixed_blockstates(out)
    no_smoke(out)
    no_fire_overlay(out)
    old_textures(out)
    return states


if __name__ == "__main__":
    print(generate(Path(__file__).resolve().parent.parent / "build" / "generated"), "blockstates")
