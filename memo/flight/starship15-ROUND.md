# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**B · 实跑 `guide` v2 验收**：参数已改完并干跑通过（§23.6），现在**只等实机一次飞行**。
验收：`MOV` / `RET` 末端 ≤ 0.5 m，`HOLD` ≤ 0.15 m，`cosMin ≥ 0.99`，`slow = 0`，
且末端**不许出现 ±10 cm 抖动**（死区收紧的已知风险）。

## 1. 只读清单（本轮最多三个；超了就不许动手，先问）

1. 本卡 + `memo/flight/starship15-constants.md`
2. `run/saves/New World/computercraft/computer/0/guide.lua` 的
   `CFG`(55~95) / `ctrlGuide`(336~366) / 相位统计(516~556) 三块
   —— 先 `grep -n` 找锚点再 `read offset/limit ≤80 行`，禁止整篇读
3. 数据一律走 `tools/digest_csv.ps1`，**原始 CSV 不进对话**

## 2. 上轮结论（§23.5 实跑 CHECK + §23.6 v2 改动，已干跑）

- 稳态没问题：`HP1` err 0.02 m、`cosMin` 1.000、`u` 0.350 ⇒ 竖直/姿态通道可信
- 上一轮三个问题都判过：MOV 5 s 装不下 6 m（不是增益不够）／VMAX 1.5 造成 16% 超调／
  `DB_POS` 0.20 让末端停在 0.11 m。v2 对应改了时长 7 s、VMAX 1.2、`DB_POS` 0.08、末端闸 `am` 0.08
- 干跑 v2：`MOV final 0.67 / RET final 0.66 / cosMin 0.996 / slow=0` PASS；
  `DB_POS` 0.20 vs 0.08 干跑输出**逐行相同**（死区在桩世界里从不触发）⇒ 实跑重点看末端抖动
- 安全：只在 **−Z** 活动（**+Z 是塔/平台，悬停高度上去必撞**，见 §22）

## 3. 本轮改动（改完填，3~5 行，一行一条）

- [x] `guide.lua` 头部加 `-- VER v2 @2026-11-17  CHANGELOG:...`，v1 冻结到 `computer/0/ver/guide_v1.lua`
- [x] `PHASES`：`MOV` / `RET` `dur` 5.0 → **7.0** s
- [x] `CFG.LAT_VMAX` 1.50 → **1.20** m/s
- [x] `CFG.DB_POS` 0.20 → **0.08** m
- [x] `ctrlGuide` 末端倾角闸 `am > 0.05` → **`am > 0.08`** m/s²

## 4. 交给你（只做两步）

1. `computer/1` 确认 `fixture` 在跑 → `computer/0` 执行 `guide`
2. 回报


CSV **不用贴** —— 我跑 `powershell -File tools/digest_csv.ps1 "...\log\guide.csv"` 自己取数。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 原始长日志**不进对话**；只吃 digest 输出（≤30 行） |
| 2 | 改 Lua 用 `edit` 局部替换，**禁止整篇 write 重写**；改完先 `lua54 .tmp_luatest/harness.lua <程序> 0.08` 干跑，输出取末 15 行 |
| 3 | 程序头必须有 `-- VER vN @date  CHANGELOG:...`；旧版本存 `computer/0/ver/xxx_vN.lua`（回滚读文件，不靠记忆重写） |
| 4 | 每轮收尾三件事：memo 只追加 ≤15 行增量 §N（不重复旧结论）｜覆写本卡｜`handover.md` 只改"状态表"那一行 |
| 5 | 常数只认 `starship15-constants.md`；新数实测到了就改那一行，别在 memo 其它地方复制 |
| 6 | 开工先报"本轮读哪几个文件"，没报不许动手；每轮工具调用预算 ≈3 次定位 + 1 次改 + 1 次干跑 |
