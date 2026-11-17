# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写本文，永远 ≤60 行**。开工只读本卡 + `starship15-constants.md`。
> 细节不在这里：`starship15-catch.md` 是归档 —— 需要时用 `grep` 定位 §N 再 `read offset`，
> **禁止整篇读**（77 KB，一次就吃掉半张上下文）。

## 0. 本轮目标（只做这一件，不扩）

**`flip` 这条 V 已收官**：v15 真机 PASS + 全量 CSV（`cc_logs_archive/flip_v15_PASS_20261118_0408.csv`）。
整条 V 从 FLIP1 起点到「竖直 + 横向刹停」只花 **149.8 m**，距地板还剩 **~60 m** —— 这段空间就是下一步的落地缓冲。

**下一段主线（等你选，我按下面顺序推荐）**：

1. **F2 · 落地剖面**（推荐）：`BRAKE` 之后接 `guide` v4 的竖直+横向环做**软着陆**。
   现状：刹车结束机体停在 h≈39.7、位置偏塔 ~90 m、`HOVER` 6 s 内降不到 `capH=-8`（3 m/s 剖面要 16 s）。
   F2 要做的是：`A_REF/VY_TOUCH` 竖直剖面 + 位置保持，把 ~60 m 余量用在「降高度 + 收位置」上。
2. **F4 · 加 ch0**（`setInverted(true)`）：全段 +25%（刹车 24 m/s²、回翻 α +25%）⇒ 余量再抬一档；先验差动对称性。
3. **爬升加速**（用户提的）：`VY_CLIMB≈8` + sqrt 减速曲线，只省时间（当前 CLIMB 28~32 s）。
4. **串任务程序**（handover §9 D 步）：`FALL→FLIP→LAND→HOLD→CLOSE` 进 `starship.lua`。

真机验收判据（F2 落地段）：`VY_TOUCH` 落地时 `|vy| ≤ 0.8`、水平 `|vh| ≤ 2`、`cosVert ≥ 0.95`、位置误差 ≤ 3 m、不撞地板。

## 1. 只读清单（下一轮开工时最多三个；超了就不许动手，先问）

1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（`computer/0/flip.lua` / `guide.lua` / `starship.lua`）—— **改前先把当前版 copy 到主机侧 `cc_logs_archive/lua_versions/computer0/`（不要放电脑磁盘）**
3. 数据：`run/flight_logs/*.csv` 与 `/log/*.csv` **只走** `tools/digest_csv.ps1`（大文件必须给 `-T0/-T1`）

## 2. 上轮结论（§41，已写进 constants §2 与 handover）

- **v15 真机 PASS（全量 CSV）**：`FLIP1 2.15 s / 5.4 m`（u=0.600）｜`HOLD 0.10 s`｜`FLIPBACK 3.25 s / 92.8 m`（峰 109.3°、vy −34.8）｜
  `BRAKE 3.20 s / 47.6 m`（vh 25.2→2.1，**u 均值 0.832、瞬时 1.00 ⇒ 刹车真开满**）｜`ARREST 0.00 s`（新退出条件）｜`HOVER 6.0 s`。
- **余量口径**：FLIP1 起点 189.5 → BRAKE 结束 **39.7 m** ⇒ 只用 149.8 m，到 `floorH=-20` 还剩 **~60 m**（v11 曾经余量 ~0）。
- **保留的签名**：Z 回滑（`BRAKE` 结束 dz −101.5、最终 drift 89.1，回滑 ~12 m 朝塔）。**不要再拿比例式反推去消它**。
- 已否决：v12 提速（角动量反噬）、v13 加高度（推力∝气压，净赚 1.5 m/10 m）、v15 比例式刹车（用户裁定保留过冲）。
- belly-flop 峰 109.3° 属过冲同源（`Δθ=w²/2α`），用户认可；桩世界升限 ~h150，190 m 只能真机验。
- **磁盘纪律**：CC 盘 1 MB，旧版本一律存主机侧；跑前看 `DISK free`（现在 ~600 KB 空闲）。

## 3. 本轮改动（代码 + 文档）

- [x] 无代码改动（v15 已在上一轮写完并上机 PASS）
- [x] `starship15-catch.md` §41、`constants` §2 剖面数字更新 + 程序表 + 磁盘纪律警告
- [x] **`starship15-handover.md` 整篇重做为「V 收官」版交接文档**：现状表 / 常数摘要 / 四条控制律 / 程序清单 /
      硬件频道 / 世界几何 / 工具与磁盘纪律 / 13 条坑 / 未解决项 / D~F 步 + 下一轮候选 F2·F4·爬升·串任务
- [x] `memo/README.md` 索引里的 handover / catch 描述同步（catch 已涨到 116 KB）
- [x] CSV/status 归档 `cc_logs_archive/flip_v15_PASS_20261118_0408.csv`
- [x] 本卡整篇覆写为「V 收官 + 选下一段主线」

## 4. 交给你

告诉我下一段做哪个：**F2 落地剖面（推荐）/ F4 ch0 / 爬升加速 / 直接串 `starship.lua`**。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 原始长日志**不进对话**；只吃 digest 输出（≤30 行）或定向流式抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，**禁止整篇 write 重写**；新文件除外；改完必跑干跑 |
| 3 | 程序头必须有 `-- VER vN @date  CHANGELOG:...`；**旧版本存主机侧 `cc_logs_archive/lua_versions/computer0/`，绝不能放电脑磁盘** |
| 4 | 每轮收尾三件事：memo 只追加 ≤15 行增量 §N（不重复旧结论）｜覆写本卡｜`handover.md` 只改"状态表"那一行 |
| 5 | 常数只认 `starship15-constants.md`；新数实测到了就改那一行，别在 memo 其它地方复制 |
| 6 | 开工先报"本轮读哪几个文件"，没报不许动手；每轮工具调用预算 ≈3 次定位 + 1 次改 + 1 次干跑 |
| 7 | 日志三条铁律：**结尾必须 close**｜**每轮独立文件名**｜**测完立刻归档到 `cc_logs_archive/`**；磁盘上限 1 MB、主机侧删文件无效 |
