# 开关模块

# 🤪

钮子开关（toggle_switch）为**锁存型**：状态保持，直到再次改变。状态变化会同步客户端渲染。

## 操作说明
- **配置模块**：手持扳手对准模块右键 或者 蹲下+右键 可以打开模块配置界面，配置模块 ID、tooltip等属性
- **拆卸模块**：手持扳手蹲下右键 可以拆卸模块

---

获取模块实例：

```lua
local sw = monitor.getModule(7)   -- 7 是开关的模块 ID
```

## sw.getToggleState()

返回当前锁存状态（布尔）。

```lua
print(sw.getToggleState())  -- false
```

## sw.setToggleState(state)

设置锁存状态。`true` = 打开（按下），`false` = 关闭（弹起）。

```lua
sw.setToggleState(true)
sw.setToggleState(false)
```

## sw.toggle()

反转锁存状态（等价于玩家点击拉杆）。

```lua
sw.toggle()
```
