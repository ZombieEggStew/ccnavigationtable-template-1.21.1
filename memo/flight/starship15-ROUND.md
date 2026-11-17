# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**F · 云端平台「横躺 → 竖直」单段验证**（`flip.lua` v6 的 `flip back` 模式）：
机体现在**横躺停靠在云端平台**（`cosVert≈0`、y≈212.5、没有塔、没有 fixture 电脑）。
判据：`ch5/ch6` 本地脱落 → 锥角闭环把 θ 90→0 在 ≤3 s 内、峰值角速度 ≤1.4 rad/s、**不越过 170°**、
随后 `ARREST` 把 vy 收住、`HOVER` 稳在 `capH`。
做法：脱落（无通信）→ `DAMP` 泄残余转速 → `ctrlConeAtt(0)`（θ̇ 反馈 + 探测式方向锁）→ 差动 PD 接管竖直保持。

## 1. 只读清单（本轮最多三个；超了就不许动手，先问）

1. 本卡 + `memo/flight/starship15-constants.md`
2. `computer/0/flip.lua` 的 `ctrlConeAtt` / `main` 的 `MODE == "back"` 分支
   —— 先 `grep -n` 找锚点再 `read offset/limit ≤80 行`，禁止整篇读
3. 数据：`run/flight_logs/*.csv` **只走** `tools/digest_csv.ps1`（大文件必须给 `-T0/-T1`）

## 2. 上轮结论（§28/§29 + 本轮 §30）

- **锥角 = 主姿态执行器**：满偏 + 满推力 **α≈2.5 rad/s²**、峰值 7.30 rad/s、90° 只要 ~1.2 s / 55 m（差动 0.33 的 7~8 倍）。
- **开环定角必滚圈**（实测 2 圈+）⇒ 必须闭环；**锥角与差动不能同刻并联**（满推力 `room=0`，见 constants §3 末）。
- `flip` 完整剖面在云端平台**不可用**：v5 第一行预检就 `!! fixture unreachable` 直接 return（02:02 那次喷口没动）；
  v6 已把夹具改为可选 + 新增 `back` 模式（横躺停机起步）。

## 3. 本轮改动（改完填，3~5 行，一行一条）

- [x] `flip.lua` **v6**：夹具预检改为**可选**（`ping` 不通就只做本地 `ch5/ch6` 脱落，不再 abort）
- [x] `flip.lua` **v6**：新增 **`MODE == "back"`** 分支 —— 跳过 FALL/CLIMB/FLIP1/HOLD，直接 DAMP→FLIPBACK→ARREST→HOVER，
      `capH = max(floorH+20, h0−40)`；桩内 `VERDICT PASS` 跑通
- [x] 常数补记「锥角+差动分时分工」；catch §30
- [ ] 上机 `computer/0` → **`flip back`**，测完归档 `cc_logs_archive/<日期>_flipback_v6/`

## 4. 交给你（两步）

1. 机体**横躺**停在云端平台（`ch5/ch6` 上电即可；`fixture` 不需要，也不要有塔）
2. `computer/0` 执行 **`flip`**（**不要加 `back`** —— v7 按起始姿态自动判定；参数在本世界不可靠，见 §31）

**预期**：`MODE back (start cosVert≈0)` → `DISK free=... KB` → `FIXTURE unreachable -- local release only`
→ `RELEASE ch5/ch6` → `DAMP` → `FLIPBACK theta 90→0`（~2~3 s、`tiltMax` 满 15°）→ `ARREST vy→0` → `HOVER`。
**磁盘**：`/log` 满 1 MB 会 out of space（已做软着陆：<120 KB 自动跳过 CSV，只写 status）。
上一轮日志已归档成 `cc_logs_archive/log.zip`（120 KB）。我直接从磁盘读 `/log/flip_<epoch>.csv` + `status_flip.txt`。
**只有 `!! ERROR` / `!! ABORT` 才需要口述那一行。**

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 原始长日志**不进对话**；只吃 digest 输出（≤30 行）或定向流式抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，**禁止整篇 write 重写**；新文件除外；改完必跑干跑 |
| 3 | 程序头必须有 `-- VER vN @date  CHANGELOG:...`；旧版本存 `computer/0/ver/xxx_vN.lua` |
| 4 | 每轮收尾三件事：memo 只追加 ≤15 行增量 §N（不重复旧结论）｜覆写本卡｜`handover.md` 只改"状态表"那一行 |
| 5 | 常数只认 `starship15-constants.md`；新数实测到了就改那一行，别在 memo 其它地方复制 |
| 6 | 开工先报"本轮读哪几个文件"，没报不许动手；每轮工具调用预算 ≈3 次定位 + 1 次改 + 1 次干跑 |
| 7 | 日志三条铁律：**结尾必须 close**（否则磁盘上是 0 字节）｜**每轮独立文件名**｜**测完立刻归档到 `cc_logs_archive/`**；磁盘上限 1 MB、主机侧删文件无效 |
