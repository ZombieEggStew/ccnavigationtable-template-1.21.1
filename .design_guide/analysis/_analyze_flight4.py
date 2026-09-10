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

t = [g(r,'time_s') for r in rows]
y = [g(r,'y') for r in rows]
pitch = [g(r,'pitchDeg') for r in rows]

def col(k): return [g(r,k) for r in rows]
wbX, wbY, wbZ = col('wbX'), col('wbY'), col('wbZ')
CK = ['chainLiftFx','chainLiftFy','chainLiftFz','chainLiftMx','chainLiftMy','chainLiftMz']
CD = ['chainDragFx','chainDragFy','chainDragFz','chainDragMx','chainDragMy','chainDragMz']
CP = ['chainPropFx','chainPropFy','chainPropFz','chainPropMx','chainPropMy','chainPropMz']

def corr(a,b):
    ab=[(x,y) for x,y in zip(a,b) if not math.isnan(x) and not math.isnan(y)]
    if len(ab)<20: return float('nan')
    ma=sum(x for x,_ in ab)/len(ab); mb=sum(y for _,y in ab)/len(ab)
    num=sum((x-ma)*(y-mb) for x,y in ab)
    da=math.sqrt(sum((x-ma)**2 for x,_ in ab)); db=math.sqrt(sum((y-mb)**2 for _,y in ab))
    return num/(da*db) if da and db else float('nan')

# 1) 哪个 wb 轴 = 俯仰速率（对 pitch 数值微分找最大相关的 wb 轴）
dt = 0.05
pr = []
for i in range(1, len(rows)):
    d = pitch[i]-pitch[i-1]
    # 角度可能跨 ±180，取最小角差
    d = (d+180)%360-180
    pr.append(d/dt)
pr = [float('nan')]+pr
print("wb 轴 vs pitch 数值导数 相关:")
for nm, w in [("wbX",wbX),("wbY",wbY),("wbZ",wbZ)]:
    print(f"  {nm}: {corr(w, pr):.3f}")

# 2) 全段：各力组力矩分量 vs 俯仰速率（找阻尼力矩轴与符号）
print("\nchain 力矩分量 vs 俯仰速率(数值导数) 相关:")
for grp, nm in [(CK,'chainLift'),(CD,'chainDrag'),(CP,'chainProp')]:
    for k in range(3,6):
        ax = ['X','Y','Z'][k-3]
        c = corr(col(grp[k]), pr)
        if abs(c) > 0.1:
            print(f"  {nm}M{ax}: {c:.3f}")

# 3) 净气动力矩（整链三组合成）——松手巡航段的均值/摆动
net = {'Mx':[], 'My':[], 'Mz':[]}
ho = [i for i in range(len(rows)) if y[i]>200 and t[i]>20
      and col('joyXA')[i]==0 and col('joyYA')[i]==0
      and abs(col('joyX')[i])<0.02 and abs(col('joyY')[i])<0.02
      and abs(col('pedL')[i])<0.05 and abs(col('pedR')[i])<0.05]
for i in ho:
    mx = sum(g(rows[i], grp[3]) for grp in (CK,CD,CP) if not math.isnan(g(rows[i],grp[3])))
    # 若有组 nan 则整行不算
    ok = all(not math.isnan(g(rows[i],grp[3])) for grp in (CK,CD,CP))
    if ok:
        net['Mx'].append(mx)
print(f"\nhands-off 巡航行(净力矩齐全): {len(net['Mx'])}")
for ax, vals in net.items():
    if vals:
        print(f"  netM{ax[-1]}: mean={sum(vals)/len(vals):.2f} min={min(vals):.2f} max={max(vals):.2f}")

# 4) 找最长连续松手段（y 250..330），作为干净样本
best = []
cur = []
for i in range(len(rows)):
    cond = (y[i]>=250 and y[i]<=330 and t[i]>20
            and col('joyXA')[i]==0 and col('joyYA')[i]==0
            and abs(col('joyX')[i])<0.02 and abs(col('joyY')[i])<0.02
            and abs(col('pedL')[i])<0.05 and abs(col('pedR')[i])<0.05)
    if cond:
        cur.append(i)
        if len(cur) > len(best): best = list(cur)
    else:
        cur = []
if best:
    print(f"\n最长连续松手段(y250-330): t {t[best[0]]:.1f}..{t[best[-1]]:.1f}s ({len(best)} 行)")
    ys=[y[i] for i in best]
    print(f"  高度范围 {min(ys):.1f}..{max(ys):.1f}  均值 {sum(ys)/len(ys):.1f}")
    print("  2s 采样 (t, y, pitch, liftMx, chainDragMx, chainLiftMx):")
    prev=None
    for i in best:
        if prev is None or t[i]-prev>=1.9:
            lm = g(rows[i],'chainLiftMx'); dm = g(rows[i],'chainDragMx')
            print(f"    {t[i]:5.1f} {y[i]:7.1f} {pitch[i]:6.1f} {lm:8.2f} {dm:8.2f}")
            prev=t[i]
