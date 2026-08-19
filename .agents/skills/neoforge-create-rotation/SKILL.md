---
name: neoforge-create-rotation
description: "Use when debugging Minecraft or Create block orientation, BlockEntityRenderer transforms, SuperByteBuffer rotation, VoxelShaper shapes, or rotated AABB hitboxes."
---

# NeoForge and Create Rotation

- Blockstate Y rotations are counter-clockwise; `SuperByteBuffer.rotateCentered(theta, Direction.UP)` is clockwise. For a standard `HorizontalDirectionalBlock` BER, use:

```java
float yRotation = (float) -Math.toRadians(facing.getOpposite().toYRot());
```

- Do not apply the `FaceAttachedHorizontalDirectionalBlock` `+Math.PI` convention to normal horizontal blocks.
- `rotateCentered` rotates around model-space `[8, 8, 8]`.
- Apply local X/Z animation translations before the facing rotation. Y translation may commute with Y rotation.
- Define a north-oriented `VoxelShaper` and obtain the facing-specific shape with `shape.get(facing)`.
- When rotating local AABBs, verify every facing in-game; do not infer the mapping from one successful direction.

## Check

Test north, east, south, and west. Validate blockstate, BER model, collision/selection shape, and interaction AABB separately.
