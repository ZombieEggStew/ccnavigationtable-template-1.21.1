# Water Cooling Module

![water_cooling_module](../img/water_cooling_module.png)

The **Water Cooling Module** (`ccpe:water_cooling_module`) is the [Engine System](overview.md)'s **liquid cooling module**: it carries an 8000 mB coolant tank and adds a fixed dissipation component (`K_WATER`) to the engine's Newton cooling.

The key difference from the [Cooling Air Duct](cooling-air-duct.md): water cooling is **not affected by ram air (airspeed) or ambient pressure (altitude)** — a liquid loop does not care about the air. So it is the answer for the three situations where ducts are weakest: **static, low speed and high altitude**. The price is **coolant consumption**, which accelerates while the engine runs hot, and it is also heavy.


## Coolant

| Item | Rule |
|---|---|
| Capacity | **8000 mB, single tank** (one coolant type at a time; drain it before switching) |
| Filling | Fluid pipes/pumps (the block exposes the `FluidHandler` capability), or **right-click with a fluid container** (right-click = item → tank first; sneak-right-click = tank → item first) |
| Coolants only | Only fluids listed in the datapack `coolant/*.json` table are accepted. Anything else (lava, crude oil, …) **cannot be inserted**, and the held container is not consumed |
| Removal | The coolant inside is **lost** when the module is broken (no bucket refund) |

!!! note "Why only coolants"
    The coolant table doubles as a whitelist: whatever goes in is guaranteed to have a cooling coefficient, and the engine's source scan skips water cooling modules so their contents are never mistaken for boiler water or fuel.

## Cooling contribution

```
k_water = K_WATER × Σ_modules [ min(amount / 4000, 1) × coolant efficiency ] × loop opening
K_WATER = 0.33 (per module at full effect)
```

- **Added outside the brackets**: `K_total = (K_CORE×length + K_AMB×running chambers + K_DUCT×ducts×shutter) × ram(speed) × f(pressure) + k_water`
  — hence water cooling is **completely independent of airspeed and pressure/altitude** (full model on the [Temperature & Cooling](temperature.md) page).
- **Amount curve**: full effect at **≥ 4000 mB**; **linear falloff** below 4000 mB; 0 at 0 mB (smooth, no flicker at the threshold).
- **Coolant efficiency**: datapack field `efficiency` (water = 1.0, `aeronautics:levitite_blend` = 1.5), multiplied directly into the dissipation.
- **Loop opening**: Lua `setWaterCooling(0..1)` (default 1.0 = fully open); 0 = loop closed (neither cools nor evaporates).
- Stacking: `k_water` is the sum over all working modules.

### Boiling also removes heat (latent heat of evaporation)

Once the coolant boils above **200°C**, what escapes really is **heat** (1 mB of water ≈ 2.26 kJ vaporised) — so boiling drains the tank *and* cools the engine:

```
heat_net = heat − EVAP_LATENT_GAIN × 20 × Σ(boiling mB/tick)
EVAP_LATENT_GAIN = BOIL_COOLING_PER_DEGREE ÷ COOLANT_BOIL_MB_PER_DEGREE_SEC = 1.5 ÷ 3.0 = 0.5 °C/s per mB/s
(equivalent form: Q_evap = 1.5 × (T − 200), i.e. "1.5 °C/s removed per °C of superheat" — same dimension as k_total)
```

- This term does **not** go into `k_water`: it is a mass-flow term (∝ drain rate), independent of the temperature difference, so it **ignores ram air and pressure** as well.
- It is **exactly 0 at T ≤ 200°C** → every existing equilibrium below 200°C (ducts, water-cooling dissipation, economy band, cold penalty) is unchanged.
- **You only get the cooling if you really burn coolant**: the evaporation rate and the drain rate are the same number, so it can never "cool more while burning less".
- Steam engines / closed loop → 0 (those configurations do not drain coolant either).

**Calibration baseline** (fuel `heat=1.0`, **4 full-load fluid chambers + 1 full water module**, 1 core segment, static, sea level → `heat=120`, `k_total≈0.55`):

| | Equilibrium | Boiling drain | Runtime per 8000 mB |
|---|---|---|---|
| Without the boiling term | ≈238°C (**repeated 220 hard-stop / 200 restart cycle**) | 0 | — |
| With it (current value) | **≈210°C (stable)** | ≈30.9 mB/s | ≈4.3 minutes |

!!! note "Plateau temperature and runtime are two separate constants"
    - **`BOIL_COOLING_PER_DEGREE = 1.5`** (°C/s per °C of superheat) **locks the plateau**: `T − 200 = (heat − 180×k_total) / (that value + k_total)`.
    - **`COOLANT_BOIL_MB_PER_DEGREE_SEC = 3.0`** sets **only how fast the tank empties**: a full tank lasts ≈4.3 minutes.
    - `EVAP_LATENT_GAIN` = the ratio = **0.5**, i.e. only ≈29% of the pure latent-heat equivalent (≈1.74) is credited — the rest leaves through the vent/exhaust. That is the price of the shorter runtime.
    - **Design ruling**: boiling-driven high-temperature operation is an **emergency measure, never a long-term mode**. Adjust the drain rate to change the runtime; the plateau will not drift.
    - Hotter fuels (diesel, `heat=1.25`) still push the plateau to ≈225°C and overheat — add another water module or run rich in that case.

### Where `K_WATER = 0.33` comes from: "one full module holds two full-load fluid chambers at ~150°C"

```
Q       = 2 chambers × full load(1.0) × fuel heat(1.0) × BASE_HEAT_FLUID(30) = 60 °C/s
need k  = Q / (150 − 20) ≈ 0.462
k_air   = K_CORE×length + K_AMB×2 ≈ 0.12 (1 core segment) ~ 0.14 (2 core segments)
K_WATER = 0.462 − 0.13 ≈ 0.33
```

Equilibrium temperatures at sea level, static, 2 chambers, fuel `heat = 1.0`, no ducts, **2 core segments** (`k_air = 0.04 + 0.05×2 = 0.14`) — `T_eq = 20 + Q/k` with `Q = 2 × 30 × load` (temperature is clamped at 264°C):

| Coolant | k_water | Full load 100% | 63% | 43% | 20% (idle) |
|---|---|---|---|---|---|
| No module / no coolant | 0 | 448 → clamped 264 (**overheat**) | 290 → clamped 264 (**overheat**) | ≈204°C (about to overheat) | ≈106°C |
| 2000 mB water (50% fill) | 0.165 | ≈217°C (about to overheat) | ≈144°C | ≈105°C | ≈59°C |
| **4000 mB+ water (full effect)** | **0.33** | **≈148°C** ✅ | ≈100°C | ≈75°C | ≈46°C |
| 8000 mB levitite (×1.5) | 0.495 | ≈115°C | ≈80°C | ≈61°C | ≈39°C |

(On a 1-segment engine `k_air = 0.12`, and a full water module at full load lands at ≈ **153°C** — so "two chambers at ~150°C" holds for both 1- and 2-segment cores.)

!!! warning "Water cooling is strong: low load overcools"
    An engine with a big water-cooling module drops below 100°C at low load, which triggers the **overcooling** fuel penalty. Either raise the load or close the loop with `setWaterCooling` to keep the heat in (0.5 ≈ half the dissipation).
    The equilibrium is also moved by the **fuel `heat` multiplier** (diesel 1.25 → hotter), the **engine length** and the **number of ducts**.

## Coolant consumption

Two consumptions exist side by side (computed server-side every tick, actually deducted only once 1 mB has accumulated):

| Consumption | Trigger | Rate |
|---|---|---|
| **Running cost** | **Engine running** (throttle ≠ 0 and not hard-stopped) | **1 mB per 8 seconds per working module** (a full tank ≈ 17.8 hours) |
| **Overheat boiling** | **Whenever T > 200°C** (from the "about to overheat" band up, **no running required**) | `3.0 × (T − 200) × its own boilMultiplier` mB/s per working module — 210°C ≈ 31 mB/s (a full tank ≈4.3 minutes) |

- **Bookkeeping is per module**: each module accumulates its own debt and is drained from **its own tank** — N modules means N tanks losing coolant at their own rates (never "only the first module drains").
- Boiling is **proportional to temperature**: the rate is 0 exactly at 200°C (no boundary flicker) and grows as it gets hotter.
- **It keeps draining after the engine hard-stops**: reaching 220°C hard-stops the engine (no fuel burned), but as long as the temperature stays above 200°C the coolant keeps evaporating — and less coolant → weaker cooling → hotter → faster loss (positive feedback). A full tank lasts ≈ 14 minutes at 219°C and ≈ 27 minutes at 210°C.
- **Stopped engines pay no running cost**: at throttle 0 only boiling drain applies (same semantics as "a stopped engine burns no fuel").
- With the loop closed (`setWaterCooling(0)`) both consumptions stop — but so does the cooling.

## Steam engines

Water cooling has **no effect on steam engines**: it does not participate in their temperature (closed boiler self-regulation, see [Steam Power Chamber](steam-power-chamber.md)) and **does not consume coolant**. Mounting one on a steam engine is purely decorative (the engine core's goggle shows "Coolant Cooling: none").

## Comparison with the cooling air duct

| | Water Cooling Module | Cooling Air Duct |
|---|---|---|
| Dissipation per block (full effect) | **0.33** (≈ 6.6 ducts) | 0.05 |
| Ram-air (airspeed) bonus | **none** | yes (up to ×2 at 30 m/s) |
| Pressure (altitude) penalty | **none** | yes (`pressure^0.8`) |
| Running cost | coolant (1 mB / 8 s per module) | none |
| Overheat penalty | accelerated coolant evaporation | none |
| Fluid tank | 8000 mB (coolants only) | none |
| Adjustable | `setWaterCooling` loop opening | `setCooling` shutter |
| Best for | static, low speed, high altitude | fast flight, no resupply |

## Datapack: coolants

The coolant table lives in `data/<namespace>/coolant/*.json`, hot-reloaded with `/reload`:

```json
{ "fluid": "minecraft:water", "efficiency": 1.0, "boilMultiplier": 1.0 }
```

| Field | Meaning |
|---|---|
| `fluid` | Coolant fluid id (the unique key). **The table is the module's filling whitelist** |
| `efficiency` | Cooling efficiency multiplier (default 1.0, must be > 0): multiplied directly into `k_water` |
| `boilMultiplier` | Boil-off multiplier (optional, default 1.0, must be > 0): higher = evaporates faster above 200°C |

- Bundled entries: `minecraft:water` (1.0) and `aeronautics:levitite_blend` (1.5). Adding a coolant is just another json file — **no code changes**.
- Fluid ids are decoupled from the installed mods: referencing a fluid from a mod you do not have will not crash; the log warns that the fluid is not registered and the entry is kept.

## Goggle display

**The module itself** (goggles on it):

```
Fluid Container
  Water 8000 mB / 8000 mB
Coolant Efficiency: ×1.00
Cooling: +0.33
```

- Below 4000 mB the "Cooling" line appends "(low coolant, 4000 mB for full effect)".
- Empty tank / non-coolant → efficiency shows "no coolant", cooling shows "none".

**Engine core** (two extra places once a module is installed):

```
Engine Status
…
Load: 43%
Coolant Cooling: +0.33        ← none when out of coolant / loop closed / steam engine (grey)
Connected Modules:
- Fluid Combustion Chamber x2
- Water Cooling Module x1
```

!!! note "Why the module does not proxy to the core"
    Cooling ducts proxy their goggle to the engine core (engine temperature + shutter). The water cooling module **deliberately does not**: its coolant level and efficiency matter more than the engine temperature, so it keeps its own fluid tooltip. Hover the engine core to read the temperature.

## Lua API

New methods (see [Engine Lua API](lua-api.md)):

| Method | Description |
|---|---|
| `hasWaterCooling()` | Whether a water cooling module is installed |
| `getWaterCooling()` / `setWaterCooling(0~1)` | Water loop **opening** (default 1.0; 0 = closed loop: no cooling, no drain) |
| `getWaterCoolingPower()` | Current effective water dissipation `k_water` (amount ratio / efficiency / opening) |
| `getWaterCoolingModules()` | Number of working modules (amount > 0) |
| `getCoolantAmount()` | Total coolant in mB (sum over working modules) |

```lua
local e = peripheral.find("ccpe:engine")
print(e.getWaterCoolingPower())   --> 0.33
print(e.getCoolantAmount())       --> 8000.0
-- keep the heat in at low load: close half of the loop
e.setWaterCooling(0.5)
```

## Related pages

- [Engine System overview](overview.md)
- [Temperature & Cooling Model](temperature.md) — the full `K_total` formula plus the altitude/pressure curves
- [Cooling Air Duct](cooling-air-duct.md) — the air-side counterpart
- [Engine Lua API](lua-api.md)
