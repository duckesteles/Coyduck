#!/usr/bin/env python3
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
TEXTURES = ROOT / "pack" / "assets" / "minecraft" / "textures"

OBSIDIAN = (58, 48, 78)
OBSIDIAN_EDGE = (32, 26, 44)


def obsidian():
    image = Image.new("RGBA", (16, 16), OBSIDIAN + (255,))
    ImageDraw.Draw(image).rectangle((0, 0, 15, 15), outline=OBSIDIAN_EDGE + (255,))
    return image


def write(path, image):
    target = TEXTURES / path
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)


if __name__ == "__main__":
    write("block/obsidian.png", obsidian())
