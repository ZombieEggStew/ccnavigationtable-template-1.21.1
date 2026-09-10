import csv, math, os

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
    qx,qy,qz,qw = -q[0], -q[1], -q[2], q[3]
    vx,vy,vz = v
    tx = qy*vz - qz*vy + qw*vx
    ty = qz*vx - qx*vz + qw*vy
    tz = qx*vy - qy*vx + qw*vz
    return (vx + 2*(qy*tz - qz*ty), vy + 2*(qz*tx - qx*tz), vz + 2*(qx*ty - qy*tx))

t, pitch, roll, wbx, wby, wbz = [], [], [], [], [], []
fix = []
for r in rows:
    q = (g(r,'qx'), g(r,'qy'), g(r,'qz'), g(r,'qw'))
    if math.isnan(q[0]): continue
    ld = qrot_inv(q, (0.0, -1.0, 0.0))
    t.append(g(r,'time_s')); pitch.append(g(r,'pitchDeg')); roll.append(g(r,'rollDeg'))
    wbx.append(g(r,'wbX')); wby.append(g(r,'wbY')); wbz.append(g(r,'wbZ'))
    fix.append(math.degrees(math.atan2(ld[0], math.sqrt(ld[1]*ld[1]+ld[2]*ld[2]))))

def corr(a,b):
    ab=[(x,y) for x,y in zip(a,b) if not math.isnan(x) and not math.isnan(y)]
    if len(ab)<20: return float('nan')
    ma=sum(x for x,_ in ab)/len(ab); mb=sum(y for _,y in ab)/len(ab)
    num=sum((x-ma)*(y-mb) for x,y in ab)
    da=math.sqrt(sum((x-ma)**2 for x,_ in ab)); db=math.sqrt(sum((y-mb)**2 for _,y in ab))
    return num/(da*db) if da and db else float('nan')

# 1) av.z (=wbZ) 是不是 roll 速率？与 roll 数值导数对比（含符号）
dt=0.05
dr=[]
for i in range(1,len(rows)):
    d=roll[i]-roll[i-1]
    d=(d+180)%360-180
    dr.append(d/dt)
dr=[float('nan')]+dr
print("wbZ vs d(rollDeg)/dt 相关: %.3f" % corr(wbz, dr))
print("wbZ vs wbX(俯仰速率) 相关: %.3f" % corr(wbz, wbx))

# 2) 找 pitch 变化剧烈的时间窗，打印 wbX/wbY/wbZ/roll/fix
print("\n时间窗 1: t 33..48 (大俯仰摆动 + 滚转摆动)")
print("  t    pitch  roll  fix  wbX(俯仰率) wbY(偏航率) wbZ(滚转率)")
for i in range(len(t)):
    if 33.0 <= t[i] <= 48.0 and (i%4==0):
        print("  %5.1f %6.1f %6.1f %6.1f  %6.1f   %6.1f   %6.1f" %
              (t[i], pitch[i], roll[i], fix[i], math.degrees(wbx[i]), math.degrees(wby[i]), math.degrees(wbz[i])))

print("\n时间窗 2: t 54..70 (陡爬升 + 推杆)")
print("  t    pitch  roll  fix  wbX(俯仰率) wbY(偏航率) wbZ(滚转率)")
for i in range(len(t)):
    if 54.0 <= t[i] <= 70.0 and (i%4==0):
        print("  %5.1f %6.1f %6.1f %6.1f  %6.1f   %6.1f   %6.1f" %
              (t[i], pitch[i], roll[i], fix[i], math.degrees(wbx[i]), math.degrees(wby[i]), math.degrees(wbz[i])))
