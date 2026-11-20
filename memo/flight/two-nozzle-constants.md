# 两喷口飞行器 · 可信常数与黑名单（唯一真相源）

> **规矩**：本文件里的数都是**实机实测**（2026-11-20，`New World` 电脑 1）。
> 不许重测、不许重推、不许在别处复制；新实测替代某个数时**只改那一行**，注明日期与出处 CSV。
> 出处指 `memo/flight/two-nozzle-evidence.md` 的章节号；设计推导在 `memo/flight/two-nozzle-vehicle.md`。
> **全局共享常数（G=11、d=0.09、K_NOZ=524.8、锥角 15°/160°/s…）不在这里复制**，见 `flight-program-standard.md` §4。

---

## 1. 本机实测常数（2026-11-20 · 只读探针 `m2probe`）

| 量 | 值 | 出处 |
|---|---|---|
| 质量 `m` | **11.25 kg** | §1（`status_m2_static.txt`） |
| 重心相对 FMC/AIC 方块中心 | **(+0.00, −1.64, +0.98)** 格 | §1 |
| 地面气压 `p` | **0.9811**（h=67.76，`getPressureFromAltitude` 兜底） | §1 |
| 满推力（2 台，p=0.981） | **1029.8 pN** | §1 |
| TWR（地面） | **8.32** | §1 |
| 悬停出力 `u_hover` | **0.1202**（p=0.981）/ 0.1196（p=0.986） | §1、§2 |
| 爬升校核 | `u=0.137` ⇒ **dH=+1.46 m/1.5 s**，vy_end=+1.99（理论 1.75 m，86%） | §2 |
| 引擎 | `ss.getEngine()` 可用；throttle 1.00、load 0.125、**1024 SU / 8192**、燃料 `aeronautics:levitite_blend`（mix 0.90 / eff 0.90）、温度 119.5 °C | §1 |
| 两台喷口 | ch0 `inv=false`、ch1 `inv=true`；**两台风扇旋向相反、都朝下喷气**（= 推力朝上），锥角坐标系**两台相同** | §1 + 用户 2026-11-20 说明 |
| 传感器配置 | **只有一台 AIC**（无皮托管、无静压孔） | §1 |

## 2. 传感器门控（源码事实，`SensorSystemAPI`）

> 这条**推翻**了 `my_sensor_system.md` 蓝图里"速度类需要速度传感器"的原始设计；实现里速度读数是 **INS 门控**。

| 读数 | 门控 | AIC 满足？ |
|---|---|---|
| `getAngles` / `getOrientation` / `getAngleRates` | **ATTITUDE（INS）** | ✅ |
| `getVelocity` / `getAngularVelocity` / `getPosition` / `getBodyPosition` | **ATTITUDE（INS）** | ✅ |
| `getPhysicsMass` / `getPhysicsCenterOfMassRel` / `getPressureFromAltitude` / 应力 / 气动工具 | **FMC**（AIC 等同 FMC） | ✅ |
| `getPressure` / `getAltitude` | **静压孔** | ❌ → 用高度模型兜底 |
| `getSpeed` / `getAirSpeed` | **皮托管 + 静压孔** | ❌（本机不需要） |

`BodySensorRegistry` 第 90 行：`AicBlockEntity` 注册 **`ATTITUDE + FMC`** ⇒ **一台 AIC 就够全部姿态/速度/质量读数**。
⚠️ `ss.hasSpeedSensor()` 可能返回 `nil`（方法缺失或无门控），**不能**据此推断"速度读数是假的"。

## 3. 控制频率与写预算（实测，**20 Hz 的真相**）

| 项 | 实测 | 说明 |
|---|---|---|
| 写方法线程 | `setThrustScale` / `setDirection` / `setInverted` 全是 **`@LuaFunction(mainThread = true)`**（`AdvancedThrustNozzleBlockEntity` 711/739/806/843 行） | **一次写块阻塞约 1 tick** |
| 错误写法 | `apply(); sleep(0.05)` ⇒ **dt 中位 0.100 s（10 Hz）**，73 循环里 40 个 >75 ms | m2dyn v1 实测 |
| 正确写法 A（星舰） | **完全不 sleep**，让写块当节拍器（`flip.lua#runPhase` 里没有任何 `sleep`） | 每循环恰好 1 tick |
| 正确写法 B（推荐） | `rest = 0.05 − (now − tStart)`，`rest > 4 ms` 才 `sleep(rest)` | 有写的 tick 由写定拍、无写的 tick 由 sleep 定拍 |
| 一 tick 一块 | 两台喷口的 `setThrustScale`+`setDirection` 放**同一个** `parallel.waitForAll` | 两个写 ≈ 1 tick，不是 2 |

## 4. 姿态偏置（实测，**两发方案的关键失败**）

| 项 | 值 | 出处 |
|---|---|---|
| 俯仰（`wx`，绕左右轴）常值偏置 | **≈0.05 rad/s²** ⇒ 5 s 内角速度 0.05→0.31 rad/s、俯仰角 −3.7°→−61° | §2 |
| 自由轴（`wz` 滚转，绕前后轴）偏置 | **≈0.015 rad/s²** ⇒ 5 s 内 0.036→0.114 rad/s | §2 |
| 0.05 rad/s² 等价的构造误差 | **约 4 mm 纵向重心偏移**，或两台推力差 **0.5%** | §3 推导 |
| 差动权限 `α/D` | 程序按 `wz` 算得 DPOS **+12** / DNEG **−11** / DPOS2 **+7** ⇒ **\|α/D\|≈11**，但**本轮不可信**：`wx` 基线漂移与脉冲同量级，无法分离 | §2 |
| 由 α/D 反推 `I` | 程序算出 180 kg·m² —— **明显荒谬（11.25 kg 机体）**，因为 α/D 不可信，**不得引用** | §2 |
| 姿态门控触发 | `up=0.48`（61° 倾角）⇒ `COS_MIN` 切推力；两次运行结果一致（可复现） | §2 |

**结论（可引用）**：这台机存在**恒值姿态偏置**，而开环测试没有任何配平 ⇒ 一定翻。
"α/D 与 b 的矛盾"（刚性预测 2068/I vs 实测 ≈11）**未解释**，必须先做锥角映射测试查清力臂，再谈增益。

## 5. 黑名单（想到就停手）

| # | 念头 | 实际后果 / 正确做法 |
|---|---|---|
| 1 | 写完执行器再 `sleep(0.05)` | 一份工作两个 tick ⇒ **10 Hz**（实测 dt=0.100）。只补足剩余预算，见 §3 |
| 2 | 用欧拉角名猜物理轴 | 游戏 `pitch` = 绕**机体局部 X**、`roll` = 绕**局部 Z**；本机机头 **−Z** ⇒ 俯仰=`pitch`(X)、**滚转=`roll`(Z)=自由轴**。记录用机体系 `wx/wy/wz` 并标注物理含义 |
| 3 | 开环测量不做姿态配平 | 恒值偏置把机体推到 61° 才被门控切掉（实物证：memo "常值偏置必须配平"）。测量程序要么短（≤3 s），要么带配平 |
| 4 | `math.rad()` 套在已经是 rad/s 的量上 | `α/D` 小 57 倍（−3 而不是 −205）。`getAngularVelocity` 已是 rad/s，`getAngleRates` 才是 deg/s |
| 5 | 速率窗口用相位"标称时长" | 首个采样点在施力之前 ⇒ 系统性丢 1 tick（0.05/0.30=17%）。用**实际采样跨度** `tLast−tFirst` |
| 6 | 多个模式共用一份 `status_*.txt` | 后跑的覆盖前跑的（本轮 id 读数就丢了一次）。按模式分文件：`status_m2_<mode>.txt` |
| 7 | 看到 `hasSpeedSensor()==nil` 就断定速度读数是假的 | 速度/姿态/角速度全在 **INS(AIC)** 门控下，是真值。见 §2 |
| 8 | 拿一次被基线淹没的脉冲当标定值 | 本轮 `α/D`、`I` 都是废数（§4）。标定值必须"信号 ≫ 基线"且能对称抵消 |
| 9 | 以为 `setDirection(nil)` 能打直 | 必须显式 `setDirection(0,0)`（沿用 global 黑名单 #21） |

## 6. 程序与版本表

| 文件 | 版本 | 作用 | 归档 |
|---|---|---|---|
| `computer/1/m2probe.lua` | **v5** | **只读**探针：`id` / `static` / `power`（引擎-燃料-应力-喷口） | `cc_logs_archive/lua_versions/computer1/m2probe_v5.lua` |
| `computer/1/m2dyn.lua` | **v1** | 第一次会动的测试：LIFT→HOLD→三段差动脉冲→LAND（含倾角闭环）。**待改造** | `…/computer1/m2dyn_v1.lua` |
| `computer/1/startup.lua` | 用户脚本 | 开机把两台喷口设 `u=1.0` + ch1 `setInverted(true)`。⚠️ **开机时机体若自由就是 TWR 8.3 满推起飞**（建议改 0，待用户决定） | — |

## 7. 工具

| 工具 | 用途 |
|---|---|
| `tools/digest_csv.ps1 -Path <csv> -Cols …` | 两喷口 CSV（16~17 列）摘要，≤30 行 |
| `.tmp_luatest/m2_harness.lua` | **两喷口专用桩世界**（几何/质量/惯量可改，实测值已同步：m=11.25、b=2、h=1.36、I=10）；改 Lua 必跑 |
| `memo/flight/starship15-constants.md` §4 | 27 条通用黑名单全集（本表只放本机新增的 9 条） |
