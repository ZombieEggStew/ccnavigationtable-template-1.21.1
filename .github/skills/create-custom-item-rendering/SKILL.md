---
name: create-custom-item-rendering
description: "Use when implementing Create CustomRenderedItemModel rendering for multi-part, NBT-driven, or dynamically assembled item models."
---

# Create Custom Item Rendering

Use `CustomRenderedItemModel` in the baking-result event and implement `CustomRenderedItemModelRenderer` for assembly. Keep an original-model fallback for stacks with no valid custom parts.

## Rendering pattern

1. Resolve each part to a `PartialModel`.
2. Compute the transformed union AABB before rendering.
3. Center on the AABB and scale by `targetExtent / longestAxis`.
4. Render each part via `CachedBuffers.partial(...).light(light).renderInto(...)`.

Use `CachedBuffers.block(state)` for a static block base. Apply each part's translation and rotation with a balanced `PoseStack` push/pop pair. For inventory-facing block models, validate whether a 180 degree Y rotation and `-0.5` origin translation are required.

## Check

- Test empty/invalid NBT fallback and a fully populated stack.
- Verify the inventory, held, ground, and fixed display contexts in a client run.
