# Minecraft NeoForge 旋转注意事项

## 关键规则
- `SuperByteBuffer.rotateCentered(θ, UP)` 使用**顺时针**，Minecraft blockstates 使用**逆时针** → 必须取负号
- 标准 `HorizontalDirectionalBlock` 的 BER 公式：`-Math.toRadians(facing.getOpposite().toYRot())`
- `FaceAttachedHorizontalDirectionalBlock` 需要 `+Math.PI` + Z轴特殊处理
- Y 平移和 Y 旋转可交换，但 X/Z 平移必须先于旋转
- 旋转中心统一为 `[8, 8, 8]`

## Direction.toYRot()
SOUTH=0, WEST=90, NORTH=180, EAST=270

## 常见错误
- 东西朝向错位 → 忘了取负号
- 所有方向偏 180° → 多了/少了 +Math.PI
- Partial model 不对齐 → BER 和 blockstates 旋转不一致

详见 `tools/rotation.md`
