# CC:T 飞行程序全局规范 + 共享常数（新载具开工基线）

> 适用范围：本仓**所有** CC:T 飞行/测量程序 —— 星舰（`flip`/`catch`）、二喷口（`m2probe`/后续飞控）、以及以后每台新机。
> 唯一真相源分工（别搞混，也别三处各写一份）：
> - **本文** = 写程序的规范（终端/日志/骨架/参数/安全门）+ **共享物理常数快照**（§4）。新载具开工只读本文 + 本机设计文档。
> - `starship15-constants.md` = 共享常数的**原始出处**（带 §N 实测证据）+ 星舰专用实测数（α=1.0/D、I=15.9、pairA→X…）+ 27 条黑名单全集。
> - `two-nozzle-vehicle.md` = 二喷口本机的设计基线与测量计划（秩 5、滚转牺牲、`m2probe` 相位）。
> 本文不复制星舰的机体系映射与增益推导；那些搬不过来。

---

## 0. 开工只读清单（新载具第一轮）

| 顺序 | 读什么 | 目的 |
|---|---|---|
| 1 | **本文** | 规范 + 共享常数 |
| 2 | `memo/flight/two-nozzle-vehicle.md` | 本机设计/测量计划 |
| 3 | 要抄代码时才读：`run/saves/starship/.../flip.lua` 对应段 | 双通道输出、`runPhase` 骨架、门控 |

预算：**3 读 + 1 改 + 1 干跑**。禁止整篇读 `flip-autopilot.md`(320 KB) / `starship15-catch.md`(126 KB)，按 §N 定向 grep ≤80 行。

---

## 1. 终端输出规范（硬性）

### 1.1 只准 ASCII

- CC 终端字库**没有 CJK 字形**，中文打印出来是 `?` 或方块，用户读不到。
- 规则：**凡进入终端的字符串一律 ASCII** —— `print`/`say` 的文本、相位名、CSV 表头与取值、`/log` 文件名、模式名。
- `--` 注释里可以写中文（不进终端），这是历史程序的实际做法，不必清洗。
- 禁：中文、emoji、全角标点、`°` `α` `τ` `→` `×` `√`。改用 `deg`、`alpha`、`tau`、`->`、`x`、`sqrt`。
- Lua 格式串坑：`string.format("0%")` 会被当成非法格式符 ⇒ 写 `0%%`（黑名单 #11）。

### 1.2 一屏原则（终端不能翻页）

- 标准电脑终端 **51 列 × 19 行**，没有可用的分页/回滚手段；写在屏幕外的东西等于没写。
- 硬指标：**一次运行的全部打印 ≤ 15 行**、单行 ≤ 51 字符；小屏（Monitor 模块 / 口袋电脑 26×20）按 ≤ 40 字符。
- 启动横幅 ≤ 3 行；每个相位**只在进入时 1 行、退出时 1 行**。
- **绝不在 20 Hz 循环里 `print`**：刷屏 → 掉帧 → 既看不清也拖慢控制。要连续看的量写 CSV。
- 长清单（外设表、通道表、参数表）最多 15 行；超了写文件，终端只打 `list -> /log/xxx.txt`。

### 1.3 行首 TAG 规范（便于 grep 与 digest）

| TAG | 用途 | 例子 |
|---|---|---|
| `BANNER` | 启动横幅 | `BANNER m2probe v1 mode=all 20Hz` |
| `MODE` | 回显解析后的模式 | `MODE   all chA=2 chB=3` |
| `DISK` | 磁盘水位（每次写 CSV 前） | `DISK   free=812.3 KB (cap ~1 MB)` |
| `PHASE` | 相位进出 + 关键量 | `PHASE  DPOS done t=0.40 wx=+0.412` |
| `WARN` | 降级但继续 | `WARN   low disk, csv skipped` |
| `!!` | 致命/中止 | `!! ABORT COS_MIN 0.06` |
| `VERDICT` | 结论 | `VERDICT PASS` / `CHECK` / `FAIL` |
| `LOG` | 产物路径 | `LOG    /log/m2_1795090123.csv rows=164` |

- 一律左对齐、ASCII 空格对齐、数值带符号（`%+.3f`）便于看方向。
- 打印只给**结论 + 关键量**；中间推导、整张表、逐帧数据 → 文件。

### 1.4 打印不可信

程序自己打印的标定量（`ALPHA`/`MAP`/`TWR`）**不得当实测值**（黑名单 #13）。任何要写进常数表的数，必须回原始 CSV 重算，并记下 CSV 名。

---

## 2. 双通道输出：终端 + 文件（解决"终端不好复制"）

### 2.1 全部打印走 `say()`，同时落 `status_<prog>.txt`

```lua
-- 入口处打开，程序结束时 close()
local realPrint = print
local fileH = nil
local function say(...)
    realPrint(...)
    if fileH then
        local p, n = {}, select("#", ...)
        for i = 1, n do p[i] = tostring((select(i, ...))) end
        fileH.writeLine(table.concat(p, "\t"))
        fileH.flush()
    end
end
print = say
```

- 劫持 `print` 后，**程序任何位置的打印都自动双写**，不用逐处改。
- `status_<prog>.txt` = 终端全文的镜像，agent 在主机侧直接读，用户不用手抄。
- 打开方式必须直写：`local f = fs.open(path, "w")`。
  **`pcall(fs.open, ...)` 成功时返回的是 `true`，不是句柄** ⇒ `fileH` 恒 nil、status 文件 0 字节（黑名单 #8）。
- 结尾 `close()`；异常分支也要 close（`pcall` 包住或最外层收尾）。**程序没跑到 END 就停 ⇒ CSV 0 字节**（v35 教训）。

### 2.2 CSV 数据文件

- 路径：`/log/<prog>_<epoch_ms>.csv`，`os.epoch("utc")`（毫秒）天然唯一，**每轮独立文件名**，不覆盖。
- 第 1 行表头；列名 ASCII、短、无空格；**逗号是分隔符 ⇒ 任何字段不得含逗号**（相位名、程序名都不行，黑名单 #7）。flip 另加 `(ph):gsub(",", "")` 兜底。
- 必带列：`t`（秒，`os.epoch("utc")/1000`）、`ph`（相位）。其余按分析需要选，**15~20 列是上限**（星舰 19 列 ≈ 55 KB/轮；列一多就几 MB，digest 变慢）。
- 每 10 行 `flush()`，结束 `close()`。
- **磁盘守卫（必须实现）**：`fs.getFreeSpace("/") < 120*1024` ⇒ 打 `WARN low disk, csv skipped` 并**跳过 CSV**，绝不因写盘失败中止飞行。
- 采样率跟控制环一致（20 Hz）；**不要每帧 `flush`**（每帧 flush 会拖慢周期，10 行一次是实测可用的折中）。

### 2.3 主机侧读取路径（退出世界后）

```
run/saves/starship/computercraft/computer/<N>/log/<prog>_<epoch>.csv
run/saves/starship/computercraft/computer/<N>/log/status_<prog>.txt
```

- **游戏运行时 CSV 被占用**（StreamReader: "used by another process"）⇒ 先 `Copy-Item` 到 `temp/` 再读/digest。
- 数据纪律：**原始 CSV / 长日志不进对话**。只吃 `tools/digest_csv.ps1` 的 ≤30 行摘要；要看某段用 `-T0/-T1/-Win`，要看某列用 `-Cols`。
- 电脑盘只有 **~1 MB**（实测可用 ~880 KB）⇒ 旧版本、旧日志一律移到主机侧 `cc_logs_archive/`；电脑盘只放**当前在跑的程序**。`/log` 满了：先归档到主机，再进游戏删。
- 读数编码：本仓 `.md/.json/.csv` 多为无 BOM UTF-8。用 `Get-Content -Encoding UTF8` 或 `[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)`；`read`/`grep` 工具不受影响，优先用它们。

### 2.4 版本与归档

- 程序头固定：`-- <name>.lua VER vN @date  CHANGELOG:`，每条变更写**为什么 + 实测数字**（flip 的头部是范本）。
- **改之前**先把旧版 `Copy-Item` 到**仓库根**的 `cc_logs_archive/lua_versions/computerN/<name>_vN.lua`（不在存档目录里，也不进电脑盘 —— 磁盘会满）。
- 文件名 ASCII、短横线/下划线、无空格、无中文。

---

## 3. 程序骨架（照这个结构写）

顺序固定：**头部注释 → `CFG` 表 → 工具函数 → `say` → `sense()` → CSV → `runPhase` → 控制律 → 模式 → 入口**。

```lua
-- prog.lua  VER v1 @2026-11-20
-- CHANGELOG: v1 first version. <what + why>

local CFG = {
    HZ       = 20,            -- constants §1 (20 Hz, dt med 0.050)
    DT       = 0.05,
    G        = 11.0,          -- constants §1
    K_NOZ    = 256 * 2.05,    -- pN per unit scale at p=1, constants §1
    COS_MIN  = 0.25,          -- guard: below this, u = 0
    DISK_MIN = 120 * 1024,
    CSV_COLS = "t,ph,h,vy,u,wx,wz,vh,perr",
}
local unpack = table.unpack or unpack   -- CC:T runs Lua 5.1 (LuaJIT)

-- ---- args: CC passes VARARGS, the global `args` is classic-CC (blacklist #16)
local ARGV = { ... }
if #ARGV == 0 and type(args) == "table" then ARGV = args end

-- ---- log dir + status file FIRST, so even a rejected mode is on disk
if not fs.exists("/log") then pcall(fs.makeDir, "/log") end
local statusPath = "/log/status_prog.txt"
local fileH = fs.open(statusPath, "w")
-- >>> 把 §2.1 的 say() 贴在这里（它自己声明局部 fileH 就删掉那个声明），然后 print = say <<<

local MODE = tostring(ARGV[1] or ""):lower()
local VALID = { static = true, run = true }
if not VALID[MODE] then
    say("!! unknown mode '" .. MODE .. "' -- NOTHING touched")
    say("   use: prog static | prog run")
    if fileH then fileH.close() end
    return
end

-- ---- disk gate
local okFree, free = pcall(fs.getFreeSpace, "/")
free = (okFree and type(free) == "number") and free or 0
say(string.format("DISK   free=%.1f KB (cap ~1 MB)", free / 1024))
local CSVH, CSVN, CSV_PATH = nil, 0, nil
if free >= CFG.DISK_MIN then
    CSV_PATH = "/log/prog_" .. tostring(os.epoch("utc")) .. ".csv"
    CSVH = fs.open(CSV_PATH, "w")
    if CSVH then CSVH.writeLine(CFG.CSV_COLS); CSVH.flush() end
else
    say("WARN   low disk, csv skipped")
end

-- ---- one tick = ONE parallel block (blacklist #4)
local function commit(tbl)
    local jobs = {}
    for i = 1, #tbl do jobs[i] = tbl[i] end
    parallel.waitForAll(unpack(jobs))
end
```

要点：

- `CFG` 里**每个数都标来源**：`-- constants §1`（物理）/ `§2`（锥角、滞后）或 `-- measured 2026-11-20 log/prog_xxx.csv`。函数体内**不写魔数**。
- `sense()` 每 tick 只读一次，返回一个 `st` 快照（`t, h, vy, vx/vz, wx/wy/wz, q, press`）；不要在各控制律里重复调外设。
- `runPhase(name, ctrl, stop, maxT)` 统一管：计时、CSV 行、冻结检测、超时、相位打印。
- 控制律返回一个**描述表**（`{u=, da=, db=, dx=, dz=}`），由 `commit()` 一次写下去 —— 控制律本身不碰外设。

---

## 4. 共享常数（全局基线快照）

> 本节数值以 `starship15-constants.md` §1/§2 为**原始出处**（那边每条带 §N 实测证据）。
> **改值顺序**：先改 constants 对应行（+日期），再同步本节那一行。星舰专用数（α=1.0/D、I=15.9、`pairA→X(负)`、悬停油门 0.284、场地几何、vN 曲线）**只留在 constants，不要搬进本节**。

### 4.1 物理

| 量 | 公式 / 符号 | 值 | 单位 | 出处 |
|---|---|---|---|---|
| 重力加速度 | `G` | **11.0** | m/s² | constants §1 |
| 通用阻力系数 | `d`，`F = -m·d·v` | **0.09** | 1/s | constants §1 |
| 速度时间常数 | `tau = 1/d` | 11.1 | s | 上式 |
| 自由落体终端速度 | `G/d` | 122.2 | m/s | 上式 |
| 单喷口推力 | `F_i = K_NOZ · p · scale` | `K_NOZ = 256 × 2.05` = **524.8** | pN | constants §1 |
| 标定区间 | 实测 pN/RPM | 2.03 ~ 2.06（定稿 2.05） | pN | constants 黑名单 #1 |
| 矢量锥角 | 满偏 | **15** (sin 0.2588, tan 0.2679) | deg | constants §2 |
| 锥角 slew | 打角速度 | 160（8/tick） | deg/s | constants §2 |
| 出力斜坡 | `setThrustScale` | 0.25 /tick，0→1 用 4 tick | 无量纲 | constants §2 |
| 控制周期 | `HZ` / `DT` | **20 Hz** / 0.050 | - | constants §1 |
| 喷口质量 | `advanced_thrust_nozzle` | 0.5 | kg | two-nozzle §7 |
| 喷管阶跃滞后 | 实测 | **0.15** | s | constants §2 |

### 4.2 由物理常数直接推出的调度公式（任何载具通用）

```
TWR(p)      = N · K_NOZ · p / (m · G)
u_hover(p)  = 1 / TWR(p) = m·G / (N·K_NOZ·p)
平飞:  tan(theta) = d·v / G = 0.00818 · v        v 单位 m/s
       v          = G·tan(theta)/d = 122.2 · tan(theta)
悬停横向加速度  a_lat = G · tan(theta)
      10 deg -> 1.94   18 deg -> 3.57   25 deg -> 5.13    (m/s²)
锥角平飞天花板  v = 122.2 · tan(15 deg) = 32.7 ~ 33 m/s   ← 与 p、m 都无关
姿态权限        alpha = tau / I                            （I 必须实测）
```

- "低气压掉速度"是错的：竖直平衡把推力钉在 `mG/cosθ`，水平分量只剩 `mG·tanθ` ⇒ 气压只抬油门、压余量。
- "质量影响速度"也是错的：质量在力与加速度里抵消。

### 4.3 传感器 / 外设调用（二喷口实测可用）

```lua
local ss = require("ccpe.sensor_system")
ss.getAngles()                 --> {pitch, roll, yaw}   deg
ss.getAngularVelocity()        --> {x, y, z}            rad/s
ss.getVelocity()               --> {x, y, z}            世界系 m/s
ss.getPosition()               --> {x, y, z}
ss.getPressure()               --> p
ss.getPressureFromAltitude(y)  --> p
ss.getPhysicsMass()            --> kg
ss.getPhysicsCenterOfMassRel() --> {x, y, z}
ss.getPeripheral(ch)           --> 外设或 nil（ch 0..15）
```

- 每次 `pcall` 包住、返回值 `type` 校验；缺件要能降级（`WARN`）而不是崩。
- 气压优先静压孔，缺件时 `getPressureFromAltitude(y)` 兜底，并在打印里标 `(port)` / `(alt)` / `ASSUMED`。

### 4.4 常数纪律

1. 一个数只在自己程序的 `CFG` 里出现一次；函数内不内联。
2. 变量名/注释带单位：`vtMax` m/s、`wMax` rad/s、`u` 无量纲、`lean` deg。
3. 新实测覆盖旧值：**只改那一行** + 日期 + 出处 CSV。被推翻的念头写进黑名单（"想到就停手"）。
4. **没实测过的量不能当常数用**，只能标 `EST` 并写进"待测清单"。
5. 星舰的数（I=15.9、α=1.0/D、b/h/x、pairA 映射）**是那台机的属性**，新机一律重标。共同点只有：喷管阶跃滞后 0.15 s、增益量级 `KP/KD ≈ 3.0/2.5`。

### 4.5 新载具必测清单（测完才可写控制环）

| 符号 | 含义 | 怎么测 |
|---|---|---|
| `m` | 质量 kg | `getPhysicsMass()`，悬空、连接器全松 |
| `CG` | 质心相对 FMC | `getPhysicsCenterOfMassRel()` |
| `p` | 当地气压 | 静压孔，记高度 |
| `TWR` / `u_hover` | 推重比 / 悬停油门 | 上述公式 + 一次悬停实测校核 |
| `I` | 转动惯量 | 已知力矩脉冲 → `Δomega/Δt` 反推 |
| `b` | CG 到喷口距离 | FMC/CG 读数差 |
| `h` / `x` | 喷口比 CG 低多少 / 横向偏移 | `CONE` 相位与对称推力下的偏置角速度 |
| `alpha/D` | 差动每单位 D 的角加速度 | `DPOS`/`DNEG` 阶跃 |
| `alpha_cone` | 锥角权限 | 满锥角 + 已知 `u` |
| 执行器滞后 | 喷管阶跃 | 阶跃后角速度到达 63% 的时间 |

---

## 5. 参数与模式

- CC:T 把程序参数当**变参 `...`** 传进来；全局 `args` 是经典 CC 遗留（本环境是 nil）。正确写法：
  ```lua
  local ARGV = { ... }
  if #ARGV == 0 and type(args) == "table" then ARGV = args end
  ```
  否则 `prog back` / `prog drop` 会**静默跑默认分支**（黑名单 #16，真实踩过两次）。
- **未知模式必须在写任何执行器之前拒绝**并打印候选（黑名单 #23：`flip fock` 掉了一整轮 306 m 数据）。可给常见拼写提示。
- 数字参数一律 `tonumber` + 范围钳位 + 回显（`MODE` 行），不信任用户输入顺序。
- 两种模式并存时，默认模式必须**无副作用**（只读）。

---

## 6. 每 tick 写预算与时序纪律

- **每周期只有一个 `parallel.waitForAll` 写块**：所有 `setDirection` / `setThrustScale` / 红石写 / `rednet.send` 一起提交。
  顺序写 N 条 = 每条 ~1 tick ⇒ 推力不对称 ⇒ 机体微倾 ⇒ 侧漏漂移/翻车（黑名单 #4）。
- `setDirection(x, y)` 是**摇杆语义**：方向 = 方位角、长度 = 锥角比例（0=直出、1=满 15°）。**打直必须显式 `setDirection(0, 0)`**；传 `nil` 不会重置，喷口保持上次偏转（黑名单 #21，真实事故）。
- `setDirection` 的长度按单位圆钳位，不要传 >1 的合成值。
- 20 Hz 靠 `sleep(0.05)`；每轮结尾统计 `LOOP cycles / worst dt / slow count` 并打印 —— 这是判断"外设调用是否超预算"的唯一手段。
- 四元数：`getOrientation()` 可能返回 `w = -1` 的镜像 ⇒ **入口统一归一 `w > 0`** 再进姿态环（黑名单 #9）。
- 锥角与差动**分时**用，不要同刻并联：差动余量 `room = min(DMAX, u-0.02, 1-u)`，满油门 `u=1 ⇒ room=0`。

---

## 7. 安全门（每个飞行程序都必须有）

| 门 | 判据 | 动作 |
|---|---|---|
| 竖直门控 | `cosVert < COS_MIN(0.25)` | `u = 0`（横躺时满推力只会越推越快往下掉） |
| 磁盘门 | `getFreeSpace < 120 KB` | 跳过 CSV，`WARN`，继续飞 |
| 冻结门 | 连续 6 帧状态签名完全相同 | `!! ABORT FROZEN` |
| 高度地板 | `h < floor` | `!! ABORT FLOOR` / 强制拉起 |
| 超时门 | 每相位 `maxT` | 退出相位并记 `CHECK` |
| 模式门 | 未知 MODE | 什么都不写，直接 return |
| 结束门 | 收尾 | `close()` + `VERDICT` + `LOG` 路径打印 |

- ABORT 必须打印**原因 + 关键量**（`!! ABORT COS_MIN 0.06`），只打 "abort" 等于没信息。
- `VERDICT PASS|CHECK|FAIL` 三档：`CHECK` = 数值达标但手感/残速有问题（星舰 v43 就是这么抓出来的）。
- 收尾摘要行：`LOOP <n> cycles worst dt=... slow=<k>`。

---

## 8. 黑名单（新载具同样会踩的通用条目）

> 完整 27 条见 `starship15-constants.md` §4。以下是**与机型无关、任何程序都会犯**的：

| # | 念头 | 实际后果 / 正确做法 |
|---|---|---|
| 4 | 顺序写多个执行器 | 每条 ~1 tick ⇒ 微倾侧漏。**一个 parallel 块写完** |
| 7 | 相位名/日志串里带逗号 | CSV 整行错位，digest 报 `COL_SHIFT`，整轮数据废 |
| 8 | `pcall(fs.open(...))` 当句柄 | 返回 `true` ⇒ 文件 0 字节。直写 `fs.open(...)` |
| 9 | `getOrientation()` 直接用 `w` | 可能是 `-1` 镜像 ⇒ 角速度符号错。入口归一 `w>0` |
| 11 | `string.format("0%")` | Lua 把 `% e` 当格式符 ⇒ 写 `0%%` |
| 13 | 相信程序自己打印的标定量 | 必须回原始 CSV 重算（已多次"打印值不可信"） |
| 16 | 用全局 `args` 读参数 | nil ⇒ 静默跑默认分支。用 `{...}` |
| 21 | `setDirection(nil)` 打直喷口 | 不写 ⇒ 保持上次偏转。写 `setDirection(0,0)` |
| 23 | 模式名打错当小事 | 未知 MODE 落到默认分支 ⇒ 先脱落再 abort。入口校验 |
| — | 一次改多个数 | 无法归因。**一次一个数，改动幅度先按公式预估**（星舰 `dockBrakeAMax` ±0.3） |
| — | 拿估计值调环 | 只会把估计误差烘进增益。先测量，再调 |
| — | 程序没跑到 END 就停 | CSV 0 字节、status 截断。跑到 done 再停 |
| — | 把旧版本塞进电脑盘 | 盘满 ⇒ CSV 被静默跳过。旧版进主机 `cc_logs_archive/` |

---

## 9. 二喷口专用提醒（本机第一轮）

- **只沿"两喷口连线"缺一个力矩轴** ⇒ 前后排布局缺滚转，**滚转是自由轴**：无被动稳定、角度与角速度都是记忆量。设计目标是**不激励它**（绝对对称、`h=0`、`x=0`），不是"控住它"。
- `h ≠ 0` 只能换来**阻尼**，换不来"可控"：`tau_roll = h·F_lat + x·F_vert` 是恒等式，常值偏置 `x·mG` 配不平。9 kg 机上 5.5 cm 的 `x` 就够 90°/0.93 s。
- 增益不要因为"权限比星舰大 10 倍"就放大：`KP/KD` 保持 ~3.0/2.5，**要改的是分母** `GAIN = alpha per unit D`（本机预计 10 量级，必须实测）。姿态环带宽 ≲ 1.5~2 rad/s（喷管滞后 0.15 s）。
- 抗干扰三件套：**常值配平积分项**、**角速度反馈为主**、**转动惯量做大**（I 做到 4~8 而非 1.5）。
- 测量顺序固定：`m2probe id` → `static` → `all`。**`DPOS/DNEG` 正负不对称 ⇒ 两台标定不同步**；`LIFT` 下沉 ⇒ 装反或需 `setInverted(true)` 重跑。
- 二喷口"能悬停"和"能飞 30 m/s"是两个独立条件：前者 `TWR(p) > 1`，后者 `TWR(p) > 1/cos(15°)`。

---

## 10. 工具

| 工具 | 用法 | 作用 |
|---|---|---|
| `tools/digest_csv.ps1` | `powershell -File tools\digest_csv.ps1 <csv> [-Cols a,b,c] [-T0 -T1 -Win] [-HeaderOnly]` | 50 KB~60 MB CSV → ≤30 行看板（相位 min/max/avg/last + `SLOW/GAP/SAT/SINK/ERR_END/COL_SHIFT` 判据），顺带打印同名 `status_*.txt` |
| 同上的记录仪用法 | `... -T0 12 -T1 20 -Win 0.5` | 大文件按时间片均值 |
| `cc_logs_archive/lua_versions/computerN/` | `Copy-Item` | 旧版程序归档（不进电脑盘） |
| `temp/` | 一次性脚本/复制出来的 CSV | 不污染 `tools/` 与仓库数据 |
| `.tmp_luatest/harness.lua` | `lua54 harness.lua <程序> [tau] [相位] [stride] [参数] [y0] [belly-or-empty] [z0]` | 桩世界干跑（桩外设 + 桩物理 + 虚拟时钟）；**改完 Lua 必跑**。二喷口新机无桩时用 `temp/` 自建最小桩 |

- `tools/*.ps1` **正文必须纯 ASCII**（PS 5.1 按 ANSI 读无 BOM UTF-8，中文会吞掉后续代码行）。
- 脚本输出 ≤30 行，用退出码区分成败，并打印 `NOFILE` / `EMPTY` / `NO DATA` 这类可判定原因。

---

## 11. 参考

- `memo/flight/starship15-constants.md`：共享常数原始出处 + 星舰专用数 + 27 条黑名单（§4）
- `memo/flight/two-nozzle-vehicle.md`：二喷口设计基线（秩 5、`tau_roll` 恒等式、`m2probe` 相位）
- `memo/flight/flip-autopilot.md`：CC:T 飞行控制环通用经验（320 KB，**只按关键词检索**）
- `memo/propulsion/advanced-thrust-nozzle.md`：喷管 Lua API（`setDirection` / `setThrustScale` / `setInverted`）、2 pN/RPM、`sable:mass = 0.5`
- `memo/peripherals/my_sensor_system.md`：`ccpe.sensor_system` 门控与读数
- `memo/peripherals/cc-rednet-communication.md`：跨电脑命令协议（`rednet` 不排队、拍频坑）
