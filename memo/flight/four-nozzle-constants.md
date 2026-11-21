# 四喷口矩形飞行器 · 可信常数与黑名单（唯一真相源）

> 状态：**2026-11-21 15:47 首轮 P0 已跑**：质量/气压/TWR/AIC 已实测；
> **`getForcePoint` 在运行时不可见**（客户端 15:03 启动早于 15:26 的编译）⇒ 完整重启客户端后重跑 `geom`（黑名单 #28）。
> 本表只放「已由用户或程序确认」的硬件事实；**禁止**把两发时代的 `m / I / α / r*` 当本机常数（喷口数、质量、力臂全变）。
> 全局共享常数（G=11、d=0.09、`K_NOZ=524.8`、锥角 15°/160°/s、20 Hz 写预算）不在这里复制：
> 见 `flight-program-standard.md` §4/§3。
> 两发 21 条黑名单**全部仍然适用**：`memo/archive/two-nozzle-constants.md` §5；本文件只记四发新增的坑。
> 计划/进度 → `four-nozzle-plan.md`；换人/收尾 → `four-nozzle-handover.md`；历史证据 → `memo/archive/two-nozzle-*.md`。

## 1. 硬件事实（已确认）

| 项 | 值 | 来源 |
|---|---|---|
| 喷口布局 | **矩形四角，4 台 `ccpe:advanced_thrust_nozzle`，全部朝下** | 用户 2026-11-21 |
| 频道 | **ch0 / ch1 / ch2 / ch3**（`ss.getPeripheral(0..3)`） | 电脑 2 `startup.lua` |
| 出力反向 | **ch0/ch1 `inv=false`；ch2/ch3 `inv=true`**（动力接入方向相反，两台必须反向才都朝上出力） | 同上 + 用户口述 |
| 安装朝向 | 四台 FACING 相同 ⇒ `setDirection(x,y)`→机体系映射**全机一套**；符号仍要实测一次（黑名单 #22） | 用户口述 |
| 飞控电脑 | `New World` 电脑 2（`run/saves/New World/computercraft/computer/2/`） | 用户 |
| 施力点 API | `getForcePoint()` → 机体系 `{x,y,z}`（方块中心，相对物理体原点；原点=质心）；**`mainThread=true`** | `propulsion/advanced-thrust-nozzle.md` §6.1 |
| 单台满推力（模型） | `K_NOZ·p = 524.8·p` pN（`K_NOZ=256×2.05`，含矢量喷管 2 pN/RPM） | `flight-program-standard.md` §4 |
| 锥角 / 偏转速率 / 出力变化率 | 15° / 160°/s / **0.25 每 tick**（`THRUST_SCALE_PER_TICK`，0→1 四 tick；小 Δu 一 tick 到位 ⇒ 小信号无滞后） | propulsion §6 + `AdvancedThrustNozzleBlockEntity:248` |
| 传感器 | **只有一台 AIC**（= INS + FMC 双登记；无皮托管、无静压孔）；**本仓所有载具统一如此** | 用户 2026-11-21 + `static` 的 `p=…(alt)` |
| 其他外设 | **ch4 有一个非喷口外设**（`m4probe id` 报 `other`，未识别；不影响飞控） | 15:47 `status_m4_id.txt` |

## 2. P0 实测（2026-11-21 15:47，`status_m4_*.txt`）

| 量 | 值 | 备注 |
|---|---|---|
| 质量 `m` | **21.00 kg** | `static`；四发比两发重得多（喷口 + 框架） |
| 重心 ↔ AIC | AIC 在重心上方 **2.52**（`CGrel=(0,−2.52,0)`）；电脑在 AIC 下方 1.00 ⇒ 电脑在重心上方 **1.52** | `geom` |
| 气压 `p` / 高度 | **p=0.9822** @ AIC y=**67.50**（`getPressureFromAltitude`） | `static`；无静压孔 |
| 测试场 | 物理体原点世界 **( −93.70, +64.98, +7.49 )**；静止 yaw **−90.56°** | `geom` / `static` |
| `TWR(p)` / `u_hover` | **8.93 / 0.1120**（`4·524.8·0.9822 = 2061.8` pN） | `static` |
| **P1 基线（16:01，`m4cal base`）** | `RES BIAS`（阻尼关 0.5 s）：`wx=+0.000`、`wy=+0.000`、`wz=−0.010` rad/s² ⇒ **四台推力配平极好**；`RES DAMP`：`w_hover_b=0.054` rad/s；落地 `vy=−0.50 m/s`、`h_end=65.00`（+0.02）；水平漂 `vx=0.07 / vz=0.13` ⇒ 几乎原地；**下降段（DOWN）速率涨到 0.54 rad/s**（无姿态环的气动自激，两发黑名单 #9 再次出现） | `m4cal_1795248090203.csv` + `status_m4cal.txt` |
| **P2 v1（16:07，作废）** | 用 `getAngularVelocity` 测：pitch `g1=−0.99 / g2=−0.65 / g3=−0.25`、roll `g1=−10.6 / g2=−7.1 / g3=−2.4`（三段互不自洽，`TRIM` 反而加速到 1.0 rad/s）⇒ 传感器在机动中不可信，**数值全部作废**，见黑名单 #30 | `m4axis_pitch_1795248455796.csv` / `m4axis_roll_1795248471963.csv` |
| 引擎 | **2048 / 8192 SU**（u=0 时）、**108.7 °C**、`levitite_blend` mix/eff 0.90 | `power` |
| `ss` 应力读数 | `getStressRemaining/Capacity` = **nil**（门控未满足）⇒ `power` 已回退用引擎的 used/cap | `power` |
| 四台施力点 `r_i` | **ch0 (−1.00, −1.48, +2.00) `inv=false`（后）**；**ch1 (+1.00, −1.48, +2.00) `inv=false`（后）**；**ch2 (−1.00, −1.48, −2.00) `inv=true`（前）**；**ch3 (+1.00, −1.48, −2.00) `inv=true`（前）** | 15:56 `geom`；机头 −Z ⇒ **ch2/ch3 = 前、ch0/ch1 = 后** |
| 矩形 | 半宽 `a`=**1.00**、半长 `b`=**2.00**、共面散差 **0.00**；形心 **(0,−1.48,0)**（x、z 居中） | `geom` ✅ |
| 分配系数 | `Σz²=16`、`Σx²=4`、`ΣL²=20`；俯仰系数：后 **+0.125** / 前 **−0.125**；滚转：`x=−1` **−0.250**、`x=+1` **+0.250** | `geom`；同一 δu 下**滚转力矩 = 俯仰的 1/4**（臂 1.00 vs 2.00） |
| 偏航切向（+τ_y） | ch0 `(Fx+0.89, Fz+0.45)`、ch1 `(+0.89, −0.45)`、ch2 `(−0.89, +0.45)`、ch3 `(−0.89, −0.45)`（机体系单位向量） | `geom`；`setDirection` 正负号 P4 实测 |

## 3. 控制通道（P1~P4 后逐行填，公式见 `four-nozzle-plan.md` §2）

| 通道 | 手段 | 实测值 | 状态 |
|---|---|---|---|
| 总距 | 四台同增 | u_hover≈**0.112** 悬停成立；竖环 +1.5 m 稳到 ±0.05 m | ✅ P1 |
| 俯仰 | 前/后推力差 | 残余角加速度 **+0.000 rad/s²**（P1 阻尼关）；**P2 v1 用 `getAngularVelocity` 测出负增益且三段不自洽（传感器问题），v2 改用 `getAngleRates` 待上机** | ⚠️ P2 待复测 |
| 滚转 | 左/右推力差 | 残余角加速度 **−0.010 rad/s²**；同上是 v2 待复测 | ⚠️ P2 待复测 |
| 偏航 | 四台切向锥角 | 待 P3 | — |
| 横移 X/Z | 四台共模锥角 | 待 P4 | 符号未知 |

## 4. 四发新增黑名单

| # | 念头 | 实际后果 / 正确做法 |
|---|---|---|
| 22 | 从 FACING 几何推出 `setDirection(+x)` 的受力符号 | 两发实测**与推导反号**（archive #14），四发仍要一次实测；符号未验证前不许开横向/偏航闭环 |
| 23 | 在 20 Hz 环里调 `getForcePoint()` / `getSensors()` | 它们是 `mainThread=true`，每次调用占一个写块（约 1 tick）；**初始化读一次缓存** |
| 24 | 改装机体后继续用旧 `r_i` | 原点=质心，加/删方块会整体漂移；改装后必须重跑 P0 |
| 25 | 用两发的 `m=11.00 / I_pitch=65 / α/D=−31.7` 直接当四发常数 | 喷口数、质量、惯量、力臂全变；一律以本表 P0/P2 实测为准 |
| 26 | 以为四台等推力就没有配平问题 | 风扇效率/应力接入不同 ⇒ 残余 `τ_x, τ_z`；四发的 4 个 `δu` 可以解 3 个方程（最小范数），但**必须先量** |
| 27 | 把「矩形四角」当成已对称 | 必须用 P0 的形心与半宽验证；不居中 ⇒ 共模锥角带力矩、偏航/平移不再解耦 |
| 28 | 改完 Java 只跑 `gradlew classes` / IDE 编译，不重启客户端 | 运行时仍是旧类：新 `@LuaFunction`（如 `getForcePoint`）在 Lua 里直接「不存在」（`no-api`）。判据：`build/classes/java/main/**` 的 mtime **晚于** JVM 启动时间 ⇒ 必是旧类；必须完整退出并重启客户端 |
| 29 | 单人游戏失焦/暂停时跑带 `mainThread` 调用的程序 | 主线程停摆 ⇒ `getPeripheral/getForcePoint/set*` 全部排队；CC watchdog 约 3 s 后 `Terminating computer #N due to timeout`，shutdown 还会卡在 `EngineCoreBlockEntity$Peripheral.detach → setChanged → getChunk` 的同步加块上。**保持窗口聚焦、世界未暂停再跑**（2026-11-21 15:46 实测） |
| 30 | **用 `getAngularVelocity()` 做脉冲标定或阻尼** | 源码注释（`SensorSystemAPI:812`）明确：Sable pose 差分的 ω 在机动中被世界旋转轴投影污染（`体 Z ≠ 滚转速率`），与真实角速度偏差可达 **0.5+ rad/s**。2026-11-21 P2 v1 实测：两轴增益都为负、三段 `a1/a2/a3` 互不自洽、TRIM 反而加速。**一律用 `getAngleRates()`**（deg/s，角度差分 + EMA 0.09 s），同时把 `getAngles()` 写进 CSV 交叉验证 |

## 5. 程序与版本表

| 文件 | 版本 | 会动? | 作用 |
|---|---|---|---|
| `run/.../computer/2/m4probe.lua` | **v1.1** | 否（只读） | `geom`（四台力臂 + 矩形校验 + 分配系数）、`static`、`id`、`power`；v1.1 修 `power` 的 DIAG 作用域 bug、应力读数回退引擎 |
| `m4cal.lua` | **v1.1** | 是 | P1 基线：竖直 PD（+1.5 m、−1.0 m/s 着陆）+ 推力差速率阻尼（`KD=0.4`，**不碰锥角**）；`HOVER_A` 量偏置、`HOVER_B` 验阻尼；输出 `status_m4cal.txt` + `m4cal_<epoch>.csv` |
| `m4axis.lua` | **v2** | 是 | P2：`pitch`/`roll` 对称三段脉冲（+C/−2C/+C，0.35 s）+ **`getAngles`/`getAngleRates` 信号**（v1 的 `getAngularVelocity` 已否决）+ 自动符号 `g`（三段的均值）+ `c_trim` + 0.8 s 残差复验；CSV 含 `pitch/roll/yaw/pr/rr/yr`；`C_pitch=0.10`、`C_roll=0.06` |
| `m4hover.lua` | 未写 | 是 | P5：三轴姿态 + 高度，再上位置/航向 |
| `computer/2/startup.lua` | 用户脚本 | — | 引擎油门 1.0；四台 `setDirection(0,0)`、`setThrustScale(0)`、ch2/ch3 `inv=true` |

日志：`/log/status_m4_<mode>.txt`（探针）；后续 CSV 放 `/log/m4*_<epoch>.csv`。
旧程序归档：`cc_logs_archive/lua_versions/computer1/`（两发时代，只读）。
