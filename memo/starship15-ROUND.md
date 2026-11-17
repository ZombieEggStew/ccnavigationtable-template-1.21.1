# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**A · `guide.lua` 参数收尾**：`MOV` 5 s → 7 s、`LAT_VMAX` 1.5 → 1.2、末端死区 0.05 → 0.08 m。
验收：`MOV` / `RET` 末端 ≤ 0.5 m，`HOLD` ≤ 0.15 m，`cosMin ≥ 0.99`，`slow = 0`。

## 1. 只读清单（本轮最多三个；超了就不许动手，先问）

1. 本卡 + `memo/starship15-constants.md`
2. `run/saves/New World/computercraft/computer/0/guide.lua`
   —— **先 `grep -n` 找函数锚点，再 `read offset/limit` ≤80 行**，禁止整篇读
3. 数据一律走 `tools/digest_csv.ps1`，**原始 CSV 不进对话**

## 2. 上轮结论（§23.5，已跑，VERDICT CHECK）

- 稳态没问题：`HP1` err 0.02 → 0.02 m、`|vh|max` 0.01、`cosMin` 1.000、`u` 0.350
- 平移装不下：`MOV` 6 m 用 5 s，末端 err 1.06 m、`|vh|max` 1.75、tiltMax 7.8°
  ⇒ **是时长不够 + VMAX 太高**，不是增益不够
- 回程同理：`RET` 末端 0.925 m；`HP3` 0.505 m 压线
- 姿态权限够用：tilt 只用到 7.8°，离 18° 上限还有余量
- 安全：只在 **−Z** 活动（**+Z 是塔/平台，悬停高度上去必撞**，见 §22）

## 3. 本轮改动（改完填，3~5 行，一行一条）

- [ ] …

## 4. 交给你（只做两步）

1. `computer/1` 确认 `fixture` 在跑 → `computer/0` 执行 `guide`
2. 回报（**只这一条模板**）：

```
RUN: guide v<N> @HH:MM  ok|crash
<paste status_guide.txt 全文，约 18 行>
备注: <一句人话，如"途中撞了平台">
```

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
