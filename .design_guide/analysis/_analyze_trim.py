import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_20260910-202305.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r,k):
    try: return float(r[k])
    except: return float('nan')

def stat(name, vals):
    vals=[v for v in vals if not math.isnan(v)]
    if not vals: print(f"  {name:24s} ALL NaN"); return None
    m=sum(vals)/len(vals)
    print(f"  {name:24s} mean={m:9.3f}  min={min(vals):9.3f}  max={max(vals):9.3f}  n={len(vals)}")
    return m

# 稳定巡航段
seg=[]
for r in rows:
    t=g(r,'time_s'); p=g(r,'pitchDeg'); vy=g(r,'vY'); a=g(r,'airSpeed')
    if abs(p)<2.5 and abs(vy)<1.5 and not math.isnan(a) and 40<a<56 and t>10:
        seg.append(r)
print(f"稳定巡航段: {len(seg)} 行, t {g(seg[0],'time_s'):.1f}..{g(seg[-1],'time_s'):.1f}s")

print("\n== 运动学 ==")
stat("pitchDeg", [g(r,'pitchDeg') for r in seg])
stat("airSpeed", [g(r,'airSpeed') for r in seg])
stat("vY", [g(r,'vY') for r in seg])
stat("pressure", [g(r,'pressure') for r in seg])
stat("altitude", [g(r,'altitude') for r in seg])

print("\n== 整链力矩（绕链质心，俯仰轴 Mx；正=?）==")
stat("chainLiftMx", [g(r,'chainLiftMx') for r in seg])
stat("chainDragMx", [g(r,'chainDragMx') for r in seg])
stat("chainPropMx", [g(r,'chainPropMx') for r in seg])

print("\n== 整链力（机体系）==")
stat("chainLiftFy(升力Y)", [g(r,'chainLiftFy') for r in seg])
stat("chainLiftFx", [g(r,'chainLiftFx') for r in seg])
stat("chainPropFz(推力Z)", [g(r,'chainPropFz') for r in seg])
stat("chainDragFx", [g(r,'chainDragFx') for r in seg])
stat("chainDragFy", [g(r,'chainDragFy') for r in seg])

print("\n== 重力 ==")
stat("chainMassKg", [g(r,'chainMassKg') for r in seg])
