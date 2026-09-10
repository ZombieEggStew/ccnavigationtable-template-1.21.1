import csv, math, os, sys

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_20260910-175949.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r,k):
    try: return float(r[k])
    except: return float('nan')

def qrot_inv(q, v):
    # v_body = q^-1 * v * q ; q=(qx,qy,qz,qw) body->world
    qx,qy,qz,qw = -q[0], -q[1], -q[2], q[3]
    vx,vy,vz = v
    tx = qy*vz - qz*vy + qw*vx
    ty = qz*vx - qx*vz + qw*vy
    tz = qx*vy - qy*vx + qw*vz
    return (vx + 2*(qy*tz - qz*ty), vy + 2*(qz*tx - qx*tz), vz + 2*(qx*ty - qy*tx))

out = []
for r in rows:
    q = (g(r,'qx'), g(r,'qy'), g(r,'qz'), g(r,'qw'))
    if math.isnan(q[0]): continue
    ld = qrot_inv(q, (0.0, -1.0, 0.0))
    roll_read = math.degrees(math.atan2(ld[0], -ld[1]))
    roll_fix  = math.degrees(math.atan2(ld[0], math.sqrt(ld[1]*ld[1] + ld[2]*ld[2])))
    out.append((g(r,'time_s'), g(r,'pitchDeg'), g(r,'rollDeg'), roll_read, roll_fix,
                g(r,'joyX'), g(r,'joyY'), g(r,'joyXA'), g(r,'joyYA')))

if not out:
    print("no data"); sys.exit(0)

# sanity: recomputed roll (game formula from quaternion) vs rollDeg column
diffs = [abs(a[3]-a[2]) for a in out if not math.isnan(a[2])]
print("rows=%d  sanity |recomputed_game_roll - rollDeg|: min %.2f max %.2f mean %.2f"
      % (len(out), min(diffs), max(diffs), sum(diffs)/len(diffs)))

def corr(a,b):
    ab=[(x,y) for x,y in zip(a,b) if not math.isnan(x) and not math.isnan(y)]
    if len(ab)<20: return float('nan')
    ma=sum(x for x,_ in ab)/len(ab); mb=sum(y for _,y in ab)/len(ab)
    num=sum((x-ma)*(y-mb) for x,y in ab)
    da=math.sqrt(sum((x-ma)**2 for x,_ in ab)); db=math.sqrt(sum((y-mb)**2 for _,y in ab))
    return num/(da*db) if da and db else float('nan')

ts=[a[0] for a in out]; ps=[a[1] for a in out]; rd=[a[2] for a in out]; rf=[a[4] for a in out]
# 只关心非手动的段（摇杆 X/Y 未按），纯 auto_roll 反应
man = [i for i in range(len(out)) if not math.isnan(out[i][5]) and abs(out[i][5])<0.02 and abs(out[i][6])<0.02]
print("\n非手动段 (joyX/Y=0) %d 行:" % len(man))
for nm, col, ref in [("|rollDeg|",[abs(rd[i]) for i in man],[abs(ps[i]) for i in man]),
                     ("|fixed_bank|",[abs(rf[i]) for i in man],[abs(ps[i]) for i in man])]:
    print("  corr(%s, |pitchDeg|) = %.3f" % (nm, corr(col, ref)))

# 找 pitch 变化大的时间窗打印
print("\n采样每 ~1.5s (t pitch rollDeg roll_fixed joyX joyY):")
prev=None
for i,a in enumerate(out):
    t = a[0]
    if prev is None or t-prev>=1.4:
        print("  %6.1f %7.1f %7.1f %9.1f %5.2f %5.2f" % (t, a[1], a[2], a[4], a[5], a[6]))
        prev=t
