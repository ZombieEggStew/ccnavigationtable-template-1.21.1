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
★ **合臂/抓取时序（`catch.lua` v1.3，2026-11-18 定型）**：复位臂 ±90 + conn 7/8/9=0 → 等 `dock` 请求（或按键）
  → 合臂 0 **@2rpm**（伺服参考 ~5.3 s；16 rpm 太快，用户裁定）+ conn=15（一个 parallel 块）
  → 用 **`|getTargetAngle()| ≤ 0.5°`** 判到位（`getRotationSpeed()` 是配置转速常量，停着也 2.00，不能当判据）
  → 回 `clamped`（**每 0.1 s 重试 15 s**：rednet 消息不排队 + 发/收周期拍频）→ 火箭在保持里 **0.25 s 控制 + 0.5 s 收信**
  → 收到立刻开 ch5/ch6=15 → `sleep 2 s` → 关喷口。**臂到位 → 开 connector ≈0.2~0.5 s**；焊接等待 2 s（用户规格）。
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
| `flip.lua` | 0 | **v20 = `dock` 任务剖面 + 合臂握手**（腹部朝下→自由落体→回翻→比例式反推→回夹持点→发 `dock` →**0.25 s 控制 + 0.5 s 收信**轮询，收到 `clamped` 立刻开 ch5/ch6=15 → 2 s → 关喷口）：**真机 PASS ×3（2026-11-18）**；`run`/`back` 保持 v15 行为 | v20 |
| `catch.lua` | **1** | 平台合臂序列（`catch`）：复位臂 ±90 + conn ch7/8/9=0 → 等火箭 `dock` 请求（或按键）→ 合臂 0 @2rpm + conn=15（一个 parallel 块）→ **用 `getTargetAngle()` 判到位**（`getRotationSpeed` 返回的是配置转速常量，不可用）→ 回 `clamped`（**每 0.1 s** 重试 15 s：rednet 不排队 + 发/收周期拍频） | v1.3 |
| `thruststress.lua` | 0 | 一次性测（v4）：**默认就断 ch5/ch6 脱落**（含 `setStrongPower(6,false)`）再测 REL/FULL/DPOS/DNEG/OFF（6.6 s，无需通信）；`measure` 只测不脱落、`cone` 追加锥角 4 朝向、`fixture` 才走 rednet；写 `/log/usts_<epoch>.csv` + `status_usts.txt` | v4 |
| `dropall.lua` | 0 | 一次性脱落（v3）：`ch5`+`ch6` 输出清零（sh5=上方固定 connector、ch6=下方燃料 connector），单 parallel 块、写后回读；`dropall show` 只读打印 0..15 的 out/in/strong/device；写 `/log/status_drop.txt` | v3 |
| `startup.lua` | 0 | **用户自写**极限测试（§28）：锁 `ch5/ch6` → 5 台 `setDirection(0,-1)` 满锥角 + `ch0 setInverted(true)` → 清 0 脱落 → 2 s 后满推力；证明锥角偏转 = 主翻转执行器（α≈2.5 rad/s²） | 用户版 |
| `spin.lua` | 0 | ✅ v1 已实飞两轮（裸机 vs 8 块对称风帆，§25.1）：角阻尼几乎为零（τ≈145 s） | v1 |
| `starship.lua` | 0 | ❌ 旧版缺姿态环，待按 §20/§21/§23 重写 | v1(旧) |
| `fixture.lua` | 1 | ✅ 命令端：`fixture` 常驻 / `status` / `release` / `clamp` / `arms <deg>` / `open` / `close` | 待补 |

## 6. 工具

| 工具 | 用法 | 作用 |
|---|---|---|
| `.tmp_luatest/harness.lua` | `lua54 harness.lua <程序> [τ=0.08]` | 桩世界干跑（桩外设 + 桩物理 + 虚拟时钟），**改完 Lua 必跑** |
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
- `memo/flight/starship15-catch.md`（77 KB）：**归档**，按 §N 定向 grep
