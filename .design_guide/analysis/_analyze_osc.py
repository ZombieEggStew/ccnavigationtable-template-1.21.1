import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_20260910-204039.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r,k):
    try: return float(r[k])
    except: return float('nan')

# 巡航段
seg=[]
for r in rows:
    t=g(r,'time_s'); p=g(r,'pitchDeg'); vy=g(r,'vY'); a=g(r,'airSpeed')
    if abs(p)<2.5 and abs(vy)<1.5 and not math.isnan(a) and 40<a<56 and t>10:
        seg.append(r)
if not seg:
    print("no cruise"); raise SystemExit
print(f"巡航段: {len(seg)} 行, t {g(seg[0],'time_s'):.1f}..{g(seg[-1],'time_s'):.1f}s")

def col(k): return [g(r,k) for r in seg]
def stat(name, v):
    vv=[x for x in v if not math.isnan(x)]
    if not vv: print(f"  {name:20s} ALL NaN"); return
    m=sum(vv)/len(vv)
    sd=math.sqrt(sum((x-m)**2 for x in vv)/len(vv))
    print(f"  {name:20s} mean={m:8.3f}  sd={sd:8.3f}  min={min(vv):8.3f}  max={max(vv):8.3f}")

print("\n== 运动学 ==")
stat("pitchDeg", col('pitchDeg'))
stat("airSpeed(实际空速)", col('airSpeed'))
stat("vY", col('vY'))
stat("pressure", col('pressure'))
stat("altitude", col('altitude'))

print("\n== 油门 ==")
stat("thrAxis(闭环油门)", col('thrAxis'))

# 模型目标速度（用实际 P 近似；模型实际用目标高度气压，差很小）
G0=45.25*11; N=36
tg=[G0/(0.475*pr*N) for pr in col('pressure')]
stat("目标速度(按实际P复算)", tg)

print("\n== 空速误差 ==")
err=[a-t for a,t in zip(col('airSpeed'),tg)]
stat("实际-目标 误差", err)

# 采样时间序列看振荡形态
print("\n采样 (t, pitch, airSpeed, 目标, thrAxis):")
prev=None
for r in seg:
    t=g(r,'time_s')
    if prev is None or t-prev>=2.0:
        pr=g(r,'pressure'); tgt=G0/(0.475*pr*N) if not math.isnan(pr) else float('nan')
        print("  %6.1f  %6.2f  %7.2f  %6.2f  %6.3f"%(t,g(r,'pitchDeg'),g(r,'airSpeed'),tgt,g(r,'thrAxis')))
        prev=t
