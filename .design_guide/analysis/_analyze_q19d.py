#!/usr/bin/env python3
"""Corrected cruise force-balance for _19."""
import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_19.csv")
rows = []
with open(path, newline="") as f:
    for r in csv.DictReader(f):
        rows.append(r)

def g(r, k):
    try: return float(r.get(k))
    except (TypeError, ValueError): return float("nan")

def hasf(r):
    return all(g(r, pre+ax) == g(r, pre+ax) for pre in ("chainPropF","chainDragF","chainLiftF") for ax in ("x","y","z"))

# longest merged segment (gap<=8) with cruise speed
segs=[]; cur=None
for i,r in enumerate(rows):
    if hasf(r):
        if cur is None: cur=[i,i,0]
        else: cur[1]=i; cur[2]=0
    else:
        if cur is not None:
            cur[2]+=1
            if cur[2]>8: segs.append(tuple(cur)); cur=None
if cur: segs.append(tuple(cur))

cands=[]
for s in segs:
    w=rows[s[0]:s[1]+1]
    sp=[g(r,"airSpeed") for r in w]
    dur=g(w[-1],"time_s")-g(w[0],"time_s")
    if dur>=5:
        cands.append((s,dur,sum(sp)/len(sp)))
cands.sort(key=lambda c:-c[1])
best=next((c for c in cands if 50<=c[2]<=75), cands[0])
s,dur,sp=best
print(f"segment rows {s[0]}..{s[1]} dur={dur:.0f}s speed~{sp:.1f}")

w=[r for r in rows[s[0]:s[1]+1] if hasf(r)]
print(f"rows with full force data: {len(w)}")

# flight direction from position deltas
prev=None; pt=None; vel=[]
for r in rows[s[0]:s[1]+1]:
    p=(g(r,"x"),g(r,"y"),g(r,"z"))
    if prev is not None:
        dt=g(r,"time_s")-pt
        if dt>0: vel.append(tuple((p[i]-prev[i])/dt for i in range(3)))
    prev,pt=p,g(r,"time_s")
vm=tuple(sum(v[i] for v in vel)/len(vel) for i in range(3))
vmag=math.sqrt(sum(c*c for c in vm))
u=tuple(c/vmag for c in vm)
up=(0.0,1.0,0.0)
print(f"flight dir u=({u[0]:.3f},{u[1]:.3f},{u[2]:.3f}) |v|={vmag:.2f}")

def proj(pre, axis):
    out=[]
    for r in w:
        f=(g(r,pre+"x"),g(r,pre+"y"),g(r,pre+"z"))
        if any(x!=x for x in f): continue
        out.append(sum(a*b for a,b in zip(f,axis)))
    return out
def mean(xs): return sum(xs)/len(xs) if xs else float("nan")

thrust=mean(proj("chainPropF",u)); drag=mean(proj("chainDragF",u)); lift_u=mean(proj("chainLiftF",u))
liftY=mean(proj("chainLiftF",up))
net=thrust+drag+lift_u
mc=mean([g(r,"chainMassKg") for r in w]); mh=mean([g(r,"massKg") for r in w])
spd=mean([g(r,"airSpeed") for r in w])
print(f"\nchainMass={mc:.2f} hostMass={mh:.2f} speed={spd:.2f}")
print(f"along flight dir (CSV imp/substep): thrust={thrust:.3f} sailDrag={drag:.3f} lift={lift_u:.3f} NET={net:.3f}")
print(f"vertical: chainLiftFy={liftY:.3f}   gravity=m*g={mc*11:.2f}")

d=0.09
print("\n== balance check: does universal damping close NET? ==")
print(f"universal damping force F=m*d*v = {mc*d*spd:.1f} (chain) / {mh*d*spd:.1f} (host)")
for substeps in (1,2,3,4):
    dt=1.0/20.0/substeps
    Jd_chain=mc*d*spd*dt   # damping impulse per substep, CSV units
    Jd_host=mh*d*spd*dt
    bal_chain=net-Jd_chain
    bal_host=net-Jd_host
    scale=1.0/dt
    print(f"  substeps={substeps}: dampImp/substep chain={Jd_chain:6.3f} host={Jd_host:6.3f} | net-damp(chain)={bal_chain:+6.3f} net-damp(host)={bal_host:+6.3f} | scale={scale:4.0f} thrust_diag={thrust*scale:6.1f} drag_diag={drag*scale:6.1f} liftY_diag={liftY*scale:6.1f} grav_diag={mc*11:6.1f} damp_diag(chain)={mc*d*spd:6.1f}")

# vertical check: lift vs gravity in diagram units
print("\nvertical balance: liftY_diag vs mass*11 (should be ~equal if units=Newton-ish)")
