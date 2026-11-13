#!/usr/bin/env python3
import sys
import zipfile
from pathlib import Path

JAR = Path.home() / ".gradle/caches/fabric-loom/1.21.11/minecraft-client.jar"
CACHE = Path.home() / ".cache/coytextures/vanilla"


def extract(jar=JAR, target=CACHE):
    target.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if entry.startswith("assets/minecraft/") and not entry.endswith("/"):
                out = target / entry
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(archive.read(entry))
                count += 1
    return count


if __name__ == "__main__":
    jar = Path(sys.argv[1]) if len(sys.argv) > 1 else JAR
    print(f"{extract(jar)} files in {CACHE}")
