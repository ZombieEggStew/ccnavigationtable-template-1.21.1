# 四喷口矩形飞行器 · 交接页（换人 / 换会话先读这一页）

> 2026-11-21 16:15 收尾。真相源仍是 `four-nozzle-constants.md`（常数/黑名单）与 `four-nozzle-plan.md`（计划/进度）。
> 本页只做「现状 + 怎么接着干 + 别踩什么」，不复制原始数据（那些在 `computer/2/log/` 与 constants §2）。

## 0. 一句话现状

**矩形四角四喷口的 21 kg 飞行器。P0 几何与 P1 基线（抬升 → 悬停 → 软着陆）已实机 PASS，
残余角加速度几乎为零（wx +0.000 / wz −0.010 rad/s²）。P2（俯仰/滚转权限）v1 因用了不可信的
`getAngularVelocity` 而 FAIL，v2 已改用 `getAngleRates`、桩内 PASS，但还没上机。
下一步就是飞 `m4axis pitch` / `m4axis roll`（v2）。**

## 1. 必带常数（完整表见 `four-nozzle-constants.md` §1/§2）

| 量 | 值 |
|---|---|
| 质量 | **m = 21.00 kg** |
| 几何 | 矩形 **2.00 (x) × 4.00 (z)**，喷口在重心下 **1.48**；形心 (0,−1.48,0)（x、z 居中）；共面散差 0 |
| 通道 | **ch0/ch1 = 后（z=+2, `inv=false`）**；**ch2/ch3 = 前（z=−2, `inv=true`）**；四台全部朝下 |
| 分配系数 | `Σz²=16`、`Σx²=4`、`ΣL²=20`；俯仰系数后 +0.125 / 前 −0.125；滚转 x=−1 → −0.250、x=+1 → +0.250 |
| 气压 / 高度 | p=**0.9822** @ AIC y=**67.50**；物理体原点 h0=**64.98**，地面 **63.0**（超平坦） |
| TWR / u_hover | **8.93 / 0.1120**（4×524.8×p = 2061.8 pN） |
| 传感器 | **只有一台 AIC**（INS+FMC）；无皮托管、无静压孔；`ss.getStressRemaining/Capacity` = nil |
| 重心 ↔ AIC | AIC 在重心上方 **2.52**；电脑在 AIC 下方 1.00 |
| P1 残余 | `wx=+0.000`、`wy=+0.000`、`wz=−0.010 rad/s²`；悬停 `w=0.054`；落地 `vy=−0.50 m/s`；漂移 0.07/0.13 |

## 2. 控制通道

| 目标 | 手段 | 状态 |
|---|---|---|
| 总距 | 四台同增 | ✅ P1（u_hover 0.112） |
| 俯仰 | **前/后推力差** | ⏳ P2 v2 待上机（符号由实测 `g` 反推） |
| 滚转 | **左/右推力差** | ⏳ P2 v2 待上机 |
| 偏航 | 四台**切向锥角** | 待 P3（`setDirection` 符号未测） |
| 横移 | 四台**共模锥角** | 待 P4（符号未测） |

## 3. 程序清单（`run/saves/New World/computercraft/computer/2/`）

| 文件 | 版本 | 会动? | 作用 |
|---|---|---|---|
| `startup.lua` | 用户脚本 | — | 引擎油门 1.0；四台 `setDirection(0,0)`、`setThrustScale(0)`；ch2/ch3 `inv=true` |
| `m4probe.lua` | **v1.1** | 否（只读） | `geom`（四台力臂 + 矩形校验 + 分配系数）、`static`、`id`、`power` |
| `m4cal.lua` | **v1.1** | 是 | P1 基线：竖直 PD（+1.5 m、−1.0 m/s）+ 推力差速率阻尼；`HOVER_A` 量偏置、`HOVER_B` 验阻尼 |
| `m4axis.lua` | **v2** | 是 | P2：`pitch`/`roll` 对称三段脉冲（+C/−2C/+C）+ **`getAngleRates`** + 自动符号 `g` + `c_trim` + 残差复验 |
| `m4axis_v1.lua` | 归档 | — | 用 `getAngularVelocity` 的错误版本（结果已作废） |

桩：`.tmp_luatest/m4_harness.lua`（4 台喷口带 `getForcePoint`、m=21、矩形 1×2、ch3 +2% 推力偏置、地面 63）。
旧程序归档：`cc_logs_archive/lua_versions/computer2/`。

## 4. 场地与安全

- 超平坦世界，地面 **y=63**；当前起飞点物理体原点 **( −93.70, 64.98, +7.49 )**，静止 yaw **−90.56°**。
- 用户确认四周无遮挡（2026-11-21），但程序自身仍有门限：`up<0.70/0.75`、`|w|>0.8 rad/s`、`|vy|>3`、
  高度 `+2.4` 上限、30 s 超时；**任何 abort 都四台归零**。
- 落地：`m4cal` −1.0 m/s、`m4axis` −1.2 m/s，切推力高度 h0+0.15；`V_DOWN` 提高是为了缩短
  无姿态环的下降时间（见 §5 第 5 条）。

## 5. 已踩的坑（按「下次一定还会再踩」排序）

1. **改 Java 后不重启客户端**：运行时仍是旧类，新 `@LuaFunction` 在 Lua 里直接不存在（`no-api`）。
   判据：`build/classes/java/main/**` 的 mtime 晚于 JVM 启动时间 ⇒ 必是旧类（黑名单 #28）。
2. **失焦/暂停时跑带 `mainThread` 调用的程序**：主线程停摆 ⇒ CC watchdog 约 3 s 杀掉电脑，
   shutdown 还会卡在引擎外设 detach 的同步加块上（黑名单 #29）。
3. **用 `getAngularVelocity` 做脉冲标定/阻尼**：Sable pose 差分的 ω 在机动中被污染（源码自己写了
   “体 Z ≠ 滚转速率”、偏差可达 0.5+ rad/s）。v1 两轴增益都为负、三段互不自洽、TRIM 反而加速。
   **一律用 `getAngleRates`**（黑名单 #30）。
4. **两发时代的 21 条黑名单仍然全部适用**：`memo/archive/two-nozzle-constants.md` §5。
5. **下降段没有姿态环会自激**：P1 的 DOWN 段速率涨到 0.54 rad/s（两发 #9 重现）。P5 之前下降段要短。
6. **`setDirection` 的符号未知**：P4 实测前不许开横移/偏航闭环（两发 #14）。

## 6. 未解决 / 待验证

| 项 | 现状 | 为什么重要 |
|---|---|---|
| `I_pitch` / `I_roll` | 待 `m4axis` v2 实测（`I = K_τ/abs(g)`） | 姿态环增益分母；两发 `I=65` 不可沿用 |
| 俯仰/滚转的符号映射 | v2 的 `CAL g1..g3` 与 `CAL AXIS` 会直接给出 | 决定姿态环 `d` 的符号 |
| 偏航/横移的锥角符号 | P3/P4 未测 | 不开符号前不能闭环 |
| 下降段自激 | 0.54 rad/s，未闭环 | P5 必须带三轴姿态环 |
| `m4cal` 的阻尼信号 | 仍是 `getAngularVelocity`（低速率下 PASS） | P5 前换成 `getAngleRates` |
| ch4 非喷口外设 | 未识别（`id` 报 `other`） | 不影响飞控 |
| 引擎外设 shutdown 死锁 | 退出世界时日志有 `Failed to stop computers under deadline`（`EngineCoreBlockEntity$Peripheral.detach → setChanged → getChunk`） | 与飞控无关，但会拖慢退出；未修 |

## 7. 下一步（一步一上机）

1. **`m4axis pitch`（v2）**：判据 `CAL g1≈g2≈g3` 同号、`CAL AXIS` 里 `dPitch` 占优、
   `I` 在几~几百 kg·m²、`RES TRIM residual_alpha` 接近 0、`VERDICT PASS`。
2. **`m4axis roll`（v2）**：同上，看 `dRoll` 占优。
3. 拿到两轴 `I` 与 `c_trim` 后：**P3 偏航**（四台切向锥角，先用小幅度测 `setDirection` 符号）。
4. **P4 共模横移**（同一套符号测试）。
5. **P5 三轴姿态环 + 高度环**（阻尼信号一律 `getAngleRates`），再上位置/航向。

## 8. 数据索引（`computer/2/log/`）

| 文件 | 内容 |
|---|---|
| `status_m4_geom/static/id/power.txt` | P0：力臂、质量、气压、AIC、引擎/应力 |
| `status_m4cal.txt` + `m4cal_1795248090203.csv` | P1 基线 PASS（残余、阻尼、落地、漂移） |
| `status_m4axis_pitch.txt` + `m4axis_pitch_1795248455796.csv` | P2 v1 pitch FAIL（传感器不可信的原始证据） |
| `status_m4axis_roll.txt` + `m4axis_roll_1795248471963.csv` | P2 v1 roll FAIL |
