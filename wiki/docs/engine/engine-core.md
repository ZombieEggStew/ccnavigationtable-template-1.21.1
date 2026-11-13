# Engine Core

![engine_core](../img/engine_core.png)

The **Engine Core** (`ccpe:engine_core`) is the skeleton of the engine system — a Create kinetic power-source block that assembles into a multi-block row. Cores placed in a line merge into **one engine** (1×1×N, up to 21 blocks) that shares a single running state, temperature and throttle; only the **controller** (one end of the row) outputs stress and makes decisions.

## Assembly & placement

- Place the first core, then **right-click an existing engine with another engine core** to extend it along its axis (Create `PoleHelper` placement). The cores auto-merge into one multi-block engine.
- Engines are persisted: after a chunk reload, the cores re-assemble via their saved positions (`Uninitialized` / `LastKnownPos` NBT, also works with Create schematics).
- The engine state (temperature, throttle, mixture, economy progress) is **inherited when you extend the engine** — extending at the controller end keeps the running state instead of resetting it.
- **Controller**: the block at one end of the row. Only the controller ticks, generates stress and runs the Lua peripheral. A non-controller core reports 0 generated speed, but every core is a valid place to read/write the engine from Lua (the peripheral delegates to the controller).

## Power output: fixed capacity, throttle sets speed, load sets fuel burn

- **Stress capacity is constant** = fluid chambers × 8192 SU × fuel `stress` + steam chambers × 4096 SU — **independent of throttle**.
- **Speed = throttle × 256 rpm** (0~256, linear). Default throttle: **0.25 (64 rpm)**. **The throttle only sets speed**.
- **Load** = stress used / capacity available (0~1): the more machines behind the engine and the higher the speed, the higher the load. **Fuel burn and heat follow the load**; with nothing attached the engine burns at an **idle floor (20%)** to stay warm.
- **No cheating**: capacity is bound to the engine's **own generated speed**, not the network speed, so an external speed controller / electronic transmission can only raise the downstream stress demand (→ overload) — it **cannot enlarge the capacity**.
- **Overload does not stop the engine** — like Create generators, the excess only shows a red "Network overloaded" line at the top of the goggles while the engine keeps burning at full load.

## Operating conditions

The engine outputs stress only while it **runs**:

- **≥ 1 running chamber** (has fuel to burn),
- **throttle > 0** (throttle 0 = stopped, no fuel burned),
- **not overheated** (fluid engines only; T ≥ 220°C hard-stops, resumes at ≤ 200°C hysteresis),
- **steam engines**: T ≥ 100°C and water available (warm-up below 100°C burns without generating),
- **overload does not stop the engine** — like Create generators, it keeps running and burning fuel, and the goggles show a red "Network overloaded" warning.
- All of the above judge the engine itself; **load never gates whether the engine runs** — it only decides how fast and how hot it burns.
- A steam engine additionally requires **fuel actually burning**: holding 155°C costs fuel, so when the fuel runs out the boiler starts to cool (see [Steam Power Chamber](steam-power-chamber.md)).

## Goggle display

Hover any engine core with Create goggles (the tooltip is delegated to the controller):

```
Engine Status
Temperature: XX.X°C
Stress Output: 32768 SU
Throttle: 25.0%
Current Speed: 64 RPM
Load: 0%
Modules:
- Fluid Combustion Chamber x4
- Cooling Air Duct x2
```

- **Stress Output** = the total stress the engine can provide (server-synced, **constant and independent of throttle**).
- **Throttle** = the current `setThrottle` value; **Current Speed** = throttle × 256.
- **Load** = the actual network utilization (0% when idle, 100% when saturated, red at ≥90%) — this is what drives fuel burn and temperature.
- The module list shows which chambers / ducts are attached.

!!! note "Throttle and Load are only shown on the engine core"
    The module goggles (fluid combustion chamber / steam power chamber / cooling air duct) no longer repeat throttle and load — they only show their own status, temperature and fuel.

## Lua control

The engine is controlled exclusively from Lua (no redstone). Wrap **any** core segment as a CC:T peripheral — the peripheral lives on the controller and non-controller segments delegate to it:

```lua
local e = peripheral.wrap("front")
print(e.getTemperature())  -- temperature in °C
e.setThrottle(0.5)         -- 50% throttle = 128 rpm (stress capacity unchanged)
print(e.getLoad())         -- load 0..1: stress used / capacity available
```

See the [Lua API page](lua-api.md) for the full method reference.
