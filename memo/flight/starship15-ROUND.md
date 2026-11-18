# 星舰复刻 · 本轮工作卡（唯一开工入口）

> 用法：**每轮整篇覆写**；开工只读本卡 + `starship15-constants.md`。细节按 §N grep `starship15-catch.md`，禁止整篇读。

## 0. 本轮状态（2026-11-18 21:36 · `flip` v30 真机 PASS，链路闭环，进入"手感打磨"）

**主线**：腹部朝下释放（y=306.5）→ 自由落体 73.5 m → 锥角回翻（h=140）→ 反推刹车 → 过冲迂回 → 回到夹持点 → 平台合臂。
**程序**：`flip` **v30**（+ `catch` v1.3 未改）。最近 9 轮（v21~v30）全部 PASS，无 FLOOR GUARD。

| 段 | v30 实测 |
|---|---|
| FALL | 3.95 s / 76.5 m / vy −36.4 |
| FLIPBACK | 1.90 s / 73.7 m，峰 90.0°，交班 lean **+5.2°**（v26 起不再停死在 0°） |
| BRAKE | 6.50 s，停 **z=−24.9**（过塔 **24.4 m**，v28 只有 1.6 m） |
| RETURN | 10.5 s，gap 24.5 → err 0.8 |
| TRIM | 4.05 s（超时），err 0.40 |
| ARMS | 2.7 s，err 0.1，`reply=true` → **PASS** |

## 1. 只读清单（最多三个；超了先问）
1. 本卡 + `memo/flight/starship15-constants.md`
2. 要改的程序（`computer/0/flip.lua`、`computer/1/catch.lua`；改前 copy 到 `cc_logs_archive/lua_versions/computer0/`）
3. 数据只走 `tools/digest_csv.ps1`（大文件给 `-T0/-T1`）

## 2. 已定型的口径（细节见 constants §2 / catch §50）

- **释放几何**：投放点 ≈ **(9.5, 306.5, +66.9)**；夹持点 (11.50, 93.05, −0.49) ⇒ 横距 **67 m**。
- **翻转点** `dockReserve=140`（h=140，y≈233，p≈0.507，TWR≈1.96）；落体 `dockFallDrop=80`、`dockFallVy=−45`、`dockFallMaxT=5.0`。
- **交班不再停死在竖直**：FLIPBACK 目标 = 刹车倾角函数，越过竖直 2° 就交给 BRAKE（v26）。
- **锥角必须在只靠差动的腿之前打直**：`DIRCMD = {0,0}`（v27；`nil` 不会写 setDirection）。
- **接近段剖面**：`apVmax=3.5 / apAMax=3.5 / apKP=0.45 / apKV=1.3 / apVyMax=6.0 / apVySqrtA=3.0`，竖直用 **√(2·a·dh) 恒减速剖面**（符号必须随 dh）。
- **收尾**：RETURN 加 `|h−clampH|≤0.30` 判据（v22）；`TRIM` ≤4 s 把误差抠到 0.35/0.15 m 再发 `dock`。

## 3. 本轮（v21~v30）改了什么

- `flip.lua` v21→v30：长自由落体、低翻转点、F4（ch0 加入集合推力）、α 随气压、交班用惯性、锥角打直、过冲（`dockBrakeAMax`）、√ 下降剖面、MODE 校验。
- 夹具 `harness.lua`：真实大气 Hermite 曲线、`sleep` 桩、程序参数/起始高度/姿态/z 偏移、ch0 计入 FIRING。
- 干跑 `python` 无关；全部走 `lua54 harness.lua`。

## 4. 剩下的（按性价比，一次只做一条）

1. **过冲回调到 8~10 m**：`dockBrakeAMax` 1.5 → **2.0**（实测 2.6⇒1.6 m、1.5⇒24.4 m，极度敏感，一次只动 0.2）。
2. **横向反向加速度**：TILT_MAX 18° 给出 3.57 m/s² 上限，实测反向均值只有 ~2.1（环在接近目标时提前收力）。可选：TILT_MAX→22°（4.45 m/s²，cos 0.927 仍在 COS_ABORT 0.86 之上）+ 横向也改用 √ 速度帽。
3. **末端 6 s 在目标附近来回（vh 2~4 m/s）**：限幅循环，加横向 √ 剖面或死区滞环。
4. **并进 `starship.lua`**（handover E 步）。

## 5. 每轮硬规矩

| # | 规矩 |
|---|---|
| 1 | 原始 CSV / 长日志不进对话；只吃 digest（≤30 行）或定向抽样 |
| 2 | 改 Lua 用 `edit` 局部替换，禁止整篇 write；改完必跑干跑 |
| 3 | 程序头写 `-- VER vN @date CHANGELOG:`；旧版存主机侧 `cc_logs_archive/lua_versions/computer0/`，绝不进电脑盘 |
| 4 | 每轮收尾：memo 追加 ≤15 行 §N｜覆写本卡｜handover 改状态行 |
| 5 | 常数只认 constants；新实测只改那一行 |
| 6 | 开工先报读哪几个文件；预算 ≈3 读 + 1 改 + 1 干跑 |
| 7 | 日志：结尾 close｜每轮独立文件名｜测完立刻归档；电脑盘 1 MB（CSV 会静默跳过） |
