# 星舰助推器复刻 —— 交接文档（2026-11-19 13:52 更新：★ **任务完成** —— 用户实飞判定"现在完美了"（`flip` v44 `earlyReqH 20.0` + `catch` v1.6）；连接器分工＝臂连接器 ch5↔塔 ch8/9 主固定、加油口 ch6↔ch7 锁后才接，`LOCK ok ch8=[S42] ch9=[S42]`；剩余待办＝并进 `starship.lua` + 清盘）

> 给"下一个接手的人（或下一次的自己）"看的**一页纸**。细节全在 `memo/flight/starship15-catch.md`（按 §N 定向 grep）。
> 本文只放：现在到哪了、哪些数是可信的、程序怎么跑、下一步做什么、哪些坑别再踩。
> **本轮工作卡**：`starship15-ROUND.md`（每轮开工只读它 + `starship15-constants.md`；本文只在换人/换会话/大阶段收尾时通读）。

---

## 0. 一句话现状

**任务链路闭环 + 视觉剖面成型（2026-11-18 夜）：高空投放（y=306.5, z=+66.9）→ 自由落体 73.5 m → 锥角回翻（h=140）
→ 反推刹车 → 过冲迂回 → 回到夹持点 → 平台合臂 → 开 ch5/ch6 → 2 s → 关喷口（`flip` v31 + `catch` v1.3，v22~v31 连续 10 轮真机 PASS）。**
**夹取时序收官（v40~v44 迭代完成后用户判定"完美"，见 catch §67）。任务链已真机 PASS ×6（v35~v39，末端 err 全部 0.x）：过冲 10.2~10.8 m、RETURN 6.9~10.3 s、TRIM/ARMS err 0.0、`catch` 合臂 `reply=true`。**
**手感整改（v37→v39）已完成**：v38（比例净空 `apClearK 0.35` + 棘轮 + 小 dh 线性）治"在上方停住+上下晃"，v39（失速检测 `apStallEps 0.20`）治"贴高度横移" —— 实测 h 回升 **0.000 m**、最长平台 **0.10 s**、末段真斜线（catch §60~§62）。
**夹取时序整改（v40→v44）已完成**：臂连接器主固定（ch5↔ch8/9）、`getConnectedName()` 做真锁判据、提前请求 `earlyReqH 20.0`、回执后 `replySettleT 1.5 s` —— 用户 2026-11-19 13:52 判定 **"现在完美了，任务完成"**（catch §63~§67）。

| 层 | 程序 | 状态 | 关键结论 |
|---|---|---|---|
| 竖直（定高/剖面） | `cal1` §16 | ✅ PASS | `A_REF=4 / A_BRK_MAX=5 / KV=6`，定高 h ±0.06 m |
| 力矩通道标定 | `att` v2 §20 | ✅ 闭合 | pairA→body X（负）、pairB→body Z（正）、`α=1.0 rad/s²/单位D`、`I≈15.9 kg·m²`、力臂≈1.0 m |
| 姿态环（四元数 PD） | `tilt` §21 | ✅ PASS | KP=3.0 KD=2.5；被撞后 1.5 s 恢复 cos 1.00 |
| 横向环（位置+速度） | `guide` v4 §23 | ✅ PASS | `MOV/RET` 末端 0.42 / 0.42 m，`HOLD` 0.03 m |
| **完整 V（0→90→0 + 反推刹车）** | **`flip` v15 §35~§41** | ✅ **真机 PASS ×3（v15 有全量 CSV）** | FLIP1 2.15 s/5.4 m｜FLIPBACK 3.25 s/92.8 m｜BRAKE 3.20 s/47.6 m（vh 25.2→2.1）⇒ 到刹车结束只用 **149.8 m**，余量 **~60 m** |
| **任务剖面 `flip dock` → 合臂** | **`flip` v31 + `catch` v1.3** | ✅ **真机 PASS ×10（v22~v31）** | v31（`dockBrakeAMax` 2.2）：FALL 3.95 s/76.5 m｜FLIPBACK 1.90 s（vh 26.6）｜BRAKE 4.55 s/横向 72.0 m → **过塔 4.6 m**（v30 是 24.4 m）｜RETURN 6.8 s/err 0.2｜TRIM 0.50 s｜ARMS err **0.1** → `PASS` |
| 接近段"手感" | `flip` **v39** | ✅ **真机 PASS（2026-11-19 13:15）** | v39 实测：RETURN 8.5 s / err 0.2 / h 回升 **0.000 m** / 最长平台 **0.10 s** / vy 换向 **0** / **h<0.5 m 时横向 0.63 m**（v38 是 4.2 m，那个是"贴高度横移"）。v38（比例净空 + 棘轮 + 小 dh 线性）治晃动、v39（失速检测）治横移 |
| 夹取时序（臂 vs 加油口） | `flip` **v44** + `catch` **v1.6** | ✅ **收官（用户判定完美）** | 臂连接器 ch5↔塔 ch8/9 主固定、加油口 ch6↔ch7 锁后才接；真锁判据 `docking_connector.getConnectedName()`（0.5 m/20°），只读臂通道 8/9；提前请求 `\|h\|≤20.0` 或 `perr≤2.0`（提前量 7.40 s vs 臂 5.3 s ⇒ 余量 2.1 s）；回执后 `replySettleT 1.5 s` 等 `\|vh\|,\|vy\|≤0.30` 再关喷口 |
| 任务集成 | `starship.lua` | ❌ 旧版缺姿态环，须按 §20/§21/§23/§40 重写 | handover §9 的 D/E 步（等夹取时序定稿后再并进） |

---

## 1. 可信常数（唯一真相源是 `starship15-constants.md`，这里只列交接最常用的）

| 量 | 值 |
|---|---|
| 重力 / 推力模型 / 质量 | `G=11`；`F = N×256×2.05×p×scale`（pN）；`m=61.00 kg` |
| 满推力 TWR | 4 台 @p≈0.54 ≈ **1.68**；@p≈0.82 ≈ **2.57**；5 台各 ×1.25 |
| 悬停油门 | 5 台 ≈0.284；**只开 4 台（ch0=0）≈0.348~0.351** |
| 差动 | `α=1.0 rad/s²` 每单位 D；pairA→body X（D>0⇒ωX<0）、pairB→body Z（D>0⇒ωZ>0）；滞后 ≈0.15 s |
| 锥角（主翻转执行器） | 15° 满锥角；**4 台** `α≈2.0 rad/s² @u=1`，**F4 后 5 台 ≈2.5**，并按气压折算 `·p/0.5495` ⇒ **α_可用 = α_full(p)·u**；`setDirection(x,y)` 是摇杆语义；**ch0 必须 `setInverted(true)`** |
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
room = min(0.33, u-0.02, 1-u)；ch1=u+D_A ch3=u-D_A；ch2=u+D_B ch4=u-D_B
-- ★ v23 起 F4 已开：ch0 = u（不再是 0），FmaxFiring 按 5 台折算；`ch0On=false` 可一键回到 4 台

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
| **`flip.lua`** | `flip` / `flip back` / `flip dock` / `flip zero` | ✅ **v37 真机 PASS ×3（剖面收官）** | `dock` = 高空投放(y=306.5) → 自由落体 → 锥角回翻 → 反推（`dockBrakeVy 4.5`）→ 过冲 ~10 m → 臂上方 2.5 m 对齐 → 制动曲线进入捕获区 → 吸力抓取 → 合臂 | → 自由落体 73.5 m → 锥角回翻(h=140) → 反推 → 过冲迂回 → 回夹持点 → `dock` 请求 → 开 ch5/ch6 → 2 s → 关喷口；**F4(ch0) 已开**；`run`/`back` 仍是 v15 剖面 |
| **`catch.lua`（`computer/1`）** | `catch`（常驻等待；Ctrl+T 停） | ✅ v1.3 PASS / ⚠️ **v1.4 待真机**：**RESET 后立即 conn=15（提前通电）**，其余同 v1.3（合臂 0 @2rpm、`getTargetAngle()` 判到位、每 0.1 s 重试回复） |
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
| 投放点（2026-11-18 夜起，当前用的） | ≈**(9.5, 306.5, +66.9)** ⇒ 横距 **67 m**、`h=213.5`；翻转点 h=140（y≈233） |
| 释放点（早期 v15~v20） | ≈(10,217,70) ⇒ `h=122.7`，`flipAlt 190` ⇒ y≈283 |
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
| 拿比例式反推去消 Z 回滑 | 会杀掉用户要保留的 Flight 15 签名，且尾部刹不住（桩 9 s / 169 m） | **保留固定 22°**（用户 2026-11-18 裁定） |
| `capH` 被 CLIMB 改成 flipAlt 后没还原 | HOVER 永远追 190 ⇒ 任何高度都判不出 PASS | 函数作用域存原值，BRAKE 前还原（v10 修） |
| `HOLD/DAMP` 后的差动腿在 u=0 下写 ±da | 会出现反向力矩/空转 | v11 起 HOLD 走锥角、DAMP 在符号锁定后整段跳过 |
| 用全局 `args` 读程序参数 | 参数**静默丢失**、跑默认分支（两次） | `local ARGV = {...}`（黑名单 #16） |
| `pcall(fs.open,…)` 被判"恒 nil" | 旧结论已推翻；但 `flush()` 不够，**必须 close()** 才落盘 | 结尾 close；CSV 带 epoch 文件名；测完立刻归档 |
| CSV 相位名带逗号 | 整行错位、解析全崩 | `ph:gsub(",","")` |
| 顺序写红石/connector | 释放瞬间倾斜 ⇒ 侧漏 ⇒ 漂移翻车 | 同一 `parallel` 块 |
| 悬停高度设在塔/平台附近 | 实测在 z≈+6 撞平台（ω 被踹 0.2 rad/s） | 只在 −Z 活动；`capH=-8` |
| 刹车后机体停在 ~40 m 却指望 HOVER 收尾 | `HOVER` 6 s 内降不到 `capH=-8`（3 m/s 剖面要 16 s） | 这就是 F2 落地剖面要接的位置 |
| `getOrientation()` 可能 `w=−1` | 四元数镜像 | 入口统一归一到 `w>0` |
| `DIRCMD = nil` 想让锥角回中 | **`nil` 不写 setDirection**，喷口保持上次偏转 ⇒ 接近段多 0.83 rad/s² 力矩 vs 差动 0.29 ⇒ 机体从 +8° 滚到 −30°，COS_ABORT | `DIRCMD = {0,0}`（v27，黑名单 #21） |
| 平方根剖面写 `max(dh,0)` | `dh=capH−h` 在上方是负数 ⇒ 目标下沉 0 ⇒ 25 m 悬停 55 s 不降 | 取 `|dh|`、符号跟 `dh`（v30，黑名单 #22） |
| 参数打错（`flip fock`） | 未知 MODE 落到 `run` 分支：**先脱落再 COS_ABORT**，306 m 白掉 | v24 入口校验，非法**不脱落**并给 typo 提示（黑名单 #23） |
| 过冲按线性直觉调 | 塔压在停止距离边缘：`dockBrakeAMax` 2.6→1.5 让过冲 1.6→24.4 m | 一次 ≤0.3；`有效a ≈ 2.7+1.0·dockBrakeAMax`、`s=vh²/2a`（黑名单 #24；v31 一步 0.7 的例外见 §51） |

---

## 8. 未解决 / 待验证（按优先级）

1. ✅ **原「软着陆（F2）」已改判并完成（2026-11-18）**：目标不是地面而是**夹臂**（h=0，夹持点实测
   (11.50, 93.05, −0.49)）；已落地为 `flip dock` v20 + `catch` v1.3 的「腹部朝下 → 自由落体 → 回翻 →
   回夹臂 → 平台合臂 → 开 connector → 2 s → 关喷口」，真机 PASS ×3（§49）。
2. **belly-flop 峰 109.3°**（过横 19°）：与过冲同源（`Δθ=w²/2α`），用户认可为效果；若哪天要压回 90°
   只能再提 `u`（F4 ch0 +25%）或加长 HOLD（但每秒 ~±10 m 落差）。
3. **`FLIP1` 的 `wMax` 实测 1.45 rad/s > 指令上限 1.05**：循环在饱和段跟不上目标速率（不影响结果，但说明
   `coneKA/滤波` 在高速率下有滞后；VERDICT 只把 FLIPBACK 的 `wMax3 ≤1.4` 当判据）。
4. ✅ **夹取闭环（2026-11-18 完成）**：`catch.lua` 合臂 + 开 connector → 火箭开 ch5/ch6 → 2 s → 关喷口；实测不掉（用户确认「完美」）。
5. **残余偏航**：机体 Y 轴无控制通道，撞一下带 ~7°/s 偏航；用锥角前要现算 yaw（`frameYawOff=180°` 已三次复现）。
6. **爬升剖面太慢**：`CLIMB` 28~32 s（限速 `VY_HOVER=3`）；若嫌慢可加 `VY_CLIMB≈8` + sqrt 减速曲线（只省时间）。
7. **接近段横向反向偏慢**（2026-11-18 v30 实测）：vh 0→4.86 m/s 用 2.3 s ≈ **2.1 m/s²**，而 18° 倾角上限本可给 3.57。
   两个成因：① `ctrlGuide` 的 `vTarget=clamp(apKP·err,±apVmax)` 在误差变小时提前收力；② 倾角是 1.3 s 的慢执行器。
   候选：TILT_MAX 18→22（4.45 m/s²，cos 0.927 > COS_ABORT 0.86 仍安全）+ 横向改 `√(2·a·err)` 速度帽。
8. **末端带误差降到臂高**（2026-11-19 v32 撞塔）：RETURN 把船降到 h≈0 时横向还差 1~3 m ⇒ 与臂/塔架持续剐蹭 ⇒ 摆幅递增 ⇒ COS_ABORT。
   v33 用**对齐门**（臂上方 2.5 m 先对齐再降）治；v34 证明门**会抬升**（到 −0.14 又被推到 +2.5）⇒ v35 加**锁存**（|h|≤0.5 后永不抬升）。
9. **`PRECONN` 放在会 abort 的腿之后 = 静默不执行**（v34 真实事故：RETURN abort ⇒ 火箭侧连接器没开）。凡"必须有"的写操作要放在**必经路径**上（BRAKE 之后、RETURN 之前）。
10. **捕获半径必须 < 提交门槛**（v35 真实事故：`apCaptureR 2.0` > `apClearTol 1.5` ⇒ 1.5~2.0 m 之间"既不追位置也不许下降" ⇒ 在点位附近晃悠）。
11. **程序没跑到 `SECURED`/`done` 就停 ⇒ CSV 未 flush**（v35 事故：读到 0 字节，其实还在缓冲）。**磁盘 335 KB 是够的**，别误判成"磁盘满静默跳过"。
12. **游戏运行时 CSV 被进程占用**：`digest_csv.ps1` 会报 "used by another process" ⇒ 先 `Copy-Item` 到 `temp/` 再 digest。
13. **接近段速度目标用恒定 cap 会超调**（v36）：1.3 s 倾角滞后让速度环超调 ~30%（目标 3.5 → 实际 4.6）⇒ 到点前必须掉头急刹。用**制动曲线** `√(2·a·(perr−captureR))`，且**在捕获半径处归零**。
14. **"固定悬停高度等对齐"必然看起来在停+上下晃**（v33~v37，用户否决）：`apVySqrtA 3.0` ⇒ dh=0.5 m 就命令 1.7 m/s，一旦 h 恒定就变成振荡（实测 3.6 s 内 vy 换向 6 次）。末端要么**连续斜降**（比例净空），要么别用 √ 剖面。
   与 7 同源（速度帽恒定、无随距离收缩），同样用横向 √ 剖面或死区滞环解决。

---

## 9. 下一步（建议顺序，一步一上机、能回滚）

| 步 | 做什么 | 验收标准 | 风险 |
|---|---|---|---|
| **A** | ✅ `guide` v4 参数收尾 | 末端 ≤0.5 m | ✅ 已达标（0.42） |
| **B** | ✅ `flip` v3：下落中 0→90° | 峰值 80°、不跑飞 | ✅（§24.3/§24.4） |
| **C** | ✅ `flip` v8 `back`：横躺→竖直（锥角 + 反推） | θ→2.8、`VERDICT PASS` | ✅（§32/§33） |
| **C+** | ✅ `flip` v15：**整条 V 连贯**（锥角两段 + 刹车开满） | 到刹车结束 ≤185 m、`aborted=nil` | ✅ **149.8 m / 余量 60 m**（§41） |
| **D** | ✅ **任务剖面 `flip dock` + 合臂握手** | 回夹臂 err ≤3 m、`|vy|≤0.8`、`|vh|≤2`、cos ≥0.95、不撞地板 | ✅ 真机 PASS ×3（err 0.0~0.2，§49） |
| **E** | 把已验证链并进 `starship.lua`：`FALL→FLIP→RETURN→CATCH→HOLD` | 一条命令跑完整链、`SECURED` 后不掉 | 中 |
| **F** | 首飞：从 (10,217,70) 放手 | 标称成功（被夹住） | 高 |

**下一轮候选（一次只做一条，按性价比）**

1. **v38：末端"连续下降"**（最急）：`hTgt = capH + apClearK·perr`（`apClearK≈0.35`）取消固定悬停；小 `dh` 改线性/降 `apVySqrtA`。**验收：RETURN 的 h 单调下降、无 ≥1 s 平台**。
2. **并进 `starship.lua`**（E 步）——其余剖面参数已冻结（过冲/接近段/吸力/合臂全 PASS ×4）。
3. （可选）横向反向加速度：vh 0→4.86 m/s 用 2.3 s ≈2.1 m/s²（18° 上限 3.57）。
4. **磁盘水位**：computer/0 的 `log/` 已 562 KB，`free` 只剩 **145.8 KB**（阈值 120 KB）⇒ **该删 `log/flip_*.csv` 了**（主机侧全部已归档）。
2. **横向反向加速度**：`TILT_MAX` 18→22 + 横向 `vTarget = min(apVmax, √(2·apAMax·err))`；验收看 vh 斜率 ≥3 m/s²、末端不再来回。
3. **末端 limit-cycle**：同上（√ 剖面）或加 `|posErr|<0.5 && |vh|<0.5` 滞环。
4. **F2 · 落地剖面**（若还要做软着陆）：把 ~25 m 余量变成「降高度 + 收位置 + 软触地」。
5. **爬升加速**：`VY_CLIMB≈8` + sqrt 减速曲线（只省时间，不影响动力学）。
6. **串任务程序**：把已验证的 V + 落地并进 `starship.lua`（D/E 步）。

**已暂停的结构方向（记录在案，别重启）**：RCS / 横向喷口要**结构大改**（现动力在机尾且空间占满，
喷口是 `create:encased_fan + thrust_nozzle` 两个方块）。RCS 需先做「整合引擎」+「单方块 RCS 喷口」两个新方块。
在此之前主线继续**锥角（大角度）+ 差动（竖直后精修）分时**；气动件对小转速无效（8 块对称风帆实测，§25.1）。

---

## 10. 数据文件索引

| 文件 / 目录 | 内容 |
|---|---|
| `memo/flight/starship15-ROUND.md` | 本轮工作卡（≤60 行，每轮覆写） |
| `memo/flight/starship15-constants.md` | **唯一真相源**：可信常数、能力上限、控制律、黑名单 #1~#20、程序版本表 |
| `memo/flight/starship15-catch.md` | 归档 §11~§59（§56 v35 PASS、§57 v36 PASS、§58 v37 PASS、§59 末端"停住+上下晃"被否决与 v38 计划） |
| `cc_logs_archive/*.csv` + `*.txt` | 真机原始数据（`flip_v15_…0408`、`flip_v31_dock_PASS_…1155`、`flip_v36_dock_PASS_…1227`、`flip_v37_dock_PASS_…1231/1234`；事故 `flip_v32/v33/v34_…`） |
| `cc_logs_archive/lua_versions/computerN/` | 旧版本源码（`flip_v3…v31/v34/v35/v36/v37`、`catch_v1.4`；v32/v33 漏存：v32 = v31 + `dockBrakeAMax 2.0`，v33 = v32 + 对齐门） |
| `run/flight_logs/*.csv` | 模组记录仪（机体系 `wb*`、喷管力矩 `propM*`）—— 交叉验证用 |
| `.tmp_luatest/harness.lua` | 桩世界干跑夹具 |
| `temp/flip_stub_v15c.lua` | 桩内验尾部相位的副本（`flipAlt 120 / floorH -400`） |