# 星舰助推器复刻 —— 交接文档（2026-11-18 04:30 收盘，V 段收官）

> 给"下一个接手的人（或下一次的自己）"看的**一页纸**。细节全在 `memo/flight/starship15-catch.md`（按 §N 定向 grep）。
> 本文只放：现在到哪了、哪些数是可信的、程序怎么跑、下一步做什么、哪些坑别再踩。
> **本轮工作卡**：`starship15-ROUND.md`（每轮开工只读它 + `starship15-constants.md`；本文只在换人/换会话/大阶段收尾时通读）。

---

## 0. 一句话现状

**「belly-flop → 锥角回翻 → 反推刹车」这条完整的 V 已经真机跑通并 PASS 三次；最后那次（v15）带完整 CSV，
从竖直起点到「回竖直 + 横向刹停」只用 149.8 m，距地板还剩 ~60 m。**
**缺的只剩：用这 ~60 m 做软着陆（F2），以及把 V 串进任务程序。**

| 层 | 程序 | 状态 | 关键结论 |
|---|---|---|---|
| 竖直（定高/剖面） | `cal1` §16 | ✅ PASS | `A_REF=4 / A_BRK_MAX=5 / KV=6`，定高 h ±0.06 m |
| 力矩通道标定 | `att` v2 §20 | ✅ 闭合 | pairA→body X（负）、pairB→body Z（正）、`α=1.0 rad/s²/单位D`、`I≈15.9 kg·m²`、力臂≈1.0 m |
| 姿态环（四元数 PD） | `tilt` §21 | ✅ PASS | KP=3.0 KD=2.5；被撞后 1.5 s 恢复 cos 1.00 |
| 横向环（位置+速度） | `guide` v4 §23 | ✅ PASS | `MOV/RET` 末端 0.42 / 0.42 m，`HOLD` 0.03 m |
| **完整 V（0→90→0 + 反推刹车）** | **`flip` v15 §35~§41** | ✅ **真机 PASS ×3（v15 有全量 CSV）** | FLIP1 2.15 s/5.4 m｜FLIPBACK 3.25 s/92.8 m｜BRAKE 3.20 s/47.6 m（vh 25.2→2.1）⇒ 到刹车结束只用 **149.8 m**，余量 **~60 m** |
| **任务剖面 `flip dock`（腹部朝下 → 自由落体 → 回翻 → 回夹臂）** | **`flip` v16** | ✅ **真机 PASS ×2（2026-11-18 14:23 / 14:40；err 0.2 m）** | FALL 2.00 s/20.8 m｜FLIPBACK 1.85 s/46.4 m（u=1.0、vh 24.0）｜BRAKE 6.15 s/dz −87.0｜RETURN gap 17.7→err 2.0｜ARMS err 0.2、cos 1.000；预算实测够、无 FLOOR GUARD |
| 任务集成 + 夹取 | `starship.lua` | ❌ 旧版缺姿态环，须按 §20/§21/§23/§40 重写 | handover §9 的 D/E 步 |

---

## 1. 可信常数（唯一真相源是 `starship15-constants.md`，这里只列交接最常用的）

| 量 | 值 |
|---|---|
| 重力 / 推力模型 / 质量 | `G=11`；`F = N×256×2.05×p×scale`（pN）；`m=61.00 kg` |
| 满推力 TWR | 4 台 @p≈0.54 ≈ **1.68**；@p≈0.82 ≈ **2.57**；5 台各 ×1.25 |
| 悬停油门 | 5 台 ≈0.284；**只开 4 台（ch0=0）≈0.348~0.351** |
| 差动 | `α=1.0 rad/s²` 每单位 D；pairA→body X（D>0⇒ωX<0）、pairB→body Z（D>0⇒ωZ>0）；滞后 ≈0.15 s |
| 锥角（主翻转执行器） | 15° 满锥角；`α=2.0 rad/s² @u=1` ⇒ **α_可用 = 2.0·u**；`setDirection(x,y)` 是摇杆语义；**ch0 必须 `setInverted(true)`** |
| 磁场/环境 | 无气动阻力模型（`d=0.09` 通用阻力，`τ≈11 s`）；地面 **Y=63**；云层 ≈Y=195 |

**实测出来的能力上限（别重新发明）**

```
过冲 Δθ = w²/(2α)，α = 2.0·u   ⇒ u=0.45 交 60°/s 必过冲 ~38°；u=0.60 过冲 ~25°
回翻腿「时长就是落差」（近横躺时 15° 锥角只给 sin15°·u·TWR ≈ 0.2~0.4 G）
高度不是杠杆：推力∝气压，翻转点每高 10 m，锥角腿多掉 ~8.5 m（净赚 1.5 m/10 m）
刹车段真实权限：4 台 @p≈0.82 ⇒ 17.3 m/s²（u=1.0）；剖面用 arrestBrk=16 时 u 会打到 1.00
```

---

## 2. 四条控制律（都实测过，直接抄）

```lua
-- ① 竖直（定高/剖面）
vyRef = clamp(K_POS*(hTarget-h), ±VY_HOVER)          K_POS=0.9 VY_HOVER=3
aDes  = clamp(KV*(vyRef-vy), -A_ACC, A_BRK_MAX)      KV=6 A_ACC=6 A_BRK_MAX=5（刹车腿临时 16）
u     = clamp(m*(G+aDes)/cosVert / FmaxFiring(p), 0, 1)；cosVert<COS_MIN(0.25) ⇒ u=0

-- ② 姿态（四元数 PD，差动）
a_x = KP*e_x - KD*w_x   a_z = KP*e_z - KD*w_z        KP=3.0 KD=2.5
D_A = clamp(a_x/GAINX, ±room)  D_B = clamp(a_z/GAINZ, ±room)  GAINX=-1.0 GAINZ=+1.0
room = min(0.33, u-0.02, 1-u)；ch0=0；ch1=u+D_A ch3=u-D_A；ch2=u+D_B ch4=u-D_B

-- ③ 横向（位置→速度→加速度→倾角）
vTarget = clamp(LAT_KP*posErr, ±LAT_VMAX)  LAT_KP=0.30 LAT_VMAX=1.2
aDes    = clamp(LAT_KV*(vTarget-vHoriz), ±LAT_AMAX)  LAT_KV=1.00 LAT_AMAX=2.0
theta = atan(|aDes|/G) ≤18°；死区 |posErr|<0.08 且 |vh|<0.08

-- ④ 锥角（大角度翻转，唯一可用的大力矩通道）
e = lean - tgt               lean = deg(atan2(up_z, up_y))  ← 带符号，负 = 朝 −Z（横躺侧）
α_now = 2.0*u                ← 分母必须用「实际权限」，不是满推力 α（v11 的真 bug）
wDes = min(WMax, sqrt(2·α_now·|e|))   ← 刹车曲线（fast 腿）；小误差也不塌尾巴
tilt = clamp(KA*(wDes - leanDot)/α_now, ±1)   KA=6；leanDot 用差分+一阶滤波(τ=0.08)
DIRCMD = {0, -CONE_SGN*tilt}   ← 极性是喷管映射属性，跨腿共享（首腿探针锁定后不再探）
```

**四条硬道理**

1. 机体倾斜方向 = 推力方向 = 漂移方向；想刹车就往速度反方向倾。
2. 倾角是慢执行器（18°≈1.3 s），横向环带宽必须 ≪0.7 rad/s。
3. **锥角力矩 ∝ u**：用满推力 α 当分母 ⇒ 以为力矩是 2.2 倍 ⇒ 饱和 + 过冲。
4. **锥角 + 差动不能同刻并联**（满推力时差动余量=0）；分工是**分时**：大角度用锥角、竖直后用差动 PD。

---

## 3. 程序清单（`run/saves/New World/computercraft/computer/0/`）

| 程序 | 用法 | 状态 |
|---|---|---|
| `probe.lua` | `probe` | ✅ 只读识别 + 把夹持点写进 `/log/target.txt` |
| `cal1.lua` | `cal1` / `cal1 zero` | ✅ §16 竖直通道标定 |
| `att.lua` | `att` / `att zero` | ✅ §20 力矩通道 |
| `tilt.lua` | `tilt` / `tilt zero` | ✅ §21 姿态 PD |
| `guide.lua` | `guide` / `guide zero` | ✅ v4 PASS（横向环 + 落地剖面的竖直环） |
| **`flip.lua`** | `flip` / `flip back` / `flip dock` / `flip zero` | ✅ **v20**：`dock` = 腹部朝下释放 → 自由落体 → 锥角回翻(u=1.0) → 比例式反推 → 回夹持点 → `dock` 请求 → **0.25 s 控制 + 0.5 s 收信轮询**（收到 `clamped` 立刻）开 ch5/ch6 → 2 s → 关喷口；**真机 PASS ×3（2026-11-18）**。`run`/`back` 仍是 v15 剖面 |
| **`catch.lua`（`computer/1`）** | `catch`（常驻等待；Ctrl+T 停） | ✅ **v1.3 真机 PASS（2026-11-18 15:08/15:17）**：复位臂 ±90 + conn off → 等 `dock` 请求/按键 → 合臂 0 @2rpm + conn 15 → **`getTargetAngle()` 到 0 判到位** → 回 `clamped`（每 0.1 s 重试 15 s） |
| `spin.lua` | `spin` | ✅ §25.1 角阻尼≈0（气动件对小转速无效） |
| `thruststress.lua` / `dropall.lua` | 一次性 | ✅ 脱落/推力取证工具 |
| `starship.lua` | `starship` | ❌ **旧版缺姿态环**，须重写 |
| `fixture.lua`（`computer/1`） | `fixture` 常驻 + `status/release/clamp/arms/open/close` | ✅ 可用 |

**通用约定**

- `xxx zero` = 应急归零；程序结尾**只把喷口 `setThrustScale=0` + `setDirection(0,0)`，引擎保持 100%**。
- 跑测试前夹具电脑必须先跑 `fixture`；**但云端平台没有夹具电脑**（`flip` 会自动降级成「本地脱落」）。
- 释放/接线一律塞进**同一个 `parallel` 块**（顺序写每条吃 1 tick ⇒ 倾斜 ⇒ 漂移 ⇒ 翻车）。

---

## 4. 硬件与频道（实测）

| 体 | 频道 | 设备 | 备注 |
|---|---|---|---|
| 火箭 | 0 | 矢量喷管**中心** | **`setInverted(true)`**；默认 scale=0（F4 才会启用） |
| 火箭 | 1 / 3 | 矢量喷管前/后 | 差动 ⇒ body X（pairA） |
| 火箭 | 2 / 4 | 矢量喷管左/右 | 差动 ⇒ body Z（pairB） |
| 火箭 | 5 | 上方两个 docking connector | `setRedstoneOutput(5,0)` = 脱落 |
| 火箭 | 6 | 下方燃料 connector | 脱落 = 输出清零 + `setStrongPower(6,false)` |
| 夹具 | 7/8/9 | fuel/right/left connector | |
| 夹具 | 10/11 | 左右 `aero_bearing` | `setTargetAngle(30/-30)` 张开；**必须有轴向输入转速** |
| — | — | 引擎 | `setThrottle(1)` ⇒ 256 RPM 常开 |

---

## 5. 世界几何与安全方向

| 项 | 值 |
|---|---|
| 世界 | 超平坦；**地面 Y=63**；云层 ≈Y=195；build limit 320 |
| 回收塔 | x≈11，Y 63~102，**z≈+12**；**夹持点实测 (11.50, 93.05, −0.49)**（probe 电脑 0 夹住时、mass 61 ⇒ `h=0` 在此）；旧电脑 1 的 target 是**平台体**（mass 133.75、z=+11.2），别当机体用 |
| 夹臂 | X 两侧，臂高 ≈Y 95；夹持平台边界到 z≈+6（去那儿必撞） |
| 释放点（实际用的） | ≈**(10,217,70)** ⇒ 程序里 `h=122.7` 起步，`flipAlt 190` ⇒ y≈283 |
| 安全方向 | `−Z` 无限 ✅｜`±X` 只要 y<93 ⚠️｜`+Z` 悬停高度禁止 ❌｜`h ≥ −30` 是地面，`floorH=-20` |

---

## 6. 工具与磁盘纪律

```
.tmp_luatest/harness.lua   桩世界（桩外设+桩物理+虚拟时钟）
  lua54 harness.lua <程序路径> [τ=0.08]
  桩的硬限制：气压模型在 h≈150 就把推力顶到重力 ⇒ 190 m 只能真机验；
  验尾部相位要复制一份程序把 flipAlt/floorH 改小（temp/flip_stub_*.lua）
tools/digest_csv.ps1       把 50 KB~60 MB CSV 压成 ≤30 行看板（正文必须纯 ASCII！）
  powershell -File tools\digest_csv.ps1 <log.csv> [-Cols h,vy,u] [-T0 12 -T1 20 -Win 0.5]
```

⚠️ **CC 电脑磁盘只有 1 MB**（可用 ~880 KB）。2026-11-18 实测：把 8 份 `flip.lua` 塞进 `computer/0/ver/`（**544 KB**）
导致剩余 69.7 KB < `diskMinFree 120 KB` ⇒ **CSV 被静默跳过**，整轮数据只剩程序自打印。
**规矩：旧版本一律存主机侧 `cc_logs_archive/lua_versions/computer0/`**，电脑盘只放运行用程序（当前 266 KB）。

---

## 7. 已踩的坑（按"下次一定还会再踩"排序）

| 坑 | 后果 | 修法 |
|---|---|---|
| 锥角环分母用满推力 α | 以为力矩 2.2 倍 ⇒ 饱和 + 每腿过冲（真机峰 90.5°，桩 107.7°） | 分母用 `α_now = 2.0·u`（v11 修） |
| 横躺段用**差动**做阻尼 | u=0 时差动几乎无权限 ⇒ 刹不住 1 rad/s ⇒ 翻过 90°（桩 114.7° OVERFLIP） | 横躺段一律用锥角（`ctrlConeDamp` / 锥角 HOLD） |
| 想「翻得快又停在 90°」 | `Δθ=w²/2α`：u 不够时物理上不可能 | 要么慢（老 KW 律，4 s / 128 m），要么提 u（0.60 ⇒ 3.25 s / 92.8 m） |
| 以为「抬高翻转点就有余量」 | 推力∝气压 ⇒ 高 10 m 反多掉 8.5 m | **别动 flipAlt**；余量靠 u / arrestBrk / ch0 |
| 拿比例式反推去消 Z 回滑 | 会杀掉用户要保留的 SN15 签名，且尾部刹不住（桩 9 s / 169 m） | **保留固定 22°**（用户 2026-11-18 裁定） |
| `capH` 被 CLIMB 改成 flipAlt 后没还原 | HOVER 永远追 190 ⇒ 任何高度都判不出 PASS | 函数作用域存原值，BRAKE 前还原（v10 修） |
| `HOLD/DAMP` 后的差动腿在 u=0 下写 ±da | 会出现反向力矩/空转 | v11 起 HOLD 走锥角、DAMP 在符号锁定后整段跳过 |
| 用全局 `args` 读程序参数 | 参数**静默丢失**、跑默认分支（两次） | `local ARGV = {...}`（黑名单 #16） |
| `pcall(fs.open,…)` 被判"恒 nil" | 旧结论已推翻；但 `flush()` 不够，**必须 close()** 才落盘 | 结尾 close；CSV 带 epoch 文件名；测完立刻归档 |
| CSV 相位名带逗号 | 整行错位、解析全崩 | `ph:gsub(",","")` |
| 顺序写红石/connector | 释放瞬间倾斜 ⇒ 侧漏 ⇒ 漂移翻车 | 同一 `parallel` 块 |
| 悬停高度设在塔/平台附近 | 实测在 z≈+6 撞平台（ω 被踹 0.2 rad/s） | 只在 −Z 活动；`capH=-8` |
| 刹车后机体停在 ~40 m 却指望 HOVER 收尾 | `HOVER` 6 s 内降不到 `capH=-8`（3 m/s 剖面要 16 s） | 这就是 F2 落地剖面要接的位置 |
| `getOrientation()` 可能 `w=−1` | 四元数镜像 | 入口统一归一到 `w>0` |

---

## 8. 未解决 / 待验证（按优先级）

1. **软着陆剖面（F2）**：`BRAKE` 结束时机体 h≈40、`vh≈2`、离塔 ~90 m。需要「降高度 + 收位置 + 触地」的完整剖面，
   预算是那 ~60 m 余量。`guide` v4 的竖直环（`A_REF/KV/VY_TOUCH`）是现成料，但**要重做触地判据**。
2. **belly-flop 峰 109.3°**（过横 19°）：与过冲同源（`Δθ=w²/2α`），用户认可为效果；若哪天要压回 90°
   只能再提 `u`（F4 ch0 +25%）或加长 HOLD（但每秒 ~±10 m 落差）。
3. **`FLIP1` 的 `wMax` 实测 1.45 rad/s > 指令上限 1.05**：循环在饱和段跟不上目标速率（不影响结果，但说明
   `coneKA/滤波` 在高速率下有滞后；VERDICT 只把 FLIPBACK 的 `wMax3 ≤1.4` 当判据）。
4. **夹取闭环**：`close` + 关推力是否真被夹住（`fixture.lua` 未联调）。
5. **残余偏航**：机体 Y 轴无控制通道，撞一下带 ~7°/s 偏航；用锥角前要现算 yaw（`frameYawOff=180°` 已三次复现）。
6. **爬升剖面太慢**：`CLIMB` 28~32 s（限速 `VY_HOVER=3`）；若嫌慢可加 `VY_CLIMB≈8` + sqrt 减速曲线（只省时间）。

---

## 9. 下一步（建议顺序，一步一上机、能回滚）

| 步 | 做什么 | 验收标准 | 风险 |
|---|---|---|---|
| **A** | ✅ `guide` v4 参数收尾 | 末端 ≤0.5 m | ✅ 已达标（0.42） |
| **B** | ✅ `flip` v3：下落中 0→90° | 峰值 80°、不跑飞 | ✅（§24.3/§24.4） |
| **C** | ✅ `flip` v8 `back`：横躺→竖直（锥角 + 反推） | θ→2.8、`VERDICT PASS` | ✅（§32/§33） |
| **C+** | ✅ `flip` v15：**整条 V 连贯**（锥角两段 + 刹车开满） | 到刹车结束 ≤185 m、`aborted=nil` | ✅ **149.8 m / 余量 60 m**（§41） |
| **D** | **F2 落地剖面**：`BRAKE` 之后接竖直+横向环做软着陆 | 触地 `|vy| ≤0.8`、`|vh| ≤2`、`cos ≥0.95`、位置误差 ≤3 m、不撞地板 | 中高 |
| **E** | 重写 `starship.lua`：`FALL→FLIP→LAND→HOLD→CLOSE` | 悬停到 (11,95,0) ±0.5 m，`close` 后不坠 | 高 |
| **F** | 首飞：从 (10,217,70) 放手 | 标称成功（被夹住） | 高 |

**下一轮候选（一次只做一条，按性价比）**

1. **F2 · 落地剖面**（推荐，主线就卡在这）：把 ~60 m 余量变成「降高度 + 收位置 + 软触地」。
2. **F4 · 加 ch0**（`setInverted(true)`）：全段 +25%（刹车 24 m/s²、回翻 α +25%、belly-flop 过冲更小）；先验差动对称性。
3. **爬升加速**：`VY_CLIMB≈8` + sqrt 减速曲线（只省时间，不影响动力学）。
4. **串任务程序**：把已验证的 V + 落地并进 `starship.lua`（D/E 步）。

**已暂停的结构方向（记录在案，别重启）**：RCS / 横向喷口要**结构大改**（现动力在机尾且空间占满，
喷口是 `create:encased_fan + thrust_nozzle` 两个方块）。RCS 需先做「整合引擎」+「单方块 RCS 喷口」两个新方块。
在此之前主线继续**锥角（大角度）+ 差动（竖直后精修）分时**；气动件对小转速无效（8 块对称风帆实测，§25.1）。

---

## 10. 数据文件索引

| 文件 / 目录 | 内容 |
|---|---|
| `memo/flight/starship15-ROUND.md` | 本轮工作卡（≤60 行，每轮覆写） |
| `memo/flight/starship15-constants.md` | **唯一真相源**：可信常数、能力上限、控制律、黑名单 #1~#20、程序版本表 |
| `memo/flight/starship15-catch.md` | 归档 §11~§41（含每次真机的原始曲线结论） |
| `cc_logs_archive/*.csv` + `*.txt` | 真机原始数据归档（`flip_v15_PASS_20261118_0408.csv` 是当前的基线） |
| `cc_logs_archive/lua_versions/computer0/` | 旧版本源码（`flip_v3/v5/v6/v7/v8/v10/v12/v13/v14`、`guide_v1`） |
| `run/flight_logs/*.csv` | 模组记录仪（机体系 `wb*`、喷管力矩 `propM*`）—— 交叉验证用 |
| `.tmp_luatest/harness.lua` | 桩世界干跑夹具 |
| `temp/flip_stub_v15c.lua` | 桩内验尾部相位的副本（`flipAlt 120 / floorH -400`） |
