# CCPE — CC 外设扩展器

!!! info "欢迎"
    CCPE (CC Peripheral Extender) 是一个为 Minecraft NeoForge 平台设计的模组，专为 Create: Aeronautics 和 ComputerCraft: Tweaked 用户提供强大的无线外设控制能力。

    **零 Mixin** — 本模组完全未使用 Mixin，因此大概率不会与其他模组产生兼容性问题。


![img](img/overview.png)


## ✨ 核心功能

### [📡外设扩展器](peripheral-extender/overview.md)

多功能远程终端，支持：

- **NBT 数据读取** — 通过频道无线访问方块数据
- **外设代理** — 远程调用 CC:T 外设方法
- **无线红石** — 发送和接收红石信号
- **导航桌集成** — 获取飞行器位置、方位、距离
- **物理数据** — 读取 Sable 物理引擎的速度、质量、姿态
- **区块加载** — 保持目标方块所在区域或者物理结构不被卸载
    
### [🤪模块化监视器](monitor/overview.md)
12x10棋盘插槽，lua自由控制，满足不同场景下的互动和信息展示需求。

### [🪑 控制台](control-desk/overview.md)
坐垫驱动的模块化控制台，支持：

- **即坐即用** — 坐上 Create 坐垫自动进入操作模式，用键盘同时驱动联动控制台
- **模块化控件** — 自行安装[脚踏板](control-desk/pedal.md)、[操纵杆](control-desk/joystick.md)、[油门杆](control-desk/throttle.md)等，支持模拟量行程与档位模式
- **Lua 读取** — 控件状态经 CC:T Lua API（`ccpe:control_desk`）实时读取

### [📻 红石收发器](redstone-transceiver/overview.md)
直接读取和发送 Create Redstone Link 信号，无需在计算机旁堆叠红石链接方块。

### [🎛️ 电子变速箱](electronic-transmission/overview.md)
专为 CC:T 控制优化的转速控制器，避免了 Create 原版控制器的网络级联问题。

### [🍌 航空轴承](aero-bearing/overview.md)
Sable 物理轴承，直接轴向动力输入。**Lua 控制模式**下旋转角度由 Lua 直接设定，跳过应力网络角度累计——精确控制你的风帆/舵面角度。

### [🛰️ 传感器系统](sensor-system/overview.zh.md)
为物理体（Sable 子次元）设计的航空传感器，全部可通过 `ccpe.sensor_system` Lua API 读取：

- **[静压孔](sensor-system/static-port.md)** — 读取静压孔所在位置的气压与高度
- **[皮托管](sensor-system/pitot-tube.md)** — 方向性速度传感器，沿管口轴线读取地速与空速
- **[惯性导航系统](sensor-system/ins.md)** — 姿态指示器：俯仰/滚转/偏航、位置、姿态四元数与角速度
- **[飞行管理计算机](sensor-system/fmc.md)** — 物理数据：质量、重力、重心；以及附着方块所在 Create 应力网络（剩余/容量应力）
- **[航空集成计算机](sensor-system/aic.md)** — 一块方块同时充当 INS 与 FMC

---
## 🚀 快速开始

[示例与教程](peripheral-extender/example.md) — 查看实际应用案例


## 🔗 相关链接

- [GitHub 仓库](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1)
- [问题反馈](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues)
- [更新日志](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/changeLog.md)
