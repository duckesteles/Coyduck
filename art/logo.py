#!/usr/bin/env python3
import sys
from pathlib import Path

from PIL import Image

VANILLA = Path.home() / ".cache/coytextures/vanilla/assets/minecraft/textures"
OLD = Path.home() / ".cache/coytextures/old"


def texture(path, root=VANILLA):
    return Image.open(root / path).convert("RGBA")


def shadow(sprite):
    dark = Image.new("RGBA", sprite.size, (0, 0, 0, 0))
    dark.putalpha(sprite.getchannel("A").point(lambda a: 150 if a else 0))
    return dark


def icon(size=256):
    canvas = Image.new("RGBA", (32, 32))
    obsidian = texture("block/obsidian.png")
    for x in (0, 16):
        for y in (0, 16):
            canvas.paste(obsidian, (x, y))
    crystal = texture("end_crystal.png", OLD)
    totem = texture("totem_of_undying.png", OLD)
    for sprite, (x, y) in ((crystal, (14, 6)), (totem, (2, 10))):
        canvas.alpha_composite(shadow(sprite), (x + 1, y + 1))
        canvas.alpha_composite(sprite, (x, y))
    return canvas.resize((size, size), Image.NEAREST)


if __name__ == "__main__":
    icon().save(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "pack" / "pack.png")
