# 两喷口飞行器 · 实测证据与推导（归档总账）

> 本文件只放**原始证据与推导过程**；结论/常数一律在 `two-nozzle-constants.md`，进度在 `two-nozzle-ROUND.md`。
> 数据来源：`run/saves/New World/computercraft/computer/1/log/`（`status_*` 与 `m2dyn_*.csv`）。
> 纪律：原始 CSV 不进对话，只贴 `tools/digest_csv.ps1` 的 ≤30 行摘要或定向抽样。

---

## §1 只读探针（2026-11-20 22:54 / 23:01，`m2probe static` + `power` + `id`）

```
BANNER m2probe v5 static  (read-only)
MASS   m=11.25 kg  CGrel=+0.00,-1.64,+0.98  FMC=yes
AIR    p=0.9811 (alt)  h=67.76 m
ATT    pit=+0.53 rol=-1.27 yaw=+160.68 deg  up=1.000
OMEGA  wx=-0.000 wy=+0.000 wz=-0.000 rad/s (body)
VEL    vx=+0.00 vy=+0.00 vz=+0.00 world m/s
THRUST Fmax=1029.8 pN  TWR=8.32  u_hover=0.1202
NOZ ch0  u=1.00 inv=false tilt=0.0/0.0 ch=0
NOZ ch1  u=1.00 inv=true  tilt=0.0/0.0 ch=1
```

```
BANNER m2probe v5 power  (read-only, nothing is written)
ENGINE getEngine thr=1.00 rev=false temp=119.5C warm=false hot=false
LOAD   load=0.125 used=1024 cap=8192 SU
FUEL   type=fluid aeronautics:levitite_blend mix=0.90 eff=0.90
NET    FMC remain=7168 cap=8192 SU   p=0.9861(alt)
NOZ    ch0 u=0.00 inv=false  ch1 u=0.00 inv=true
DIAG   network is loaded (fans should spin)
```

```
BANNER m2probe v5 id  (read-only, nothing is written)
BODY   on=true id=d70bee81 free=974KB
SENS   angles=table omega=table vel=table press=nil
GATE   hasSpeedSensor=nil hasPressureSensor=nil
BLOCKS speed=0 pressure=0  (getSensors)
CH  0 nozzle   u=0.00 inv=false tilt=0.0/0.0 ch=0
CH  1 nozzle   u=0.00 inv=true  tilt=0.0/0.0 ch=1
CH  3 other
MASS   m=11.25 kg  CGrel=+0.00,-1.64,+0.98
```

**读出来的事实**

- 机体上**只有一台 AIC**（没有任何 `speed_sensor` / `pressure_sensor` 登记），但 `angles/omega/vel` 全是 `table` ⇒ **速度读数是真的**。
  依据（源码）：`SensorSystemAPI` 里 `velocityAvailable / angularVelocityAvailable / orientationAvailable /
  insPositionAvailable / bodyPosAvailable` **全部由 `attitudeGate`（ATTITUDE=INS）门控**；
  `BodySensorRegistry` 第 90 行 `AicBlockEntity → [ATTITUDE, FMC]`。
  ⇒ `my_sensor_system.md` 蓝图里"速度类需速度传感器"是**未落地的原始设计**，实现并非如此。
- 气压 `nil` ⇒ 无静压孔；`p` 由 `getPressureFromAltitude(y)` 兜底（FMC 门控，AIC 满足）。
- 推力链正常：`used=1024 SU`（两台风扇各 ~512）、燃料 levitite blend、不过热不暖机。
  ⇒ 前一轮"u=1 却不飞"的猜测（RPM=0）**错误**，真实原因是**物理手杖把机体钉住了**。

## §2 第一次动态测试（2026-11-20 23:19，`m2dyn v1`，两次运行几乎一致）

```
BANNER m2dyn v1  cap u<=0.30  free=966KB
MODE   A=ch0 B=ch1 m=11.25 p=0.986(alt) u_hover=0.120 D=0.005
VERDICT FAIL  COS_MIN up=0.48  cyc=73 dt=150ms sl=40
RES    LIFT dH=+1.46 vy=+1.99
RES    HOLD dH=+0.87 vy=+0.13 wx=+0.032 (x bias -> free axis)
RES    a/D: DPOS=+12 DNEG=-11 DPOS2=+7 rad/s2/D (wz)
RES    cross rr/D=-0 / -0  wx=+0.132
RES    I est=180.0 kg m2   (I = 2*b*K*p/(a/D), b=2.0)
RES    LAND vy=-2.05 h=66.8
```

`digest_csv.ps1` 摘要（第 2 次 run，`m2dyn_1795187992903.csv`）：

```
| phase | n | dtMd | h | vy | wx | wz | ux | d |
| LIFT | 30 | 0.050 | 66.50..67.96 | 0..1.99 | -0.0028..0.075 | -0.0083..0.036 | 0..0.027 | 0 |
| HOLD | 15 | 0.100 | 68.06..69.28 | 0.131..2.17 | 0.076..0.121 | 0.037..0.057 | 0.028..0.095 | 0 |
| DPOS | 3 | 0.100 | 69.22..69.27 | -0.083..0.055 | 0.123..0.149 | 0.058..0.069 | 0.101..0.113 | 0.005 |
| DNEG | 6 | 0.100 | 68.89..69.18 | -0.464..-0.146 | 0.120..0.204 | 0.054..0.094 | 0.121..0.159 | -0.005 |
| DPOS2| 4 | 0.101 | 68.58..68.82 | -0.701..-0.526 | 0.075..0.121 | 0.033..0.053 | 0.164..0.176 | 0.005 |
| LAND | 11 | 0.100 | 66.56..68.48 | -2.22..-0.758 | 0.149..0.312 | 0.065..0.114 | 0.182..0.280 | 0 |
| CUT | 5 | 0.050 | 65.88..66.48 | -1.02..0.633 | 1.06..1.12 | 0.310..0.422 | 0.298..0.376 | 0 |
FLAGS - SLOW: 39 cycles dt>0.07s (max 0.103 s, median 0.098 s)
```

**姿态时间线（关键证据，第 2 次 run）**

| t (s) | `pit` | 机体 `wx` | 机体 `wz` | `ux` | 事件 |
|---|---|---|---|---|---|
| 0.05 | −0.00° | 0.0037 | −0.008 | 0.0000 | 起抬 |
| 0.65 | −0.57° | 0.031 | 0.015 | 0.0043 | LIFT |
| 1.50 | −3.50° | 0.075 | 0.036 | 0.027 | LIFT 末（dH=1.46） |
| 2.40 | −8.40° | 0.103 | 0.049 | 0.063 | HOLD |
| 3.00 | −12.69° | 0.121 | 0.057 | 0.095 | HOLD 末 |
| 3.30 | −15.17° | 0.149 | 0.069 | 0.113 | DPOS 末 |
| 3.90 | −21.58° | 0.120 | 0.054 | 0.159 | DNEG 末 |
| 4.30 | −24.01° | 0.121 | 0.053 | 0.176 | DPOS2 末 |
| 5.40 | −40.37° | 0.312 | 0.114 | 0.280 | LAND |
| 5.65 | −61.62° | 1.105 | 0.310 | 0.376 | CUT / `up=0.48` 门控 |

**三条从数据里直接读出的事实**

1. **LIFT 段 dt=0.050、HOLD 起 dt=0.100**：唯一区别是 HOLD 每 tick 都写执行器（u 随 vy 变化），
   LIFT 的 u 恒定 ⇒ 写入不改变 ⇒ 不写。写入 = 1 tick 的额外开销，实锤。
2. `vx/vz` 在 0~0.3 s（机体水平、`ux<0.001`）**保持 0** ⇒ 水平状态下**没有侧向力** ⇒ 两台喷流是直的，
   漂移不是"喷歪了"，而是姿态自己转起来后的必然结果。
3. `pit` 单调、无恢复趋势：**恒值角加速度**，不是摆锤（若 h=1.4 的倒摆稳定，会看到 ~1.7 s 周期的振荡）。

## §3 推导（只写能站住的）

### 3.1 恒值偏置的量级

`α_x ≈ 0.05 rad/s²`（LIFT 段 wx 0→0.075 用 1.45 s；HOLD 段 +0.032）。等价的构造误差：

```
若来自横向/纵向重心偏移 δz：  τ = δz·T_total,  δz = α·I / T_total
  取 I≈10 kg·m²、T_total=124 pN ⇒ δz ≈ 0.05·10/124 ≈ 4 mm
若来自两台推力差 ΔT：        τ = b·ΔT ⇒ ΔT ≈ 0.25 pN ≈ T 的 0.5%
```

⇒ **毫米/千分之几级别的不对称就足够**。memo 的"两发稳只能来自绝对对称"这条，量级上被证实。

### 3.2 α/D 与 b 的矛盾（未解决）

刚性预测 `α/D = 2·b·K·p/I`。b=2、K·p=517、I≈10 ⇒ **≈200 rad/s²/单位 D**；实测 `|α/D|≈11`（且是 `wz` 轴）。
差 ~10 倍。可能：① 有效前後力臂只有 ~0.2 格；② 差动没有真的产生 `2D·K·p` 的推力差；
③ 本轮 `α/D` 本身就是废数（`wx` 基线 0.05 与脉冲同量级）。
**在锥角映射测试（用 `τ = r × F` 直接测力臂）之前，不得采用任何 α/D 数值。**

### 3.3 姿态门控为什么在 61° 触发

`up = 1 − 2(qx² + qz²)` = 机体"上"向量与世界"上"的点积；`up=0.48` ⇒ 倾角 61°。
此时竖直推力分量只剩 0.48 ⇒ 必然掉高。工程含义：**`COS_MIN=0.5` 对这台机偏松**，下一轮建议 0.7（45°）就切。

## §4 待查清单（下一轮的输入）

1. 新机体几何：两喷口 / 重心 / AIC / FMC 的**方块坐标**（X 左右、Y 上下、Z 前后）；重点是 `b₁/b₂`（重心到两台的前後距离）。
2. 锥角 `setDirection(x,y)` 的 x/y 各对应机体哪个轴（**从未实测过**）。
3. `h`（喷口比重心低多少）实测值 —— 决定自由轴能不能用锥角阻尼。
4. 两台喷流的实际推力差（`τ = b·ΔT` 的 ΔT 量级）。
5. 20 Hz 修正后重跑，确认 `dtMd=0.050`、`SLOW=0`。
