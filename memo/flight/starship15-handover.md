# 星舰助推器复刻 —— 交接文档（2026-11-17 收盘）

> 给"下一个接手的人（或下一次的自己）"看的**一页纸**。细节全在 `memo/flight/starship15-catch.md`，
> 本文只放：现在到哪了、哪些数是可信的、程序怎么跑、下一步做什么、哪些坑别再踩。

> ⚡ **每轮开工先读** `memo/flight/starship15-ROUND.md`（本轮工作卡，≤60 行）+ `memo/flight/starship15-constants.md`（常数表 / 黑名单）。
> 本文只在**换人、换会话、或大阶段收尾**时通读一次 —— 别每轮都读。

---

## 0. 一句话现状

**三条控制通道（竖直 / 姿态 / 横向）都已在实机上跑通并标定完毕，还差"大角度翻转 + 任务集成"。**

| 层 | 程序 | 状态 | 关键结论 |
|---|---|---|---|
| 竖直（定高/剖面） | `cal1` §16 | ✅ PASS | `A_REF=4 / A_BRK_MAX=5 / KV=6` 可用，定高 h ±0.06 m |
| 力矩通道标定 | `att` v2 §20 | ✅ 闭合 | pairA→body X（负）、pairB→body Z（正）、`α=1.0 rad/s²/单位D`、`I≈15.9 kg·m²`、力臂≈1.0 m |
| 姿态环（四元数 PD） | `tilt` §21 | ✅ PASS | KP=3.0 KD=2.5；±6°/±9° 阶跃误差 ≤0.8°；被撞后 1.5 s 恢复 cos 1.00 |
| 横向环（位置+速度） | `guide` §23 | ⚠️ 结构 OK / 参数待收 | 悬停保持 0.02 m、末端 0.11 m；MOV 5 s 装不下 6 m、速度超调 16% |
| 大角度翻转 0→90→115→90 | — | ❌ **未做** | 下一步 |
| 任务集成 + 夹取 | `starship.lua` | ❌ 旧版缺姿态环，需按 §20/§21/§23 重写 | 再下一步 |

---

## 1. 可信的物理常数（全部实测，别再重测）

| 量 | 值 | 出处 |
|---|---|---|
| 重力 | **G = 11 m/s²** | §13/§14/§15/§19/§21 五次一致 |
| 推力模型 | `F = N × 256 × 2.05 × p × scale`（pN） | §14/§16/§20 |
| 机体质量 | **61.00 kg**（链质量夹住时 219 kg） | §11 |
| 满推力 TWR | 平台 p≈0.887 时 **3.44~3.55**；云层 p≈0.58 时 ≈**2.26** | §13/§16 |
| 悬停油门（5 台） | **u_hover ≈ 0.284**；**只开 4 台**（ch0=0）时 **0.348~0.351** | §16/§23 |
| 差动增益 | **α = 1.0 rad/s² 每单位 D**（D=0.22 时 0.22）；两路相同 | §20（Lua + 飞行记录仪交叉验证） |
| 差动映射 | `pairA(ch1/ch3)` → body **X**，`D>0 ⇒ ωX<0`；`pairB(ch2/ch4)` → body **Z**，`D>0 ⇒ ωZ>0`；正交、**真串扰 0** | §19/§20 |
| 推力对 `setThrustScale` 的阶跃滞后 | **≈0.15 s**（一阶） | §19/§20 |
| 机体惯量 / 力臂 | **I ≈ 15.9 kg·m²** / **r ≈ 1.0 m** | §20（由 propM 与 α 反解） |
| 控制频率 | **20 Hz**（dt 中位 0.050 s），每周期**只有一个** `parallel.waitForAll` 写块 | §15.5/§16/§23 |
| 锥角坐标系 | `setDirection(1,0)` 的 model +X → 世界 **(yaw+180°)**（三次复现） | §13/§14/§16 |
| 推力方向 | 严格沿**机体上方向**（机体系 `|Fh|=0`，无寄生横向分量） | §23.5（记录仪） |

**⇒ 由这些常数推出来的能力上限**

```
姿态权限 α_max = 1.0 × D_max ≈ 0.33 rad/s²（D_max≈0.33，悬停油门下）
纯差动翻 90°：t = 2√(θ/α) ≈ 4.3 s，自由落体掉 ~100 m   ← 所以翻转必须靠机体倾角做主
悬停横向加速度 = G·tan(tilt)：10° ⇒ 1.94 m/s²；18° ⇒ 3.57；25° ⇒ 5.13
```

---

## 2. 三条控制律（都已验证，直接抄）

```lua
-- 竖直（定高 / 剖面）
vyRef = clamp(K_POS*(hTarget-h), ±VY_HOVER)          K_POS=0.9 VY_HOVER=3
aDes  = clamp(KV*(vyRef-vy), -A_ACC, A_BRK_MAX)      KV=6 A_ACC=6 A_BRK_MAX=5
u     = clamp(m*(G+aDes)/cosVert / Fmax4, 0, 1)      Fmax4 = 4*256*2.05*p
cosVert < COS_MIN(0.25) ⇒ u=0

-- 姿态（四元数 PD）
n_w = up × u_cmd  (把机体上方向拽到指令方向的世界转轴)
e   = acos(up·u_cmd)；n_b = R⁻¹n_w ⇒ e_x,e_z(rad)
a_x = KP*e_x - KD*w_x     a_z = KP*e_z - KD*w_z      KP=3.0 KD=2.5
D_A = clamp(a_x/GAINX, ±room)   D_B = clamp(a_z/GAINZ, ±room)   GAINX=-1.0 GAINZ=+1.0
room = min(0.33, u-0.02, 1-u)
ch0=0；ch1=u+D_A ch3=u-D_A；ch2=u+D_B ch4=u-D_B     ← 每对和为 2u，净竖直推力不变

-- 横向（位置 → 速度 → 加速度 → 倾角）
vTarget = clamp(LAT_KP*posErr, ±LAT_VMAX)        LAT_KP=0.30,  LAT_VMAX=1.5→(建议 1.2)
aDes    = clamp(LAT_KV*(vTarget-vHoriz), ±LAT_AMAX)   LAT_KV=1.00, LAT_AMAX=2.0
theta   = atan(|aDes|/G) ≤18°；u_cmd = normalize(cosθ·ŷ + sinθ·âDes)
死区 |posErr|<0.20m 且 |vh|<0.08 ⇒ 不给倾角       （末端对接建议 0.08）
```

**反复强调的三条硬道理**（都是实测撞出来的）

1. **机体倾斜方向 = 推力方向 = 漂移方向**（§21.5）。想加速就往那倾，想刹车就往速度反方向倾。
2. **机体倾角是慢执行器**：18° 摆动要 ~1.3 s ⇒ 横向环带宽必须 ≪0.7 rad/s，别往上加 KP。
3. **没有姿态环之前绝不能用倾角/锥角拉横向**（§15.2 的正反馈翻车）。现在姿态环有了，禁令解除。

---

## 3. 程序清单（都在 `run/saves/New World/computercraft/computer/0/`）

| 程序 | 用法 | 干什么 | 日志 | 状态 |
|---|---|---|---|---|
| `probe.lua` | `probe` | 只读识别、TWR 预测、**把夹持点写进 `/log/target.txt`** | `/log/probe_0.txt` | ✅ 已跑 |
| `cal1.lua` | `cal1` / `cal1 zero` | 释放→落体→满推力脉冲→定高悬停→锥角探针 | `/log/cal1.csv` | ✅ 已跑（§16） |
| `att.lua` | `att` / `att zero` | 悬停工作点上的正反差动对 + 开环回正 | `/log/att.csv` | ✅ 已跑（§20） |
| `tilt.lua` | `tilt` / `tilt zero` | 姿态 PD 跟踪 ±6°/±9° 倾角阶跃 | `/log/tilt.csv` | ✅ 已跑（§21） |
| `guide.lua` | `guide` / `guide zero` | 横向闭环：悬停保持 + 沿 −Z 平移 6 m 再回来 | `/log/guide.csv` | ⚠️ 已跑（§23.5，参数待收） |
| `starship.lua` | `starship` | 任务程序（**旧版，缺姿态环**） | `/log/starship.csv` | ❌ 待重写 |
| `fixture.lua`（在 `computer/1`） | `fixture` 常驻；`status`/`release`/`clamp`/`arms <deg>`/`open`/`close` | 夹具命令端 | `/log/fixture_1.txt` | ✅ 可用 |

**通用约定**

- 所有测试程序：`xxx zero` = 应急归零；结尾 **只把喷口 `setThrustScale=0` + `setDirection(0,0)`，引擎保持 100%**（用户规则）。
- **跑测试前夹具电脑必须先跑 `fixture`**（轴承无转速时会拒绝释放）。
- 释放要在**同一个 `parallel` 块**里关掉所有 connector（顺序写会各吃 1 tick，造成倾斜→漂移→翻车）。

---

## 4. 硬件与频道（实测）

| 体 | 频道 | 设备 | 备注 |
|---|---|---|---|
| 火箭 | 0 | 矢量喷管**中心** | `inverted=true`，**测试里一律 scale=0**（无力臂、反推力） |
| 火箭 | 1 / 3 | 矢量喷管前/后 | 差动 ⇒ body X 力矩（pairA） |
| 火箭 | 2 / 4 | 矢量喷管左/右 | 差动 ⇒ body Z 力矩（pairB） |
| 火箭 | 5 | 上方两个 docking connector | `setRedstoneOutput(5,15)` = 夹紧 |
| 火箭 | 6 | 下方燃料 connector | 强充能强度=红石输出值 |
| 夹具 | 7/8/9 | fuel/right/left connector | |
| 夹具 | 10/11 | 左右 `aero_bearing` | `setTargetAngle(30/-30)`=张开，`0`=搭接；**必须有轴向输入转速**（实测 ±256） |
| — | — | 引擎 | `setThrottle(1)` ⇒ 256 RPM（保持常开） |

---

## 5. 世界几何与安全方向

| 项 | 值 |
|---|---|
| 世界 | 超平坦；**地面 Y=63**；云层 ≈**Y=195** |
| 回收塔 | x≈11，Y 63~102，**z≈12（+Z 侧）** |
| 夹持平台 | **+Z**，实测边界到 z≈+6（去那儿必撞） |
| 夹臂 | **X 两侧，臂高 ≈Y 95** |
| 夹持点 | ≈**(11, 95, 0)**；`target.txt` 的 `dockY=93.047` 是机体原点 |
| 未来释放点 | ≈**(10, 217, 70)**（比夹持点高 ~122 m、+Z 偏 ~70 m，对应直播的"落点在后方 69 m"） |

**安全方向表**：`−Z` 无限 ✅｜`±X` 只要 y<93（低于夹臂）⚠️｜`+Z` 悬停高度**禁止** ❌｜竖直 `h ≥ −30`（地面）。

---

## 6. 工具（改完 Lua 先干跑，别直接上机）

```
.tmp_luatest/harness.lua   桩世界（桩外设 + 桩物理 + 虚拟时钟）
用法： lua54 harness.lua <程序路径> [推力滞后τ, 默认0.08]
    物理：α=1.0/单位D、p(y)=1-0.00371(y-63)、一阶推力滞后、G=11、mass=61
输出：完整 status 行 + 每个日志文件的行数（能抓"日志没写进去"这类 bug）
```

已知它抓过的真 bug：脉冲基准没串接、`S0` 取错（脉冲期爬升 20 m）、`pcall(fs.open(...))` 写法错导致 status 文件恒空。

**日志摘要器（每轮取数都走它，别把 CSV 读进对话）**

```
tools/digest_csv.ps1   把 50 KB~60 MB 的 CSV 压成 ≤30 行看板
用法： powershell -File tools/digest_csv.ps1 <log.csv> [-Cols h,vy,u] [-T0 12 -T1 20 -Win 0.5] [-HeaderOnly]
输出： 每相位 n / dtMd / 各列 min..max (avg) →last  +  自动判据
       SLOW(dt>0.07) / GAP(>1 s 停顿) / ATT_DIP(cosVert<0.9 且 u>0.1)
       SAT(u 打满) / SINK(出推力还在掉高度) / ERR_END(收尾 posErr>0.5) / COL_SHIFT(行字段数不符)
       + 同目录 status_*.txt 尾部
注意： 本脚本正文必须保持纯 ASCII（PS 5.1 读无 BOM 的 UTF-8 会按 ANSI 解码，中文注释会吞代码行）
```

---

## 7. 已踩的坑（按"下次一定还会再踩"排序）

| 坑 | 后果 | 修法 |
|---|---|---|
| 横向环增益按"位置误差"直接给大 | 倾角爬升慢 ⇒ 速度超调、过冲数米 | `LAT_KP≈0.3 / LAT_VMAX≤1.5 / LAT_AMAX≤2.0` |
| 开环 ±对称倾斜当"居中测试" | 每步 1.5 s 爬坡 ⇒ 横向冲量不对称，净漂 5~9 m | 一律走**速度闭环**给倾角 |
| 悬停高度设在平台/塔附近 | 实测在 z≈+6 撞平台（ω 被踹 0.2 rad/s） | 只在 −Z 活动；`capH` 用 −8（y≈85） |
| `pcall(fs.open(path,"w"))` | `fileH` 恒 nil，status 文件 0 字节 | 写 `pcall(fs.open, path, "w")` |
| CSV 相位名带逗号（`A(1,3)+`） | 整行错位，解析全崩 | `ph:gsub(",","")` |
| 顺序写红石 / connector | 释放瞬间倾斜 → 推力侧漏 → 漂移翻车 | 全部塞进**同一个** `parallel` 块 |
| 锥角偏转当横向阻尼 | 俯仰耦合正反馈，被掀翻（cosVert 0.06） | **先有姿态环**再用横向；阻尼交给 PD |
| `cosVert=0`（横躺）时出集合推力 | 越推越快往下掉 | `COS_MIN` 门控 + FLIP 段（待做） |
| `getOrientation()` 可能 `w=−1` | 四元数镜像 | 入口统一归一 `w>0` |
| 日志读取抢跑 | 读到 0 字节的半成品文件 | 看 mtime 是不是还在涨；或直接读 `status` 的最后一行 |

---

## 8. 未解决 / 待验证（按优先级）

1. **大角度翻转**（0°→90°→115°→90°）：PD 只在小角度（±9°）验证过；90° 时 `cosVert≈0`，
   竖直通道必须关掉/降权，翻转靠倾角+差动；**掉高预算**：纯差动 4.3 s ⇒ 100 m（释放点到夹持点有 122 m，够用）。
2. **横向环末端精度**：`DB_POS` 收到 0.08 m、`MOV` 时长 7 s、`LAT_VMAX` 1.2 —— 改完要再跑一遍 `guide` 确认。
3. **跨相位衔接**：`starship.lua` 里 `FALL→FLIP→LAND→HOLD` 的切换判据（"越过目标高度"误判是已知缺陷 §18.3）。
4. **夹取闭环**：`close` + 关推力验证是否真被夹住（代码在 `fixture.lua`，未联调）。
5. **锥角精修**：现在锥角通道完全没用；`frameYawOff=180°` 已在 §16 复现三次，可用于 ±15° 精修。
6. **残余偏航**：机体 Y 轴没有控制通道，撞一下就会带着 7°/s 的偏航（§21.5）；对位置夹取无害，
   但会转动锥角的模型系 —— 用锥角前要现算 yaw。

---

## 9. 下一步（建议顺序，每步都能独立上机、能回滚）

| 步 | 做什么 | 验收标准 | 风险 |
|---|---|---|---|
| **A** | `guide` 参数收尾（MOV 7 s、VMAX 1.2、末端死区 0.08） | `MOV/RET` 末端 ≤0.5 m、`HOLD` ≤0.15 m | 低 |
| **B** | 新程序 `flip.lua`：**下落中**做 0→90° 翻转（集合推力按 cosVert 门控，抬起角速度用 PD） | 到达 90±5°、cosVert ≥0.95、掉高 ≤100 m、落地不死 | 中高（可能砸，但机体不坏） |
| **C** | `flip.lua` 加 115° 过冲段（用倾斜消横向速度） | 横向速度被压到 ≤2 m/s、姿态回到 90° | 中 |
| **D** | 重写 `starship.lua`：`FALL→FLIP→LAND（竖直剖面+横向环）→HOLD→CLOSE` | 悬停到 (11,95,0) ±0.5 m，`close` 后不坠 | 高 |
| **E** | 首飞：从 (10,217,70) 放手 | 标称成功（被夹住） | 高 |

**建议下一轮就只做 A**（半小时的量），把数据拿稳再上 B。

---

## 10. 数据文件索引

| 文件 | 内容 |
|---|---|
| `memo/flight/starship15-catch.md` | 主文档：§11~§16 标定飞行、§19~§20 力矩通道、§21 姿态环、§22 世界几何、§23 横向环 |
| `memo/flight/flip-autopilot.md` | 落地剖面 / 四元数姿态环 / 20 Hz 写预算 / COS_MIN 门控（通用经验） |
| `memo/propulsion/advanced-thrust-nozzle.md` | 喷管 Lua API 与推力模型 |
| `memo/peripherals/cc-rednet-communication.md` | 跨电脑命令协议 |
| `memo/peripherals/my_aero_sensor.md` | INS 读数与门控 |
| `run/saves/.../computer/0/log/*.csv, *.txt` | 每次实跑的原始数据（att/tilt/guide/cal1/starship） |
| `run/flight_logs/*.csv` | 模组自带 FlightDataRecorder（机体系角速度 `wb*`、喷管力矩 `propM*`）—— 交叉验证用 |
| `.tmp_luatest/harness.lua` | 桩世界干跑夹具 |
