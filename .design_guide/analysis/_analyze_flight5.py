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
wbX = col('wbX')
CK = ['chainLiftMx','chainLiftMy','chainLiftMz']
CD = ['chainDragMx','chainDragMy','chainDragMz']
CP = ['chainPropMx','chainPropMy','chainPropMz']
CX = ['chainLiftMx','chainDragMx','chainPropMx']

def corr(a,b):
    ab=[(x,y) for x,y in zip(a,b) if not math.isnan(x) and not math.isnan(y)]
    if len(ab)<20: return float('nan')
    ma=sum(x for x,_ in ab)/len(ab); mb=sum(y for _,y in ab)/len(ab)
    num=sum((x-ma)*(y-mb) for x,y in ab)
    da=math.sqrt(sum((x-ma)**2 for x,_ in ab)); db=math.sqrt(sum((y-mb)**2 for _,y in ab))
    return num/(da*db) if da and db else float('nan')

def mean(vals):
    vs=[v for v in vals if not math.isnan(v)]
    return sum(vs)/len(vs) if vs else float('nan')

# 干净段 46.6..87.0s, y 250..330, 松手
seg = [i for i in range(len(rows)) if 46.0 <= t[i] <= 88.0 and 250 <= y[i] <= 330
       and col('joyXA')[i]==0 and col('joyYA')[i]==0
       and abs(col('joyX')[i])<0.02 and abs(col('joyY')[i])<0.02
       and abs(col('pedL')[i])<0.05 and abs(col('pedR')[i])<0.05]
print(f"clean segment rows: {len(seg)}")

# 各力矩列在该段内与 wbX 的相关（阻尼轴判定）
print("\n[clean] corr with wbX(pitch rate):")
for grp, nm in [(CK,'chainLift'),(CD,'chainDrag'),(CP,'chainProp')]:
    for k, ax in enumerate(['Mx','My','Mz']):
        c = corr([col(grp[k])[i] for i in seg], [wbX[i] for i in seg])
        if not math.isnan(c):
            print(f"  {nm}{ax}: {c:+.3f}")

# 段内均值（配平残差）
print("\n[clean] mean moments:")
for grp, nm in [(CK,'chainLift'),(CD,'chainDrag'),(CP,'chainProp')]:
    for k, ax in enumerate(['Mx','My','Mz']):
        m = mean([col(grp[k])[i] for i in seg])
        print(f"  {nm}{ax}: {m:+.2f}")

# 净 Mx 相关（阻尼/刚度指示）
netx = []
for i in seg:
    vals = [col(grp[0])[i] for grp in (CK,CD,CP)]
    if all(not math.isnan(v) for v in vals):
        netx.append((i, vals[0]+vals[1]+vals[2]))
if netx:
    idx = [p[0] for p in netx]; nv = [p[1] for p in netx]
    print(f"\n[clean] net Mx: mean={mean(nv):+.2f} corr(netMx, wbX)={corr(nv, [wbX[i] for i in idx]):+.3f}")

# phugoid 幅值序列（该段）
ys = [(t[i], y[i]) for i in seg]
peaks=[]; trs=[]
for k in range(1,len(ys)-1):
    if ys[k][1]>ys[k-1][1] and ys[k][1]>=ys[k+1][1]:
        peaks.append(ys[k])
    if ys[k][1]<ys[k-1][1] and ys[k][1]<=ys[k+1][1]:
        trs.append(ys[k])
print(f"\npeaks: {[(round(a,1),round(b,0)) for a,b in peaks]}")
print(f"troughs: {[(round(a,1),round(b,0)) for a,b in trs]}")
if len(peaks)>=3:
    per=[peaks[i+1][0]-peaks[i][0] for i in range(len(peaks)-1)]
    print(f"peak period: {[round(p,1) for p in per]} mean={sum(per)/len(per):.1f}s")
    amps=[]
    for pk, tr in zip(peaks, trs[:len(peaks)]):
        if tr[0]>pk[0]:
            amps.append(pk[1]-tr[1])
    if len(amps)>=2:
        print(f"amplitudes(峰-随后的谷): {[round(a,0) for a in amps]}")
