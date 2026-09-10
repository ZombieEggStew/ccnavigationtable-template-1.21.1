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

def qmul(a,b):
    aw,ax,ay,az = a; bw,bx,by,bz = b
    return (aw*bw - ax*bx - ay*by - az*bz,
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw)

def qconj(a): return (a[0], -a[1], -a[2], -a[3])

def qrot_inv(q, v):
    # v' = q^-1 v q  (q body->world; 结果 = 世界向量在机体系的分量)
    qx,qy,qz,qw = -q[1], -q[2], -q[3], q[0]
    vx,vy,vz = v
    tx = qy*vz - qz*vy + qw*vx
    ty = qz*vx - qx*vz + qw*vy
    tz = qx*vy - qy*vx + qw*vz
    return (vx + 2*(qy*tz - qz*ty), vy + 2*(qz*tx - qx*tz), vz + 2*(qx*ty - qy*tx))

def angvel_from_q(q_now, q_prev, order):
    # 与 Sable 相同的轴角差分：difference = q_now ⊗ q_prev⁻¹ (或反序)，再取共轭
    if order == 'now*prev^-1':
        diff = qmul(q_now, qconj(q_prev))
    else:
        diff = qmul(q_prev, qconj(q_now))
    diff = qconj(diff)
    w, x, y, z = diff
    v = (x, y, z)
    l2 = x*x + y*y + z*z
    if l2 <= 1e-15:
        scale = 2.0 / w if w != 0 else 0.0
        ang = (x*scale, y*scale, z*scale)
    else:
        l = math.sqrt(l2)
        ang = (x/l*2*math.acos(max(-1,min(1,w))), y/l*2*math.acos(max(-1,min(1,w))), z/l*2*math.acos(max(-1,min(1,w))))
    return tuple(c*20.0 for c in ang)  # ×20 per tick

# 比较两种 order，看哪个能复现 CSV wb
for order in ['now*prev^-1', 'prev*now^-1']:
    err = [0,0,0]; n = 0
    worst = None
    for i in range(1, len(rows)):
        qn = (g(rows[i],'qw'), g(rows[i],'qx'), g(rows[i],'qy'), g(rows[i],'qz'))
        qp = (g(rows[i-1],'qw'), g(rows[i-1],'qx'), g(rows[i-1],'qy'), g(rows[i-1],'qz'))
        if math.isnan(qn[0]) or math.isnan(qp[0]): continue
        ww = angvel_from_q(qn, qp, order)
        # 转机体系
        wb = qrot_inv(qn, ww)
        wb_csv = (g(rows[i],'wbX'), g(rows[i],'wbY'), g(rows[i],'wbZ'))
        if math.isnan(wb_csv[0]): continue
        for k in range(3):
            d = abs(wb[k]-wb_csv[k])
            err[k]+=d; n+=1
            if worst is None or d > worst[0]:
                worst = (d, i, order, wb, wb_csv)
    print("order=%s  rows=%d  |recompute-wb| mean: X %.2f Y %.2f Z %.2f   max %.2f (row %d)"
          % (order, n, err[0]/n, err[1]/n, err[2]/n, worst[0] if worst else 0, worst[1] if worst else -1))
    if worst:
        print("   worst  recomputed=%s  csv=%s" % (worst[3], worst[4]))
