---
name: catnip-outliner
description: "Use when adding or debugging Create Catnip Outliner AABB, line, cluster, preview, or highlight rendering."
---

# Catnip Outliner

Use `Outliner.getInstance()` with a stable key. For this mod, include `BlockPos` in every key because multiple Monitor instances can render simultaneously.

```java
Object key = Pair.of(pos, "module-preview");
Outliner.getInstance().showAABB(key, bounds)
    .colored(0x4CDA64)
    .lineWidth(1 / 16f);
```

- `showAABB` renders boxes; `showLine` renders segments; `showCluster` outlines block sets; `chaseAABB` smoothly tracks changing bounds.
- Call `keep(key)` every tick for a persistent outline, or use a bounded lifetime / `remove(key)` when ending interaction.
- Typical valid/invalid colors are `0x4CDA64` / `0xFF5E5E`.

## Source locations

- Compile-layout copy (packages match project imports): `api/ponder-neoforge-1.0.82+mc1.21.1-sources/net/createmod/catnip/outliner/Outliner.java` — Catnip is bundled inside the ponder sources; **not** in the create sources.
- Standalone newer version (0.8.54, `utility.*` layout): `api/Catnip-NeoForge-1.21.1-0.8.54-sources/net/createmod/catnip/utility/outliner/Outliner.java` — same class, different package; also mirrored at `references/Catnip-NeoForge-1.21.1-0.8.54-sources/`.
- More Catnip package mapping: `memo/api-code-map.md` (⭐ 核心三件套 section).

## Check

Test multiple monitors at once and ensure preview keys are removed when interaction is cancelled.
