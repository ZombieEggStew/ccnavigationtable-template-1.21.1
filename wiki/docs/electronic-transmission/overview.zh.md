# 电子变速箱

![电子变速箱](../img/transmission_peripheral_v.png)

> **与 create:RotationSpeedController 有什么不同?**

> 使用机械动力的转速控制器作为外设执行 `getTargetSpeed()` 会触发 `RotationPropagator.handleRemoved()` 会级联清空整个下游子网络的 source，导致不符合预期的结果（比如在转速控制器的下游使用 aeroworks 的 stepper_servo，改变转速的同时激活步进电机，电机会乱转）。
而 simulated 的 analog_transmission 难以精细调节。

!!! info "说明"
    该方块还支持[**舵机模式**](servo-mode.zh.md)：输出轴可经 Lua 绝对定位到指定角度（±180°，走最短路径）。

**电子变速箱** 是纯 CC:T 外设控制的 Create 动能变速器。**不接受红石信号**，只能通过 Lua 控制。可放置在应力网络中间，实时调节下游转速。

| 方法 | 说明 |
|---|---|
| `setRatio(ratio)` | 设置变速比（≥0），比例模式 `mainThread=true` |
| `getRatio()` | 获取当前变速比 |
| `setTargetSpeed(speed)` | 直接设定下游转速（0~256.00）`mainThread=true` |
| `getTargetSpeed()` | 获取目标转速 |



```lua
local t = peripheral.find("ccpe:transmission_peripheral")

-- 比率模式：下游 = 上游 × 比率
t.setRatio(0.5)   -- 下游降速至 50%
t.setRatio(3.0)   -- 下游加速至 3 倍（上限 256 RPM）

-- 目标模式：直接设定下游转速（0~256，保留两位小数）
t.setTargetSpeed(128.56)
print(t.getTargetSpeed())  -- 128.56

-- 查询当前状态
print(t.getRatio())
```

