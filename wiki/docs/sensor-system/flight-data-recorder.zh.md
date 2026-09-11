# 飞行数据记录系统

> 服务端调试工具：把每个已注册 FMC/AIC 物理体的逐 tick 飞行数据落盘为 CSV，用于飞行特性分析与调试。

**飞行数据记录系统**（`FlightDataRecorder.java`）是 CCPE 内置的**只读**调试工具。每个服务端 tick（可配置间隔）对**每个已注册 FMC（含 AIC）的物理体**采样一行数据，写入 `<gameDir>/flight_logs/` 下的 CSV 文件。它**只读不改**——不修改任何物理或控制行为，是分析操纵性、phugoid 稳定性、配平与控制输入的仪器。

!!! note "不是方块"
    记录器不是可放置的方块，也不接入 Lua API。它通过 mod 配置文件开关，在服务端后台运行；数据落到游戏目录的 CSV，用 Python / 表格软件分析。

## 开启方式（配置文件）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `flightDataRecorderEnabled` | `false` | 总开关。**默认关**，需要录制时在 `config/ccpe-common.toml` 打开；**需重启生效** |
| `flightDataRecorderIntervalTicks` | `1` | 采样间隔（tick）。`1` = 每 tick 一行 = 20 Hz |

- 只有**至少一个 FMC/AIC 在某个 Sable 物理体上**时才会产生文件。
- 记录器同样覆盖仅装有 INS（ATTITUDE）的机体：运动学/速度诊断列始终可用，无 FMC 时物理数据列为 `nan`。

## 输出文件

路径：`<gameDir>/flight_logs/flight_<维度>_<机体UUID前8位>_<yyyyMMdd-HHmmss>.csv`

- **每个 FMC/AIC 机体一个文件**（按机体 UUID 键）。
- 后缀时间戳 = **文件创建时刻**（不是起始 tick）→ 重启后不会覆盖旧文件（旧命名按起始 tick，重启后同机体同 tick 会覆盖）。
- 表头只在**建文件时**写入 → 改过记录器代码后必须重启游戏再飞，否则新列对不上表头。
- 机体被拆卸/卸载、服务器停止、或记录器被关闭时，文件正常关闭。

## CSV 列参考（顺序即表头顺序）

### 1. 运动学 / 环境

| 列 | 含义 |
|---|---|
| `tick` / `time_s` | 游戏 tick 与秒（tick/20） |
| `body` | 机体 UUID |
| `x` `y` `z` | 机体原点世界坐标 |
| `qx` `qy` `qz` `qw` | 姿态四元数（世界系） |
| `pitchDeg` `rollDeg` `yawDeg` | 欧拉角（度），与 `ss.getAngles()` 同约定。⚠ **低头 = pitch 正**（用户实测确认；屏幕显示层取反是 startup.lua 特意调整） |
| `vX` `vY` `vZ` `vNorm` | 世界系线速度 + 模长（Sable 每 tick 世界 pose 差分，静止时严格为 0） |
| `wX` `wY` `wZ` | 世界系角速度 |
| `wbX` `wbY` `wbZ` | 机体系角速度（同一 tick 姿态四元数逆旋转）——`wbX` = 俯仰速率 |
| `pressure` / `altitude` | 全部静压孔的平均气压 / 平均高度 |
| `airSpeed` / `groundSpeed` | 最后放置的皮托管沿管口轴线的对地速度 / 空速（皮托静压门控，不满足为 `nan`） |

### 2. 质量 / 重心

| 列 | 含义 |
|---|---|
| `massKg` / `chainMassKg` | 主机质量 / 整条约束链质量 |
| `comX` `comY` `comZ` | 主机重心（世界系） |
| `comRelX` `comRelY` `comRelZ` | 主机重心相对机体原点 |
| `chainComRelX` `chainComRelY` `chainComRelZ` | **链质心**相对机体原点（含尾部舵面子体，可偏离主机质心 ~1 m） |

### 3. 受力（主机自身）—— LIFT / DRAG / PROPULSION 各 6 列

| 列组 | 含义 |
|---|---|
| `liftFx` `liftFy` `liftFz` | 升力组合力（**机体局部系**），由 Sable `QueuedForceGroup` 记录的点力重算：F = Σf |
| `liftMx` `liftMy` `liftMz` | 绕主机 plot 质心的合力矩（局部系）：M = Σ(point − comPlot) × f |
| `dragFx` … `dragMz` | 阻力组，同上 |
| `propFx` … `propMz` | 推进组（螺旋桨），同上 |

### 4. 受力（整条约束链）—— chainLift / chainDrag / chainProp 各 6 列

与第 3 组同构，但**聚合整条约束链**（含 aero_bearing 从动 sub-level 上的尾翼/副翼面）：链上每个 sub-level 的点力先转到世界系求和，力矩绕**链质心**（世界坐标）计算，再整体转回主机局部系。

- 机翼与螺旋桨全在主机体上时：`chainLift == lift`、`chainProp == prop`；
- 舵面在从动子体上时：`chainDrag ≠ drag`——**链级列才包含全部控制面的贡献**（图纸看不到的部分就在这）。

### 5. 通用阻力

| 列 | 含义 |
|---|---|
| `univFx` `univFy` `univFz` | **通用阻力**（Rapier 线速度阻尼，`universal_drag` 默认 0.09，维度数据包可覆盖）：整链逐体按 m·d·v·Δt/(1+d·Δt) 求和的每物理子步冲量，转回主机局部系 |

力组/图纸里都没有这项，但物理里恒存在——**平飞平衡时 prop + drag + lift + univ ≈ 0**（早期分析中"净力神秘缺口"就是它）。

### 6. 控制输入（自动发现）

| 列组 | 含义 |
|---|---|
| `pedCh` `pedL` `pedR` | 脚踏板：左/右踏板轴值（-1..1） |
| `joy1Ch` `joy1X` `joy1Y` `joy1XA` `joy1YA` | 操纵杆 1：X/Y 轴值 + 活动标志 |
| `joyCh` `joyX` `joyY` `joyXA` `joyYA` | 操纵杆 2：X/Y 轴值 + 活动标志 |
| `thrCh` `thrAxis` `thrGear` `thrFwd` `thrBack` | 油门 1：轴值 / 档位 + 前进/后退活动标志 |
| `thr2Ch` `thr2Axis` `thr2Center` `thr2Up` `thr2Down` | 油门 2（总距杆）：轴值（0..1）/ 居中轴（-1..1）+ 上/下活动标志 |

记录器**自动扫描**链上短程信号链接器频道空间中的控制台，记录其已安装的上述全部控件（每类取链内第一台，同类型多台只记第一台并在日志警告一次）。`*Ch` 列 = 该控制台**真实频道**（读 `getChannel()`，不再硬编码）；链上无控制台 / 未安装该控件 → 该组列全为 0。

## 力是怎么被记录的（解读数据前必读）

- **力组跟踪**：记录器对主机及整条约束链调用 `ServerSubLevel.enableIndividualQueuedForcesTracking(true)`（Simulated 图纸同款机制，每 tick 幂等开启；文件关闭时对当前链恢复 `false`）。不开这个开关，LIFT/DRAG 的点力不会被 Sable 记录。
- **力组匹配**：运行时按注册表 id 匹配（`sable:force_groups` 的 `lift` / `drag` / `propulsion` 路径），不直接引用 `ForceGroups` 类。
- **采样时机** = 游戏 tick 末（`ServerTickEvent.Post`），读到的是该 tick **最后一个物理子步**记录的力组（组在每个物理步开始被 reset）——phugoid 级分析足够。
- **单位**：Sable 每物理步**冲量刻度**，不是绝对牛顿值 → 用于看趋势/力矩平衡，勿当绝对力值。
- 力组不存在（无对应力源）→ 该组全 `nan`；链上无控制台 / 未安装该控件 → 该组控制列全为 0；整行采样失败 → 一行全 `nan` 占位保持列对齐（Python 可安全解析）。

## 解读约定

- **平衡等式**：稳定巡航时 `chainProp + chainDrag + chainLift + univ ≈ 0`。
- **力矩参考点**：链级力矩绕**链质心**求——主机质心不含尾部子体（链质心偏移 ~0.95 m），绕它求矩会凭空产生 ∝ 升力的虚假恒定俯仰力矩（早期"净 Mx ≈ +8 残余抬头力矩"正是参考点假象，已修正）。
- **符号**：`pitchDeg` **低头 = 正**（与 `ss.getAngles()` 实测一致）；屏幕显示层取反（抬头显示为正）是 startup.lua 特意调整，不是 API 符号。
- **通用阻力不乘气压 P**：帆阻力、推力都 ∝ P，通用阻力只 ∝ m·v → 高空时推力/帆阻力同衰而通用阻力不衰，是高空极速上限下降的原因之一。

## 分析工具

[`.design_guide/analysis/`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/tree/1.1.3/.design_guide/analysis) 下有一组 Python 分析脚本（纯标准库 `csv` 模块）。CSV 数据仍由记录器写在 `run/flight_logs/`；脚本用相对自身位置的 `LOG_DIR` 自动定位该目录，默认自动选最新 `flight_*.csv`，**从任意目录运行均可**：

- [`_analyze_flight.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight.py) — 基础统计（高度/俯仰/空速/气压）+ 巡航段切分 + phugoid 峰谷检测
- [`_analyze_flight3.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight3.py) — 控制使用率 + 力列可用性 + 松手段力矩/相关分析
- [`_analyze_flight6.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight6.py) — 满油门全松手干净段提取、对数衰减阻尼比 ζ、力矩-气压回归（推力线偏置估算）
- [`_analyze_loop_delay.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_loop_delay.py) — 摇杆阶跃脉冲 → 指令/气动/总回路延迟分解
- [`_fit_aero_model.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_fit_aero_model.py) — 气动模型缩放结构标定（升力/阻力/推力对 P、v 的依赖）
- [`_verify_pressure.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_verify_pressure.py) — 气压公式（指数近似 vs Sable 曲线）与记录数据核对


## 相关文件速查

| 用途 | 路径 |
|---|---|
| 记录器主类 | `src/main/java/com/zzy205/myfirstmod/compat/cc/FlightDataRecorder.java` |
| 配置项 | `src/main/java/com/zzy205/myfirstmod/Config.java` |
| 物理体传感器注册表（FMC/INS 门控） | `src/main/java/com/zzy205/myfirstmod/compat/cc/BodySensorRegistry.java` |
| 物理读取助手 | `src/main/java/com/zzy205/myfirstmod/compat/sable/SableCompat.java` |
| 气动模型 / 通用阻力设计指南 | `.design_guide/aircraft.md` |
