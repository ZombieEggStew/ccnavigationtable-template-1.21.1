# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**B · 实飞 `guide` v3 验收**：`MOV/RET` 时长 9 s 已改完并干跑 PASS（末端 0.41 m）。
验收：`MOV` / `RET` 末端 **≤0.5 m**、`HOLD` ≤0.15 m、`cosMin ≥0.99`、`slow = 0`。

## 1. 只读清单（本轮最多三个；超了就不许动手，先问）

1. 本卡 + `memo/flight/starship15-constants.md`
2. `run/saves/New World/computercraft/computer/0/guide.lua` 的 `PHASES`(94~102) 与 `ctrlGuide`(336~366)
   —— 先 `grep -n` 找锚点再 `read offset/limit ≤80 行`，禁止整篇读
3. 数据一律走 `tools/digest_csv.ps1`，**原始 CSV 不进对话**

## 2. 上轮结论（§23.7 v2 实飞，PASS 但末端未达标）

- 稳态很好：`HP1` err 0.03 m、`HOLD` err 0.03 m、`cosMin` 0.996、`slow=0`、x 摆幅 7 mm（**无抖动**）
- **末端不达标**：`MOV` 0.84 m、`RET` 0.84 m（判据 0.5）—— 但 `HP2` 自动收敛到 0.15、`HP3` 0.23
- 规律：末端 err 5 s→1.19、7 s→0.84 ⇒ **与时长线性相关，不是增益不够** ⇒ v3 只加时长
- 姿态只用到 6.8°，离 18° 上限很远；`DB_POS` 0.08 可保留（实测无抖动）
- 安全：只在 **−Z** 活动（**+Z 是塔/平台**，见 §22）

## 3. 本轮改动（改完填，3~5 行，一行一条）

- [x] `guide.lua` 头 `-- VER v3 @2026-11-17  CHANGELOG: MOV/RET dur 7.0 -> 9.0 s`
- [x] `PHASES`：`MOV` `dur` 7.0 → **9.0** s
- [x] `PHASES`：`RET` `dur` 7.0 → **9.0** s
- [x] 干跑：`MOV/RET` 末端 0.67 → **0.41 m**，PASS、`slow=0`、`cosMin` 0.997
- [x] 新增 `computer/0/fsprobe.lua`（文件写入门禁探针，已跑通，结论见 §23.7）

## 4. 交给你（两步，**不用抄屏幕**）

1. `computer/1` 确认 `fixture` 在跑 → `computer/0` 执行 `guide`
2. 等它跑完（约 45 s）→ **保存并退到标题** → 回我一句「好了」

我不需要你贴任何文本：电脑日志**退出世界后**才完整落盘，我直接从磁盘读
`computer/0/log/guide.csv` + `status_guide.txt`（走 digest ≤30 行）。
只在**崩了 / 屏幕上出现 `!!` 报错**时才需要你口述那一行。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 原始长日志**不进对话**；只吃 digest 输出（≤30 行） |
| 2 | 改 Lua 用 `edit` 局部替换，**禁止整篇 write 重写**；改完先 `lua54 .tmp_luatest/harness.lua <程序> 0.08` 干跑，输出取末 15 行 |
| 3 | 程序头必须有 `-- VER vN @date  CHANGELOG:...`；旧版本存 `computer/0/ver/xxx_vN.lua`（v1 已存；v2 与 v3 只差两处 `dur`） |
| 4 | 每轮收尾三件事：memo 只追加 ≤15 行增量 §N（不重复旧结论）｜覆写本卡｜`handover.md` 只改"状态表"那一行 |
| 5 | 常数只认 `starship15-constants.md`；新数实测到了就改那一行，别在 memo 其它地方复制 |
| 6 | 开工先报"本轮读哪几个文件"，没报不许动手；每轮工具调用预算 ≈3 次定位 + 1 次改 + 1 次干跑 |
| 7 | **看到 `computer/*/log/*` 是 0 字节 ≠ 没写**：游戏运行时那只是内存镜像，退出世界才落盘（§23.7）；排查用游戏内 `fsprobe` |
