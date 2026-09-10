import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_18.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r, k):
    try: return float(r[k])
    except Exception: return float('nan')

def col(k): return [g(r,k) for r in rows]

t = col('time_s'); y = col('y'); pitch = col('pitchDeg')
wbX = col('wbX'); P = col('pressure')
joyX = col('joyX'); joyY = col('joyY'); pedL = col('pedL'); pedR = col('pedR'); thrA = col('thrAxis')

CL = {k: col(k) for k in ['chainLiftMx','chainLiftMy','chainLiftMz']}
CD = {k: col(k) for k in ['chainDragMx','chainDragMy','chainDragMz']}
CP = {k: col(k) for k in ['chainPropMx','chainPropMy','chainPropMz']}
HOST = {k: col(k) for k in ['liftMx','liftMy','liftMz','dragMx','dragMy','dragMz','propMx','propMy','propMz']}

def corr(a,b):
    ab=[(x,zz) for x,zz in zip(a,b) if not math.isnan(x) and not math.isnan(zz)]
    if len(ab)<20: return float('nan')
    ma=sum(x for x,_ in ab)/len(ab); mb=sum(zz for _,zz in ab)/len(ab)
    num=sum((x-ma)*(zz-mb) for x,zz in ab)
    da=math.sqrt(sum((x-ma)**2 for x,_ in ab)); db=math.sqrt(sum((zz-mb)**2 for _,zz in ab))
    return num/(da*db) if da and db else float('nan')

def stats(vals):
    vs=[v for v in vals if not math.isnan(v)]
    if not vs: return (float('nan'),)*3
    m=sum(vs)/len(vs)
    sd=math.sqrt(sum((v-m)**2 for v in vs)/len(vs))
    return (m, sd, len(vs))

# ---------- 干净段：满油门 + 全松手 ----------
T0, T1 = 21.9, 146.0   # 松手起点（最后一次拉杆 t~21.4 结束后）到收油门 t~146.9 前
seg = [i for i in range(len(rows)) if T0 <= t[i] <= T1
       and abs(joyX[i])<0.02 and abs(joyY[i])<0.02
       and abs(pedL[i])<0.05 and abs(pedR[i])<0.05
       and thrA[i] > 0.99]
print(f"clean hands-off full-throttle segment: {len(seg)} rows ({len(seg)/20:.1f} s)")
if not seg: raise SystemExit("no clean segment")

# ---------- phugoid: 峰谷 / 周期 / 阻尼 ----------
ys = [(t[i], y[i]) for i in seg]
peaks=[]; trs=[]
for k in range(1,len(ys)-1):
    if ys[k][1]>ys[k-1][1] and ys[k][1]>=ys[k+1][1]: peaks.append(ys[k])
    if ys[k][1]<ys[k-1][1] and ys[k][1]<=ys[k+1][1]: trs.append(ys[k])
print("\n-- phugoid (hands-off, full throttle) --")
print(f"peaks  : {[(round(a,1),round(b,0)) for a,b in peaks]}")
print(f"troughs: {[(round(a,1),round(b,0)) for a,b in trs]}")

ext = sorted(peaks+trs, key=lambda p:p[0])
per = [ext[k+1][0]-ext[k][0] for k in range(len(ext)-1)]
print(f"half-periods: {[round(p,1) for p in per]}")
print(f"full period mean: {2*sum(per)/len(per):.1f} s")

amps=[]
for k in range(min(len(peaks),len(trs))):
    pk=peaks[k]; tr=min([x for x in trs if x[0]>pk[0]], key=lambda x:x[0], default=None)
    if tr: amps.append((pk[0], pk[1]-tr[1]))
print(f"amplitudes(peak-to-following-trough): {[(round(a,1),round(b,0)) for a,b in amps]}")

# log-decrement（相邻同向极值对：峰对峰、谷对谷 -> 完整周期）
dec=[]
pk_sorted = sorted(peaks, key=lambda p:p[0])
tr_sorted = sorted(trs, key=lambda p:p[0])
for a1,a2 in zip(pk_sorted, pk_sorted[1:]):
    if a2[1]>0: dec.append(math.log(a1[1]/a2[1]))
for a1,a2 in zip(tr_sorted, tr_sorted[1:]):
    if a1[1]>0: dec.append(math.log(a2[1]/a1[1]))
dec=[d for d in dec if not math.isnan(d)]
if dec:
    lam=sum(dec)/len(dec)
    # lam = 每周期对数衰减；zeta 由 lam = 2*pi*zeta/sqrt(1-zeta^2) 反解
    if lam != 0:
        zeta=lam/math.sqrt(lam*lam+4*math.pi*math.pi) if lam<0 else -lam/math.sqrt(lam*lam+4*math.pi*math.pi)
        Tper=2*sum(per)/len(per)
        print(f"log-decrement mean: {lam:+.4f}/cycle -> zeta={zeta:+.4f} (positive=GROWING/divergent, negative=decaying)")
        print(f"  half-amplitude time: {abs(0.693/lam)*Tper:.0f} s" if lam<0 else "  AMPLITUDE GROWING (no decay)")

# ---------- 力矩分析 ----------
print("\n-- moments in clean segment (mean +/- sd, n) --")
for grp, nm in [(CL,'chainLift'),(CD,'chainDrag'),(CP,'chainProp')]:
    for ax in ['Mx','My','Mz']:
        key=f"{nm}{ax}"
        m,sd,nn = stats([grp[key][i] for i in seg])
        if nn: print(f"  {nm}{ax}: {m:+.2f} +/- {sd:.2f} (n={nn})")

netx = []
for i in seg:
    vals=[CL['chainLiftMx'][i], CD['chainDragMx'][i], CP['chainPropMx'][i]]
    if all(not math.isnan(v) for v in vals):
        netx.append((i, vals[0]+vals[1]+vals[2]))
idx=[p[0] for p in netx]; nv=[p[1] for p in netx]
m,sd,nn=stats(nv)
print(f"\nnet Mx (chainLift+chainDrag+chainProp): {m:+.2f} +/- {sd:.2f}  corr(netMx,wbX)={corr(nv,[wbX[i] for i in idx]):+.3f}")

print("\ncorr with wbX (pitch rate):")
for grp, nm in [(CL,'chainLift'),(CD,'chainDrag'),(CP,'chainProp')]:
    key=f"{nm}Mx"
    print(f"  {nm}Mx: {corr([grp[key][i] for i in seg],[wbX[i] for i in seg]):+.3f}")

# 残余力矩 vs 气压
print("\n-- net Mx vs pressure regression (netMx = a + b*P) --")
pr=[P[i] for i in idx]
mP=sum(pr)/len(pr); mN=sum(nv)/len(nv)
num=sum((p-mP)*(v-mN) for p,v in zip(pr,nv)); den=sum((p-mP)**2 for p in pr)
b=num/den if den else float('nan'); a=mN-b*mP
print(f"  P range in seg: {min(pr):.4f}..{max(pr):.4f} (alt {min(y[i] for i in idx):.0f}-{max(y[i] for i in idx):.0f} m)")
print(f"  a(intercept, ~thrust-line offset, P-independent)={a:+.2f}")
print(f"  b(slope, aero residual per unit P)={b:+.2f}")
print(f"  -> extrapolate net Mx at P=1 (sea level): {a+b:+.2f}")

# 控制面贡献：chain - host
print("\n-- control-surface contribution (chain - host) mean in clean seg --")
for chgrp, hostpre, nm in [(CL,'lift','lift'),(CD,'drag','drag'),(CP,'prop','prop')]:
    for k, ax in enumerate(['Mx','My','Mz']):
        hk=f"{hostpre}{ax}"; ck=f"chain{hostpre[0].upper()}{hostpre[1:]}{ax}"
        diffs=[chgrp[ck][i]-HOST[hk][i] for i in seg
               if not math.isnan(chgrp[ck][i]) and not math.isnan(HOST[hk][i])]
        m2,_,_=stats(diffs)
        print(f"  {nm}{ax}: {m2:+.3f} (n={len(diffs)})")

# 推力线偏置估算
pmx=[CP['chainPropMx'][i] for i in seg if not math.isnan(CP['chainPropMx'][i])]
print(f"\nchainPropMx in clean seg: mean {stats(pmx)[0]:+.3f} sd {stats(pmx)[1]:.3f}")

m2,_,_=stats([wbX[i] for i in seg])
print(f"wbX (pitch rate) in clean seg: mean {m2:+.3f}")
print("\nNOTE: P dynamic range within segment is small; a/b split is indicative. Check low-alt + P=1 by low-level pass or landing-approach data.")
