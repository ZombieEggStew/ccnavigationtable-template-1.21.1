---
applyTo: "src/main/resources/**/*.{json,lang,mcmeta,png}"
description: "NeoForge resource rules. Use only while editing mod assets, data, or metadata."
---

# CC Peripheral Extender Resource Rules

- This is a Minecraft 1.21.1 NeoForge mod. Resource and data files belong under `src/main/resources`.
- Change only assets or data directly required by the requested feature.
- Preserve existing namespace, JSON layout, and model conventions.
- Treat `build/`, `bin/`, `run/`, `sources/`, `reference/`, and `libs/` as read-only reference directories; do not edit them.
