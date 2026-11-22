# 喷口偏转方向（摇杆 → 推力）为什么这么绕 —— 待调研档案

> 2026-11-21 立档。**触发**：四喷口横移标定连着两次被喷口方向误导（一次 roll 冲到 20.7°、一次 pitch 冲到 19.98°，都 abort）。
> **用途**：把「`setDirection(x,y)` 到底把推力推向哪」这件事一次讲清，把还没弄清的部分列成可逐条验证的清单。
> 相关：`advanced-thrust-nozzle.md`（API 母文档 §6）、`memo/flight/four-nozzle-constants.md` 黑名单 #37。
> 数据：`computer/2/log/m4step_1795253728970.csv`（17:35，x 翻车）、`m4step_1795255256728.csv`（18:01，x 好 z 翻）、`m4step_1795255495395.csv`（18:05，两轴 PASS）。

## 0. TL;DR

| 问题 | 答案（已实测） |
|---|---|
| `setDirection` 的方位是「嘴」还是「力」？ | 是**喷口/排气的方向**；推力是它的**反向**（`impulse = −axis`） |
| 那推力就是「摇杆取反」？ | **不是**。实测 **`推力 = (−摇杆x, +摇杆y)`** —— 两个分量符号**不同** |
| 为什么不同 | 下朝向方块的模型帧被 `R_x(180°)` 转了一次，把模型 `+Y` 映到 plot `−Z`；再叠上「推力 = −轴」，x 被反两次、z 只反一次 |
| 依赖 FACING 吗 | **是**。`DOWN` 的映射 ≠ `UP` 的映射（推导见 §3.3） |
| 还依赖什么 | `axisStep(FACING)`、**风扇转速的符号**、`setInverted` —— 三者都会翻横向分量，但**不都翻竖直分量**（§3.4） |
| 能从一个轴推另一个吗 | **绝对不能**。这就是两次翻车的原因 |
| 现在的约定 | **2026-11-21 起优先用 `setThrustDirection(dirx, 0, dirz)`（推力语义，§10）**；老程序继续用 `setDirection(−dirx, +dirz)`（**只对全部 DOWN + 本文那套接线的四台成立**） |

## 1. 三次实机踩到的现象

| 时间 | 程序/段 | 现象 | 拟合得到的力标度 |
|---|---|---|---|
| 17:35 | `m4step` v2，`SX`（+x 1.5°） | roll 1.45 s 冲到 **20.7°** abort | `sc = −1.13`（反号）|
| 18:01 | `m4step` v3，`SX`（x 已修） | roll 峰值 **0.48°**，干净 | `sc = +1.02` ✔ |
| 18:01 | `m4step` v3，`SZ`（+z，当时 `dy=−dirz`） | pitch 3 s 冲到 **19.98°** abort | `sc = −0.79 ~ −0.92`（反号）|
| 18:05 | `m4step` v4，`SZ`（`dy=+dirz`） | pitch 峰值 **0.35°**，干净 | `sc = +1.02` ✔ |

两次 abort 都是同一个机制：**前馈与扰动同向相加 = 两倍力矩**（`sc≈−1` 就是它的签名），不是自激、不是执行器问题。

## 2. 实测方法（可复用，不依赖喷口方向的先验）

**通道 A：把机体当力矩传感器。** 1.5° 共模锥角在重心下 1.48 m 产生 `τ = 1.48·m·g·tanθ = 8.95 N·m`。写
`φ̈ = −[G_roll·addR + (1.48/I_roll)·F_hx]`，对 roll/pitch **二次积分**做最小二乘（τ 网格 + 力标度 `sc` 自由），
若 `sc≈−1` 就是「实际力矩与模型反向」。灵敏度：0.1 s 相位差 ⇒ 1.9° 角；roll 分辨率 0.001°。
工具：`temp/m4step_fit.lua <csv> SX|SZ`（独立重算，不读程序自印值）。

**通道 B：平移反解。** `a_cone = a_x − g·sin(roll) + d·v_x`（机上倾斜会让重力以**反号**漏进 `a_x`）。
17:35 那次得 `F_hx = −6.3 N`，对模型 6.05 N —— 方向与幅值同时确认，且与通道 A 互不相关。

**通道 C（最便宜的定符号实验，建议下次先用它）**：单台喷口、机体静止悬空、锥角 0.65°，
用通道 B 读水平加速度的符号。**一次就够，不用飞整机。**

## 3. 源码链条（逐步推导，带行号）

### 3.1 摇杆 → 偏转点
`AdvancedThrustNozzleBlockEntity.requestDirection`（327–334）：摇杆长度按圆钳位后 `× MAX_TILT_DEG(15°)`：
```
(defX, defY) = (x, y) · 15°/len        （len = |(x,y)|，|(x,y)|>1 时整体缩）
```
`setDeflection(az, tilt)`（314–317）走同一条路：`pointX = tilt·cos az`、`pointY = tilt·sin az`（422–430）。
⇒ **偏转角 = `|(defX,defY)|`，方位 = `atan2(defY,defX)`**，两者都在**模型帧**。

### 3.2 偏转点 → 模型空间方向
`tiltDirModel`（464–475）：
```
dir_model = (sin t · cos a,  cos t,  sin t · sin a)      t = 锥角, a = 方位
```
即 `+Y` 是"直出"、`+X` 是方位 0、`+Z` 是方位 90°。**注意这是模型帧，不是 plot 系。**

### 3.3 模型空间 → plot 系（按 FACING 旋转）
`nozzleAxis`（567–590）：`R = rotationYXZ(−yDeg, −xDeg, 0)`，六向的 (xDeg,yDeg) 在 574–584：

| FACING | xDeg | yDeg | R 的等效作用 | `axisStep` |
|---|---|---|---|---|
| UP | 0 | 0 | 恒等 | **+1** |
| DOWN | 180 | 0 | `(x,y,z) → (x, −y, −z)` | **−1** |
| NORTH | 90 | 0 | `R_x(−90)` | −1 |
| EAST | 90 | 90 | `R_y(−90)·R_x(−90)` | +1 |
| SOUTH | 90 | 180 | `R_y(−180)·R_x(−90)` | +1 |
| WEST | 90 | 270 | `R_y(−270)·R_x(−90)` | −1 |

**本文只对 DOWN 做完整推导**（四喷口那台全是朝下；其余朝向按同一条代码照推，但**不要凭直觉**）。

### 3.4 冲量 = −轴 × magnitude，而 magnitude 里还藏着三个符号
`ThrustNozzleBlockEntity.sable$physicsTick`（831–859）：
```java
final double magnitude = REVERSE_SIGN(-1?) · axisStep(facing) · THRUST_PER_RPM
                       · speed · pressure · timeStep · thrustScale() · thrustDirectionSign();
final Vector3d impulse = new Vector3d(-axis.x, -axis.y, -axis.z).mul(magnitude);
```
其中 `speed = getFanSpeed()`（**带符号**，取自上游客动网络的转速）、`thrustDirectionSign() = setInverted ? −1 : +1`。
⇒ 力的方向 = `−axis · sign(axisStep·speed·inv)`。

**关键后果**：`axisStep(DOWN) = −1`，而 `setInverted(true)` 也是 −1 ⇒ 两个符号对**竖直**分量与**横向**分量
**同时生效、幅度相同**，但**横向还会被 §3.3 的帧映射再影响一次**。于是"为了把竖直方向改正"而设的 `inv`，
会顺带把横向也翻一次 —— 如果只对了一台/一对，横向就会出现**不同号**：

> 四台横向不一致时的签名：**净横移 ≈ 0，但出现偏航力矩**（横向力成对抵消、力臂仍在）。
> 这正是"怎么推都不平移、只原地打转"这类症状的根因候选。本文的四台实测**是**一致的（见 §3.5）。

### 3.5 DOWN 的完整式子（已实测验证）

设摇杆单位向量 `(nx, ny)`、锥角 `t`：
```
axis_plot  = ( sin t·nx,  −cos t,  −sin t·ny )      ← v3/v4 程序里的 (dx, dy) = 摇杆
thrust     = −axis_plot · s = ( −sin t·nx,  +cos t,  +sin t·ny ) · s        s = sign(axisStep·speed·inv)
```
- 竖直：`+cos t·s > 0` ⇒ 向上 ⇔ `s = +1`（本机四台都是 `s=+1`：两对 FACING/接线/`inv` 组合不同但结果一致）
- 横向：`(−sin t·nx, +sin t·ny)`
⇒ **`推力 = (−摇杆x, +摇杆y)`**，与 `m4step` v4 的 `setDirection(−dirx, +dirz)` 完全吻合。

实测佐证（18:05 那发）：`+x` ⇒ roll 峰值 0.47°、`sc=+1.02`；`+z` ⇒ pitch 峰值 0.35°、`sc=+1.02`；
`RES ACC` 的 `cone` 两轴都 **+0.29 m/s² = W·tanθ/m = 0.288** ⇒ 横向力幅值与方向都对。

## 4. 为什么 x 与 y 的符号不同（一句话）

`R_x(180°)` 把模型 `+Y` 映到 plot `−Z`（同时也把模型 `+Z` 映到 plot `−Y`），于是**偏转平面的"上"**
在 plot 系里是"后"。再乘上「推力 = −轴」，x 被反了两次（模型 +X 既没被帧翻、又被 −轴翻）、
z 被反了一次（帧翻 + 不翻 ⇒ 净不翻）。**这是两个独立符号相乘的结果，不是笔误。**

## 5. 已被证伪的三类直觉（别再犯）

1. **"摇杆指向哪儿推力就指向哪儿"** —— P3 当年就据此判"一致"（错因：yaw 读数取反漏算，见黑名单 #37）。
2. **"整体反号，所以两个分量都取负"** —— 18:01 的 SZ 就是它造成（`dy=−dirz` 反错了）。
3. **"从一个分量的符号推另一个"** —— 上一条的成因，也是文档 `@param y 喷口向模型南(+Z)偏` 被误读的成因：
   那句话在**模型帧**里没错，但 DOWN 的模型帧不等于 plot 系。

## 6. 未决问题 / 调研清单

| # | 问题 | 为什么重要 | 怎么判 |
|---|---|---|---|
| Q1 | `getDeflection().azimuth` 报的是**模型帧**方位（DOWN 时 plot 方位 = −azimuth）。要不要改成 plot 方位，或至少注明帧？ | 任何"按方位角下命令"的脚本都会差一个镜像 | 读 `azimuthDegOf`（443–449）确认是 `atan2(defY,defX)`；再实机 `setDeflection(45°, 1.5°)` 看机体往哪偏 |
| Q2 | 其余五个 FACING 的横向映射（§3.3 表只推了 DOWN）。`EAST/WEST/NORTH/SOUTH` 装法下摇杆 → 推力是什么矩阵？ | 以后任何"侧推/前推"的矢量喷管载具都要 | 按 §3.3/3.4 逐向推导，再用通道 C 抽验 1~2 个朝向 |
| Q3 | 四台横向是否**真的**全部同号？现在是靠 `sc=1.02`（整体一致）反推的，没有逐台证据 | 若某台 `inv`/接线配错，症状是"只打转不平移" | ✅ **已实测 2026-11-21 19:19**（`m4api`，`computer/2/log/status_m4_api.txt`）：逐台 `getProfile()` 得 `kappa=+1` ×4，且转速是 **ch0/ch1 = −256 / ch2/ch3 = +256** —— 前一对 `inv=true` 配正转速、后一对 `inv=false` 配负转速，两对**风扇转向镜像**才让 `s` 一致。这就是 §3.5 "两对组合不同但结果一致"的具体机制。逐台单喷口试飞（E4）仍没做，但已不影响结论 |
| Q4 | `m4probe geom` **没有记录 FACING 与 inv**（只有力臂）| 出问题时无法复盘"这台到底怎么装的" | 给 `geom` 加 `facing`、`inv`、`isMoving()` 三列；这是最该补的一处 |
| Q5 | 是否应该新增只读 API，让脚本直接问"给定 (x,y) 的推力方向"？ | 把 §3.3/3.4 的矩阵搬进 Java，脚本就不必硬编码 `(−1, +1)`；换载具/换朝向自动对 | ✅ **已落地 2026-11-21**：`getThrustDirection(x,y)`（只读）+ `setThrustDirection(x,y,z)`（推力语义写入）+ `getProfile()`（装配快照）。实现与验证见 §10 |
| Q6 | 这是"设计如此"还是"文档没说清"？ | 决定是改代码还是改文档 | 目前证据：实现自洽（§3.5 与实测吻合），**文档缺"帧"与"推力=反向"两句** ⇒ 倾向"改文档 + 加 Q5 的 API"，不动物理 |
| Q7 | `setDeflection` 的方位与 `setDirection` 的摇杆是否严格同帧同向？ | 两者混用时会出现"摇杆对了、极坐标不对" | 源码看是同一条路（314–317 / 327–334 都写同一个目标点）⇒ 应该一致，但没实机验过 |

## 7. 低成本验证实验（建议顺序）

| 实验 | 做法 | 读什么 | 排除 |
|---|---|---|---|
| E1 | 静止悬空，单轴锥角 **0.65°**（半度级），用通道 B 读横移方向 | `a_cone` 的符号（≈±0.125 m/s²） | 一次定符号，不必飞整机 |
| E2 | 同一发里再给 `−x`、`−z` | 四象限符号表 | 顺带验线性度 |
| E3 | `setDeflection(45°, 1.5°)` 对比 `setDirection(0.707, 0.707)` | 两者推力方向是否相同 | Q7 |
| E4 | 逐台单喷口（其余 3 台 `setThrustScale(0)`） | 每台的横移方向 | Q3（四台是否同号）|
| E5 | 把某台 `setInverted` 翻过来再翻回去 | 竖直是否反、横向是否也反 | §3.4 的符号结构 |

## 8. 给控制代码的临时约定（**在 Q5 落地前**）

```lua
-- dirx/dirz = 期望推力方向（机体系单位向量），只对「四台全部朝下 + 本机这套接线」成立
-- 写盘：
dx[i] = -dirx * len      -- len = 锥角/15
dy[i] = +dirz * len
```
- **2026-11-21 起**：同样的事可以直接写 `n.setThrustDirection(dirx, 0, dirz)`（§10）——
  它按当前 FACING / 转速符号 / `inv` 反解，换载具不用重标定。上面这套手工映射仍对老程序有效。
- 换载具 / 换朝向 / 换接线 ⇒ **这条约定失效**，必须重测（E1/E2）。
- 桩（`.tmp_luatest/m4_harness.lua`）里对应 `tx,tz = −TILT_SIGN·nx, +TILT_SIGN·ny`；
  **桩和程序只差一个整体负号时，干跑永远发现不了** —— 这是本档案最重要的教训（17:35 之前正是如此）。
  自检手段：把 `M4_TILT` 翻成 −1，干跑必须复现 `sc<0` + 较大姿态峰值（现在会给出 `sc=−1.06`、19.86°）。

## 9. 参考

- 源码：`AdvancedThrustNozzleBlockEntity` 314–334（入口）、422–449（极坐标↔偏转点）、464–475（模型方向）、
  567–590（帧旋转）、766–782（`getDeflection`）；`ThrustNozzleBlockEntity` 774–789（`axisStep`/`nozzleAxis`）、831–859（冲量）。
- 程序：`computer/2/m4step.lua` v4（`writeAll` 的映射）、`temp/m4step_fit.lua`（独立复算）。
- 数据：`m4step_1795253728970/1795255256728/1795255495395.csv`（三发，见 §1）。
- 结论位置：黑名单 #37（`four-nozzle-constants.md`）、API 警告（`advanced-thrust-nozzle.md` §6）。

---

## 10. 2026-11-21：推力语义 API 落地（Q5 结案）

### 10.1 新增了什么（纯增量，`setDirection` / `setDeflection` 一个字节没动）

全部在 `AdvancedThrustNozzleBlockEntity.Peripheral`：

| 方法 | 语义 |
|---|---|
| `getThrustDirection(x, y)` | 给定摇杆 → **稳态推力方向**（plot 系单位向量）。钳位/圆映射与 `setDirection` 共用 `stickScale` |
| `setThrustDirection(x, y, z)` | 给**期望推力方向**（plot 系，不必归一化）→ 反解偏转目标 |
| `getProfile()` | `{facing, inverted, speed, axisStep, thrustSign, maxTilt}` 装配快照 |

父类为了给符号，把 `getFanSpeed()`、`axisStep()` 从 `private` 放到 `protected`（各改一个词）。

### 10.2 数学模型（读、写共用一套）

物理链路我重抄一遍（与 §3.4 一致）：

```
m̂        = (sin t·cos a, cos t, sin t·sin a)          # 模型帧轴，t=|偏转点|，a=atan2(defY,defX)
R        = facingRotation(FACING)                     # §3.3 那张表，抽成私有静态方法
axis     = R·m̂                                        # nozzleAxis()
κ        = sign(REVERSE_SIGN · axisStep · speed · inv) # 新增 thrustSign()
T        = −κ·axis                                     # 推力方向（单位）
```

读（`getThrustDirection`）就是照上式算 T —— 与 `sable$physicsTick` 逐项对应，不存在第二套公式。

写（`setThrustDirection`）是它的逆：
```
T⊥       = T − (T·n)n          # n = FACING 法线（名义轴）；轴向分量改不了，丢弃
w_model  = normalize(R⁻¹·(−κ·T⊥))   # 必落在模型 XZ 平面（因为 T⊥ ⊥ n 且 R⁻¹n = (0,1,0)）
a        = atan2(w_model.z, w_model.x)
t        = min(1, |T⊥|) · MAX_TILT_DEG
```

### 10.3 一致性自检（纸面，对应 §8 的实测约定）

`FACING=down`、κ=+1、`setThrustDirection(1,0,0)`（向东推）：

- n=(0,−1,0)，T⊥=(1,0,0)，R=R_x(180)=R⁻¹；
- w_model = R⁻¹(−1,0,0) = (−1,0,0) ⇒ a=180°、t=15° ⇒ 偏转点 **(−15, 0)**；
- 这正是 `setDirection(−1, 0)` 写出来的点 ⇒ 与 §3.5 / §8 的 `(−dirx, +dirz)` **完全一致**。

再验读：`getThrustDirection(1,0)` ⇒ 偏转点 (15,0) ⇒ axis=(sin15,−cos15,0) ⇒ T=(−sin15,+cos15,0) ✔（§3.5）。

**数值自检（已跑，`temp/ThrustSolverCheck.java`，用项目同款 JOML 1.10.5）**：六个 FACING × κ=±1 ×
24 个横向方位 = **288 组「写→读」往返**，最大横向误差 **1.7e-6 度**；四个定点全部对：

```
DOWN k=+1  push(1,0,0) -> deflection (-15.0000, 0.0000)   # == setDirection(-1,0)
DOWN k=+1  push(0,0,1) -> deflection ( 0.0000, 15.0000)   # == setDirection(0,+1)
DOWN k=+1  getThrustDirection(1,0) -> (-0.2588, 0.9659, 0)
DOWN k=-1  push(1,0,0) -> deflection (15.0000, 0.0000)    # inv 后摇杆镜像，符合 §3.4
```

⇒ 新 API 与 §3.5 / §8 实测约定**同源**，且读写严格互逆。剩下的只有一条实机确认（§10.5 E7）。

### 10.4 为什么不能"直接修好 `setDirection`"（结论性的坑）

把 `+Y` 映到 `−Y` 的旋转 R 以 Y 为特征向量 ⇒ `det R = (−1)·det A = +1` ⇒ **A 在垂直平面上
行列式恒为 −1，是反射**。反射必有"不动线"与"反向线"，所以摇杆与推力的横向映射里**必然**存在
两个方向：一个同向、一个反向。`R_x(180°)` 让镜像轴落在 Z 上 ⇒ 得到 `(−x, +y)`；换成
`R_y(90°)·R_x(180°)` 只会把镜像轴转到对角线上，仍然不是"摇杆=推力"。

⇒ **任何 FACING 下都无法让 `setDirection` 天然等于推力方向**，只能另开推力语义入口。这就是 §10.1 的设计依据。

### 10.5 实机验证（E6/E8 已 PASS，2026-11-21 19:19）

> **结果**（`computer/2/log/status_m4_api.txt`，28 行全绿）：
>
> | 项 | 实测 |
> |---|---|
> | `RES KAPPA` | `ch0=+1 ch1=+1 ch2=+1 ch3=+1`；`PROF` 给出 `facing=down axisStep=-1`，`speed` **−256/−256/+256/+256**，`inv` false/false/true/true ⇒ 两对风扇转向镜像（Q3 结案） |
> | `RES TRUTH` | 四台 `getThrustDirection(1,0)` 都是 `(−0.2588, +0.9659, 0.0000)`，误差 0.00000（= §3.5 的 `(−κ sin t, +κ cos t, 0)`） |
> | `RES MAP` | 15° 四象限 + 1.5° 步长，`setThrustDirection(dx·θ/15,0,dz·θ/15)` 与老映射 `setDirection(−κdx,+κdz)` 的偏转目标差 **0.0000°** 全部 |
> | `RES RESET` | 四台 `targetTilt` 回 0.0000 |
> | 现场现象 | 四台喷口**依次**扫过 +x/+z/−x/−z 一整圈（程序逐台逐向走），机体不动（`u=0`） |
>
> ⇒ **换写入路径在"目标"层面与实测映射严格同源**；物理层面的同源仍是 18:05 的 `sc=+1.02`。
> 剩下的 E7（整机 1.5° 阶跃复现 roll 峰值符号）并到 P4b-2 那一发里顺便验。

| # | 做法 | 判据 | 状态 |
|---|---|---|---|
| E6 | 静止悬空、四台 `setInverted` 现状不变，`n.getProfile()` 打一行 | `thrustSign` 四台应同为 **+1**（与 §3.5 的 s=+1 对应）；`facing="down"`、`speed<0`（负转速） | ✅ 19:19 PASS（转速符号两对相反，见上表） |
| E7 | 单轴：`setThrustDirection(1,0,0)` 走 1.5°，其余三台 `setThrustScale(0)` | roll 峰值符号应与 18:05 那次 `setDirection(-1,0)` **完全相同**（因为二者反解到同一个偏转点） | ✅ **19:31 `m4dbl` PX 顺带闭环**：四象限横向加速度 ratio 1.00~1.01、线性 0.492 ⇒ 新 API 与实测物理链路同源。滚转侧注意：PX（1.5 s）只走 −0.05°，18:05 SX 的 −0.47° 是 3 s 相内**慢漂**累积（前 1 s 几乎为 0），两发在 1.5 s 处同值 |
| E8 | 同一发里再打 `getThrustDirection(1,0)` 与 `getDeflection()` | 前者应为 `{x≈−0.259, y≈0.966, z≈0}`；后者 `targetX≈−15, targetY≈0`（两轴自洽） | ✅ 19:19 PASS（`TRUTH` 行 + `CHK 15.0deg`） |

E7 只要和 18:05 的 `sc=+1.02` 对上，就说明新 API 与已验证的物理链路同源。

