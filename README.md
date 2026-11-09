<p align="center">
  <img src="assets/logo.svg" width="280" alt="Coyduck Logger">
</p>

<p align="center">Minecraft 1.21.11 · Fabric</p>

# Coyduck Logger

Records your own fights in detail so they can be studied later, and never writes where you are.

It is a plain Fabric mod with no other dependencies, so it works next to any Fabric client. Everything your client sends to the server goes through the same connection, so the log shows what your client really did, whichever client or addon did it.

## Install

Put the jar in your `mods` folder. When you join a server you see `[Coyduck Logger] recording to coyduck-logger/...` in chat. Each join gets its own file in `.minecraft/coyduck-logger/`. To share a fight, send that file.

## What it records

- Every packet your client sends during a fight: movement and rotations, block and item use, attacks, mining, slot changes, inventory clicks, swings, sprint and elytra toggles, inputs, and how many tick ends were sent in one client tick.
- What the server sends back: damage with its type and source, totem pops and deaths, your health, teleports and setbacks with how far they moved you, acknowledged placements, block changes and mining progress next to you, crystals appearing and how long they lived, explosions, and your hotbar and offhand as the server sees them.
- Other players near you: health changes, pops, swings, item and armor changes, gliding, and the items they hold and eat.
- A state line every second, and a summary at the end of every fight: counts, damage taken per type, pops per player, and the most packets sent in one tick.

## Privacy

- Positions are written only inside fight windows. A window opens when another player comes within 24 blocks or you take damage, and closes after 30 quiet seconds.
- Inside a window every position is relative to where that window opened. No absolute coordinate is ever written.
- A window closes once you move 96 blocks from its start, and the next one cannot open for 20 seconds, so windows cannot be chained into a route home.
- Chat, commands, signs, item names and the world seed are never recorded.
- As a last check, every line passes a guard that replaces any number of 1000 or more with `?`, unless it is an entity id, a sequence number or a time. The guard cannot tell a coordinate under 1000 from a relative one, but the logger never writes absolute coordinates in the first place.
- Nothing is sent anywhere. The log stays on your computer until you share it.
