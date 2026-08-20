# 旋钮模块

![旋钮模块](../img/knob.png)

角度单位为**度**，范围 0..360。

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
