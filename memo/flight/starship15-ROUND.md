# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮状态（2026-11-19 13:4x · 连接器方案**真机已稳**，只剩"合臂再早一点"）

**飞行剖面不动**（v39 收官）。连接器：**臂连接器 ch5↔塔 ch8/9 主固定、加油口 ch6↔ch7 锁后才接**，
真机连续两轮都是 `LOCK ok  ch8=[S42] ch9=[S42]`（catch §65/§66）。

**程序**：`flip` **v43**（待真机）+ `catch` **v1.6**（真机已稳，本轮未动）。
**备份**：`flip_v42.lua` / `catch_v1.6.lua` / `flip_v39.lua` / `catch_v1.4.lua` / `starship_v1.lua`（都在 `cc_logs_archive/lua_versions/`）。

| 频道 | 用途 | 现状 |
|---|---|---|
| 火箭 ch5 | 臂连接器（上方两个） | PRECONN 起 15 全程保持 ✅ |
| 火箭 ch6 | 加油口（下方） | 收到 `clamped` 后 15 + `setStrongPower(6,true)` ✅ |
| 塔 ch8 / ch9 | 臂上两个连接器 | 待命 15 → 合臂 → `getConnectedName()≠""` 即锁定 ✅ |
| 塔 ch7 | 加油口 | `LOCK ok` 之后才 15 ✅ |

## 1. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`（§2 的"合臂/抓取时序"＝本轮口径）
2. 要改的程序（`computer/0/flip.lua`、`computer/1/catch.lua`；**改之前** copy 到 `cc_logs_archive/lua_versions/computerN/`）
3. 数据只走 `tools/digest_csv.ps1`——游戏开着时 CSV 被占用，先 `Copy-Item` 到 `temp/` 再 digest

## 2. 13:44 真机（v42）：时序对了，但 CHECK
`early: h=+7.8`｜RETURN 8.4 s / err 0.9｜TRIM 1.85 s｜**ARMS 0.3 s / `vh=1.3 m/s`** → `DOCK REPORT err 0.0` 但 **`VERDICT CHECK`**（判据要求 `vh` 小）。
`LOCK ok  ch8=[S42] ch9=[S42]`、`FUEL` 在锁后 —— **连接器链路完全正确**，问题只是"回执早到 → 带速度关喷口"。

## 3. 本轮改动（flip v43，两个，都很小）
1. **`earlyReqH 8.0 → 14.0`**：用户要"夹臂开始闭合的时间点再提前一点"。
   实测提前量曲线（v42 场次）：`8→5.35 s、10→5.70、12→6.05、**14→6.40**、16→6.70、18→7.05、20→7.40`。
   臂需 **5.3 s** ⇒ 14.0 留 **1.1 s 余量**（连接器在船停稳前锁好）。
2. **ARMS settle hold**（`replySettleT 1.5 s`）：收到回执后继续保持，直到 `|vh|,|vy| ≤ 0.30` 或到 1.5 s 上限 ⇒ **不再带残余速度关喷口**，`VERDICT` 应恢复 PASS。
干跑：`dock` 到 `done`（`ARMS 1.9 s` 即 settle 生效）、`back` `VERDICT PASS`。

## 4. 下一步＝真机一轮
1. 电脑 1 起 `catch`（确认 `PROBE ... arm channels 8/9`），电脑 0 起 `flip dock`，**跑到 `SECURED`/`done`**。
2. 回填：flip 的 `CATCH request (early: h=… perr=…)` / `ARMS`（看 `vh` 是否已小）/ `VERDICT` / `FUEL`；catch 的 `LOCK`（`arm state`）/ `FUEL`。
3. **验收**：`LOCK ok ch8=[S42] ch9=[S42]`、`FUEL` 在锁后、**`VERDICT PASS`**（不再是 CHECK）、画面上臂在船还剩 ~14 m 时就开始合。
4. 还嫌晚 ⇒ `earlyReqH` 继续加（16.0 ≈ +0.3 s、20.0 ≈ +1.0 s）；觉得太早/挡路 ⇒ 回调到 10~12。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 长日志不进对话；只吃 digest（≤30 行）或定向抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，禁止整篇 write；改完必跑干跑（catch 用 `temp/catch_stub.lua`） |
| 3 | 程序头写 `-- VER vN @date CHANGELOG:`；**改之前**就把旧版 copy 到 `cc_logs_archive/lua_versions/computerN/`，绝不进电脑盘 |
| 4 | 每轮收尾：catch.md 追加 ≤15 行 §N｜覆写本卡｜handover 改状态行｜constants 改对应行 |
| 5 | 常数只认 constants；新实测只改那一行 |
| 6 | 开工先报读哪几个文件；预算 ≈3 读 + 1 改 + 1 干跑 |
| 7 | 日志：结尾 close｜每轮独立文件名｜测完立刻归档；**程序跑到 done 再停** |

## 6. 真机跑完回填什么（贴这几行就够）

catch：`PROBE` / `CLOSE` / `SETTLE` / `LOCK`（含 `arm state`）/ `FUEL`。
flip：`CATCH request (early: h=… perr=…)` / `RETURN` / `TRIM` / `ARMS` / `DOCK REPORT` / `VERDICT` / `FUEL` / `SECURED`。
