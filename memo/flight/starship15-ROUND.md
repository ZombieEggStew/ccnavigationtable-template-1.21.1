# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**B · 实飞 `guide` v4 验收**：v3 参数（`MOV/RET` 9 s）+ v4 日志体系（每轮独立 CSV 名、每 10 行 flush、结尾 close）都已改完并干跑 PASS。
验收：`MOV` / `RET` 末端 **≤0.5 m**、`HOLD` ≤0.15 m、`cosMin ≥0.99`、`slow = 0`。
附带验收日志：跑完**不用退世界**就能在磁盘读到完整 CSV（close 生效）。

## 1. 只读清单（本轮最多三个；超了就不许动手，先问）

1. 本卡 + `memo/flight/starship15-constants.md`
2. `run/saves/New World/computercraft/computer/0/guide.lua` 的 `PHASES` 与 `ctrlGuide`
   —— 先 `grep -n` 找锚点再 `read offset/limit ≤80 行`，禁止整篇读
3. 数据一律走 `tools/digest_csv.ps1`，**原始 CSV 不进对话**

## 2. 上轮结论（§23.7 v2 实飞 / §23.8 落盘条件）

- 稳态很好：`HP1` err 0.03 m、`HOLD` err 0.03 m、`cosMin` 0.996、`slow=0`、x 摆幅 7 mm（**无抖动**）
- **末端不达标**：`MOV` 0.84 m、`RET` 0.84 m（判据 0.5）；但规律是「5 s→1.19、7 s→0.84」**与时长线性相关**
- **落盘条件已锁死**：CC:T 写句柄只有 `close()`（或电脑关机/存档）才推到主机磁盘，`flush()` 不够
  ⇒ 以前「测完发现记录是空的」= 程序从来没 close（旧项目 `flip-autopilot` 的 `finish()` 是 close 的，所以它能测完直接读）
- 安全：只在 **−Z** 活动（**+Z 是塔/平台**，见 §22）；落地停在 y≈69（地面 63 + 机体长度）正常，摔不坏

## 3. 本轮改动（改完填，3~5 行，一行一条）

- [x] `-- VER v4 @2026-11-17  CHANGELOG: ...`（v3: `MOV/RET dur 7.0 -> 9.0`；v4: 日志体系）
- [x] CSV 文件名改 **`/log/guide_<epoch>.csv`**（每轮独立，不再覆盖上一轮）
- [x] `csvRow` 每 10 行 `CSVH.flush()`
- [x] 结尾写 `=== finished: guide v4 ... rows=N ===` 后 **close `CSVH` + `fileH`**
- [x] 干跑：`MOV/RET` 末端 0.41 m、PASS、`slow=0`；`CSV /log/guide_1794911300050.csv`、`FILES … closed` 正常
- [x] v2 实飞数据已归档 `run/saves/New World/cc_logs_archive/2026-11-17_guide_v2/`

## 4. 交给你（两步，**不用抄屏幕、不用退世界**）

1. `computer/1` 确认 `fixture` 在跑 → `computer/0` 执行 `guide`
2. 等它跑完（约 45 s，屏幕上出现 `FILES  csv + status closed`）→ 回我一句「好了」

我直接从磁盘读**最新的** `computer/0/log/guide_<epoch>.csv` + `status_guide.txt`（digest ≤30 行）。
现在有 close，跑完就能读；万一显示 `!! ERROR` / 崩了，请口述那一行。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 原始长日志**不进对话**；只吃 digest 输出（≤30 行） |
| 2 | 改 Lua 用 `edit` 局部替换，**禁止整篇 write 重写**；改完先 `lua54 .tmp_luatest/harness.lua <程序> 0.08` 干跑，输出取末 15 行 |
| 3 | 程序头必须有 `-- VER vN @date  CHANGELOG:...`；旧版本存 `computer/0/ver/xxx_vN.lua`（v1 已存；v2/v3 与 v4 只差参数与日志） |
| 4 | 每轮收尾三件事：memo 只追加 ≤15 行增量 §N（不重复旧结论）｜覆写本卡｜`handover.md` 只改"状态表"那一行 |
| 5 | 常数只认 `starship15-constants.md`；新数实测到了就改那一行，别在 memo 其它地方复制 |
| 6 | 开工先报"本轮读哪几个文件"，没报不许动手；每轮工具调用预算 ≈3 次定位 + 1 次改 + 1 次干跑 |
| 7 | 日志三条铁律：**结尾必须 close**（否则主机磁盘上是 0 字节）｜**每轮独立文件名**｜**测完立刻归档到 `cc_logs_archive/`**；磁盘上限 1 MB、主机侧删文件无效（用游戏内 `fs.delete`） |
