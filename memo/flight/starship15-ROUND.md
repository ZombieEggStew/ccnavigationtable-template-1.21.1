# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮状态（2026-11-19 12:37 · v37 真机 **PASS ×4**（末端 err 0.0），但**末端手感被用户否决**：不许"停住 + 上下晃 + 平移"）

**主线**：腹部朝下释放 → 自由落体 76.5 m → 锥角回翻 → 反推刹车 → 过冲 ~10 m → 回夹持点 → 合臂 → PASS。
**程序**：`flip` **v37**（已真机 PASS）+ `catch` **v1.4**。**代码未改，等下一轮做 v38（比例净空连续下降）。**

| 段 | v35 | v36 | **v37（当前盘上）** |
|---|---|---|---|
| BRAKE | 5.25 s / 过冲 10.8 m | 同 | 5.15 s / 过冲 **10.2 m** / vy −4.4 |
| RETURN | 10.3 s / err 0.6 | 6.9 s / err 1.0 | 8.1 s / err 0.4 |
| TRIM / ARMS | 0.35 s / err 0.0 | 0.40 s / err 0.0 | 0.35 s / **err 0.0** → PASS |
| **末端观感** | ❌ 降下去又弹起 | ⚠️ 晃悠 | ❌ **上方 2.5 m 停 3.6 s + 上下晃 6 次** ← 用户否决 |

## 1. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（`computer/0/flip.lua`、`computer/1/catch.lua`；**改之前**先 copy 到 `cc_logs_archive/lua_versions/computerN/`）
3. 数据只走 `tools/digest_csv.ps1`——**游戏开着时 CSV 被占用，先 `Copy-Item` 到 `temp/` 再 digest**

## 2. 已定型的口径（细节见 constants §2 / catch §51~§59）

- **过冲**：`dockBrakeAMax = 2.0`（8.8~10.8 m）、`dockBrakeVy 4.5`、`brakeVh 2.5`（**都不要动**，见黑名单 #24/#26）。
- **接近段**：`apVmax 2.2` + 制动曲线 `min(apVmax, √(2·2.0·max(perr−apCaptureR,0)))`（v37，已验证：掉头消失）。
- **增益**：`apKP 0.30` / `apKV 0.85` / `apTiltNear 12°`（ω 0.50 rad/s，别再加）。
- **吸力**：flip `ch5/ch6=15` 在 BRAKE 之后（`PRECONN`）；catch v1.4 `conn 7/8/9=15` 在 RESET 后。
- **已否决**：① 固定悬停高度对齐（黑名单 #25，本轮用户裁掉）；② `brakeVh` 收紧（#26）。

## 3. 下一步＝v38 计划（**待实施**，用户 2026-11-19 12:37 要求先只更新文档）

**目标**：RETURN 期间 **h 单调连续下降到点位**，不允许"h 恒定 ≥1 s"的平台段，也不许上下荡。

1. **取消固定悬停**：`hTgt = capH + apClear` → **`hTgt = capH + apClearK · perr`**（建议 `apClearK = 0.35`）：
   perr 10 m → +3.5 m；3 m → +1.05 m；1 m → +0.35 m；0 → 0。斜着连续贴上去，**没有停住点**。
2. **小 dh 改线性**：`apVySqrtA 3.0` 让 `dh=0.5 m` 就命令 `1.7 m/s`（悬停时 dh 摆 ±0.5 ⇒ vyRef 摆 ±1.7 ⇒ 上下荡，实测 3.6 s 内换向 6 次）。方案：`dh < apLinDH(1.5 m)` 时用 `K_POS·dh` 线性，或 `apVySqrtA → 1.2`。
3. **保留**（防撞结构，不产生悬停）：`apCaptureR 1.0` + 捕获区只刹速度、门锁存、`apGateMaxT`、`PRECONN`、拒绝硬上。
4. 若低空斜降仍蹭结构 ⇒ 只留**极小净空下限 0.5~1.0 m**，但**永不停住**。
5. **验收**：CSV 里 RETURN 段 `h` 单调下降（回弹 <0.2 m）；ARMS err ≤2 m 且 `reply=true`。

## 4. 其它待办

1. **并进 `starship.lua`**（handover E 步）——剖面参数基本冻结，只差末端手感。
2. **磁盘水位**：computer/0 的 `log/` 已占 **562 KB**（12 个文件），`fs.getFreeSpace` 只剩 **145.8 KB**，接近 `diskMinFree 120 KB` ⇒ 再存几轮 **CSV 会被静默跳过**。这些 CSV 都已归档到主机侧，**可在游戏里删掉 `log/flip_*.csv` 释放 ~470 KB**。
3. （可选）横向反向加速度：vh 0→4.86 用 2.3 s ≈2.1 m/s²（18° 上限 3.57）。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 长日志不进对话；只吃 digest（≤30 行）或定向抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，禁止整篇 write；改完必跑干跑 |
| 3 | 程序头写 `-- VER vN @date CHANGELOG:`；**改之前**就把旧版 copy 到 `cc_logs_archive/lua_versions/computerN/`，绝不进电脑盘 |
| 4 | 每轮收尾：catch.md 追加 ≤15 行 §N｜覆写本卡｜handover 改状态行｜constants 改对应行 |
| 5 | 常数只认 constants；新实测只改那一行 |
| 6 | 开工先报读哪几个文件；预算 ≈3 读 + 1 改 + 1 干跑 |
| 7 | 日志：结尾 close｜每轮独立文件名｜测完立刻归档；**程序跑到 done 再停** |

## 6. 真机跑完回填什么（贴这几行就够）

status_flip.txt 的 **BRAKE / PRECONN / RETURN / TRIM / ARMS / DOCK REPORT / VERDICT** + catch 终端 `PRECONN`/`CLOSE`/`SETTLE`。
**v38 之后还要看**：RETURN 里 h 有没有平台段（恒定 ≥1 s）＝这次的核心验收项。
