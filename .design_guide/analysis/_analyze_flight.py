import csv, math, os, sys

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "flight_logs")

path = os.path.join(LOG_DIR, "flight_overworld_00b1000b_21.csv")

rows = []
with open(path, newline='', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        rows.append(row)

def g(r, k):
    try:
        return float(r[k])
    except Exception:
        return float('nan')

def stats(name, vals):
    vs = [v for v in vals if not math.isnan(v)]
    if not vs:
        print(f"{name}: (no data)")
        return
    mn, mx = min(vs), max(vs)
    mean = sum(vs) / len(vs)
    print(f"{name}: n={len(vs)} min={mn:.3f} max={mx:.3f} mean={mean:.3f}")

t = [g(r, 'time_s') for r in rows]
y = [g(r, 'y') for r in rows]
pitch = [g(r, 'pitchDeg') for r in rows]
roll = [g(r, 'rollDeg') for r in rows]
v = [g(r, 'vNorm') for r in rows]
vs = [g(r, 'vY') for r in rows]
air = [g(r, 'airSpeed') for r in rows]
press = [g(r, 'pressure') for r in rows]
alt = [g(r, 'altitude') for r in rows]
wz = [g(r, 'wbZ') for r in rows]  # body-frame Z ang vel (pitch rate axis?)

print(f"rows={len(rows)} duration={t[-1]-t[0]:.1f}s (t0={t[0]}, t1={t[-1]})")
stats("y (height)", y)
stats("pitchDeg (nosedown+)", pitch)
stats("rollDeg", roll)
stats("vNorm", v)
stats("vY (vertical)", vs)
stats("airSpeed", air)
stats("pressure", press)

# --- takeoff & flight phase split by height ---
# find contiguous cruise segments around 300
cruise = [(i, ti, yi) for i, (ti, yi) in enumerate(zip(t, y)) if 250 <= yi <= 350]
print(f"\ncruise(250..350): {len(cruise)} rows, time {cruise[0][1]:.1f}..{cruise[-1][1]:.1f}s" if cruise else "no cruise rows")

# --- peak/trough detection on cruise altitude ---
if cruise:
    seg_t = [c[1] for c in cruise]
    seg_y = [c[2] for c in cruise]
    # simple local extrema with prominence filter
    peaks, troughs = [], []
    for k in range(1, len(seg_y) - 1):
        if seg_y[k] > seg_y[k-1] and seg_y[k] >= seg_y[k+1]:
            peaks.append((seg_t[k], seg_y[k]))
        if seg_y[k] < seg_y[k-1] and seg_y[k] <= seg_y[k+1]:
            troughs.append((seg_t[k], seg_y[k]))
    # filter tiny wiggles: keep extrema alternating with amplitude > 1.5 m
    def clean(ext):
        out = []
        for e in ext:
            if out and abs(e[1] - out[-1][1]) < 1.5:
                continue
            out.append(e)
        return out
    peaks = clean(peaks)
    troughs = clean(troughs)
    print(f"cruise peaks({len(peaks)}): {[(round(a,1), round(b,1)) for a,b in peaks[:8]]}")
    print(f"cruise troughs({len(troughs)}): {[(round(a,1), round(b,1)) for a,b in troughs[:8]]}")
    if len(peaks) >= 2:
        per = [(peaks[i+1][0]-peaks[i][0]) for i in range(len(peaks)-1)]
        print(f"peak period: mean={sum(per)/len(per):.1f}s list={[round(p,1) for p in per]}")
        amps = [abs(p[1]-tr[1]) for p, tr in zip(peaks, troughs[:len(peaks)])]
        if amps:
            print(f"peak-to-trough amplitude: mean={sum(amps)/len(amps):.1f}m first3={[round(a,1) for a in amps[:3]]} last3={[round(a,1) for a in amps[-3:]]}")
    # pitch vs climb rate correlation in cruise
    n = len(cruise)
    dy = [(seg_y[k] - seg_y[k-1]) / 0.05 for k in range(1, n)]
    pc = [seg_t[k] for k in range(1, n)]
    def corr(a, b):
        ma, mb = sum(a)/len(a), sum(b)/len(b)
        num = sum((x-ma)*(y-mb) for x, y in zip(a, b))
        da = math.sqrt(sum((x-ma)**2 for x in a))
        db = math.sqrt(sum((x-mb)**2 for x in b))
        return num/(da*db) if da and db else float('nan')
    # map cruise row index back to global for pitch
    idx = [c[0] for c in cruise]
    cruise_pitch = [pitch[i] for i in idx[1:]]
    cruise_roll = [abs(roll[i]) for i in idx[1:]]
    print(f"corr(pitch, climbRate) in cruise: {corr(cruise_pitch, dy):.3f}")
    stats("cruise |roll|", cruise_roll)
    stats("cruise pitch", cruise_pitch)

# --- low-altitude behaviour check (below 150 after takeoff) ---
low = [(ti, yi, pi) for ti, yi, pi in zip(t, y, pitch) if 70 < yi < 150]
if low:
    print(f"\nlow-alt(70..150): {len(low)} rows, y {min(l[1] for l in low):.1f}..{max(l[1] for l in low):.1f}, pitch {min(l[2] for l in low):.2f}..{max(l[2] for l in low):.2f}")
else:
    print("\nno low-altitude cruise rows (below 150)")

# --- takeoff climb stats ---
climb = [(ti, yi, pi) for ti, yi, pi in zip(t, y, pitch) if 100 < yi < 300]
if climb:
    print(f"climb(100..300): pitch min/max = {min(c[2] for c in climb):.2f}/{max(c[2] for c in climb):.2f}, climbRate y mean = ...")
