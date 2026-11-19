# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮状态（2026-11-19 13:52 · ★ **任务完成**：夹取时序定稿）

用户实飞判定：**"现在完美了，任务完成"**（`earlyReqH` 由用户从 14.0 改为 **20.0**）。
`flip` **v44** + `catch` **v1.6** 为定稿版本；成品已归档。

| 段 | 结果（末轮真机） |
|---|---|
| 投放→自由落体 | FALL 3.95 s / 76.5 m，vy −36.4 |
| 回翻 | FLIPBACK 1.90 s，vh 26.6，cos 0.996 |
| 反推刹车 | BRAKE 5.1 s，过冲 10.2 m，h≈23.5 |
| 接近段 | RETURN 8.4 s / err 0.9（末端 `tgt=+0.01`，h 单调斜线） |
| 夹臂 | `CATCH request (early: h=+20)` → 塔合臂，**`LOCK ok  ch8=[S42] ch9=[S42]`** |
| 加油口 | 锁上后 `ch7=15` ↔ 火箭 `ch6=15 + strong power` |
| 收尾 | `replySettleT 1.5 s`（`|vh|,|vy|≤0.30`）→ 关喷口 → `SECURED` |

## 1. 定稿时序（别改）

`PRECONN ch5=15（ch6=0）` → RETURN 里 `|h|≤earlyReqH(20.0)` 或 `perr≤2.0` 发 `dock` 请求（船继续飞）
→ 塔合臂（火箭 **ch5** ↔ 塔 **ch8/9**，2 rpm ≈5.3 s）→ `getConnectedName()≠""` 判定锁定
→ 塔开加油口 **ch7=15** → 回 `clamped` → 火箭 **ch6=15 + `setStrongPower(6,true)`**
→ `replySettleT 1.5 s` → 关喷口。提前量实测 **7.40 s** vs 臂 5.3 s ⇒ 余量 ~2.1 s。

## 2. 版本与归档
| 文件 | 版本 | 归档 |
|---|---|---|
| `computer/0/flip.lua` | **v44** | `cc_logs_archive/lua_versions/computer0/flip_v44.lua`（＋ v42 / v39 历史） |
| `computer/1/catch.lua` | **v1.6** | `computer1/catch_v1.6.lua`（＋ v1.4） |
| `computer/0/starship.lua` | v1（旧） | `computer0/starship_v1.lua` |
| `dockprobe.lua` | 探针 | 两台电脑都有（只读，不切换任何东西） |

## 3. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（**改之前** copy 到 `cc_logs_archive/lua_versions/computerN/`）
3. 数据只走 `tools/digest_csv.ps1`——游戏开着时 CSV 被占用，先 `Copy-Item` 到 `temp/` 再 digest

## 4. 剩余待办（本任务之外）
1. **并进 `starship.lua`**（handover E 步）：以 `flip` v44 为基线，目标是一条命令跑完 fixture 夹紧→释放→翻转→刹车→夹取→加油→关喷口。
2. **磁盘清理**：computer/0 的 `log/` 已积累多轮 CSV，进游戏删 `log/flip_*.csv` 释放空间（都在主机侧归档过）。
3. （可选）横向反向加速度：vh 0→4.86 用 2.3 s ≈2.1 m/s²（18° 上限 3.57）。

## 5. 硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 长日志不进对话；只吃 digest（≤30 行）或定向抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，禁止整篇 write；改完必跑干跑（catch 用 `temp/catch_stub.lua`） |
| 3 | 程序头写 `-- VER vN @date CHANGELOG:`；**改之前**就把旧版 copy 到 `cc_logs_archive/lua_versions/computerN/`，绝不进电脑盘 |
| 4 | 每轮收尾：catch.md 追加 ≤15 行 §N｜覆写本卡｜handover 改状态行｜constants 改对应行 |
| 5 | 常数只认 constants；新实测只改那一行 |
| 6 | 开工先报读哪几个文件；预算 ≈3 读 + 1 改 + 1 干跑 |
| 7 | 日志：结尾 close｜每轮独立文件名｜测完立刻归档；**程序跑到 done 再停** |

## 6. 再飞一次要看的行

catch：`PROBE ... arm channels 8/9` / `LOCK ok ... arm state: ch8=[S42] ch9=[S42]` / `FUEL`。
flip：`CATCH request (early: h=+20 …)` / `RETURN` / `ARMS`（`vh` 应已小）/ `VERDICT PASS` / `FUEL` / `SECURED`。
