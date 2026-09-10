# -*- coding: utf-8 -*-
"""
气动模型标定/结构验证：_fit_aero_model.py

用途：验证并拟合 ccpe 飞机气动力的缩放结构，支撑"由油门算平衡高度"的前馈方案。
  - 普通帆升力：L = kL * P * v          （待验证：线性 v 还是 v^2）
  - 风帆阻力：   D = kD * P * v          （同上）
  - 螺旋桨推力： T = kT * P * f(throttle, v)   （f 形式待测；油门→转速→推进效率）

数据：飞行记录 CSV（lift/drag/prop 的 |矢量| 与 pressure/airSpeed/thrAxis 同 tick）。
      要分离 v 与 P 的依赖，需要 v 变化大的数据（巡航 v≈62 恒定无法区分！）——
      用风洞扫速（定 P 变 v）+ 不同高度巡航（变 P）组合最干净。

用法：
    python _fit_aero_model.py <csv...>      # 一个或多个 CSV，全部行合并拟合
"""
import csv
import math
import os
import statistics
import sys

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

MIN_V = 3.0          # 忽略静止/倒流行
MIN_FORCE = 0.05     # 数值噪声下限

def load(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    names = rows[0].keys() if rows else []
    out = {k: [] for k in names}
    for r in rows:
        for k in names:
            try:
                out[k].append(float(r[k]))
            except (TypeError, ValueError):
                out[k].append(float("nan"))
    return out

def mag(d, fx, fy, fz, i):
    x, y, z = d[fx][i], d[fy][i], d[fz][i]
    if all(math.isfinite(q) for q in (x, y, z)):
        return math.sqrt(x * x + y * y + z * z)
    return float("nan")

def collect(d):
    """返回行列表 (P, v, thr, lift, drag, prop)"""
    out = []
    n = len(d["tick"])
    for i in range(n):
        P = d["pressure"][i]
        v = d["airSpeed"][i]
        thr = d["thrAxis"][i]
        if not (math.isfinite(P) and math.isfinite(v) and abs(v) > MIN_V):
            continue
        lift = mag(d, "liftFx", "liftFy", "liftFz", i)
        drag = mag(d, "dragFx", "dragFy", "dragFz", i)
        prop = mag(d, "propFx", "propFy", "propFz", i)
        out.append((P, v, thr, lift, drag, prop))
    return out

def ols(xs, ys):
    pairs = [(x, y) for x, y in zip(xs, ys) if math.isfinite(x) and math.isfinite(y)]
    if len(pairs) < 5:
        return None
    mx = statistics.mean(p for p, _ in pairs)
    my = statistics.mean(y for _, y in pairs)
    den = sum((p - mx) ** 2 for p, _ in pairs)
    if den == 0:
        return None
    b = sum((p - mx) * (y - my) for p, y in pairs) / den
    a = my - b * mx
    ss = sum((y - (a + b * p)) ** 2 for p, y in pairs)
    tot = sum((y - my) ** 2 for y, _ in pairs)
    r2 = 1 - ss / tot if tot > 0 else float("nan")
    return a, b, r2, len(pairs)

MODELS = [
    ("const", lambda P, v: 1.0),
    ("v",     lambda P, v: v),
    ("P",     lambda P, v: P),
    ("Pv",    lambda P, v: P * v),
    ("Pvv",   lambda P, v: P * v * v),
    ("Pv+c",  None),  # 特殊处理
]

def fit_table(name, allrows, mask):
    print(f"--- {name} ---")
    sel = [r for r, ok in zip(allrows, mask) if ok]
    if len(sel) < 20:
        print(f"  样本不足 ({len(sel)})"); return
    xs_all = {m: [f(r[0], r[1]) for r in sel] for m, f in MODELS if f}
    for mname in ("const", "v", "P", "Pv", "Pvv"):
        res = ols(xs_all[mname], [r[3] if name.startswith("LIFT") else (r[4] if name.startswith("DRAG") else r[5]) for r in sel])
        if res:
            a, b, r2, n = res
            print(f"  y = a + b*{mname:<4}: a={a:9.3f} b={b:10.4f}  R2={r2:.4f}  (n={n})")
    # 过原点版本（物理上更合理）：y = b*feature
    for mname in ("v", "P", "Pv", "Pvv"):
        xs = xs_all[mname]
        ys = [r[3] if name.startswith("LIFT") else (r[4] if name.startswith("DRAG") else r[5]) for r in sel]
        pairs = [(x, y) for x, y in zip(xs, ys) if math.isfinite(x) and math.isfinite(y) and abs(x) > 1e-9]
        if len(pairs) < 20:
            continue
        b = sum(x * y for x, y in pairs) / sum(x * x for x, y in pairs)
        ss = sum((y - b * x) ** 2 for x, y in pairs)
        tot = sum((y - statistics.mean([y for _, y in pairs])) ** 2 for _, y in pairs)
        r2 = 1 - ss / tot if tot > 0 else float("nan")
        print(f"  y = b*{mname:<4} (过原点): b={b:10.4f}  R2={r2:.4f}  (n={len(pairs)})")

def main():
    paths = sys.argv[1:] or ["_latest"]
    if paths == ["_latest"]:
        import glob
        paths = sorted(glob.glob(os.path.join(LOG_DIR, "flight_*.csv")),
                       key=os.path.getmtime)[-1:]
    allrows = []
    for p in paths:
        d = load(p)
        print(f"载入 {p}: {len(d['tick'])} 行")
        allrows.extend(collect(d))
    print(f"合并有效行: {len(allrows)}  (v 范围 {min(r[1] for r in allrows):.1f}..{max(r[1] for r in allrows):.1f}, "
          f"P 范围 {min(r[0] for r in allrows):.3f}..{max(r[0] for r in allrows):.3f})")
    P = [r[0] for r in allrows]; v = [r[1] for r in allrows]
    thr = [r[2] for r in allrows]
    lift = [r[3] if math.isfinite(r[3]) and r[3] > MIN_FORCE else float("nan") for r in allrows]
    drag = [r[4] if math.isfinite(r[4]) and r[4] > MIN_FORCE else float("nan") for r in allrows]
    prop = [r[5] if math.isfinite(r[5]) and r[5] > MIN_FORCE else float("nan") for r in allrows]
    fit_table("LIFT |L|", allrows, [math.isfinite(x) for x in lift])
    fit_table("DRAG |D|", allrows, [math.isfinite(x) for x in drag])
    fit_table("PROP |T| (thr>0.9)", allrows,
              [math.isfinite(x) and (math.isfinite(t) and t > 0.9) for x, t in zip(prop, thr)])
    print("\n判读:")
    print("  - R2(Pv) 明显高于 R2(v)/R2(P)/R2(Pvv) -> 该力 ∝ P*v 成立")
    print("  - 若只有巡航数据(v≈恒定)，Pv 与 v 无法区分——需要风洞变 v 或含下滑/爬升段的数据")
    print("  - 推力需油门扫描（thrAxis 0..1 多档平飞）才能拟合 f(throttle,v)")

if __name__ == "__main__":
    main()
