# 导航台集成

**附着在 航空学 的 导航台上**，读取目标的数据

| 方法 | 返回值 | 说明 |
|---|---|---|
| `pe.getNavTargetPos(ch)` | `{x, y, z}` | 目标世界坐标 |
| `pe.getNavSelfPos(ch)` | `{x, y, z}` | 自身世界坐标 |
| `pe.getNavDistance(ch)` | `number` | 到目标距离（米） |
| `pe.getNavRelativeAngle(ch)` | `number` | 方位角（度，0~360） |