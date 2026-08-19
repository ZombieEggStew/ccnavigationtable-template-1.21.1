---
name: add-monitor-module
description: "Use when adding a Monitor module, its assets, interaction, configuration section, item, or renderer."
---

# Add Monitor Module

Read only the relevant existing module implementation first. The monitor uses per-monitor IDs `0..9999`; modules and screens share that namespace.

## Required change points

1. Add the type and dimensions in `ModuleType`.
2. Add model locations in `MonitorPreloadedModels`; standalone BER models must be registered in both model events.
3. Add or extend the module behavior in `ModuleRenderBehavior`.
4. Register the item, creative-tab entry, item model, and both language entries.
5. Add client interaction, server payload handling, and `GridState` persistence only when this module has state.
6. For type-specific configuration, implement `ModuleConfigSection` and register a factory in `ModuleConfigSections`. Factories must create a new section each time.

## Model and coordinate notes

- Put static bases and moving parts in separate models when they animate independently.
- `offsetX/Y/Z()` use model-space coordinates before `applyInitialRotation()`; `renderExtra()` transforms occur afterwards.
- Use the relevant `neoforge-model-rendering` or `neoforge-create-rotation` skill for OBJ, baking, or orientation problems.

## Check

- Confirm `GridState.trySetId` re-keys every map that stores data by module ID.
- Run `./gradlew.bat classes`.
