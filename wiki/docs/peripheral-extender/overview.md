# 外设扩展器概述

<div class="feature-row">
  <div class="feature-text">
    外设扩展器（Peripheral Extender）是 CCPE 的核心组件，整合多种读取信息与控制方式，并且支持区块加载和物理结构加载。
  </div>
  <div class="feature-img">
    <img src="../img/micro_peripheral_extender.png" width="120" alt="外设扩展器">
  </div>
</div>


## 工作原理

1. 将传感器贴在目标方块上
2. 设置唯一的频道号
3. 在任意位置的计算机中通过频道号访问与控制

## 使用场景

### 飞船自动驾驶
将传感器贴在导航桌上，实时获取位置和方位，实现自动导航。

### 仓储监控
在多个箱子上安装传感器，集中监控物品库存。

### 机械控制
读取 Create 机械装置的状态，动态调整红石信号或转速。

### 物理模拟
监控飞行器的速度、姿态，实现稳定控制系统。

## 性能特点

| 指标 | 数值 | 说明 |
|---|---|---|
| 数据刷新频率 | 50ms | 服务端每 tick 更新 |
| Lua 读取延迟 | ~0.02ms | 单次调用耗时 |
| 最大传感器数 | 65535 | 受频道号范围限制 |
| 传输距离 | 无限制 | 默认配置 |

!!! success "高频监控友好"
    即使需要每 tick 读取多个传感器的数据，性能开销也非常小。

## 快速开始

1. [第一个脚本](../getting-started/first-script.md) — 5 分钟上手
2. [NBT 读取详细文档](nbt-reading.md)

## API 模块

外设扩展器的 Lua API 通过 `ccpe.pe` 模块提供：

```lua
local pe = require("ccpe.pe")

-- NBT 数据读取
local data = pe.getAll(channel)
local value = pe.get(channel, "path.to.field")

-- 外设代理
local peripheral = pe.getPeripheral(channel)

-- 无线红石
pe.setRedstoneOutput(channel, 15)
local signal = pe.getRedstoneInput(channel)

-- 导航桌（需要 Simulated）
local pos = pe.getNavTargetPos(channel)
local angle = pe.getNavRelativeAngle(channel)

-- 物理数据（需要 Sable）
local velocity = pe.getPhysicsVelocity(channel)
local mass = pe.getPhysicsMass(channel)
```

完整 API 参考：[Lua API 参考](api-reference.md)
