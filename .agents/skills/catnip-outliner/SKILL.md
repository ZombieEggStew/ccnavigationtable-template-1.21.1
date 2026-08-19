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

## Check

Test multiple monitors at once and ensure preview keys are removed when interaction is cancelled.
