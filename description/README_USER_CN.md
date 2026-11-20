# CCPE — 飞行器系统

> **给 Create: Aeronautics 装上一套电传飞控。**
> 让传感器、执行器、控制台和引擎都听得懂 ComputerCraft: Tweaked 的话——在 20 Hz 的 Minecraft tick 上读全状态、直接下指令。

**[Wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/) · [示例](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/examples/trainer_aircraft/) · [GitHub](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1) · [更新日志](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/changeLog.md)**

---

## 为什么需要这个模组

Create: Aeronautics 赋予你的载具真实的物理——Sable 子次元、质量、升力、推力、力矩。但它没有给你**闭合控制环**的手段。唯一的原生接口是红石，而红石从来不是为飞控设计的：

- **读数只有 4 bit。** 陀螺仪默认 ±45° 量程下每格 **3.1°**（±90° 时 **6.2°**）；高度计默认把整个世界高度切成 15 格，**每格 25.6 m**；速度计默认 **0.67 m/s 一格**，还有 0.05 m/s 死区，正负号还得去另一面读。
- **链条更长。** 传感器 → 红石链接 → 比较器 / 模拟变速器 → 机械轴承，端到端 **3~4 tick（典型 150~200 ms）**。你要做「目标减实际」的闭环，每加一级比较器再加 1~2 tick，而误差还是 4 bit。
- **没有控制律。** 红石能表达静态比例、开关和时序；表达不了四元数 PD、级联、前馈、增益调度、带滞环的死区、看门狗——更别说事后能分析的 CSV 日志。

CCPE 把它换成 `传感器 → Lua → 执行器`，还是**同一个 20 Hz tick**，但手里是 double 和真正的代码：

| | 红石链 | CCPE Lua 链 |
|---|---|---|
| 信号 | 0~15，4 bit，无符号，单面 | double，带符号，三轴 |
| 姿态读数 | 3.1°/格（±45°），6.2°（±90°） | 精确四元数，没有 4 bit 台阶 |
| 高度读数 | 25.6 m/格（默认量程） | 米；飞行中优于 0.1 m |
| 速度读数 | 0.67 m/s/格，0.05 m/s 死区 | m/s，静止严格为 0 |
| 传感器 → 执行器 | 3~4 tick（150~200 ms） | 2~3 tick（100~150 ms） |
| 执行器分辨率 | 16 档变速、开关、开环 1° | 推力 0.00~1.00、角度伺服 |
| 控制律 | 静态比例、开关、比较器 | PD / 级联 / 前馈 / 看门狗 |

两条链的硬上限都是 Minecraft 的 20 Hz tick——**这个模组不能突破 tick。** 它拿掉的是 4 bit 量化和红石转换多出来的 1~2 tick。按源码级调研估算的闭环带宽：红石链 **0.2~0.5 Hz**，CCPE 姿态环 **0.5~1 Hz**；CCPE 的位置与末端制导在 0.1~0.3 Hz——那已经是机体的极限，不是线的极限。

## 飞起来是什么感觉

以下数字来自模组自己的飞行项目实测，不是估算：

- **20 Hz 控制环**——完整自动驾驶飞行中，周期 0.048 / 0.050 / 0.052 s（最小 / 中位 / 最大）。
- **0.15 s**——从 `setThrustScale` 阶跃到物理上可见的响应。
- **±0.06 m** 定高。
- 制导末端误差 **0.42 m**，星舰式捕获对接误差 **0.2 m**；被撞后姿态 **1.5 s** 恢复到 cos = 1.00。
- [演示：星舰 Flight 15 捕获](https://www.youtube.com/watch?v=EJPllPvQiYY)——五个矢量喷管、一台 AIC、两台电脑，飞回塔架被机械臂接住。

这不是什么绕过 tick 的魔法，而是「每个 tick 都装真数字和真控制器」之后，20 Hz 本来的样子。

## 上手示例：一个 20 Hz 控制环

```lua
local ss   = require("ccpe.sensor_system")
local elev = assert(peripheral.wrap("left"),  "缺少舵机轴承（升降舵）")
local ail  = assert(peripheral.wrap("right"), "缺少舵机轴承（副翼）")
local eng  = assert(ss.getEngine(),           "本物理体上必须恰好一台引擎")

elev.assemble()                              -- 每个舵面放好后调用一次
ail.assemble()

local TARGET_PITCH, TARGET_ROLL = 5, 0     -- 度
local TARGET_ALT                = 120      -- 米
local KP, KD, ALT_KP            = 1.5, 0.4, 0.02

while true do
    local a, r   = ss.getAngles(), ss.getAngleRates()   -- 零开销：读缓存
    local alt, v = ss.getAltitude(), ss.getVelocity()
    if not (a and r and alt and v) then error("需要 INS 和静压孔") end

    local pitch = math.max(-25, math.min(25, KP * (TARGET_PITCH - a.pitch) - KD * r.pitchRate))
    local roll  = math.max(-25, math.min(25, KP * (TARGET_ROLL  - a.roll ) - KD * r.rollRate))
    local thr   = math.max(0, math.min(1, 0.5 + ALT_KP * (TARGET_ALT - alt) - 0.5 * v.y))

    -- 一个 parallel 块 = 一个 tick 的写操作，块里塞多少条都一样
    parallel.waitForAll(
        function() elev.setTargetAngle(pitch) end,
        function() ail.setTargetAngle(roll)   end,
        function() eng.setThrottle(thr)       end
    )
    -- 不用 sleep：上面这个块本身已经正好花掉一个 tick
end
```

每个 `set...` 方法都会排队到服务端主线程、在下一 tick 开头返回；放进一个 `parallel.waitForAll`，它们就都落在同一个 tick 里。所以整个循环——读、算、写——正好 **1 tick（20 Hz）**。一条条顺序调用的话，每条各吃一个 tick（实测四条顺序写只有 4 Hz）；拆成两个 parallel 块是 10 Hz；一个块里塞 10~14 条仍然 20 Hz——示例里的自动驾驶都是这么写的。增益和符号随机体而定，从小往大调。完整自动驾驶（自动回正、自动油门、定高）在[教练机示例](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/examples/trainer_aircraft/)里，附带可下载存档。

## 包含内容

**🛰️ 感知——[传感器系统](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/overview/)**
一个 Lua 模块 `require("ccpe.sensor_system")`，按物理体隔离，只认电脑所在的这条船。[静压孔](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/static-port/)（气压、高度）、[皮托管](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/pitot-tube/)（地速、空速）、[INS](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/ins/)（姿态、四元数、角速度、线速度）、[FMC](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/fmc/)（质量、重力、重心、应力、螺旋桨与气动解算）、[AIC](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/aic/)（一块方块同时充当 INS 与 FMC），外加把每个 tick 记成 CSV 的[飞行数据记录仪](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/flight-data-recorder/)。读数每 tick 刷新一次，对主线程零开销。

**🎮 操控——执行器**

- [矢量喷管](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/actuators/advanced-thrust-nozzle/)——15° 锥角内任意指向，2 pN/RPM，推力系数与偏转全部由 Lua 驱动。
- [航空轴承](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/aero-bearing/overview/)——Sable 物理轴承，轴向动力输入；Lua 模式下角度直接写。
- [舵机轴承](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/actuators/servo-bearing/)——纯 Lua 角度伺服，运动学刚性、无需动力、渲染不爬行。
- [电子变速箱](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/electronic-transmission/overview/)——为 CC:T 打造的转速控制器：0.01 RPM 步进、可反转、伺服模式。
- [引擎系统](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/engine/overview/)——模块化多方块动力；油门只定转速，**油耗跟着应力负载走**，而不是跟着油门走。

**🪑 座舱**

- [控制台](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/control-desk/overview/)——坐下就飞：操纵杆、油门、脚踏、屏幕，全部可由 Lua 读取。
- [模块化监视器](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/monitor/overview/)——12×10 的按钮 / 开关 / 旋钮 / 屏幕网格，自己摆、自己写。

**🔌 连接**

- [外设扩展器](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/peripheral-extender/overview/)——频道读 NBT、远程调用外设、无线红石、区块加载。
- [红石收发器](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/redstone-transceiver/overview/)——直接收发 Create 红石链接频率，不用堆链接方块。
- [短程信号链接器](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/sensor-system/short-range-linker/)——物理体内部的频道与红石 I/O。

## 它做不到什么

- **它突破不了 tick。** 所有采样都是 20 Hz，物理还是 60 Hz 子步。可用闭环带宽在 2 Hz 以下；高频振动仍然只能靠质量和阻尼。
- **它不绕过物理。** 推力、惯量、约束、碰撞，全都还是 Sable 的。
- **它不让红石过时。** 慢速配平、限位开关、失电保持，红石仍然是合适工具。

## 来历

CCPE 一开始并不是飞行模组。它最初叫 **CC Navigation Table**，只是一个小插件，用来读取导航台里的坐标。后来看到 **Microcontrollers**——一个和 CC:Tweaked 不同的电脑模组：它的 Sensor 通过频道无线连接、直接读取目标方块的 NBT，不需要为每个模组单独写外设适配器。这个「通用传感器」的思路，后来就成了**外设扩展器**。

再往后，我依赖的那些模组总是做不到我想做的事，与其等作者更新，不如自己写。于是传感器、飞行计算机、执行器、引擎整条链都被做了出来——「外设扩展器」这个名字已经装不下它了。所以现在叫 **CCPE — Aircraft Systems（飞行器系统）**，以前叫 CC 外设扩展器（CC Peripheral Extender）。

模组仍在开发中。缺什么，或者对某个方块、某条 API 有想法，欢迎提 feature request。

## 要求

- **NeoForge** 1.21.1
- **CC:Tweaked** 1.118.0+
- **Create** 6.0.10+
- **Simulated**（Create: Aeronautics）1.3.0+
- **Sable** 2.0.3+

## 链接

- [Wiki 与完整 Lua API](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/)
- [示例：教练机](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/examples/trainer_aircraft/) · [星舰捕获](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/zh/examples/starship/)
- [问题反馈](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues)
