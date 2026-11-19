# 星舰复刻 · 可信常数与黑名单（唯一真相源）

> **规矩**：这里每个数都是实机实测过的。**不许重测、不许重推、不许在别处复制**；
> 新的实测替代某个数时，**只改那一行**并注明日期。
> 出处指 `memo/flight/starship15-catch.md` 的章节号。

## 1. 物理常数（五次以上一致，别再测）

| 量 | 值 | 出处 |
|---|---|---|
| 重力 | **G = 11 m/s²** | §13/§14/§15/§19/§21 |
| 推力模型 | `F = N × 256 × 2.05 × p × scale`（pN） | §14/§16/§20 |
| 机体质量 | **61.00 kg**（夹住时链质量 219 kg） | §11 |
| 满推力 TWR | 平台 p≈0.887 → **3.44~3.55**；云层 p≈0.58 → **≈2.26** | §13/§16 |
| 悬停油门 | 5 台 **u≈0.284**；只开 4 台（ch0=0）**0.348~0.351** | §16/§23 |
| 差动增益 | **α = 1.0 rad/s² 每单位 D**（两路相同，阶跃滞后 0.15 s） | §20（Lua + 记录仪交叉验证） |
| 差动映射 | `pairA(ch1/ch3)` → body **X**，`D>0 ⇒ ωX<0`；`pairB(ch2/ch4)` → body **Z**，`D>0 ⇒ ωZ>0`；正交、串扰 0 | §19/§20 |
| 惯量 / 力臂 | **I ≈ 15.9 kg·m²** / **r ≈ 1.0 m** | §20 |
| 控制频率 | **20 Hz**（dt 中位 0.050 s），每周期**只有一个** `parallel.waitForAll` 写块 | §15.5/§16/§23 |
| 锥角坐标系 | `setDirection(1,0)` 的 model +X → 世界 **(yaw+180°)**（三次复现） | §13/§14/§16 |
| 推力方向 | 严格沿**机体上方向**（机体系 `|Fh| = 0`，无寄生横向力） | §23.5（记录仪） |
| 场地 | 超平坦；地面 **Y=63**，云层 ≈**Y=195**；塔 x≈11 / z≈12；**夹持点实测 (11.50, 93.05, −0.49)**（probe 电脑 0、夹住时、mass 61，2026-11-18 14:30）⇒ `h=0` 就在夹臂里。⚠️ `computer/1/log/target.txt` 存的是**平台体**（mass 133.75、z=+11.2），不是机体 | §22 + probe |
| 阻力模型 | **没有空气阻力/升力模型**。只有 **通用阻力**：`F = −m·d·v`，**d = 0.09**（Sable `DimensionPhysics.DEFAULT_UNIVERSAL_DRAG`，可被维度 `dimension_physics` 覆盖）；等价于每子步 `v ← v/(1+d·Δt)` 的连续近似 `dv/dt = −d·v` ⇒ **减速度 `a = d·v` 与质量无关**（质量在力与加速度里抵消）。API：`ss.getUniversalDragForce(m, V)` | `wiki/docs/sensor-system/fmc.zh.md` L298-327 |
| 由此推出 | 自由落体终端速度 **`G/d ≈ 122 m/s`**（154 m 落差内根本到不了，实测 8.6 s 只有 ~48 m/s）；速度衰减时间常数 **τ = 1/d ≈ 11 s** ⇒ 横向漂移与残余角速度**几乎不会自然衰减**，必须主动刹/主动倾 | 同上 |

## 2. 由常数推出来的能力上限

```
姿态权限 α_max = 1.0 × D_max ≈ 0.33 rad/s²（悬停油门下）
纯差动翻 90°： t ≈ 2√(θ/α) ≈ 4.3 s，自由落体掉 ~100 m   ← 翻转必须靠机体倾角做主
实测带速率环（wMax=0.30）回翻 90° ≥ 5.4 s，入口 vy≈-40 ⇒ **要 ~250~300 m 落差**（§27）
★ 锥角偏转（矢量喷管，主翻转执行器）**已实测 2026-11-18**（记录仪 §28）：
  15° 满锥角 + 5 台满推力 ⇒ **α ≈ 2.5 rad/s²**（wbX 6.40→6.55 s 内 5.66→6.04），**峰值 w = 7.30 rad/s（418°/s）**
  ⇒ 90° 只要 **~1.2 s**、落差 **~55 m** ⇒ 是差动的 **7~8 倍**，让「回落中回翻」从不可行变可行
  横躺时竖直分量 = sin15°·F ≈ **0.26 × 全推力** ⇒ 云端 p≈0.55 时 `a_vert ≈ 0.56 G`（能减半重力、不够悬停）
  偏转 slew **160°/s**（8°/tick，15° 满偏 ≈2 tick）；出力系数 **0.25/tick**（0→1 用 4 tick）
  `setDirection(x,y)` = **摇杆语义**：方向=方位角、长度=锥角比例（0=直出、1=满 15°，按单位圆钳位）
  **ch0（中心喷口）必须 `setInverted(true)`**，其余 4 台不反（用户 2026-11-18 实测）
★ **锥角力矩 ∝ 几何推力**：`α_可用 = α_full(at u=1) × u`。用满推力 α 做环的分母会让循环以为有 2.2 倍力矩（u=0.45）⇒
  必然饱和 + 过冲（2026-11-18 v11 桩/真机同源）。环的 `tilt` 与刹车曲线 `sqrt(2·α·|e|)` 都必须用 `α_full×u`。
★ **锥角极性（`setDirection(0,±1)` 的方向）是喷管映射属性，不是腿属性** ⇒ 一次探针锁定后跨腿复用（v11 `CONE_SGN`）；
  所以「洗掉残速才好探针」的 DAMP 腿只在**首次**锥角腿之前才有意义。
★ 翻转段**推力封顶** `u`（v14 起 `coneUCap=0.60`）：v8 曾用 0.45 治「飞远」，但实测它同时限制了**力矩**：
  过冲 `Δθ = w²/2α`、`α = 2.0·u` ⇒ 0.45 时 60°/s 交接必然过冲 ~38°（过 90° 后 `cosVert<0` ⇒ 推力朝下，vy −45）；
  0.60 ⇒ α=1.2、过冲 ~25°、腿短 ~1.5 s、竖直分量 +33%，代价是横向推力 +33%（drift 72~90 → ~140 m）
★ **高度不是杠杆**（v13 实测）：推力 ∝ 气压 ⇒ 翻转点每高 10 m，锥角腿多掉 ~8.5 m ⇒ 净赚 ~1.5 m/10 m。余量只能靠**权限**（u / arrestBrk / ch0）买
★ 刹车段的**真实权限**：`u=0.80` 时 4 台在 p≈0.82 给 net 11.9 m/s²；`u=1.0` 给 **17.3**、加 ch0 约 **24** ⇒
  `arrestBrk` 12 只用了硬件的一半多；43 m/s 入口：12 m/s² ⇒ 65 m，16 ⇒ 54 m，24 ⇒ 38 m
★ 反推刹车（`BRAKE`）固定 22° 会给**反向过冲**：实测 HOVER 段反向滑回 **+28.9 m**（v8）；
  **v14 实测 dz −103.0 → drift 82.3 ⇒ 回滑 20.7 m，方向朝塔**（这就是 SN15 直播的签名，`run` 模式保留）
  ⇒ **2026-11-18 用户改判：允许用比例式反推去消它**（「可以用，做好备份，看效果，不好就回退」）。
  v16 实现：`a_lat = clamp(brakeKV·|vh|, 0, brakeAMax=tan22°·G)`、`lean = atan(a_lat/G)`，`CFG.brakeProp=true`；
  回退口径 = `brakeProp=false`（v15 固定 22° 与源码备份都在）。
  旧否决证据仍留档：v15 的「硬」比例式（不随 vh 归一）会把 BRAKE 从 5.25 s 拖到 9.00 s、drift 140.9→169.2 —— 那是**尾部刹不住**的版本，不是 v16 这个。
★ 完整 V 落差预算（2026-11-18 v9 真机实测）：`FLIP1` 起点 → 竖直 = **163.5 m**（h 149.5→−14），
  其中 `FLIPBACK` 一腿 **129.5 m**、入口 vy **−23 m/s**（峰值 −36.5）；`BRAKE`@12 m/s² 还要 ~30 m
  ⇒ 整条 V 需 **~240 m** 房间 ⇒ `flipAlt = 190`（y≈283，build limit 320）+ `BRAKE` 用 `arrestBrk = 12`
  **横向刹车不随推力变大**：稳态 a_lat = G·tan22° = 4.4 m/s² ⇒ vh 23.6→2.5 要 5.4 s / 64 m（F3 待做）
★ **完整 V 实测剖面（v15 真机 PASS，起点 189.5，全量 CSV，2026-11-18 04:08）**：FLIP1 2.15 s / **5.4 m**（u=0.60）｜
  HOLD 0.10 s / 1.4 m｜FLIPBACK 3.25 s / **92.8 m**（峰 109.3°、vy −34.8、vh 25.2）｜BRAKE 3.20 s / **47.6 m**（vh→2.1）｜
  ARREST 0.00 s｜HOVER 6.0 s ⇒ **到 BRAKE 结束只花 149.8 m**、距 `floorH=-20` 还剩 **~60 m**（v11 是余量 ~0）。
  **刹车段 `u` 均值 0.832 且瞬时打到 1.00 ⇒ 真的开满了**；drift 89.1（Z 回滑为用户指定的 SN15 签名，保留）。
  ⚠️ `HOVER` 6 s 内降不到 `capH=-8`（刹车后停在 ~40 m、按 3 m/s 剖面要 16 s）⇒ **不要**再按「软着陆触地」去做：
  用户 2026-11-18 明确 **目标是夹臂**（夹持点 (11,95,0)、`target.txt` 的 y=93.047 ⇒ h=0），不是地面。
★ **任务剖面 `flip dock`（v20，2026-11-18）**：腹部朝下释放 → 自由落体 → 锥角回翻 → 反向刹车 → 回夹持点 → 合臂握手 → 关喷口。
  常数：`dockUCap=1.0`（u=0.6 时回翻腿 3.25 s/93 m，从云层高度起翻会在刹车前落地；1.0 ⇒ α=2.0、近横躺竖直分量 ≈0.6 G ⇒ 腿 ~1.5~2 s）；
  `dockReserve=100 m`（回翻+刹车+末段所需高度）、自由落体深度 `fallPlan=clamp(h0-0-100, 0, 22)`（**按实测高度截断，不写死云层**）；
  `RETURN` 用 guide v4 环，`retVmax=2.0`、容差 5 m（**docking connector 有短距吸力，用户 2026-11-18**）。
  ✅ **真机 PASS ×3（2026-11-18 14:23 / 14:40 / 15:29）**：FALL 2.00 s/20.8 m（vy −19.9）｜FLIPBACK **1.85 s/46.4 m**（vh 24.0、wMax 1.25）｜
  BRAKE **6.20 s**/dz −87.4（vh 24→2.4、u 均值 0.497）｜RETURN 12.1 s（gap 18→err 2.1）｜ARMS 2.7 s（err 0.0、cos 1.000）
  ⇒ 停在 (11.50, −0.5, h=0)。**高度预算实测够**：release h=119.5 全部用完仍停在 h=0，未触发 FLOOR GUARD。
★ **任务剖面 `flip dock` v30（2026-11-18，最新真机 PASS 的版本）**：投放点改成高空 **≈(9.5, 306.5, +66.9)**，横距 **67 m**。
  - `dockReserve=140`（数值上 = 翻转点 h；y≈233、p≈0.507、TWR≈1.96）、`dockFallDrop=80`、`dockFallVy=−45`、`dockFallMaxT=5.0`
  - **F4 已开**（`ch0On=true`）：ch0 加入集合推力 = 5 台，`FmaxFiring` 按 5 台折算，全剖面 u 降 20%；`setInverted(true)` 在 `discover()` 里
  - α 随气压：`alphaNow = coneAlphaDeg·u·(p/0.5495)`（`coneAlphaRefP` 就是 v20 PASS 的气压）
  - **交班用惯性**（v26）：FLIPBACK 目标 = `brakeLean(vh)` 函数，越过竖直 `flipHandoverDeg=2°` 即交给 BRAKE（不再停死在 0°）
  - **锥角必须在只靠差动的腿前打直**（v27）：`DIRCMD={0,0}`（`nil` 不写 setDirection，见黑名单 #21）
  - 接近段：`apVmax=3.5 / apAMax=3.5 / apKP=0.45 / apKV=1.3 / apVyMax=6.0 / apVySqrtA=3.0`；竖直 `vyRef=−min(VY,√(2·apVySqrtA·|dh|))`（符号随 dh，#22）
  - ✅ 真机 PASS 连续 9 轮（v22~v30）：FALL 3.95 s/76.5 m｜FLIPBACK 1.90 s/73.7 m｜BRAKE 6.5 s 停 z=−24.9｜RETURN 10.5 s/err 0.8｜ARMS err 0.1
  - ⚠️ **过冲对 `dockBrakeAMax` 极敏感**（塔压在停止距离边缘）：`2.6 ⇒ 1.6 m`、`1.5 ⇒ 24.4 m`；实测有效横向减速度 ≈ **`2.7 + 1.0·dockBrakeAMax`**（锥角自身贡献 ~2.7，倾角只 ~2.6）
  - 灵敏度：**在 D≈2.0 处，D 每 +0.1 ≈ 过冲 −1.5~2 m**；**一次别动超过 0.3**。真正的自变量是**刹车入口 vh**（见下）
  - ⚠️ 横向反向实测：vh 0 → 4.86 m/s 用 **2.3 s（≈2.1 m/s²）**；18° 上限本应给 3.57 ⇒ 差距来自"环在目标附近提前收力 + 倾角 ≈1.3 s 摆动滞后"
  - ⚠️ 末端 6 s 在目标附近 **limit-cycle**（vh 2~4 m/s 来回、posErr 0.8），TRIM 因此跑满 4 s
★ **v31 真机（2026-11-19 11:55 PASS，`dockBrakeAMax=2.2`）**：过冲 **4.6 m**（v30 的 24.4 m → 4.6 m）
  FALL 3.95 s/76.5 m｜FLIPBACK 1.90 s（h 135.1→61.9、**vh 26.6**）｜BRAKE 4.55 s/横向 **72.0 m**（结束仍 h=31.0）｜
  RETURN **6.8 s**/err 0.2（垂直降 30.9 m）｜TRIM **0.50 s**（v30 是 4.05 s 超时）｜ARMS err 0.1 → PASS。
  ✅ **`a_eff = 2.7 + 1.0·D` 被精确证实**：`26.6²/(2×72.0) = 4.91 = 2.7 + 2.2`。
  ⇒ **过冲 = `vh²/(2·(2.7+D)) − 67.4`，vh ±1 m/s ⇒ 过冲 ±6~7 m**（D=2.0：25.6→2.3、26.6→7.9、27.6→13.6 m）。
    调 D 之前**先看 FLIPBACK 的 vh 有没有变**，D 的"最优值"是 vh 的函数（v30/v31 两轮都是 26.6，可信）。
  ⚠️ 顺带：本剖面下**末端 limit-cycle 不复现**（TRIM 0.5 s），§50 第 ③ 条在这个入口 vh 下自愈。
★ **v32 真机（2026-11-19 11:59 撞塔 COS_ABORT，`dockBrakeAMax=2.0`）**：过冲 **9.8 m** ✅（目标 8~10 命中）
  ⇒ **`dockBrakeAMax = 2.0` 定稿**（1.5→24.4 m、2.2→4.6 m、2.0→9.8 m，公式三连中）。
  ❌ **但末端撞了**：RETURN 把它降到 **h=−0.1 时横向仍差 1.1 m**（＝在臂的高度、臂的结构里）⇒ TRIM 全程无收敛
  （posErr 1.1→3.3→0.07→2.0、lean ±16°、vh 4.5）⇒ ARMS 荡到 x=2.6（离目标 9 m）再以 **vh 7.1 / lean −21.8°** 冲回 ⇒ `COS_ABORT`。
  判读：**接触**（跟臂/塔架剐蹭的冲量）把这个环越推越大，不是制导律自己发散；v31 只是恰好落在正中心（err 0.2）才幸存。
★ **v33（2026-11-19，代码+干跑完成，待真机）：末端"对齐门"** —— 只加一条调度规则，不动任何环的增益
  `ctrlGuide` 里：横向误差 > `apClearTol(0.45 m)` 时竖直目标 = `capH + apClear(2.5 m)`（在臂**上方**对齐，不碰结构），
  进 0.45 m 才提交下降、下沉封顶 `apCommitVyMax(1.0 m/s)`、回差 `apClearRelease(0.90 m)`。
  只对带横向目标的腿（RETURN/TRIM/ARMS）生效；HOVER 传 nil 不受影响（`back`/`run` 回归 PASS）。
  ⚠️ 安全阀：TRIM 结束仍在夹持点上方 >1 m ⇒ **不发 `dock` 请求**、保持悬停（v21「臂夹空」的反向保护）。
  ⚠️ 2.5 m 净空在塔架结构上够不够＝未验证；仍剐蹭则 `apClear → 3.0`。
★ **v33 真机（2026-11-19 12:07 COS_ABORT）**：门**有效**（RETURN 全程 h 2.28~2.73 m 没降），
  但横向环在门高度上**不收敛**：posErr 0.69→1.0→2.0→3.5→4.5→3.4→1.2→1.8→3.0→4.3 m、vh 到 **5.1**、`lean` **−26°**（指令上限 18°）、`perp` +17° ⇒ cos 0.856 撞。
  0.45 m 的门只碰到一次（0.69）就被 0.90 m 回差顶回 ⇒ **11.5 s 悬停**（用户："反推太狠、下降太低、不连贯"）。
★ **v34（2026-11-19，代码+干跑完成，待真机）：把最后一米交给连接器吸力**（用户 2026-11-19 定案）
  `apClearTol 0.45→1.5`（进 1.5 m 就下降）、`apClearRelease 0.90→2.5`、`apCommitVyMax 1.0→2.0`；
  **两侧提前通电**：flip `ch5/ch6=15`（TRIM 前，`PRECONN`）、catch v1.4 `conn 7/8/9=15`（RESET 后即通电，等请求期间就有吸力）。
  干跑 `dock` PASS（RETURN 12.0 s/err 0.5、TRIM 7.00 s/err 0.37、ARMS err 0.2）。
  ⚠️ 未解：真机横向环为何摆到 −26°（桩里不出）；吸力兜不住 ⇒ 降 `apKV`、近距收缩 `apVmax`。
★ **v34 真机（2026-11-19 12:17 又 COS_ABORT）**：过冲 9.8 m ✅；RETURN `h` **到过 −0.144**（确实降到夹持点）却被横向摆动推过 2.5 m 回差 ⇒ **门把它抬回 +2.5 m**（这就是"下降太慢/一跳一跳"）。
  火箭侧连接器没开＝**bug**：`PRECONN` 当时放在 TRIM 前，RETURN abort ⇒ 那行没执行（catch 侧的 `PRECONN` 正常）。
★ **v35（2026-11-19，代码+干跑完成，待真机）**：`PRECONN` 移到 **BRAKE 后**；
  `apKP 0.45→0.30` / `apKV 1.30→0.85`（闭环 ω √(KP·KV)：0.76 → **0.50 rad/s**，1.3 s 倾角执行器要求 <0.7）；
  **捕获区** `apCaptureR 2.0`（进 2 m 只刹速度、不追位置；近距 `apTiltNear 12°`；TRIM 退出容差 = `apCaptureR×0.75`）；
  **门锁存** `apLatch`（|h|≤0.5 后永不抬升；TRIM/ARMS 用 `initCommit` 继承提交状态）；
  `dockBrakeVy 4.5`（BRAKE 段下沉上限，通用 `VY_HOVER` 仍是 3.0）。
  **设计转变**：末端不追求 0 误差，1~2 m 交给连接器吸力 ⇒ DOCK REPORT err ~1 m 属正常（`retPosTol 5.0` 判 PASS）。
★ **v35 真机（2026-11-19 12:23 PASS，末端首次全绿）**：BRAKE 5.25 s（vy −4.4、h=23.1、过冲 **10.8 m**）｜`PRECONN` 在 BRAKE 后执行 ✅
  RETURN 10.3 s/err 0.6/`gate=true`（**无回弹**）｜TRIM 0.35 s/**err 0.00**｜ARMS 2.6 s/**err 0.0** → `DOCK REPORT err 0.0` **PASS**。
  唯一抱怨："在点位附近晃悠好久才接近" ⇒ **元凶 `apCaptureR(2.0) > apClearTol(1.5)`**：1.5~2.0 m 这段既不追位置也不许下降。
★ **v36（2026-11-19，代码+干跑完成，待真机）**：`apCaptureR 2.0→1.0`（**必须 < apClearTol**）、`apCommitVyMax 2.0→3.0`、
  `apGateMaxT 4.0 s`（悬停等待超 4 s 强制提交下降，靠吸力吃 2 m 偏差）。增益一个没动。
  ⚠️ v35 的 CSV 0 字节：程序在 `close()` 前被停 ⇒ **必须跑到 SECURED/done 再停**。
★ **v36 真机（2026-11-19 12:27 PASS，连续第二次）**：RETURN **6.9 s**（v35 10.3 s）/ err 1.0、TRIM 0.40 s、ARMS **err 0.0** → `DOCK REPORT err 0.0` PASS。
  仍"晃悠几下" ⇒ **接近段速度剖面太陡**：横向目标恒定 `apVmax 3.5`，实际冲到 **4.6 m/s**（1.3 s 倾角滞后 ⇒ 速度环超调 ~30%），2 m 处只能把 `lean` 从 **+16° 翻到 −9.5°** 才停。
★ **v37（2026-11-19，代码+干跑完成，待真机）**：`apVmax 3.5→2.2` + **制动曲线**
  `vTarget = min(apVmax, √(2·apBrakeLat·max(perr − apCaptureR, 0)))`、`apBrakeLat = 2.0` ⇒ **在捕获半径处速度归零**（不加 `−apCaptureR` 偏移会以 2 m/s 冲进捕获区再追回来）。
  桩扫描 `apVmax` 2.2/2.5/2.8/3.0/3.5 ⇒ 末段 err 1.2/1.5/2.0/2.9/4.5（越低越准），只有 2.2 判 PASS。
  ⚠️ 工具：**游戏运行时 CSV 被占用**（StreamReader "used by another process"）⇒ 先 copy 到 `temp/` 再 digest。
★ **v37 真机（2026-11-19 12:31 PASS ×3，err 0.0）**：制动曲线见效——RETURN 8.1 s（v36 6.9 s，慢 1.2 s）
  **`max|lean|` 16.2°→13.4°、`max vh` 4.65→3.03 m/s、lean 掉头反转 1 次→0 次**，末段 err 0.4→TRIM 0.00→ARMS 0.0。
  过冲 **10.2 m**；BRAKE 5.15 s。
  ❌ **否决**：`brakeVh 2.5→1.0`（让 BRAKE 多刹一点以减小残余漂移）——桩里 BRAKE 6.2→**9.0 s**（撞上限）、停点再远 **4.8 m**、RETURN 反而变慢 ⇒ 保持 2.5。
  ⇒ **`flip dock` 剖面收官**（v35/v36/v37 连续三次 PASS，末端 err 0.0）。
★ **用户对手感的裁定（2026-11-19 12:37，必须执行）**：**"在点位上方停住 + 上下晃 + 平移过去"不可接受**，要求**平滑连续下降到点位**。
  实测（v37，RETURN 164 行）：h 在 **1.96~3.04 m 卡了 3.6 s**，`vy` **−2.7~+1.46（6 次换向）**，同时横向平移 posErr 8.8→1.5。
  ⇒ **两个成因（记死）**：
  ① **固定悬停高度**对齐门 `hTgt = capH + apClear`（v33 设计）；
  ② **√ 剖面在 dh 小时太凶**：`apVySqrtA 3.0` ⇒ dh=0.5 m 就命令 **1.7 m/s** ⇒ 悬停必然上下荡。**连续下降时无害，一旦"停住"就振荡**。
  ⇒ **v38 已实施**：`hTgt = capH + apClearK(0.35)·perr` + `apHFloor` 棘轮（只取历史最小、首帧夹到 `st.h` ⇒ 绝不先升）；竖直剖面 `vyRef = min(vyCap, √(2·3.0·|dh|), apLinK(2.0)·|dh|)`（两曲线在 dh=1.5 m / 3.0 m/s 相交，无缝）。
  ⇒ **v38 真机（2026-11-19 13:06 PASS，err 0.0）**：RETURN 7.8 s、h **23.25→0 单调**（回升 0.001 m）、**vy 换向 0 次**（v37 是 6）、TRIM 0.00 s、ARMS err 0.0；
     但 `apGateMaxT` 秒表在 +4 s 切断斜线（最后 4.2 m 贴点位高度横移）⇒ **v39 = 失速检测**（`apStallEps 0.20`）。
  ⇒ **v39 真机（2026-11-19 13:15 PASS）**：RETURN 8.5 s / err 0.2 / `tgt=+0.09`、h **回升 0.000 m**、**最长平台 0.10 s**、vy 换向 0、
     **h<0.5 m 时横向只剩 0.63 m**（v38 是 4.2 m）、TRIM 0.25 s / err 0.00、ARMS err 0.0 ⇒ 末端"斜着贴上去"成立（catch §62）。
  ⇒ **验收（v38 起）**：RETURN 期间 h **单调下降**，**末段不得"贴点位高度横移"**（v38 实测的最后 4.2 m）。
★ **合臂/抓取时序（`catch.lua` v1.5 起：臂连接器主固定、加油口后接，2026-11-19 用户裁定）**：
  **分工**：臂连接器 = 火箭 **ch5** ↔ 塔 **ch8/ch9**（主固定）；加油口 = 火箭 **ch6** ↔ 塔 **ch7**（锁上之后才接）。
  **锁定判据**：`simulated:docking_connector.getConnectedName()`（`""`=未锁，否则对面物理体名；`references/Simulated-Project-main` `DockingConnectorPeripheral.java`）；
  容差 **0.5 格 / 20°**（`SimPhysics.dockingConnectorDistanceTolerance/AngleTolerance`）。读取路径自动探测：邻近外设 → `ss.getPeripheral(0..15)`（短程链接器贴连接器）。
  **提前合臂**：RETURN 里 `|h|≤earlyReqH` **或** `perr≤earlyReqPerr(2.0)`（OR，谁先满足谁触发）就发 `dock` 请求、船继续飞；臂 90°→0 @2rpm **实测 5.3 s**（`SETTLE ok after 5.3 s`）。`earlyReqH` 与提前量的实测曲线（v42 场次，请求在 h=7.8 发出）：**8→5.35 s、10→5.70、12→6.05、14→6.40、16→6.70、18→7.05、20→7.40**；**定稿 v44：`earlyReqH = 20.0`（用户实飞判定"完美"）⇒ 提前量 7.40 s，臂 5.3 s ⇒ 余量 ~2.1 s**。回执早到会带速度关喷口 ⇒ v43 起 `replySettleT 1.5 s`（`|vh|,|vy|≤0.30` 才放行）。**夹取时序到此收官（§67）。**
  ⚠️ v40 用 `AND`（1.5/1.5）时直到 `h=+0.8` 才发请求 ⇒ 臂在船停稳后才动（§64）。
  ⚠️ **锁定探针只许读臂通道 8/9**：v1.5 扫 0..15 命中了 ch7（加油口，故意不通电）⇒ 永远 `NOT DETECTED`（§64）。
  时序：复位臂 ±90 + arm/fuel=0 → **arm=15、fuel=0**（待命吸力）→ 等 `dock` 请求 → 合臂 0（同一 parallel 块）→ **`getConnectedName()≠""` 才算锁上**
  → 才 `fuel ch7=15` → 回 `clamped`（**未锁不发回执**，火箭保持悬停）→ 火箭收到后才 `ch6=15` + `setStrongPower(6,true)` → `sleep 2 s` → 关喷口。
  ⚠️ 旧口径（v1.4 及以前）是 ch5/ch6 与 7/8/9 **一起通电**，导致加油口在"抱"火箭 ⇒ 已废。
  ⚠️ 诊断三连（都已在 memo §46~§48）：① rednet 不排队（目标不在 receive 就丢，send 仍返回 true）；
  ② `getRotationSpeed` ≠ 实际转动；③ 0.5 s 发 / 0.9 s 收的拍频会凭空多出 ~3 s。
  ⚠️ **但「提速」实测无效**（v12）：75°/s 让交班角动量 0.85→1.1 rad/s，多出来的动量把省下的落差全吃回去（208.7 vs 207.1，
  且 `BRAKE` 入口更深、`HOLD` 过冲到 100.1°）。**1.31 rad/s 的刹车距离 `w²/(2α)` ≈ 54° ⇒「快翻」与「停在 90°」不能兼得**。
  真正有效的余量手段是**加高度**（v13 `flipAlt 200`）+ 最终净空 ≥10 m（v12 就是擦地 `COS_ABORT`）。
悬停横向加速度 = G·tan(tilt)： 10°→1.94 / 18°→3.57 / 25°→5.13 m/s²
机体倾角是慢执行器：18° 摆动 ≈1.3 s ⇒ 横向环带宽 ≪0.7 rad/s，别加 KP
```

> 方向锁刚性规矩（§27 撞出来的）：**永远不要一次性命令 90° 误差**（θ≈90 处 `tiltError` 的 (e_x,e_z)
> 符号会两边都错，桩里 θ 88→97）；分级目标 ≤30~36° 误差 + 每级探测式锁（θ 动 0.2° 即判定符号）。
> **但锥角执行器不适用上式**：它直接给力矩（`α≈2.5`），必须做成闭环（偏转角 ∝ 误差 − 角速度阻尼），
> 开环定角会像 2026-11-18 实测那样滚 2 圈以上（§28）。
> **锥角 + 差动不能同刻并联**：差动余量 `room = min(DMAX, u-0.02, 1-u)`，满推力 `u=1 ⇒ room=0`
> ⇒ 要腾出差动空间必须降到 `u≤0.67`，锥角力矩同时掉到 67%（2.0→1.34），净亏。
> 正确分工是**分时**：大角度翻转用锥角满推力；竖直后姿态保持/微调（`u≈0.35` 或锥角斜坡已降到 `u<0.4`）用差动 PD。

## 3. 三条已验证控制律（直接抄，不要重新发明）

```lua
-- 竖直（定高 / 剖面）
vyRef = clamp(K_POS*(hTarget-h), ±VY_HOVER)          K_POS=0.9  VY_HOVER=3
aDes  = clamp(KV*(vyRef-vy), -A_ACC, A_BRK_MAX)      KV=6  A_ACC=6  A_BRK_MAX=5
u     = clamp(m*(G+aDes)/cosVert / Fmax4, 0, 1)      Fmax4 = 4*256*2.05*p
cosVert < COS_MIN(0.25) ⇒ u = 0

-- 姿态（四元数 PD）
n_w = up × u_cmd   (把机体上方向拽到指令方向的世界转轴)
e   = acos(up·u_cmd)；n_b = R⁻¹ n_w ⇒ e_x, e_z (rad)
a_x = KP*e_x - KD*w_x        a_z = KP*e_z - KD*w_z        KP=3.0  KD=2.5
D_A = clamp(a_x/GAINX, ±room)  D_B = clamp(a_z/GAINZ, ±room)   GAINX=-1.0  GAINZ=+1.0
room = min(0.33, u-0.02, 1-u)
ch0=0；ch1=u+D_A  ch3=u-D_A；ch2=u+D_B  ch4=u-D_B   ← 每对和为 2u，净竖直推力不变

-- 横向（位置 → 速度 → 加速度 → 倾角）
vTarget = clamp(LAT_KP*posErr, ±LAT_VMAX)            LAT_KP=0.30  LAT_VMAX=1.2（v2 收尾，v1 是 1.5）
aDes    = clamp(LAT_KV*(vTarget-vHoriz), ±LAT_AMAX)  LAT_KV=1.00  LAT_AMAX=2.0
theta   = atan(|aDes|/G) ≤ 18°；u_cmd = normalize(cosθ·ŷ + sinθ·âDes)
死区 |posErr| < 0.08 m（v2，v1 是 0.20）且 |vh| < 0.08 m/s ⇒ 不给倾角；|aDes| < 0.08 m/s² 也不给倾角
相位时长 MOV/RET = 9 s（v3）／7 s（v2）／5 s（v1）；对应末端 err ≈0.2~0.4 ／0.84 ／1.19 m（实飞）
```

**三条硬道理（都是撞出来的）**

1. **机体倾斜方向 = 推力方向 = 漂移方向**（§21.5）。想加速往那倾，想刹车往速度反方向倾。
2. **倾角是慢执行器**，横向环带宽 ≪0.7 rad/s。
3. **没有姿态环之前绝不能用倾角/锥角拉横向**（§15.2 正反馈翻车）。现在姿态环有了，禁令解除。

## 4. 已否决假设黑名单（想到这几件事，立刻停手）

| # | 念头 | 为什么不行 | 出处 |
|---|---|---|---|
| 1 | "真实 pN/RPM 只有 0.898，TWR≈1.53" | 那是被夹臂吃推力的产物；用户实测 **2.03~2.06 pN/RPM** | §13.3/§13.4 |
| 2 | 用锥角偏转做横向阻尼/平移 | 同一通道同时给力**和力矩** ⇒ 俯仰正反馈，cosVert 0.06 掀翻 | §14.3/§15.2 |
| 3 | `hoverCtrl` 用 `h - target` | 符号反，越低于目标越往下钻 | §14.2 |
| 4 | 顺序写 connector/红石 | 每条约 1 tick ⇒ 机体微倾 ⇒ 推力侧漏 ⇒ 漂移/翻车 | §15.3/§16.1 |
| 5 | 开环对称倾斜做"居中测试" | 1.5 s 爬坡 ⇒ 横向冲量不对称，净漂 5~9 m | handover §7 |
| 6 | 把 cal1 的 `VY_TOUCH=0.9` 抄进悬停程序 | 会一直以 −0.9 m/s 往下钻，永远进不了 hold | §17.3 |
| 7 | 相位名/日志串里带逗号 | CSV 整行错位，解析全崩（digest 会报 `COL_SHIFT`） | handover §7 |
| 8 | `pcall(fs.open(path,"w"))` | `fileH` 恒 nil，status 文件 0 字节 | handover §7 |
| 9 | `getOrientation()` 直接当 w>0 用 | 实测可能 `w = −1` ⇒ 四元数镜像；入口统一归一 `w>0` | §11.3 |
| 10 | 用文档/直觉猜力矩符号 | 已实测 pairA→X(负)、pairB→Z(正)，别猜 | §20 |
| 11 | `string.format("0%")` | Lua 把 `% e` 当格式符 ⇒ `0%%` | §14.5 |
| 12 | `cosVert≈0` 时出集合推力 | 越推越快往下掉（横躺阶段几乎全程在门控区） | §18.3 |
| 13 | 直接相信程序自己打印的数（`ALPHA/MAP/TWR` 等） | 已多次发生"打印值不可信、回原始曲线重算才对" | §19.1/§20 |
| 14 | connector 脱落要擦「强充能」才松 / 只写一个通道就掉 | **接线事实（用户 2026-11-18 `startup.lua` 实测）**：`ch5` = 机体**上方左右两个** docking connector（固定主用）；`ch6` = 机体**下方一个** connector（`setStrongPower(6,true)` 强充能，给燃料）；**脱落 = 两个通道输出清零**（`ch5`/`ch6` → 0），一次 parallel 块内写完；强充能跟脱落无关 | §28 |
| 15 | 「回翻只能靠差动（α=0.33）⇒ 要 250~300 m ⇒ 不可行」 | **已被 2026-11-18 实测推翻**：矢量喷管满锥角(15°)+满推力 α≈**2.5 rad/s²**（7~8 倍），回翻 90° 只要 1.0~1.2 s / ~49 m ⇒ 可行；差动只是辅助通道 | §28 |
| 16 | 用全局 `args` 表读命令行参数 | **CC:T 把程序参数当变参 `...` 传进来**，`args` 是经典 CC 的遗留全局（本环境根本是 nil）⇒ `flip back` / `thruststress drop` 两次**静默跑默认分支**。老项目（`New World (2)` 的 `autopilot.lua` L590）一直用 `local args = { ... }` 所以从没踩过。**正确写法**：`local ARGV = { ... }; if #ARGV == 0 and type(args)=="table" then ARGV = args end` | §31/§32 |
| 17 | 把「姿态角度的比例（P）环」当锥角环用（`wDes = K·e`） | 角度**指数衰减**（τ=1/K）：90→30° 1.2 s，30→3° 要 2.6 s ⇒ 一腿 4 s、掉 137 m。必须用刹车曲线 `wDes = min(WMax, sqrt(2·α·|e|))` | §35/§36 |
| 18 | 锥角环分母用满推力 α、或用差动（u=0）做横躺段阻尼 | 前者以为力矩是 2.2 倍 ⇒ 过冲；后者 u=0 时差动几乎无权限（0.33 rad/s²），刹不住 1 rad/s ⇒ 翻过 90°（桩 114.7° OVERFLIP） | §36 |
| 19 | 「把翻转点抬高就有余量」 | **v13 实测否掉**：推力 ∝ 气压 ⇒ 高 10 m 反让锥角腿多掉 **8.5 m**（FLIPBACK 128→136.5），净赚只有 ~1.5 m/10 m | §39 |
| 20 | 用「更温的速率曲线」治「翻过 90°」的过冲 | 过冲 `Δθ=w²/(2α)`，α=2·u 由推力封顶决定；实测循环一直钉在速率帽、**根本没跟曲线** ⇒ 压需求无效，唯一出路是提高 α（`coneUCap` 或 ch0） | §39 |
| 21 | `DIRCMD = nil` 以为能把锥角打直 | `commitTable()` 收到 `dx=nil` **不写 setDirection** ⇒ 喷口保持上次偏转。RETURN 段 5.4° 锥角 ≈ **0.83 rad/s²**，而差动只有 0.29 ⇒ `dA` 满舵也压不住，机体 +8°→−30° 触发 COS_ABORT | §50（v26 事故） |
| 22 | 平方根下降剖面写成 `max(dh,0)` | `dh=capH−h` 在目标**上方**是负数 ⇒ 目标下沉速度恒 0，机体 25 m 悬停 55 s 不降（TRIM 硬拽到 −0.8） | §50（v29 事故） |
| 23 | 程序参数打错（`flip fock`）当小事 | 未知 MODE 落到 `run` 分支：**先脱落再 COS_ABORT**，306 m 白掉、零数据 | §50（v23 事故）；v24 起入口校验，非法**不脱落** |
| 24 | 过冲量按线性直觉调 | 塔压在停止距离边缘 ⇒ `dockBrakeAMax` 2.6→1.5 让过冲 1.6→24.4 m | 用 `有效a ≈ 2.7+1.0·dockBrakeAMax` + `s=vh²/(2a)` 预估（§2）；**一次 ≤0.3**（v31 例外：两模型一致 + 用户裁定，1.5→2.2，§51） |
| 25 | 用"固定悬停高度 + 等待对齐"做末端（v33~v37 的对齐门） | 用户 2026-11-19 明确否决："在点位上方停住、上下晃悠、平移过去"。而且 `apVySqrtA 3.0` 让 dh=0.5 m 就命令 1.7 m/s ⇒ 一停就荡（实测 3.6 s 内 vy 换向 6 次） | 改**比例净空** `hTgt = capH + apClearK·perr`（斜着连续降），小 dh 用线性（**v38 已实施**：`apClearK 0.35` / `apLinK 2.0` / `apHFloor` 棘轮 / 首帧夹到 `st.h`，§60）；验收＝h 单调下降、无 ≥1 s 平台（§59） |
| 26 | 把 `brakeVh` 收紧来减小 RETURN 开头的残余漂移 | 桩扫描：2.5→1.0 让 BRAKE 6.2→**9.0 s**（撞 cap）、停点再远 **4.8 m**、RETURN 反而 16.3→17.9 s | 保持 `brakeVh = 2.5`；那 1.6 s 的残余漂移刹车属于"过冲→回程"拓扑的固有代价（§58） |
| 27 | 把"永不停住"的安全阀做成**固定秒表**（v36~v38 的 `apGateMaxT` 纯计时） | 它会在**正常下降途中**触发：v38 真机在 RETURN +4 s（perr 7.9 m、h 3.5 m、vy −1.4 正在跟剖面）就把目标压到夹持点，`0.35·perr` 斜线被切断 ⇒ 最后 4.2 m 变成"贴点位高度横移" | 安全阀按**没有进展**判：`apHMark` 记最低 h + `apStallEps 0.20`，单调下降永不触发（§61） |

## 5. 程序清单与版本锚点（维护这张表，别在别处记）

> 程序目录：`run/saves/New World/computercraft/computer/{0,1}/`
> 旧版本存**主机侧** `cc_logs_archive/lua_versions/computerN/xxx_vN.lua`（电脑磁盘只有 1 MB，见 §6 的警告）；
> 每个程序头写 `-- VER vN @date  CHANGELOG:`。

| 程序 | 电脑 | 状态 | VER |
|---|---|---|---|
| `probe.lua` | 0 | ✅ 已跑，写 `/log/target.txt` | 待补 |
| `cal1.lua` | 0 | ✅ 已跑（§16），竖直通道标定 | v5 |
| `att.lua` | 0 | ✅ 已跑并闭合（§20），力矩通道 | v2 |
| `tilt.lua` | 0 | ✅ PASS（§21），姿态 PD ±6°/±9° | 待补 |
| `guide.lua` | 0 | ✅ 已跑 PASS（v4，末端 0.42 m，§23.9） | v4 |
| `flip.lua` | 0 | **v30 = `dock` 任务剖面（高空投放 y=306.5 → 自由落体 73.5 m → 翻转 h=140 → 反推 → 过冲 → 回夹持点 → 合臂握手）**：F4/ch0 已开、α 随气压、交班用惯性、锥角打直、√ 下降剖面、MODE 校验；**v22~v30 连续 9 轮真机 PASS**；`run`/`back` 保持 v15 行为；**v31 = 2.2 PASS；v32~v34 末端撞；v35/v36/v37 连续 PASS（err 0.0）；但 v37 的末端"停住+上下晃"被用户否决 ⇒ 真机 **v39 PASS**（RETURN 8.5 s / err 0.2 / h 回升 0.000 / 平台 0.10 s / vy 零换向 / **h<0.5 m 时横向仅 0.63 m**、TRIM 0.25 s、ARMS err 0.0）⇒ **`flip dock` 剖面收官（v35~v39 连续 PASS）**；**v40 = 臂连接器主固定 + 提前请求（`earlyReqH/Perr 1.5`）+ 收到锁回执才开 ch6；**v41 = AND→OR + 提前量 4.0（§64）；v42 = `earlyReqH 8.0`（§65）；**v44 = `earlyReqH 20.0`（用户实飞定稿，提前量 7.40 s）+ 无速度才关喷口 ⇒ 夹取时序收官（§67）** | v44 |
| `catch.lua` | **1** | 平台合臂序列（`catch`）：复位臂 ±90 + conn ch7/8/9=0 → 等火箭 `dock` 请求（或按键）→ 合臂 0 @2rpm + conn=15（一个 parallel 块）→ **用 `getTargetAngle()` 判到位**（`getRotationSpeed` 返回的是配置转速常量，不可用）→ 回 `clamped`（**每 0.1 s** 重试 15 s：rednet 不排队 + 发/收周期拍频）**；v1.4 起 RESET 后连接器提前通电；**v1.5：arm(8/9) 与 fuel(7) 分开写 —— 待命只开 8/9，`getConnectedName()≠""` 才算锁上，锁上后才开 ch7 并回 `clamped`（未锁不发回执），读取路径自动探测（邻近外设 / `ss.getPeripheral(ch)`）；**v1.6 = 探针只读臂通道 8/9、LOCK 行打印每通道状态、DONE 区分成败 —— 真机全绿（`ch8=[S42] ch9=[S42]`、FUEL 在锁后，§65/§66）** | v1.6 |
| `thruststress.lua` | 0 | 一次性测（v4）：**默认就断 ch5/ch6 脱落**（含 `setStrongPower(6,false)`）再测 REL/FULL/DPOS/DNEG/OFF（6.6 s，无需通信）；`measure` 只测不脱落、`cone` 追加锥角 4 朝向、`fixture` 才走 rednet；写 `/log/usts_<epoch>.csv` + `status_usts.txt` | v4 |
| `dropall.lua` | 0 | 一次性脱落（v3）：`ch5`+`ch6` 输出清零（sh5=上方固定 connector、ch6=下方燃料 connector），单 parallel 块、写后回读；`dropall show` 只读打印 0..15 的 out/in/strong/device；写 `/log/status_drop.txt` | v3 |
| `startup.lua` | 0 | **用户自写**极限测试（§28）：锁 `ch5/ch6` → 5 台 `setDirection(0,-1)` 满锥角 + `ch0 setInverted(true)` → 清 0 脱落 → 2 s 后满推力；证明锥角偏转 = 主翻转执行器（α≈2.5 rad/s²） | 用户版 |
| `spin.lua` | 0 | ✅ v1 已实飞两轮（裸机 vs 8 块对称风帆，§25.1）：角阻尼几乎为零（τ≈145 s） | v1 |
| `starship.lua` | 0 | ❌ 旧版缺姿态环，待按 §20/§21/§23 重写 | v1(旧) |
| `fixture.lua` | 1 | ✅ 命令端：`fixture` 常驻 / `status` / `release` / `clamp` / `arms <deg>` / `open` / `close` | 待补 |

## 6. 工具

| 工具 | 用法 | 作用 |
|---|---|---|
| `.tmp_luatest/harness.lua` | `lua54 harness.lua <程序> [τ=0.08] [相位] [stride] [程序参数] [y0] [belly\|""] [z0]` | 桩世界干跑（桩外设 + 桩物理 + 虚拟时钟），**改完 Lua 必跑**。v23+ 用真实大气 Hermite 曲线（`p(300)=0.306`，旧线性拟合给 0.12）；新增 `sleep` 桩、程序参数透传、起始高度/姿态/z 偏移；`FIRING` 含 ch0 |
| `tools/digest_csv.ps1` | `powershell -File tools/digest_csv.ps1 <log.csv> [-Cols a,b,c] [-T0 -T1 -Win] [-HeaderOnly]` | 把 50 KB~60 MB 的 CSV 压成 ≤30 行看板（相位 min/max/avg/last + 自动判据 `SLOW/GAP/ATT_DIP/SAT/SINK/ERR_END/COL_SHIFT`），并顺带打印 `status_*.txt` |
| `tools/digest_csv.ps1`（记录仪） | `... <run/flight_logs/*.csv> -T0 12 -T1 20 -Win 0.5` | 70 MB 记录仪按时间片均值（大文件必须给 `T0/T1`） |
| `computer/0/fsprobe.lua` | 在游戏内 computer 0 执行 `fsprobe` | 文件写入门禁取证：A~E 五种 open 写法 + `free/cap`（结果同时写 `/fsprobe_report.txt`，退出世界后读） |

> ⚠️ `tools/digest_csv.ps1` **正文必须保持纯 ASCII**：PowerShell 5.1 读无 BOM 的 UTF-8 时按 ANSI 解码，
> 中文注释会吞掉后续代码行（已踩过一次，症状是"循环体没执行、数据为 0"）。

> ⚠️ **CC 电脑磁盘只有 1 MB**（`fs.getFreeSpace("/")` 上限 ~1,000,000 B、实际可用 ~880 KB）。
> 2026-11-18 实测：把 `flip.lua` 的八份旧版本塞进 `computer/0/ver/`（**544 KB**）⇒ 剩余 69.7 KB < `diskMinFree 120 KB`
> ⇒ **CSV 被静默跳过**，整轮真机数据只能靠程序自打印。**规矩**：旧版本一律存主机侧 `cc_logs_archive/lua_versions/computerN/`，
> 电脑磁盘只放运行用的当前程序（`computer/0` 14 个文件 265 KB 是正常水位）。

## 7. 相关经验文档（按需查，不要通读）

- `memo/flight/flip-autopilot.md`（320 KB，只做关键词检索）：落地剖面 / 四元数姿态环 / **写预算与 20 Hz 不变量** / COS_MIN 门控
- `memo/propulsion/advanced-thrust-nozzle.md`（58 KB）：喷管 Lua API 与推力模型
- `memo/peripherals/cc-rednet-communication.md`（10 KB）：跨电脑命令协议
- `memo/peripherals/my_aero_sensor.md`（12 KB）：INS 读数与门控
- `memo/flight/starship15-catch.md`（126 KB）：**归档**，按 §N 定向 grep（§50 = v21~v30 演进链）
