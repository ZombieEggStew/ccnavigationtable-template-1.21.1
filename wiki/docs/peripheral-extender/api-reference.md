# Lua API 参考

本页面提供外设扩展器的完整 Lua API 文档。

## 导入模块

```lua
local pe = require("ccpe.pe")
```

## NBT 数据读取

### pe.getAll(channel)

读取指定频道方块的完整 NBT 数据。

**参数：**
- `channel` (number) — 频道号，范围 1-65535

**返回值：**
- (table|nil) — NBT 数据表，如果频道无数据则返回 nil

**示例：**
```lua
local data = pe.getAll(1)
if data then
    print("物品数量: " .. #data.Items)
end
```

---

### pe.get(channel, path)

读取指定频道方块的特定 NBT 字段。

**参数：**
- `channel` (number) — 频道号，范围 1-65535
- `path` (string) — NBT 路径，如 `"Items[0].count"`

**返回值：**
- (any|nil) — 字段值，类型取决于 NBT 数据类型

**示例：**
```lua
local count = pe.get(1, "Items[0].count")
local itemId = pe.get(1, "Items[0].id")
```

---

## 外设代理

### pe.getPeripheral(channel)

获取指定频道方块的 CC:T 外设对象，可调用该方块的所有外设方法。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table|nil) — 外设对象，如果方块不支持 CC:T 外设则返回 nil

**示例：**
```lua
-- 假设频道 2 贴在一个 Create 转速控制器上
local controller = pe.getPeripheral(2)
if controller then
    controller.setTargetSpeed(64)
    local speed = controller.getSpeed()
    print("当前转速: " .. speed)
end
```

---

## 无线红石

### pe.setRedstoneOutput(channel, level)

向指定频道的传感器发送红石信号。

!!! warning "主线程调用"
    此方法在主线程执行，可能会有轻微延迟。

**参数：**
- `channel` (number) — 频道号
- `level` (number) — 红石信号强度，范围 0-15

**返回值：**
- 无

**示例：**
```lua
-- 发送满信号
pe.setRedstoneOutput(1, 15)

-- 关闭信号
pe.setRedstoneOutput(1, 0)
```

---

### pe.getRedstoneOutput(channel)

读取指定频道传感器发送的红石信号强度。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 红石信号强度，范围 0-15

**示例：**
```lua
local output = pe.getRedstoneOutput(1)
print("发送信号: " .. output)
```

---

### pe.getRedstoneInput(channel)

读取指定频道传感器接收的红石信号强度。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 红石信号强度，范围 0-15

**示例：**
```lua
local input = pe.getRedstoneInput(1)
if input > 0 then
    print("检测到红石信号: " .. input)
end
```

---

## 导航桌集成

!!! info "依赖要求"
    以下 API 需要传感器贴在 `simulated:navigation_table` 上。

### pe.getNavTargetPos(channel)

获取导航桌设定的目标位置（世界坐标）。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 世界坐标（米）

**示例：**
```lua
local target = pe.getNavTargetPos(1)
print(string.format("目标: %.1f, %.1f, %.1f", target.x, target.y, target.z))
```

---

### pe.getNavSelfPos(channel)

获取导航桌所在飞行器的当前位置（世界坐标）。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 世界坐标（米）

**示例：**
```lua
local self = pe.getNavSelfPos(1)
print(string.format("当前位置: %.1f, %.1f, %.1f", self.x, self.y, self.z))
```

---

### pe.getNavDistance(channel)

获取到目标的直线距离。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 距离（米）

**示例：**
```lua
local distance = pe.getNavDistance(1)
print(string.format("距离目标: %.1f 米", distance))
```

---

### pe.getNavRelativeAngle(channel)

获取到目标的相对方位角（从正北顺时针）。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 方位角（度），范围 0-360

**示例：**
```lua
local angle = pe.getNavRelativeAngle(1)
print(string.format("方位: %.1f°", angle))
```

---

## 物理数据

!!! info "依赖要求"
    以下 API 需要 Sable 模组和物理结构。速度相关方法需要传感器贴在 `simulated:velocity_sensor` 上。

### pe.getPhysicsPos(channel)

获取物理体的世界坐标。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 世界坐标（米）

---

### pe.getPhysicsOrientation(channel)

获取物理体的旋转姿态（四元数）。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number, w = number}` 四元数

---

### pe.getPhysicsCenterOfMass(channel)

获取物理体的质心世界坐标。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 世界坐标（米）

---

### pe.getPhysicsMass(channel)

获取物理体的质量。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 质量（千克）

---

### pe.getPhysicsChainMass(channel)

获取物理体链的总质量（包括所有连接的物理体）。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 总质量（千克）

---

### pe.getPhysicsGravityForce(channel)

获取物理体受到的重力。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 重力（牛顿）

---

### pe.getPhysicsChainGravityForce(channel)

获取物理体链受到的总重力。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (number) — 总重力（牛顿）

---

### pe.getPhysicsVelocity(channel)

获取物理体的地面速度（相对地面）。

!!! warning "需要速度传感器"
    传感器必须贴在 `simulated:velocity_sensor` 上。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 速度（米/秒）

---

### pe.getPhysicsAirVelocity(channel)

获取物理体的空速（已减去风速）。

!!! warning "需要速度传感器"
    传感器必须贴在 `simulated:velocity_sensor` 上。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 空速（米/秒）

---

### pe.getPhysicsAngularVelocity(channel)

获取物理体的角速度。

!!! warning "需要速度传感器"
    传感器必须贴在 `simulated:velocity_sensor` 上。

**参数：**
- `channel` (number) — 频道号

**返回值：**
- (table) — `{x = number, y = number, z = number}` 角速度（弧度/秒）

---

## 错误处理

所有 API 调用在出错时返回 `nil`，而不是抛出异常。建议使用条件判断：

```lua
local data = pe.getAll(1)
if data then
    -- 处理数据
else
    print("频道 1 无数据")
end
```

## 性能注意事项

- NBT 数据每 tick（50ms）刷新
- 单次 API 调用约 0.02ms
- 推荐使用路径读取而非完整读取以提高效率
- 高频调用时考虑缓存不变数据
