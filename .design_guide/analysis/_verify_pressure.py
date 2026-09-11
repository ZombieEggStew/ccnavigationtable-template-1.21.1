#!/usr/bin/env python3
"""Verify air-pressure formula vs recorded flight data.

P0 = pure exponential  e^(-0.004*(y-63))          (设计指南的近似)
P1 = sable default curve: Hermite cubic over anchors (DimensionPhysics.createDefault +
     BezierResourceFunction.evaluateFunction, sea=63, minY=-64, logicalHeight=384)
Recorded = flight_logs CSV 'pressure' column (static-port world y -> getAirPressure)
"""
import csv, glob, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

def hermite(points, y):
    if not points: return 1.0
    if len(points) == 1: return points[0][1]
    idx = -1
    for p in points:
        if y < p[0]: break
        idx += 1
    if idx == -1: return points[0][1]
    if idx >= len(points) - 1: return points[-1][1]
    a1, v1, s1 = points[idx]; a2, v2, s2 = points[idx+1]
    dx = a2 - a1; dy = v2 - v1; t = (y - a1) / dx
    c = (s1 + s2) * dx - 2 * dy
    q = 3 * dy - (2 * s1 + s2) * dx
    l = dx * s1
    return max(((c*t + q)*t + l)*t + v1, 0.0)

def default_points(sea=63.0, miny=-64.0, logical=384.0):
    maxAlt = miny + logical
    baseSlope = -0.004; maxPressure = 1.5; maxStep = 200.0
    smoothing = maxAlt - 40
    cur = max(miny, math.log(maxPressure)/baseSlope + sea)
    pts = []
    while True:
        v = math.exp(baseSlope * (cur - sea)); s = v * baseSlope
        pts.append((cur, v, s))
        if cur < sea and cur + maxStep >= sea: cur = sea
        elif cur < smoothing and cur + maxStep >= smoothing: cur = smoothing
        elif cur >= smoothing: break
        else: cur += maxStep
    sp = pts[-1][1]
    pts.append((maxAlt, 0.0, -2*sp/(maxAlt - smoothing)))
    return pts

PTS = default_points()
P0 = lambda y: math.exp(-0.004 * (y - 63.0))
P1 = lambda y: hermite(PTS, y)

# sanity: anchor points
print("default curve anchors (alt, value, slope):")
for a, v, s in PTS: print(f"  {a:8.2f}  {v:9.5f}  {s:10.6f}")
print(f"P0(252)={P0(252):.5f}  P1(252)={P1(252):.5f}")
print(f"P0(300)={P0(300):.5f}  P1(300)={P1(300):.5f}")
print(f"P0(320)={P0(320):.5f}  P1(320)={P1(320):.5f}")
print(f"P0(361)={P0(361):.5f}  P1(361)={P1(361):.5f}\n")

def load(fn):
    rows = []
    with open(fn, newline="") as f:
        for r in csv.DictReader(f):
            try:
                y = float(r["y"]); p = float(r["pressure"])
                if y == y and p == p: rows.append((y, p))
            except (TypeError, ValueError, KeyError):
                pass
    return rows

def stats(rows, label):
    if not rows: return
    d0 = [abs(p - P0(y)) for y, p in rows]
    d1 = [abs(p - P1(y)) for y, p in rows]
    lo = [ (y,p) for y,p in rows if y <= 280 ]
    hi = [ (y,p) for y,p in rows if y > 280 ]
    print(f"== {label}: n={len(rows)}")
    print(f"   |rec-P0| mean={sum(d0)/len(d0):.5f}  max={max(d0):.5f}")
    print(f"   |rec-P1| mean={sum(d1)/len(d1):.5f}  max={max(d1):.5f}")
    if hi:
        d0h = [abs(p-P0(y)) for y,p in hi]; d1h = [abs(p-P1(y)) for y,p in hi]
        print(f"   y>280 ({len(hi)} rows): |rec-P0| mean={sum(d0h)/len(d0h):.5f}   |rec-P1| mean={sum(d1h)/len(d1h):.5f}")
    # biggest disagreements
    worst = sorted(rows, key=lambda r: abs(r[1]-P1(r[0])), reverse=True)[:5]
    for y, p in worst:
        print(f"   worst vs P1: y={y:7.2f} rec={p:.5f} P0={P0(y):.5f} P1={P1(y):.5f}")

for fn in sorted(glob.glob(os.path.join(LOG_DIR, "flight_*.csv"))):
    stats(load(fn), fn)

# high-altitude detail from _8_delaytest (y up to 361)：历史日志非必现，缺失时跳过（不中断整段分析）
print("\n-- y>270 detail from flight_overworld_00b1000b_8_delaytest.csv --")
detail = os.path.join(LOG_DIR, "flight_overworld_00b1000b_8_delaytest.csv")
rows = load(detail) if os.path.exists(detail) else []
if not rows:
    print("   (detail log not present under flight_logs; skipped)")
else:
    hi = [r for r in rows if r[0] > 270]
    step = max(1, len(hi)//12)
    for y, p in hi[::step]:
        print(f"   y={y:7.2f}  rec={p:.5f}  P0={P0(y):.5f}  P1={P1(y):.5f}  rec-P0={p-P0(y):+.5f}  rec-P1={p-P1(y):+.5f}")
