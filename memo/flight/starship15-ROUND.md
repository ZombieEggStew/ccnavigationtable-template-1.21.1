# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮目标 + 现状

**用户 2026-11-18 修正两处旧目标**：① 主线不是「0→90→0」整条 V（只用于测执行器）；
② F2 不是「软着陆触地」——目标是**夹臂**（h=0 = `target.txt` 夹持点），不是地面。
**真实需求**：90° 腹部朝下自由落体 → 锥角回翻竖直 → 落回夹臂附近。

**`flip dock` v16：真机 PASS ×2（2026-11-18 14:23 / 14:40）**
`RELEASE(ch5/ch6=0、无平台电脑) → FALL → DAMP → FLIPBACK(u=1.0) → BRAKE(比例反推) → RETURN → ARMS`

| 腿 | 真机（release h=119.5 / y=212.5 / p=0.5495） | 桩 |
|---|---|---|
| FALL | 2.00 s / 20.8 m（vy −19.9） | 1.75 / 20.4 |
| FLIPBACK | **1.85 s / 46.4 m**（vh 24.0、wMax 1.22、u=1.00） | 1.75 / 48.4 |
| BRAKE | 6.15 s、vh 24→2.4、dz −87.0（u 均值 0.497） | 7.9 / −98.8 |
| RETURN | 11.9 s，gap 17.7 → **err 2.0 m**、h=0 | 19.4 / 1.6 |
| ARMS | 5.0 s，**err 0.2 m**、vh 0.2、cos 1.000 | 5 / 0.2 |

⇒ 停点 **(11.50, −0.69, h=0)**、`VERDICT PASS`、`aborted=nil`。**高度预算实测够**（119.5 m 用完仍在 h=0，无 FLOOR GUARD）。
首飞（14:23）唯一缺陷是电脑 0 缺 `target.txt`（RETURN 原地停在 z≈−18.4）；已由 `probe`（夹住时）实测夹持点
**(11.50, 93.05, −0.49)** 写入 `/log/target.txt` + CFG 兜底（`clampX=11.5 / clampZ=−0.492`）。⚠️ 电脑 1 的旧 target 是**平台体**（mass 133.75、z=+11.2）。

**合臂序列（v20 / `catch.lua` v1.3 在电脑 1）**：复位臂 ±90 + conn ch7/8/9=0（臂速 **2 rpm**，90°≈7.5 s）
→ 等火箭 `dock` 请求（或按键）→ 合臂 0 @2rpm + conn=15（一个 parallel 块）→ **`getTargetAngle()` 到 0 判到位**
→ 回 `clamped`（重试 15 s：rednet 消息不排队）→ 火箭在 `armHoldT` 上限内 **0.25 s 控制 + 0.5 s 收信** 轮询（平台每 0.1 s 重试，避免发/收拍频），
拿到回信立刻开 ch5/ch6=15 → 2 s → 关喷口（平台+火箭已连成刚体）。收不到回信时退回 `ARM HOLD`。

## 1. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（改前 copy 到 `cc_logs_archive/lua_versions/computer0/`）
3. 数据只走 `tools/digest_csv.ps1`（大文件给 `-T0/-T1`）

## 2. 本轮结论（细节见 constants §2 / catch §42~44）

- **比例式反推已解禁且实测有效**（`brakeProp=true`）：尾部 lean 自动收到 +2.5°/实际 +4.3°，不再拿 22° 硬推；`false` = v15 固定 22°（备份 `flip_v15.lua`）。
- **`dockUCap` 必须 1.0**（真机回翻腿 1.85 s / 46.4 m；0.6 是 3.25 s / 93 m，从云层起翻会撞地）。
- **自由落体不写死**：`fallPlan=clamp(h0-clampH-dockReserve,0,22)`；首飞预算 19.5、实掉 20.8 m。
- **两段式回程有效**：RETURN 在 5 m 容差处退出（2.0 m），ARMS 5 s 继续收到 0.2 m。
- 桩踩出两个 bug 已修（`driftMax` 要在 ARMS 后恢复；FALL 腿 `flipping=true`）；`run`/`back` 保持 v15。

## 3. 本轮改动

- [x] `flip.lua` v16（dock 模式 + 比例式反推 + target.txt x/z + 夹持点 CFG 兜底 + ARM_HOLD 收尾）
- [x] 备份 `flip_v15.lua`；桩夹具 `temp/harness_dock.lua`、`temp/flip_stub_dock.lua`
- [x] `constants`（黑名单 #6、§2 dock 真机 PASS、夹持点实测、程序表）｜`handover` 状态/几何｜`catch` §42~44

## 4. 下一步（自动合臂 → 串任务）

1. 电脑 1 跑 **`catch`**（常驻：先复位臂 ±90 + connector 关）；电脑 0 跑 **`flip dock`**。
2. 火箭到位后自动发 `dock` ⇒ 电脑 1 合臂 + 开 connector ⇒ 回 `clamped` ⇒ 火箭开 ch5/ch6 ⇒ 2 s ⇒ 关喷口。
   日志 `SECURED` = 夹取闭环完成；`ARM HOLD` = 没收到回信（保持悬停，安全）。
3. 然后把这套并进 `starship.lua`（handover E 步）；可选 F4（ch0 +25%）、爬升加速。
4. 每趟结束：CSV + status 立刻归档 `cc_logs_archive/`。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 长日志不进对话；只吃 digest（≤30 行）或定向抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，禁止整篇 write；改完必跑干跑 |
| 3 | 程序头写 `-- VER vN @date CHANGELOG:`；旧版存主机侧，绝不进电脑盘 |
| 4 | 每轮收尾：memo 追加 ≤15 行 §N｜覆写本卡｜handover 改状态行 |
| 5 | 常数只认 constants；新实测只改那一行 |
| 6 | 开工先报读哪几个文件；预算 ≈3 读 + 1 改 + 1 干跑 |
| 7 | 日志：结尾 close｜每轮独立文件名｜测完立刻归档；磁盘 1 MB |
