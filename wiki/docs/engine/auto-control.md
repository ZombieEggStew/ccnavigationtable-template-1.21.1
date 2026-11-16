# Temperature & Mixture Auto Control

The engine core ships with a **built-in automatic controller**: install the mod and it just works — **no Lua script required**. Whenever the engine is running, the controller keeps the **temperature in the economy band (target 155°C)** and the **effective mixture pinned at 0.9**, opening the cowl shutters, engaging water cooling, and finally enriching the mixture when the available cooling is not enough.

> Fluid engine only. The **steam engine** never participates — its closed boiler self-regulates at 155°C, it has no mixture/economy axis, and water cooling does nothing for it (see [Steam Power Chamber](steam-power-chamber.md)).

## What you own, what the engine owns

Auto control **never touches the throttle and never touches the load** — those are the player's two degrees of freedom (see [Engine Core](engine-core.md)):

| You handle | The controller handles |
|---|---|
| Chambers / cooling ducts / (optional) water cooling, plus fuel | Mixture lever (compensating altitude auto-rich every tick) |
| **Throttle** (= output speed) | Cooling duct **shutter** (PI+D temperature loop) |
| **How many machines** (= stress load) | Water cooling module **loop opening** (model feed-forward + proportional trim) |
| Dropping load / adding cooling when heat wins | **Enrichment fallback** and the hard floor (1.4 at 200°C) |

The controller's actuators all live on the **cooling** side. If the load is too high, the altitude too high or the radiators too few, it can open the shutters and the water loop to 100% and still lose — the only fix then is **lowering the load** (fewer machines / lower throttle) or adding cooling.

!!! note "Where it runs in the tick"
    Auto control runs every tick on the controller — after the steam/fluid arbitration and **before** the water-cooling settlement and the mixture read — so the lever, shutter and water opening it writes take effect **on the same tick** (no 1-tick lag). Its input is the previous tick's temperature (the temperature update happens later in the tick), which makes no practical difference to the loop.

## When to write your own Lua

Auto control aims for "**good enough**": it only looks at temperature, which modules exist and the altitude auto-rich factor — it **knows nothing about your load, altitude or speed strategy**. Switch it off and do it yourself when you want:

- maximum fuel efficiency (pre-trimming the mixture to the edge of the economy window by altitude, staging the shutter by flight phase);
- a target temperature other than 155°C (e.g. deliberately cooler to leave acceleration margin);
- integration with an autopilot (different temperature targets for climb, cruise and descent).

Do it by calling `setAutoControl(false)` first, then `setMixture` / `setCooling` / `setWaterCooling` as needed. See [trainer aircraft v2](../examples/trainer_aircraft_2.md) for a full example (a Lua control program managing engine temperature and mixture, with markedly lower fuel burn).

## The four actuators (in order of engagement)

| # | Actuator | Engages when | Target / output |
|---|---|---|---|
| 1 | **Mixture lever** | Always (while auto control is on) | Effective mixture `m_eff = lever × autoRichness` pinned at **0.9** |
| 2 | **Cowl shutter** (cooling duct) | `T ≥ 145°C` | PI+D pulls the temperature toward **155°C**; `T < 145` → 0 (hold heat in) |
| 3 | **Water cooling opening** | Ducts fully open and still not enough (feed-forward > 0), or no ducts at all | Supplies the missing `k` needed to reach 155°C |
| 4 | **Enrichment fallback** (gradual) | Ducts + water fully open (or absent) and still `T ≥ 165°C` | `m_eff` climbs from 1.05 to 1.4 (capped at ≈175°C) |
| — | **Hard floor** | `T ≥ 200°C` | Force `m_eff = 1.4` — avoid the 220°C hard stop and coolant boiling |

Heat-holding wins: below **145°C** both the shutter and the water loop stand down (only the mixture lever keeps working) — the engine warms itself up first, then worries about the economy band.

## Mixture: pin the effective mixture at 0.9

```
lever        = clamp( 0.9 / autoRichness , 0.6 , 1.4 )
autoRichness = clamp( 1 + 0.45 × (1 − pressure) , 0.75 , 1.25 )   ← thinner air at altitude → naturally richer
```

- **Why divide by `autoRichness`**: the effective mixture is `lever × autoRichness`. Altitude auto-rich (up to ×1.25) would push it out of the economy window, so the controller lowers the lever every tick — **altitude compensation, handled for you**.
- **Why 0.9 and not 1.0**: the economy mixture window is `0.8 ~ 1.1`, so 0.9 leaves margin on both sides; and `m_eff = 0.9` gives a heat factor of **×1.1** (slightly hot), which helps small, low-altitude engines climb into the 145–165°C economy band.
- **Where the fuel saving comes from**: once both temperature and mixture are in range, the economy factor ramps in at ×0.75 — that 25% is what auto control actually saves.

| Situation | Pressure | autoRichness | Auto lever | Effective m_eff |
|---|---|---|---|---|
| Sea level and below | ≈1.0 | 1.00 | 0.90 | **0.90** |
| Y≈260 | ≈0.45 | ≈1.25 | ≈**0.72** | **0.90** |
| World top / very high | →0 | 1.25 (capped) | ≈0.72 | **0.90** |

The lever is clamped to `0.6 ~ 1.4` (in extremely thin air it cannot fully compensate, so the mixture ends up slightly rich — the only cost is missing the fuel discount; the engine is not damaged).

## Shutter: a PI+D temperature loop

```
err     = T − 155
integral += err × 0.05 s        (clamped to ±100 °C·s; not accumulated while the output is saturated in the same direction → anti-windup)
shutter  = clamp( 0.3 + 0.05×err + 0.005×integral + 0.02×dT/dt , 0 , 1 )
```

| Constant | Value | Meaning |
|---|---|---|
| `AUTO_COOL_BASE` | **0.3** | Base opening at zero error (≈30% shutter at a 155°C steady state) |
| `AUTO_COOL_KP` | **0.05** | Proportional: +5% opening per 1°C above target |
| `AUTO_COOL_KI` | **0.005** | Integral: removes the steady-state offset (player-flight-validated gains) |
| `AUTO_COOL_KD` | **0.02** | Derivative: damps overshoot when temperature rises fast |
| `AUTO_COOL_INT_MAX` | **100 °C·s** | Integral clamp |
| `AUTO_WARM_OFF_TEMP` | **145°C** | Lower edge of the economy band (= 155 − 10): below this the shutter goes to 0 and the integral resets |

- **Heat-holding branch**: below **145°C** the shutter is forced to **0** (all duct cooling off, keep the heat). If the engine still cannot reach the band, the controller stops helping — that is a load/altitude problem, not something cooling can fix.
- **No cooling duct installed** → the controller **does not write** the shutter field (it would have no `K_DUCT` term to act on and would overwrite a player's manual value).

## Water cooling: model feed-forward + proportional trim

Water cooling is the **second stage, after the ducts saturate**. The engine already knows its own heat `Q` and every cooling term, so it does not need to hunt — it solves directly for "how much cooling capacity is still missing to hit 155°C":

```
kNeed   = Q / (155 − T_amb)                                     // total k required to hit the target
kAirMax = (K_CORE×length + K_AMB×running chambers) × f(pressure)
        + K_DUCT×ducts × ram(speed) × f(pressure)                // k with the ducts fully open
waterFF = (kNeed − kAirMax) / waterCoolingCapacity               // 0 when the ducts already suffice
target opening = clamp( waterFF + 0.1×(T − 155) , 0 , 1 )
opening moves at most 0.05 per tick (ramped, no step changes)
```

- **Why feed-forward is mandatory**: a pure proportional loop outputs exactly 0 at `T = 155` — and water cooling is often the *only* or *dominant* actuator, so the steady state inevitably drifts. Measured: "2 chambers at full load + 1 water module" settled at **164°C / 90% opening** with pure proportional control; feed-forward puts the operating point on target (same config solves to ≈0.99 opening → ≈155°C) and the proportional term only trims.
- **Gating (saves coolant)**: the loop only engages when `T ≥ 145` **and** (feed-forward > 0 **or** no ducts **or** shutter ≥ 0.98). **While the ducts suffice, water cooling stays at 0** — a 0 opening burns no coolant and also avoids the 512 SU/module stress draw (see [Water Cooling Module](water-cooling-module.md)).
- **Self-consistent**: engaging the water loop adds stress → load up → both more heat and more cooling; the feed-forward is recomputed every tick and settles on 155°C within a couple of ticks.
- **No water module installed** → the controller **does not write** the loop opening.
- Water cooling only takes a small share of ram pressure and pressure scaling, so it stays the workhorse when parked or at altitude (see [Temperature & Cooling Model](temperature.md)).

## Enrichment fallback (gradual)

When the shutters and the water loop are wide open (or simply absent) and the temperature is still climbing past 165°C, the controller **stops chasing fuel economy** and starts paying fuel for cooling:

```
enter: cooling fully open (or absent) and T ≥ 165°C
target effective mixture = clamp( 1.05 + 0.035×(T − 165) , 1.05 , 1.4 )   // 1.4 at ≈175°C
exit:  T ≤ 150°C, or cooling no longer fully open
hard floor: T ≥ 200°C → force 1.4 (bypassing the 165°C entry)
```

| Temperature | Target m_eff | Heat factor `2 − m` | Note |
|---|---|---|---|
| 165°C | 1.05 | ×0.95 | Still inside the economy mixture window (≤1.1) — **the fuel discount is not lost yet** |
| 170°C | ≈1.23 | ×0.77 | Heat cut by a quarter |
| ≥175°C | 1.40 | **×0.60** | Heat cut by 40% (capped) |
| ≥200°C | 1.40 | ×0.60 | Hard floor, purely to avoid the 220°C hard stop |

Enrichment is a **last resort**: beyond `m_eff > 1.1` the mixture leaves the economy window and the 25% fuel discount is gone (fuel burn rises with the lever). Its purpose is "rather burn extra fuel than overheat and shut down mid-cruise".

## Typical situations

| Situation | What auto control does |
|---|---|
| Small engine, parked (1 fluid chamber + 2 core sections, 25% load, no radiators) | Natural cooling already reaches the band; shutter stays low, water cooling stays at 0 — **nothing to manage** |
| Normal cruise (2 ducts, mid altitude, mid load) | Shutter trims in the mid-to-high range; the lever is automatically pushed down to compensate altitude (values in the mixture table above) |
| High altitude / full load (Y≈260) | Shutter approaches fully open → water cooling fills the gap → enrichment only if that still is not enough; temperature rides 155–175°C |
| The End (ambient 0°C) | Larger target gap, harder cooling — shutter and water loop run much higher |
| **The Nether (ambient fixed 155°C)** | **The economy band is essentially unreachable**: ambient temperature equals the target, so the cooling ΔT collapses. The controller can only run full cooling + enrichment to hold the line; a high load will overheat |
| Just lit / restarted after a stop | Below 145°C → shutter 0, water 0, keep the heat for warm-up; once past 145 it starts regulating |

!!! warning "Auto control is not unlimited cooling"
    It can only **use the radiators you built and buy cold with fuel**. Too few ducts + high altitude + heavy load together will still drive the temperature into the 220°C hard stop. Drop the load (fewer machines / lower throttle) or add cooling (ducts / water module) — do not wait for the controller to save it.

## Switching it off & taking over

| Action | Effect |
|---|---|
| Default | **On** (`AUTO_ENABLED_DEFAULT = true`; persisted as NBT `AutoControl`, and old saves without the tag default to on) |
| Lua calls **any** of `setMixture` / `setCooling` / `setWaterCooling` | **Auto control switches off**, all three fields are handed back to the player (`getAutoControl()` returns `false`, goggles show "Auto Control: Off") |
| `setAutoControl(false)` | Switch it off explicitly (the engine then writes none of the control fields) |
| `setAutoControl(true)` | Switch it back on: integral / derivative / enrichment state reset, takes over from the automatic targets on the next tick |
| Extending the engine / new controller | The `autoControl` flag is inherited along with the rest of the running state (controller internals reset) |
| Reloading the world | `autoControl` is persisted; integral, last temperature and enrichment state are transient and reset |

- Manual takeover is **one-shot**: after a single `set*` call, auto control stays off until `setAutoControl(true)` (or a controller hand-over inherits it). **If you are writing your own control loop, call `setAutoControl(false)` first** — otherwise your very first write is what switches it off.
- On a steam engine auto control does nothing at all (it writes no fields).

## Goggle display

Wearing Create goggles over any core section:

```
    Temperature: 156.3°C
    Total stress capacity: 8192 SU
    Throttle: 100.0%
    Current speed: 256 RPM
    Load: 43%
    Water cooling: +0.02
    Coolant stress: -512 SU
    Auto Control: On
```

- **Auto Control: On** (green) / **Off** (grey) — tells you at a glance whether the engine is regulating itself or has been taken over.
- The controller only writes fields and **does not send a packet every tick**; it rides the existing "discrete field diff + 1 Hz heartbeat" sync (the same channel as the rest of the goggle data).



## Constants at a glance

| Constant | Value | Meaning |
|---|---|---|
| `AUTO_ENABLED_DEFAULT` | `true` | Auto control on by default |
| `AUTO_MIX_EFF` | **0.9** | Target effective mixture |
| `AUTO_COOL_BASE` / `KP` / `KI` / `KD` | **0.3 / 0.05 / 0.005 / 0.02** | Shutter PI+D gains |
| `AUTO_COOL_INT_MAX` | **100 °C·s** | Integral clamp |
| `AUTO_WARM_OFF_TEMP` | **145°C** | Heat-holding threshold (shutter/water stand down; = 155 − 10) |
| `AUTO_WATER_KP` | **0.1** | Proportional trim gain on the water feed-forward |
| `AUTO_WATER_RAMP` | **0.05** | Max water opening change per tick |
| `AUTO_WATER_GATE` | **0.98** | "Cooling fully open" threshold (≥98%) |
| `AUTO_ENRICH_ON` / `OFF` | **165 / 150°C** | Enrichment entry / exit (15°C hysteresis) |
| `AUTO_ENRICH_START` / `MAX` / `RATE` | **1.05 / 1.4 / 0.035** | Enrichment start / cap / per-°C rate |
| `AUTO_SAFETY_TEMP` | **200°C** | Forced-enrichment floor (= overheat resume line) |

Related pages: [Engine Core](engine-core.md) · [Fluid Combustion Chamber](fluid-combustion-chamber.md) · [Cooling Air Duct](cooling-air-duct.md) · [Water Cooling Module](water-cooling-module.md) · [Temperature & Cooling Model](temperature.md) · [Engine Lua API](lua-api.md)
