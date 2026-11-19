# Example - Starship Flight 15 Catch

> A complete CCPE flight-control showcase: a Starship Flight 15 replica (ship S42) is released belly-down near the build limit, free-falls, flips upright on five [vector nozzles](../actuators/advanced-thrust-nozzle.md), brakes, flies back to the tower and is caught by its arms — flown end to end by two CC:Tweaked computers talking over rednet.

![starship](../img/starship.png)

## [> Download Save <](#)

<!-- TODO: replace "#" with the save download link -->

!!! tip "Note"
    Required mods:

    - CC Peripheral Extender (this mod)
    - CC:Tweaked
    - Create
    - Sable
    - Create: Aeronautics — includes Simulated, which provides the docking connectors
    - Create: Copycats+


## Flying it yourself

1. Load the save and let the world settle: the ship hangs from its top connectors, the tower arms are at rest.
2. On the **ship** computer (computer 0) run `flip dock`. Its startup has already powered the engine, straightened the nozzles and clamped both connectors; the program then cuts them, falls, flips, brakes, returns, requests the dock, receives the lock reply, fuels and shuts down.
3. The **tower** computer (computer 1) needs no command. Its `startup.lua` clears the arm and fuel connectors, waits 1 s and then runs `catch` by itself, so the arms reset and wait for a request.
4. **To disconnect the arms from the ship** — or to reset between runs — restart the tower computer: the first second of its startup drops both arm connectors and the fuel port.


## Hardware

### Ship (computer 0)

- **5 × vector nozzles** on encased fans — one on the centreline (`ch0`), four around it (`ch1`–`ch4`). This is the only actuator that produces thrust.
- **AIC** (aviation integrated computer) — one block that counts as both INS and FMC: attitude quaternion, angular velocity, mass and gravity. The local air pressure `p` comes from the same sensor system.
- **2 docking connectors on top** (`ch5`) as the main hold, **1 underneath** (`ch6`) for fuel; `ch6` is driven with `setStrongPower`.
- **Flight data recorder** — every flight writes a CSV to `/log/flip_*.csv`.

### Tower (computer 1)

- **2 catch arms** (`ch8` / `ch9`) that travel from ±90° to 0° at 2 rpm.
- **1 fuel port** (`ch7`) that is opened only after the lock is confirmed.
- The catch sequence stands by and waits for the ship's rednet request.

## The mission in one table

`h` is height above the **catch point** `(11.50, 93.05, −0.49)` — `h = 0` means the vehicle is sitting inside the closed arms.

| # | Phase | Program | Measured on the final flight |
|---|---|---|---|
| 1 | Release & free fall | `flip dock` | 3.95 s / 76.5 m, `vy` −36.4 m/s |
| 2 | Flip back upright | `flip` | 1.90 s, horizontal speed 26.6 m/s, `cos` 0.996 |
| 3 | Reverse-thrust brake | `flip` | 5.1 s, lateral overshoot 10.2 m, ends ~23.5 m above the catch point |
| 4 | Guided return | `flip` | 8.4 s, ends 0.9 m from the target, altitude strictly monotonic |
| 5 | Dock request | `flip` → `catch` | sent **early**, 20 m above the catch point; arms need 5.3 s, lead is 7.40 s |
| 6 | Catch & lock | `catch` | `getConnectedName() ≠ ""` → `LOCK ok  ch8=[S42] ch9=[S42]` |
| 7 | Fuel connection | both | tower `ch7 = 15`, then ship `ch6 = 15` + `setStrongPower(6, true)` |
| 8 | Secured | `flip` | after 1.5 s with horizontal and vertical speed under 0.30 m/s the nozzles shut down |

The whole run is autonomous: one command on each computer and no player input.

`S42` in the lock line is the ship's physics-body name — that is why it appears on both arm channels.


## Constants worth trusting

Every number below was measured on the actual build.

| Quantity | Value |
|---|---|
| Gravity | **11 m/s²** |
| Thrust | `F = N × 256 × 2.05 × p × scale` (2.05 pN/RPM measured, 2.0 nominal) |
| Dry mass | **61 kg** (219 kg when the chain is clamped) |
| TWR at full throttle | 3.44–3.55 at the platform (`p ≈ 0.887`), ≈2.26 in the cloud layer (`p ≈ 0.58`) |
| Hover throttle | **0.284** with 5 nozzles, **0.35** with 4 |
| Differential authority | **1.0 rad/s² per unit D**, 0.15 s step lag |
| Inertia / thrust arm | `I ≈ 15.9 kg·m²`, `r ≈ 1.0 m` |
| Control rate | **20 Hz**, exactly one `parallel.waitForAll` write block per cycle |
| Universal drag | `F = −m·0.09·v` — deceleration `0.09·v`, independent of mass |
| Terminal velocity | `G/d ≈ 122 m/s` (never reached inside these altitudes) |

Two consequences of the drag model matter for the profile: velocity decays with a time constant of ~11 s, so **lateral drift and residual spin do not bleed off by themselves** — they must be actively braked; and a fall of 150 m only reaches ~48 m/s, not terminal.

## Programs in the save

| Program | Computer | Role |
|---|---|---|
| `flip.lua` (**v44**) | 0 | flight: release → fall → flip → brake → return → dock request → fuel handshake → shutdown |
| `catch.lua` (**v1.6**) | 1 | tower: reset arms → wait for request → close arms → lock check → fuel → reply |
| `startup.lua` | 0 / 1 | boot: the ship powers the engine, straightens the nozzles and clamps both connectors; the tower drops the arm + fuel connectors for 1 s, then runs `catch` |
| `probe.lua`, `cal1.lua`, `att.lua`, `tilt.lua`, `guide.lua`, `thruststress.lua` | 0 | one-shot calibrations and diagnostics used while developing the control loops |

## Related pages

- [Advanced Thrust Nozzle](../actuators/advanced-thrust-nozzle.md) — the only actuator on the ship
- [Aviation Integrated Computer](../sensor-system/aic.md) — the ship's INS + FMC in one block
- [Short-Range Signal Linker](../sensor-system/short-range-linker.md) — physics-body channels
- [Flight Data Recorder](../sensor-system/flight-data-recorder.md) — the CSV behind every number here
- [Aeronautics Sensor Integration](../peripheral-extender/simulated-integration.md) — reading Simulated docking connectors
- [Aerodynamics: Real vs Game](aerodynamics.md) — why the drag model behaves the way it does
