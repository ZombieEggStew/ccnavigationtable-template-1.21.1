# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮状态（2026-11-18：任务链路闭环）

**用户修正过的目标（此前文档写错过两处）**：① 主线不是「0→90→0」整条 V（那只测执行器）；
② 不是「软着陆触地」——目标是**夹臂**（h=0 = 夹持点，实测 (11.50, 93.05, −0.49)）。
**真实需求（已实现）**：90° 腹部朝下自由落体 → 锥角回翻竖直 → 落回夹臂 → 合臂 → 开 connector → 关喷口。

**`flip dock` v20 + `catch` v1.3：真机 PASS ×3（2026-11-18 14:23 / 14:40 / 15:29）**

```
RELEASE(ch5/ch6=0，无平台电脑) → FALL → DAMP → FLIPBACK(u=1.0) → BRAKE(比例式反推)
→ RETURN(guide 环回夹持点) → 发 dock 请求 → 0.25 s 控制 + 0.5 s 收信轮询
→ 收到 clamped 立刻开 ch5/ch6=15 → 2 s → 关喷口（SECURED）
```

| 环节 | 最后一次实测（release h=119.5 / y=212.5 / p=0.5495） |
|---|---|
| FALL | 2.00 s / 20.8 m（vy −19.9） |
| FLIPBACK | **1.85 s / 46.4 m**（vh 24.0、wMax 1.25、u=1.00） |
| BRAKE | **6.20 s**、vh 24→2.4、dz −87.4（u 均值 0.497） |
| RETURN | 12.1 s，gap 18.0 → err 2.1 m |
| ARMS | 2.7 s，**err 0.0 m**、vh 0.1、cos 1.000、`reply=true` |
| 平台 `catch` | RESET ±90 @2rpm → `SETTLE ok 5.3 s` → 回 `clamped`（每 0.1 s 重试） |
| 收尾 | `CONNECT ch5/ch6=15` → `WAIT 2 s` → `SECURED`（喷口 0、引擎 100%） |

⇒ `VERDICT PASS`、`aborted=nil`；**预算实测够**（119.5 m 用完仍停在 h=0，无 FLOOR GUARD）。
归档：`cc_logs_archive/flip_dock_v20_PASS_20261118_1529.csv`（+status）、`catch_v1.3_PASS_20261118_1528.txt`。

## 1. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（`computer/0/flip.lua`、`computer/1/catch.lua`；改前 copy 到 `cc_logs_archive/lua_versions/`）
3. 数据只走 `tools/digest_csv.ps1`（大文件给 `-T0/-T1`）

## 2. 已定型的口径（细节见 constants §2 / catch §42~§49）

- **`dockUCap=1.0`**：0.6 的回翻腿 3.25 s/93 m（从云层起翻会撞地）；1.0 ⇒ 1.85 s/46.4 m。
- **自由落体不写死**：`fallPlan=clamp(h0-clampH-dockReserve,0,22)`，日志打 `BUDGET/FALL`。
- **合臂时序**：复位 ±90 + conn off → 等 `dock` → 合臂 0 @**2rpm**（16 rpm 太快，用户裁定）+ conn=15
  → **`|getTargetAngle()| ≤ 0.5°` 判到位**（`getRotationSpeed` 是配置转速常量，停着也 2.00）→ 回 `clamped`。
- **回信**：rednet **不排队**（目标不在 `receive` 就丢，`send` 仍返回 true）⇒ 平台 **每 0.1 s 重试 15 s**；
  火箭 **0.25 s 控制 + 0.5 s 收信**（0.5 s 发 / 0.9 s 收会拍频，凭空多 ~3 s）。
- **收尾**：收到 `clamped` 才开 ch5/ch6 → `sleep 2 s`（喷口保持最后悬停 scale）→ `commitZero()`；没回信则 `ARM HOLD`。
- `run`/`back` 模式保持 v15 已验证行为，随时可回归。

## 3. 本轮改动

- [x] `flip.lua` v20（`dock` 模式 + 比例式反推 + 夹持点 CFG 兜底 + 合臂握手轮询 + `ARM_HOLD`）
- [x] `catch.lua` v1.3（平台合臂序列：复位 / 等待 / 合臂+conn / 判到位 / 回信重试）
- [x] 归档 + 文档：constants §2（dock/合臂时序）、handover §0/状态/§8/§9、catch §42~§49、本卡

## 4. 剩下的（都是打磨）

1. **并进 `starship.lua`**（handover E 步）：把 `FALL→FLIP→RETURN→CATCH→SECURED` 合成一条命令跑完；先验「关推力不坠」。
2. **可选 F4（ch0 +25%）**：`setInverted(true)`，刹车 24 m/s² / 回翻 α +25%；先验差动对称性。
3. **可选小调**：合臂 `ARMS_RPM`（要更快就提到 ~4.5）、焊接等待 2 s、爬升加速（只省时间）。
4. 磁盘纪律：电脑盘只留运行版（`flip.lua` 95 KB + `catch.lua` 7 KB + CSV），旧版一律主机侧；测完立刻归档。

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
