# 旋钮模块

![旋钮模块](../img/knob.png)

角度单位为**度**。旋钮内部保存的是累计**绝对角度**（开启物理限位时按限位夹紧，可能超过 360）；**归一化角度**把它折算到一圈内（0..360）。

## 操作说明
- **配置模块**：手持扳手对准模块右键 或者 蹲下+右键 可以打开模块配置界面，配置模块 ID、tooltip等属性
- **拆卸模块**：手持扳手蹲下右键 可以拆卸模块
- **卡位**：在配置界面中可以开启卡位功能，吸附到 设定角度 的 倍数角度 上

---

获取模块实例：

```lua
local knob = monitor.getModule(7)   -- 7 是旋钮的模块 ID
```

## knob.getAngle()

返回当前角度（数字，0..360）。

```lua
print(knob.getAngle())  -- 45.0
```

## knob.setAngle(angle)

设置角度（数字，度）。自动归一化到 0..360；开启卡位（detent）时会吸附到最近档位。

```lua
knob.setAngle(180)
knob.setAngle(90)   -- 若开了 45° 卡位，会吸附到 90
```

## knob.getNormalizedAngle()

返回归一化角度（数字，度，0..360）：绝对角度折算到一圈内。

```lua
print(knob.getNormalizedAngle())  -- 45.0
```

## knob.getAbsoluteAngle()

返回累计绝对角度（数字，度）。开启超过 360° 的物理限位时可能大于 360；未开启物理限位时反向旋转可能为负数。

```lua
print(knob.getAbsoluteAngle())  -- 405.0
```

## knob.getRelativeDetent()

返回相对档位（整数）：归一化角度 / 设定的卡位角度（四舍五入）。未开启卡位（自由旋转）时返回 0。

```lua
-- 卡位 90°，旋钮在 270°：返回 3
print(knob.getRelativeDetent())
```

## knob.getAbsoluteDetent()

返回绝对档位（整数）：绝对角度 / 设定的卡位角度（四舍五入）。未开启卡位（自由旋转）时返回 0。

```lua
-- 卡位 90°，旋钮在 405°：返回 5
print(knob.getAbsoluteDetent())
```

## knob.getRelativePercent()

返回相对百分比（数字，0..100）：归一化角度 / 设定的最大旋转角度 × 100。

```lua
-- 最大旋转角度 360°，旋钮在 180°：返回 50.0
print(knob.getRelativePercent())
```

## knob.getAbsolutePercent()

返回绝对百分比（数字）：绝对角度 / 设定的最大旋转角度 × 100。未开启物理限位时旋钮可转出设定范围，返回值可能超过 100 或为负数。

```lua
-- 最大旋转角度 360°，旋钮在 405°：返回 112.5
print(knob.getAbsolutePercent())
```
