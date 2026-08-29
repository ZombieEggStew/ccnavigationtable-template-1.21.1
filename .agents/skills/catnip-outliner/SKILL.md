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

- API class (compile layout): `api/create/ponder-neoforge-1.0.82+mc1.21.1/net/createmod/catnip/outliner/Outliner.class` — Catnip is bundled inside the ponder jar, **not** in the create slim jar.
- Full implementation: `references/Catnip-NeoForge-1.21.1-0.8.54-sources/net/createmod/catnip/` — note 0.8.54 uses the `net.createmod.catnip.utility.outliner` package layout, which differs from the ponder-shaded compile layout above.
- More Catnip package mapping: `memo/api-code-map.md` (⭐ 核心三件套 section).

## Check

Test multiple monitors at once and ensure preview keys are removed when interaction is cancelled.
