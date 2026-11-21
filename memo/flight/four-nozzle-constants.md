# 四喷口矩形飞行器 · 可信常数与黑名单（唯一真相源）

> 状态：**2026-11-21 15:30 重建机体，P0 未跑** —— 本表只放「已由用户或程序确认」的硬件事实；
> 实测数一律写 **待 P0**。**禁止**把两发时代的 `m / I / α / r*` 当本机常数（喷口数、质量、力臂全变）。
> 全局共享常数（G=11、d=0.09、`K_NOZ=524.8`、锥角 15°/160°/s、20 Hz 写预算）不在这里复制：
> 见 `flight-program-standard.md` §4/§3。
> 两发 21 条黑名单**全部仍然适用**：`memo/archive/two-nozzle-constants.md` §5；本文件只记四发新增的坑。
> 计划/进度 → `four-nozzle-plan.md`；历史证据 → `memo/archive/two-nozzle-*.md`。

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
| 锥角 / 偏转速率 / 出力渐变 | 15° / 160°/s / 0.2 s | propulsion §6 |

## 2. 待 P0 填（`m4probe geom` / `static`）

| 量 | 值 | 备注 |
|---|---|---|
| 质量 `m` | 待 P0 | `getPhysicsMass`；四发比两发多约 2×0.5 kg 喷口 + 框架 |
| 气压 `p` / 地面高度 `h0` | 待 P0 | 无静压孔 ⇒ `getPressureFromAltitude(y)` |
| 四台施力点 `r_i=(x_i,y_i,z_i)` | 待 P0 | `getForcePoint()` ×4（ch0..ch3） |
| 矩形半宽 `a` / 半长 `b` / 共面 `y` | 待 P0 | 由 `abs(x_i)`、`abs(z_i)`、`y_i` 直接算 |
| 施力点形心 | 待 P0（应 ≈0,0,0） | `mean(r_i)`；≠0 ⇒ 常值力矩偏置 |
| `TWR(p)` | 待 P0 | `4·K_NOZ·p/(m·G)` |
| `u_hover` | 待 P0 | `m·G/(4·K_NOZ·p)` |
| AIC 是否在机体上 | 待 P0 | `m4probe id`；没有 ⇒ 计划要改 |
| 应力余量 | 待 P0 | `m4probe power`；4 台风扇可能吃满 1024 SU |

## 3. 控制通道（P1~P4 后逐行填，公式见 `four-nozzle-plan.md` §2）

| 通道 | 手段 | 实测值 | 状态 |
|---|---|---|---|
| 总距 | 四台同增 | 待 P1 | — |
| 俯仰 | 前/后推力差 | 待 P2 | — |
| 滚转 | 左/右推力差 | 待 P2 | — |
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

## 5. 程序与版本表

| 文件 | 版本 | 会动? | 作用 |
|---|---|---|---|
| `run/.../computer/2/m4probe.lua` | **v1** | 否（只读） | `geom`（四台力臂 + 矩形校验 + 分配系数）、`static`、`id`、`power` |
| `m4cal.lua` | 未写 | 是 | P1~P4：等 u 配平 + 俯仰/滚转/偏航/横移双峰标定 |
| `m4hover.lua` | 未写 | 是 | P5：三轴姿态 + 高度，再上位置/航向 |
| `computer/2/startup.lua` | 用户脚本 | — | 引擎油门 1.0；四台 `setDirection(0,0)`、`setThrustScale(0)`、ch2/ch3 `inv=true` |

日志：`/log/status_m4_<mode>.txt`（探针）；后续 CSV 放 `/log/m4*_<epoch>.csv`。
旧程序归档：`cc_logs_archive/lua_versions/computer1/`（两发时代，只读）。
