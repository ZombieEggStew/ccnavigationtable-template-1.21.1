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
| 场地 | 超平坦；地面 **Y=63**，云层 ≈**Y=195**；塔 x≈11 / z≈12 | §22 |

## 2. 由常数推出来的能力上限

```
姿态权限 α_max = 1.0 × D_max ≈ 0.33 rad/s²（悬停油门下）
纯差动翻 90°： t ≈ 2√(θ/α) ≈ 4.3 s，自由落体掉 ~100 m   ← 翻转必须靠机体倾角做主
悬停横向加速度 = G·tan(tilt)： 10°→1.94 / 18°→3.57 / 25°→5.13 m/s²
机体倾角是慢执行器：18° 摆动 ≈1.3 s ⇒ 横向环带宽 ≪0.7 rad/s，别加 KP
```

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

## 5. 程序清单与版本锚点（维护这张表，别在别处记）

> 程序目录：`run/saves/New World/computercraft/computer/{0,1}/`
> 旧版本存 `computer/0/ver/xxx_vN.lua`；每个程序头写 `-- VER vN @date  CHANGELOG:`。

| 程序 | 电脑 | 状态 | VER |
|---|---|---|---|
| `probe.lua` | 0 | ✅ 已跑，写 `/log/target.txt` | 待补 |
| `cal1.lua` | 0 | ✅ 已跑（§16），竖直通道标定 | v5 |
| `att.lua` | 0 | ✅ 已跑并闭合（§20），力矩通道 | v2 |
| `tilt.lua` | 0 | ✅ PASS（§21），姿态 PD ±6°/±9° | 待补 |
| `guide.lua` | 0 | 🔶 v2 参数已收尾（§23.6），**待实跑验收** | v2 |
| `starship.lua` | 0 | ❌ 旧版缺姿态环，待按 §20/§21/§23 重写 | v1(旧) |
| `fixture.lua` | 1 | ✅ 命令端：`fixture` 常驻 / `status` / `release` / `clamp` / `arms <deg>` / `open` / `close` | 待补 |

## 6. 工具

| 工具 | 用法 | 作用 |
|---|---|---|
| `.tmp_luatest/harness.lua` | `lua54 harness.lua <程序> [τ=0.08]` | 桩世界干跑（桩外设 + 桩物理 + 虚拟时钟），**改完 Lua 必跑** |
| `tools/digest_csv.ps1` | `powershell -File tools/digest_csv.ps1 <log.csv> [-Cols a,b,c] [-T0 -T1 -Win] [-HeaderOnly]` | 把 50 KB~60 MB 的 CSV 压成 ≤30 行看板（相位 min/max/avg/last + 自动判据 `SLOW/GAP/ATT_DIP/SAT/SINK/ERR_END/COL_SHIFT`），并顺带打印 `status_*.txt` |
| `tools/digest_csv.ps1`（记录仪） | `... <run/flight_logs/*.csv> -T0 12 -T1 20 -Win 0.5` | 70 MB 记录仪按时间片均值（大文件必须给 `T0/T1`） |

> ⚠️ `tools/digest_csv.ps1` **正文必须保持纯 ASCII**：PowerShell 5.1 读无 BOM 的 UTF-8 时按 ANSI 解码，
> 中文注释会吞掉后续代码行（已踩过一次，症状是"循环体没执行、数据为 0"）。

## 7. 相关经验文档（按需查，不要通读）

- `memo/flight/flip-autopilot.md`（320 KB，只做关键词检索）：落地剖面 / 四元数姿态环 / **写预算与 20 Hz 不变量** / COS_MIN 门控
- `memo/propulsion/advanced-thrust-nozzle.md`（58 KB）：喷管 Lua API 与推力模型
- `memo/peripherals/cc-rednet-communication.md`（10 KB）：跨电脑命令协议
- `memo/peripherals/my_aero_sensor.md`（12 KB）：INS 读数与门控
- `memo/flight/starship15-catch.md`（77 KB）：**归档**，按 §N 定向 grep
