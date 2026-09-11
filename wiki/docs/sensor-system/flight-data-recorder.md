# Flight Data Recorder

> Server-side debug tool: logs per-tick flight data for every FMC/AIC physics body to CSV, for flight-characteristic analysis and debugging.

The **Flight Data Recorder** (`FlightDataRecorder.java`) is a **read-only** debug tool built into CCPE. Every server tick (at a configurable interval) it samples one row of data for **every physics body registered with an FMC (including AIC)** and writes it to a CSV file under `<gameDir>/flight_logs/`. It only reads — it never modifies physics or control behavior — making it an instrumentation tool for analyzing handling, phugoid stability, trim and control inputs.

!!! note "Not a block"
    The recorder is not a placeable block and does not expose a Lua API. It is toggled through the mod config and runs in the background on the server; data lands in CSVs in the game directory, analyzed with Python / spreadsheet software.

## Enabling (config file)

| Option | Default | Description |
|---|---|---|
| `flightDataRecorderEnabled` | `false` | Master switch. **Off by default**; enable in `config/ccpe-common.toml` when you need to record. **Requires a restart** |
| `flightDataRecorderIntervalTicks` | `1` | Sampling interval in ticks. `1` = one row per tick = 20 Hz |

- Files are produced only while **at least one FMC/AIC/INS sensor is on a Sable physics body**.
- The recorder also covers bodies with only an INS (ATTITUDE) sensor: the kinematics/velocity diagnostic columns are always available; without an FMC the physics-data columns read `nan`.

## Output files

Path: `<gameDir>/flight_logs/flight_<dimension>_<first-8-of-body-UUID>_<yyyyMMdd-HHmmss>.csv`

- **One file per body** carrying any FMC/AIC/INS sensor (keyed by body UUID).
- The timestamp suffix is the **file creation time** (not the starting tick) → files are never overwritten after a restart (the old start-tick naming overwrote previous files for the same body & tick after a restart).
- The header is written only **when the file is created** → after changing the recorder code you must restart the game and re-fly, otherwise new columns won't match the header.
- Files are closed normally when the body is disassembled/unloaded, on server stop, or when the recorder is disabled.

## CSV column reference (order = header order)

### 1. Kinematics / environment

| Column | Meaning |
|---|---|
| `tick` / `time_s` | Game tick and seconds (tick/20) |
| `body` | Body UUID |
| `x` `y` `z` | Body-origin world position |
| `qx` `qy` `qz` `qw` | Attitude quaternion (world frame) |
| `pitchDeg` `rollDeg` `yawDeg` | Euler angles in degrees, same convention as `ss.getAngles()`. ⚠ **nose-down = positive pitch** (confirmed by in-game measurement; the on-screen display flips it, which is a deliberate change in `startup.lua`) |
| `vX` `vY` `vZ` `vNorm` | World-frame linear velocity + magnitude (Sable per-tick world pose difference; exactly 0 when stationary) |
| `wX` `wY` `wZ` | World-frame angular velocity |
| `wbX` `wbY` `wbZ` | Body-frame angular velocity (inverse-rotated by the same-tick attitude quaternion) — `wbX` = pitch rate |
| `pressure` / `altitude` | Average pressure / altitude of all static ports |
| `airSpeed` / `groundSpeed` | Ground speed / airspeed along the mouth axis of the most recently placed pitot tube (pitot-static gate; `nan` if the gate fails) |

### 2. Mass / center of mass

| Column | Meaning |
|---|---|
| `massKg` / `chainMassKg` | Host-body mass / whole-constraint-chain mass |
| `comX` `comY` `comZ` | Host center of mass (world frame) |
| `comRelX` `comRelY` `comRelZ` | Host COM relative to the body origin |
| `chainComRelX` `chainComRelY` `chainComRelZ` | **Chain center of mass** relative to the body origin (includes tail control-surface sub-bodies; can sit ~1 m away from the host COM) |

### 3. Forces (host body) — LIFT / DRAG / PROPULSION, 6 columns each

| Column group | Meaning |
|---|---|
| `liftFx` `liftFy` `liftFz` | Lift-group resultant force (**body-local frame**), recomputed from the point forces recorded in Sable's `QueuedForceGroup`: F = Σf |
| `liftMx` `liftMy` `liftMz` | Resultant moment about the host plot COM (local frame): M = Σ(point − comPlot) × f |
| `dragFx` … `dragMz` | Drag group, same |
| `propFx` … `propMz` | Propulsion group (propellers), same |

### 4. Forces (whole constraint chain) — chainLift / chainDrag / chainProp, 6 columns each

Same shape as group 3, but **aggregated over the whole constraint chain** (including aero_bearing slave sub-levels with tailplane/aileron surfaces): each member's point forces are transformed to world frame and summed, moments are taken about the **chain center of mass** (world coordinates), then the whole result is rotated back to the host's local frame.

- If the wings and propellers are all on the host body: `chainLift == lift` and `chainProp == prop`;
- If control surfaces sit on slave sub-bodies: `chainDrag ≠ drag` — **only the chain-level columns capture the full control-surface contribution** (the part the diagram can't show).

### 5. Universal drag

| Column | Meaning |
|---|---|
| `univFx` `univFy` `univFz` | **Universal drag** (Rapier linear velocity damping, `universal_drag` default 0.09, overridable via the dimension datapack): per-substep impulse summed over the chain as m·d·v·Δt/(1+d·Δt), transformed to the host's local frame |

This force is not in any force group or the diagram, but is always present in the physics — at level cruise **prop + drag + lift + univ ≈ 0** (an early "mysterious net-force gap" turned out to be exactly this).

### 6. Control inputs (auto-discovered)

| Column group | Meaning |
|---|---|
| `pedCh` `pedL` `pedR` | Foot pedals: left/right pedal axis (-1..1) |
| `joy1Ch` `joy1X` `joy1Y` `joy1XA` `joy1YA` | Joystick 1: X/Y axis values + active flags |
| `joyCh` `joyX` `joyY` `joyXA` `joyYA` | Joystick 2: X/Y axis values + active flags |
| `thrCh` `thrAxis` `thrGear` `thrFwd` `thrBack` | Throttle 1: axis / gear + forward/back active flags |
| `thr2Ch` `thr2Axis` `thr2Center` `thr2Up` `thr2Down` | Throttle 2 (collective): axis (0..1) / center axis (-1..1) + up/down active flags |

The recorder **automatically scans** the control desks in the short-range-linker channel space of the body chain and records all of the above controls they have installed (first desk per type; if several desks install the same type, only the first is recorded and a one-time warning is logged). The `*Ch` columns carry the desk's **real channel** (read from `getChannel()`, no longer hardcoded); if the chain has no desk or the control isn't installed, the whole group reads 0.

## How forces are recorded (read before interpreting)

- **Force tracking**: the recorder calls `ServerSubLevel.enableIndividualQueuedForcesTracking(true)` on the host and the whole constraint chain (same mechanism as the Simulated diagram; idempotent, re-enabled every tick, restored to `false` on the current chain when the file closes). Without this, the LIFT/DRAG point forces are not recorded by Sable.
- **Force-group matching**: done at runtime by registry id (`sable:force_groups` paths `lift` / `drag` / `propulsion`); the `ForceGroups` class is not referenced directly.
- **Sampling moment** = end of the game tick (`ServerTickEvent.Post`), reading the force group recorded by the **last physics substep** of that tick (groups are reset at the start of each substep) — sufficient for phugoid-level analysis.
- **Units**: Sable's per-physics-substep **impulse scale**, not absolute Newtons → use for trends / moment balance, not as absolute force values.
- If a force group doesn't exist (no corresponding force source) → all 6 columns of that group are `nan`; if the chain has no desk or a control isn't installed → that control group reads all 0; a whole-row sampling failure → a row of all-`nan` placeholders keeps column alignment (safe for Python parsing).

## Interpretation conventions

- **Balance equation**: at steady cruise `chainProp + chainDrag + chainLift + univ ≈ 0`.
- **Moment reference point**: chain-level moments are taken about the **chain COM** — the host COM excludes the tail sub-bodies (~0.95 m offset), so taking moments about it produces a spurious constant pitch moment ∝ lift (the early "net Mx ≈ +8 residual nose-up moment" was exactly this reference-point artifact; since fixed).
- **Signs**: `pitchDeg` **nose-down = positive** (matches in-game `ss.getAngles()` measurements); the on-screen display flips it (nose-up shown positive) — a deliberate `startup.lua` display-layer change, not the API sign.
- **Universal drag does not scale with pressure P**: sail drag and thrust are ∝ P, universal drag only ∝ m·v → at altitude thrust/sail drag decay while universal drag doesn't, one reason the ceiling-speed limit drops with height.

## Analysis tooling

A set of Python analysis scripts lives in [`/.design_guide/analysis/`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/tree/1.1.3/.design_guide/analysis) (pure-stdlib `csv` module). The CSV data is still written by the recorder to `run/flight_logs/`; the scripts resolve that directory automatically through a `LOG_DIR` constant relative to the script location and by default pick the newest `flight_*.csv`, so **they can be run from any working directory**:

- [`_analyze_flight.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight.py) — basic stats (height/pitch/airspeed/pressure) + cruise-segment split + phugoid peak/trough detection
- [`_analyze_flight3.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight3.py) — control-usage ratios + force-column availability + hands-off moment/correlation analysis
- [`_analyze_flight6.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_flight6.py) — clean hands-off full-throttle segment extraction, log-decrement damping ratio ζ, moment-vs-pressure regression (thrust-line offset estimate)
- [`_analyze_loop_delay.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_analyze_loop_delay.py) — joystick step-pulse → command/aero/loop delay decomposition
- [`_fit_aero_model.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_fit_aero_model.py) — aero-model scaling calibration (lift/drag/thrust vs P and v)
- [`_verify_pressure.py`](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/analysis/_verify_pressure.py) — pressure-formula (exponential approx vs Sable curve) verification against recorded data


## Related files at a glance

| Purpose | Path |
|---|---|
| Recorder main class | `src/main/java/com/zzy205/myfirstmod/compat/cc/FlightDataRecorder.java` |
| Config options | `src/main/java/com/zzy205/myfirstmod/Config.java` |
| Physics-body sensor registry (FMC/INS gating) | `src/main/java/com/zzy205/myfirstmod/compat/cc/BodySensorRegistry.java` |
| Physics-reading helpers | `src/main/java/com/zzy205/myfirstmod/compat/sable/SableCompat.java` |
| Aerodynamics / universal-drag design guide | `.design_guide/aircraft.md` |
