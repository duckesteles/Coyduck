#!/usr/bin/env python3
import argparse
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

from generate import generate

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "pack"
DIST = ROOT / "dist"
GENERATED = ROOT / "build" / "generated"
INSTANCE = Path.home() / ".local/share/ElyPrismLauncher/instances/1.21.11/minecraft/resourcepacks"


def check():
    meta = json.loads((PACK / "pack.mcmeta").read_text())["pack"]
    for key in ("description", "min_format", "max_format"):
        if key not in meta:
            raise SystemExit(f"pack.mcmeta is missing {key}")
    for root in (PACK, GENERATED):
        for path in list(root.rglob("*.json")) + list(root.rglob("*.mcmeta")):
            json.loads(path.read_text())


def build():
    subprocess.run([sys.executable, str(ROOT / "art" / "textures.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "art" / "logo.py")], check=True)
    generate(GENERATED)
    check()
    version = (ROOT / "VERSION").read_text().strip()
    DIST.mkdir(exist_ok=True)
    target = DIST / f"CoyTextures-{version}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        written = set()
        for root in (PACK, GENERATED):
            for path in sorted(root.rglob("*")):
                name = path.relative_to(root).as_posix()
                if path.is_file() and path.name != ".gitkeep" and name not in written:
                    archive.write(path, name)
                    written.add(name)
    return target


def install(zip_path):
    INSTANCE.mkdir(parents=True, exist_ok=True)
    for old in INSTANCE.glob("CoyTextures-*.zip"):
        if old.name != zip_path.name:
            old.unlink()
    temp = INSTANCE / f".{zip_path.name}.tmp"
    shutil.copyfile(zip_path, temp)
    temp.replace(INSTANCE / zip_path.name)
    return INSTANCE / zip_path.name


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    built = build()
    print(built)
    if args.install:
        print(install(built))
