#!/usr/bin/env python3
import hashlib
import io
import json
import urllib.request
import zipfile
from pathlib import Path

CACHE = Path.home() / ".cache/coytextures"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
OLD = {
    "assets/minecraft/textures/items/totem.png": "totem_of_undying.png",
    "assets/minecraft/textures/items/end_crystal.png": "end_crystal.png",
    "assets/minecraft/textures/entity/endercrystal/endercrystal.png": "end_crystal_entity.png",
}


def get(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def client(version):
    versions = json.loads(get(MANIFEST))["versions"]
    meta = json.loads(get(next(v["url"] for v in versions if v["id"] == version)))["downloads"]["client"]
    data = get(meta["url"])
    if hashlib.sha1(data).hexdigest() != meta["sha1"]:
        raise SystemExit(f"{version} client jar failed its checksum")
    return zipfile.ZipFile(io.BytesIO(data))


def fetch():
    vanilla = CACHE / "vanilla"
    if not (vanilla / "assets/minecraft/blockstates").is_dir():
        with client("1.21.11") as jar:
            for entry in jar.namelist():
                if entry.startswith("assets/minecraft/") and not entry.endswith("/"):
                    out = vanilla / entry
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(jar.read(entry))
    old = CACHE / "old"
    if not all((old / name).exists() for name in OLD.values()):
        old.mkdir(parents=True, exist_ok=True)
        with client("1.12.2") as jar:
            for entry, name in OLD.items():
                (old / name).write_bytes(jar.read(entry))


if __name__ == "__main__":
    fetch()
