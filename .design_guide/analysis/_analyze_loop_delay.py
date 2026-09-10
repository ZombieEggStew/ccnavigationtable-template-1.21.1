# -*- coding: utf-8 -*-
"""
回路延迟测量分析：_analyze_loop_delay.py

输入：一次"俯仰脉冲测试"飞行的记录 CSV（flight_*.csv，20 Hz / tick 一行）
测法：摇杆2 前后（joyY 列）做几个 ~2 s 的阶跃脉冲，分析每个脉冲
      "joyY 开始离开 0" 到 "wbX / dragMx / pitchDeg 开始响应" 的 tick 数。

延迟分解（同一次 CSV）：
    joyY 起点 -> dragMx 起点  = 指令通道延迟（Lua 调度 + setTargetAngle + 舵面动）
    dragMx 起点 -> wbX 起点   = 气动/惯量响应延迟（舵面出力 -> 机体俯仰速率变化）
    joyY 起点 -> wbX 起点     = 总回路延迟 τ（决定俯仰阻尼环 GAIN 上限的数值）

用法：
    python _analyze_loop_delay.py                 # 自动选最新的 flight_*.csv
    python _analyze_loop_delay.py <csv 路径>      # 指定文件
"""
import csv
import glob
import math
import os
import statistics
import sys

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

CMD_COL = "joyY"                 # 输入参考列（摇杆2 前后轴）
RESP_COLS = ["wbX", "dragMx", "chainDragMx", "pitchDeg"]  # 候选响应列
EDGE_THRESH = 0.08               # |joyY| 超过此值 = 按下
MIN_SEP_TICKS = 30               # 两个脉冲起点最小间隔（防同脉冲双检）
BASELINE_TICKS = 25              # 脉冲前安静窗口长度
ONSET_WINDOW = 20                # 脉冲后找响应的最大窗口
SUSTAIN = 2                      # 响应需连续 N tick 超过阈值才算
STD_MULT = 6.0                   # 响应阈值 = STD_MULT × 安静窗 std
MAX_LOOK_TICKS = 15              # 速率峰值搜索窗口（响应变化率最大值位置）

def load(path):
    """读 CSV -> (列名列表, {列名: [数值列表]})，非数值置 nan"""
    with open(path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    names = reader.fieldnames or []
    data = {}
    for col in names:
        vals = []
        for row in rows:
            raw = (row.get(col) or "").strip()
            try:
                vals.append(float(raw))
            except ValueError:
                vals.append(float("nan"))
        data[col] = vals
    return names, data

def pick_csv():
    files = sorted(glob.glob(os.path.join(LOG_DIR, "flight_*.csv")),
                   key=os.path.getmtime)
    if not files:
        sys.exit("没找到 flight_*.csv（放在 run/flight_logs/ 下）")
    return files[-1]

def median(xs):
    xs = [x for x in xs if math.isfinite(x)]
    return statistics.median(xs) if xs else float("nan")

def stdev(xs):
    xs = [x for x in xs if math.isfinite(x)]
    return statistics.pstdev(xs) if len(xs) > 1 else 0.0

def find_press_edges(cmd):
    """返回按下边沿 tick 列表：|cmd| 从 <阈值 变为 >阈值，且与上个边沿间隔足够"""
    edges = []
    last = -10 ** 9
    prev = False
    for i, v in enumerate(cmd):
        cur = abs(v) > EDGE_THRESH
        if cur and not prev and i - last >= MIN_SEP_TICKS:
            edges.append(i)
            last = i
        prev = cur
    return edges

def onset_after(t0, resp, baseline_slice, window=ONSET_WINDOW):
    """在 [t0, t0+window] 找响应首次超过阈值的 tick（需连续 SUSTAIN tick）。
    返回 (onset_tick, 阈值)；找不到返回 (None, 阈值)。"""
    med = median(baseline_slice)
    sd = stdev(baseline_slice)
    if not math.isfinite(med):
        return None, float("nan")
    thresh = max(STD_MULT * sd, 1e-6)
    run = 0
    for i in range(t0, min(t0 + window, len(resp))):
        v = resp[i]
        if math.isfinite(v) and abs(v - med) > thresh:
            run += 1
            if run >= SUSTAIN:
                return i - SUSTAIN + 1, thresh
        else:
            run = 0
    return None, thresh

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else pick_csv()
    print(f"== 分析文件: {os.path.basename(path)} ==")
    names, d = load(path)
    for need in [CMD_COL] + RESP_COLS:
        if need not in names:
            sys.exit(f"CSV 缺少列: {need}（表头只有 {len(names)} 列，确认没拿错文件/没被旧文件覆盖）")

    n = len(d["tick"])
    tick0 = d["tick"][0] if n else 0
    print(f"共 {n} 行, tick 范围 {tick0}..{tick0 + n - 1}")

    edges = find_press_edges(d[CMD_COL])
    if not edges:
        sys.exit("没检测到 joyY 脉冲（全程 |joyY| < 0.08）。"
                 "确认：这趟飞行真的推拉过摇杆2？频道 7 接的是你要测的摇杆？")
    print(f"检测到 {len(edges)} 个脉冲起点: tick {edges}（相对文件首行 {[e - tick0 for e in edges]}）\n")

    results = {col: [] for col in RESP_COLS}
    hdr = "脉冲#  t_cmd  t_rel  joyY    方向" + "".join(f"  {c:>9}:lag" for c in RESP_COLS)
    print(hdr)
    print("-" * len(hdr))
    for k, t0 in enumerate(edges):
        if t0 < BASELINE_TICKS + 5 or t0 + ONSET_WINDOW >= n:
            print(f"  {k + 1:>3}  {t0:>6}  {t0 - tick0:>6}  靠文件边缘，跳过")
            continue
        base = range(t0 - BASELINE_TICKS, t0 - 5)
        joy = d[CMD_COL][t0] if math.isfinite(d[CMD_COL][t0]) else float("nan")
        direction = "后拉(joyY<0)" if joy < 0 else ("前推(joyY>0)" if joy > 0 else "?")
        line = f"  {k + 1:>3}  {t0:>6}  {t0 - tick0:>6}  {joy:>6.2f}  {direction}"
        for col in RESP_COLS:
            lag, thr = onset_after(t0, d[col], [d[col][i] for i in base])
            if lag is None:
                line += f"  {col:>9}: --"
                print(line); line = ""
                continue
            results[col].append(lag - t0)
            line += f"  {col:>9}:{lag - t0:>3}t"
        if line:
            print(line)

    print("\n== 延迟汇总（tick；×50 ms = 毫秒）==")
    print("     joyY起点 -> 响应列起点（各脉冲取中位数）")
    for col in RESP_COLS:
        lags = results[col]
        if lags:
            med = statistics.median(lags)
            lo, hi = min(lags), max(lags)
            print(f"  {col:>11}: 中位 {med:>4.1f} tick ({med * 50:>5.0f} ms)   范围 {lo}..{hi} tick"
                  + ("" if len(lags) < 3 else f"   n={len(lags)}"))
        else:
            print(f"  {col:>11}: 未检出（响应太弱/窗口内没动静）")

    if "dragMx" in results and "wbX" in results and results["dragMx"] and results["wbX"]:
        a = statistics.median(results["dragMx"])
        b = statistics.median(results["wbX"])
        print(f"\n  分段: 指令通道 joyY->dragMx ≈ {a:.1f} tick | 气动响应 dragMx->wbX ≈ {max(0, b - a):.1f} tick")
        print(f"  总回路延迟 τ ≈ {b:.1f} tick ≈ {b * 50:.0f} ms（含传感器陈旧 1 tick + Lua 调度 + 生效 1 tick + 气动）")

    m = statistics.median(results["wbX"]) if results["wbX"] else float("nan")
    print("\n== 判读 ==")
    if not math.isfinite(m):
        print("  wbX 未检出延迟：脉冲太小或飞行太乱，加大舵量（0.3~0.5 杆量）重飞一次。")
    elif m <= 2:
        print("  τ ≤ 2 tick：链路很紧，延迟不再是约束。俯仰阻尼 GAIN 提到 1.0 试飞是安全的（留意舵面抖动）。")
        print("  高度环：延迟允许 τ_h 20~40 s，但 phugoid(~17 s) 耦合仍在——保守取 40~60 s + 垂速限幅，或配合自动油门。")
    elif m <= 4:
        print("  τ ≈ 3~4 tick：正常范围（含 1~2 tick 气动响应）。GAIN 加要谨慎、一次加 0.25 试；高度环 30~60 s。")
    else:
        print("  τ ≥ 5 tick：偏大！先做循环频率检查（startup.lua 里数 Hz），看是否被屏幕绘制/NBT 缓存拖到 <20 Hz；")
        print("  再查舵面 PD（swivelBearingStiffness/Damping）是否太软导致到位慢。别急着加 GAIN。")

if __name__ == "__main__":
    main()
