---
name: neoforge-model-rendering
description: "Use when creating or debugging NeoForge OBJ models, MTL textures, standalone BakedModel loading, or BlockEntityRenderer model rendering."
---

# NeoForge Model Rendering

## OBJ resources

- Use a JSON model with `"loader": "neoforge:obj"`, `"flip_v": true`, and a `model` path under `models/`.
- Map MTL materials through JSON texture slots: MTL `map_Kd #0` maps to JSON texture key `"0"`.
- For reusable geometry with different skins, use a base OBJ JSON and small parent JSON texture variants.
- Blender exports need UVs, materials, triangulated faces, and a tested axis convention. Treat visual verification in-game as authoritative.

## Standalone BER models

Models not referenced by a blockstate must be registered with `ModelEvent.RegisterAdditional` using `ModelResourceLocation.standalone(location)`, then captured from `ModelEvent.BakingCompleted`.

```java
event.register(ModelResourceLocation.standalone(location));
BakedModel model = event.getModels().get(ModelResourceLocation.standalone(location));
```

Render them through the block-model renderer using `Sheets.solidBlockSheet()`, not `RenderType.solid()`. A missing registration or wrong buffer commonly presents as a purple-black model.

## Check

- Confirm resource paths match the namespace and model layout exactly.
- Run `./gradlew.bat classes`; launch the client for texture, lighting, and orientation verification.
