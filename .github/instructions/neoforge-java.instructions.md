---
applyTo: "src/main/java/**/*.java"
description: "NeoForge 1.21.1 Java mod source rules. Use only while editing mod Java sources."
---

# CC Peripheral Extender Java Rules

- This is a Minecraft 1.21.1 NeoForge mod. Mod Java source is stored in `src/main/java` and targets Java 21 and NeoForge 21.1.235.
- Keep changes focused on the requested code. Treat `build/`, `bin/`, `run/`, `sources/`, `reference/`, and `libs/` as read-only; use them only as references when needed.
- Reuse existing project patterns before introducing new abstractions.
- Read the directly relevant class and nearest caller or test. Use the `minecraft-mod-source-lookup` skill when an external API or example implementation needs confirmation.
- Validate Java changes with `./gradlew.bat classes`, unless a narrower relevant check exists.