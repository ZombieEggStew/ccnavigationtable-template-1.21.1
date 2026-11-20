# Example - Starship Flight 15 Catch

> A complete CCPE flight-control showcase: a Starship Flight 15 replica (ship S42) is released belly-down near the build limit, free-falls, flips upright on five [vector nozzles](../actuators/advanced-thrust-nozzle.md), brakes, flies back to the tower and is caught by its arms — flown end to end by two CC:Tweaked computers talking over rednet.

![starship](../img/starship.png)

## [Demo video](https://www.youtube.com/watch?v=EJPllPvQiYY)

## [> Download Save <](https://drive.google.com/file/d/1Gsv2WK7fHYYxessZFwcspR20blYbuhIq/view?usp=sharing)

!!! tip "Note"
    Required mods:

    - CCPE — Aircraft Systems (this mod)
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

## Design document
https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/memo/flight/starship15-catch.md

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
