import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_20260910-195835.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r,k):
    try: return float(r[k])
    except: return float('nan')

# 巡航段：俯仰接近 0、摇杆 Y 未按、皮托管有读数
seg = []
for r in rows:
    t = g(r,'time_s'); p = g(r,'pitchDeg'); jy = g(r,'joyY')
    a = g(r,'airSpeed'); vn = g(r,'vNorm'); gs = g(r,'groundSpeed')
    if abs(p) < 3 and abs(jy) < 0.02 and not math.isnan(a) and not math.isnan(vn) and vn > 30:
        seg.append(r)
if not seg:
    print("no cruise segment found"); raise SystemExit

# 用中间段（去掉首尾加速/减速）
mid = seg[len(seg)//4 : 3*len(seg)//4]
def mean(vals): return sum(vals)/len(vals)

t0 = min(g(r,'time_s') for r in mid); t1 = max(g(r,'time_s') for r in mid)
print(f"巡航段: t {t0:.1f}..{t1:.1f}s, {len(mid)} 行")

as_  = [g(r,'airSpeed') for r in mid]
gs_  = [g(r,'groundSpeed') for r in mid]
vn_  = [g(r,'vNorm') for r in mid]
vy_  = [g(r,'vY') for r in mid]
pr_  = [g(r,'pressure') for r in mid]
pit_ = [g(r,'pitchDeg') for r in mid]
m_   = [g(r,'chainMassKg') for r in mid]

def stat(name, vals):
    print(f"  {name:22s} mean={mean(vals):8.3f}  min={min(vals):8.3f}  max={max(vals):8.3f}")

print("\n== 速度对比 ==")
stat("vNorm(真实|v|)", vn_)
stat("groundSpeed(皮托管)", gs_)
stat("airSpeed(皮托管空速)", as_)
stat("vY(垂直速度)", vy_)
stat("pitchDeg", pit_)
stat("pressure", pr_)
stat("chainMassKg", m_)

print("\n== 皮托管 vs 真实速度 ==")
print(f"  airSpeed / vNorm 平均 = {mean([a/v for a,v in zip(as_,vn_)]):.4f}")
print(f"  groundSpeed / vNorm 平均 = {mean([gv/v for gv,v in zip(gs_,vn_)]):.4f}")
# 夹角（若轴沿速度方向则 ≈0）
ang = math.degrees(math.acos(max(-1,min(1, mean([gv/v for gv,v in zip(gs_,vn_)])))))
print(f"  速度与皮托管轴夹角(按 groundSpeed 投影) ≈ {ang:.1f}°")

print("\n== 升力 vs 重力（谁的速度才是平衡点）==")
# chainLiftFy 是机体系 Y 分量；重量 = chainMass*g（g=11）
w_ = [g(r,'chainMassKg')*11 for r in mid]
lfy_ = [g(r,'chainLiftFy') for r in mid]
stat("重量 m*g(=重力)", w_)
stat("chainLiftFy(升力机体系Y)", lfy_)
# 升力大小
lf_ = [math.sqrt(g(r,'chainLiftFx')**2+g(r,'chainLiftFy')**2+g(r,'chainLiftFz')**2) for r in mid]
stat("|chainLiftF|(升力大小)", lf_)
