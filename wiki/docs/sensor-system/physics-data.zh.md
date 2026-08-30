# 物理数据

`ccpe.sensor_system` 的物理数据方法**不门控**：**不需要任何传感器方块**。只要电脑在物理体（Sable sub-level，含约束链）上就有值；不在物理体上或底层物理数据不可用时返回 `nil`。

| 方法 | 返回 | 说明 |
|---|---|---|
| `getPhysicsCenterOfMassRel()` | table / nil | 重心**相对当前电脑**的机体局部系位置 `{x, y, z}` |
| `getPhysicsMass()` | number / nil | 电脑所在物理体的质量（kg） |
| `getPhysicsChainMass()` | number / nil | 物理体**含全部约束链**的总质量（kg） |
| `getPhysicsGravityForce()` | number / nil | 所在物理体的重力（pN = 质量 × 11） |
| `getPhysicsChainGravityForce()` | number / nil | 整条物理体链的总重力（pN = 链总质量 × 11） |

## 重心语义

`getPhysicsCenterOfMassRel()` 返回重心相对电脑的**机体局部系**（plot 帧）偏移：

```
重心相对电脑 = (重心相对物理体原点的偏移) − (电脑相对物理体原点的偏移)
```

- 与 `getSensors()` 的 `pos_rel` 同一坐标系——**不随物理体移动/旋转变化**，适合稳定地识别重心装在机体的哪个位置（比如离驾驶舱前后/上下多远）。
- 需要世界系时，用 `getOrientation()` 把该向量旋转到世界。

## 重力

重力是**标量**（大小，方向向下）：

```
重力 (pN) = 质量 (kg) × 11
```

`getPhysicsGravityForce()` 用电脑所在物理体自身的质量；`getPhysicsChainGravityForce()` 用链总质量（见 `getPhysicsChainMass()`）。

## 示例

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("电脑不在物理体上")
end

print("质量 (kg):        ", ss.getPhysicsMass())
print("链总质量 (kg):    ", ss.getPhysicsChainMass())
print("重力 (pN):        ", ss.getPhysicsGravityForce())
print("链总重力 (pN):    ", ss.getPhysicsChainGravityForce())

-- 重心相对当前电脑（机体局部系，旋转时不变）
local com = ss.getPhysicsCenterOfMassRel()
if com then
    print("重心相对电脑:", string.format("x=%.2f y=%.2f z=%.2f", com.x, com.y, com.z))
end
```

这些方法**不需要任何传感器**即可读取；需要 INS 门控的姿态/物理方法见[惯性导航系统](ins.zh.md)页面。
