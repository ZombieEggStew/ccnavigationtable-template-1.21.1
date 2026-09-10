import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_20.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r, k):
    try: return float(r[k])
    except Exception: return float('nan')

def pct(vals, p):
    s = sorted(v for v in vals if not math.isnan(v))
    if not s: return float('nan')
    i = (len(s)-1)*p/100.0
    return s[int(i)]

def stats(name, vals, nd=3):
    vs = [v for v in vals if not math.isnan(v)]
    if not vs:
        print(f"{name}: (empty)"); return
    print(f"{name}: n={len(vs)} p10={pct(vs,10):.{nd}f} p50={pct(vs,50):.{nd}f} p90={pct(vs,90):.{nd}f} min={min(vs):.{nd}f} max={max(vs):.{nd}f}")

t = [g(r,'time_s') for r in rows]
y = [g(r,'y') for r in rows]
pitch = [g(r,'pitchDeg') for r in rows]
air = [g(r,'airSpeed') for r in rows]
vy = [g(r,'vY') for r in rows]
press = [g(r,'pressure') for r in rows]
joyX = [g(r,'joyX') for r in rows]; joyY = [g(r,'joyY') for r in rows]
joyXA = [g(r,'joyXA') for r in rows]; joyYA = [g(r,'joyYA') for r in rows]
pedL = [g(r,'pedL') for r in rows]; pedR = [g(r,'pedR') for r in rows]
thrA = [g(r,'thrAxis') for r in rows]

K = ['liftFx','liftFy','liftFz','liftMx','liftMy','liftMz']
D = ['dragFx','dragFy','dragFz','dragMx','dragMy','dragMz']
P = ['propFx','propFy','propFz','propMx','propMy','propMz']
CK = ['chainLiftFx','chainLiftFy','chainLiftFz','chainLiftMx','chainLiftMy','chainLiftMz']
CD = ['chainDragFx','chainDragFy','chainDragFz','chainDragMx','chainDragMy','chainDragMz']
CP = ['chainPropFx','chainPropFy','chainPropFz','chainPropMx','chainPropMy','chainPropMz']

print(f"rows={len(rows)} duration={t[-1]-t[0]:.1f}s")
stats("y", y); stats("pitchDeg(低头+)", pitch); stats("airSpeed", air)
stats("vY", vy); stats("pressure", press)
stats("thrAxis", thrA)
# 控制使用率（非零/按住比例）
def frac(idx, cond):
    if not idx: return float('nan')
    return sum(1 for i in idx if cond(i))/len(idx)
fly = [i for i in range(len(rows)) if y[i] > 90]
print(f"\nfly rows(y>90): {len(fly)}")
print(f"  joy active 比例: {frac(fly, lambda i: joyXA[i]>0 or joyYA[i]>0):.2f}")
print(f"  ped |值|>0.1 比例: {frac(fly, lambda i: abs(pedL[i])>0.1 or abs(pedR[i])>0.1):.2f}")
print(f"  thrAxis>0.05 比例: {frac(fly, lambda i: thrA[i]>0.05):.2f}")

# 力列可用性（巡航段）
cruise = [i for i in range(len(rows)) if 200 <= y[i] <= 400 and t[i] > 20]
print(f"\ncruise rows(y 200..400, t>20): {len(cruise)}")
for nm, cols in [("lift",K),("drag",D),("prop",P),("chainLift",CK),("chainDrag",CD),("chainProp",CP)]:
    ok = sum(1 for i in cruise if not math.isnan(g(rows[i], cols[0])))
    print(f"  {nm}: 非nan行 {ok}/{len(cruise)}")

# 松手窗口（巡航内：无摇杆动作、踏板接近零）
ho = [i for i in cruise if (joyXA[i]==0 and joyYA[i]==0 and abs(joyX[i])<0.02 and abs(joyY[i])<0.02
                           and abs(pedL[i])<0.05 and abs(pedR[i])<0.05)]
print(f"\nhands-off cruise rows: {len(ho)}")

# pitch 轴 = 机体局部 X（SensorSystemAPI 约定 pitch 绕局部 X）→ 力矩列 Mx 看俯仰
for nm, cols in [("lift",K),("drag",D),("prop",P),("chainLift",CK),("chainDrag",CD),("chainProp",CP)]:
    mx = [g(rows[i], cols[3]) for i in ho]
    stats(f"{nm}Mx(ho) 均值→ ", mx)
# 力的量级（|F| 中位数）
def mag(cols, i):
    fx, fy, fz = g(rows[i], cols[0]), g(rows[i], cols[1]), g(rows[i], cols[2])
    return math.sqrt(fx*fx+fy*fy+fz*fz) if not any(math.isnan(v) for v in (fx,fy,fz)) else float('nan')
for nm, cols in [("lift",K),("drag",D),("prop",P),("chainLift",CK),("chainDrag",CD),("chainProp",CP)]:
    mags = [mag(cols, i) for i in ho]
    stats(f"|{nm}F|(ho)", mags)

# 相位：pitch 速率(约 wbX) 与 chainDragMx / chainLiftMx 相关
def corr(a,b):
    ab = [(x,y) for x,y in zip(a,b) if not math.isnan(x) and not math.isnan(y)]
    if len(ab)<10: return float('nan')
    ma = sum(x for x,_ in ab)/len(ab); mb = sum(y for _,y in ab)/len(ab)
    num = sum((x-ma)*(y-mb) for x,y in ab)
    da = math.sqrt(sum((x-ma)**2 for x,_ in ab)); db = math.sqrt(sum((y-mb)**2 for _,y in ab))
    return num/(da*db) if da and db else float('nan')
wbX = [g(r,'wbX') for r in rows]
print(f"\ncorr(wbX[pitchRate], chainDragMx) = {corr([wbX[i] for i in ho], [g(rows[i],CD[3]) for i in ho]):.3f}")
print(f"corr(wbX, chainLiftMx)           = {corr([wbX[i] for i in ho], [g(rows[i],CK[3]) for i in ho]):.3f}")
print(f"corr(chainLiftMx, y)             = {corr([g(rows[i],CK[3]) for i in ho], [y[i] for i in ho]):.3f}")

# 巡航高度峰谷（phugoid 是否还在）
seg = sorted(ho, key=lambda i: t[i])
ys = [y[i] for i in seg]; ts = [t[i] for i in seg]
peaks=[]; trs=[]
for k in range(1,len(seg)-1):
    if ys[k]>ys[k-1] and ys[k]>=ys[k+1] and abs(ys[k]-ys[k-1])>1 and abs(ys[k]-ys[k+1])>1:
        peaks.append((ts[k], ys[k]))
    if ys[k]<ys[k-1] and ys[k]<=ys[k+1] and abs(ys[k]-ys[k-1])>1 and abs(ys[k]-ys[k+1])>1:
        trs.append((ts[k], ys[k]))
print(f"\nhands-off 高度峰: {[(round(a,0),round(b,0)) for a,b in peaks[:12]]}")
print(f"hands-off 高度谷: {[(round(a,0),round(b,0)) for a,b in trs[:12]]}")
if len(peaks)>=2:
    per=[peaks[i+1][0]-peaks[i][0] for i in range(len(peaks)-1)]
    print(f"峰周期: mean={sum(per)/len(per):.1f}s {[round(p,1) for p in per]}")
