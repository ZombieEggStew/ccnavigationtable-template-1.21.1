import csv, math, os

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_21.csv")
rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r, k):
    try: return float(r[k])
    except Exception: return float('nan')

def pct(vals, p):
    s = sorted(vals); i = (len(s)-1)*p/100.0
    return s[int(i)] if len(s) else float('nan')

t = [g(r,'time_s') for r in rows]
y = [g(r,'y') for r in rows]
pitch = [g(r,'pitchDeg') for r in rows]
air = [g(r,'airSpeed') for r in rows]
vy = [g(r,'vY') for r in rows]
wbz = [abs(g(r,'wbZ')) for r in rows]
press = [g(r,'pressure') for r in rows]

# cruise = 250..350 & time>24
idx = [i for i in range(len(rows)) if 24 < t[i] < 168 and 245 <= y[i] <= 355]
print("cruise rows:", len(idx))
for name, col in [("y",y),("pitch",pitch),("airSpeed",air),("vY",vy),("|wbZ|",wbz),("pressure",press)]:
    vals=[col[i] for i in idx if not math.isnan(col[i])]
    print(f"{name:8s} p10={pct(vals,10):8.3f} p50={pct(vals,50):8.3f} p90={pct(vals,90):8.3f} max={max(vals):8.3f}")

# log-decrement: successive altitude peak amplitudes (peak minus following trough)
peaks_t = [29.6,41.6,58.2,76.1,90.7,134.8,163.2]
troughs_t = [35.8,82.4,97.8,127.2,157.3]
def val_at(ts, col):
    # nearest sample
    best=min(range(len(t)), key=lambda i: abs(t[i]-ts))
    return col[best]
amps=[]
for pt, tt in zip(peaks_t, troughs_t):
    if tt>pt: amps.append(val_at(pt,y)-val_at(tt,y))
print("peak-trough amps:", [round(a,1) for a in amps])
if len(amps)>=3:
    dec=math.log(amps[0]/amps[-1])/(peaks_t[-1]-peaks_t[0])
    print(f"decay rate ~ {dec:.4f} /s (amp halves every {0.693/dec:.0f}s)")

# 2 s grid preview
print("\n t(s)   y     pitch   vY    air")
prev=None
for i in range(len(rows)):
    if 25 <= t[i] <= 170 and abs(t[i]-round(t[i]/2)*2)<0.03:
        if prev is None or abs(t[i]-prev)>1.9:
            print(f"{t[i]:5.1f} {y[i]:7.1f} {pitch[i]:7.1f} {vy[i]:7.1f} {air[i]:6.1f}")
            prev=t[i]
