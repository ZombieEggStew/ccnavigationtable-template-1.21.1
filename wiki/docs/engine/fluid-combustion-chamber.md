# Fluid Combustion Chamber

![fluid_combustion_chamber](../img/fluid_combustion_chamber.png)

The **Fluid Combustion Chamber** (`ccpe:fluid_combustion_chamber`) attaches to an [Engine Core](engine-core.md) and burns **fluid fuels from other mods** — each chamber contributes **8192 SU** (× the fuel's stress multiplier). This is the "economy management" half of the engine system: fuel cost and temperature are tuned through the **mixture**, the throttle and the cooling duct shutter.

## Placement

- Right-click the chamber against any face of an engine core (the chamber attaches to the face it is placed against). A placement ghost previews the attachment.
- Attach as many chambers as you like around the row of cores; each chamber is a separate input module.
- **Fluid and steam chambers are mutually exclusive on one engine** — the two types cannot be mixed (placement is blocked; the controller arbitrates by majority).

## Fuel

- Fuel comes from **datapacks**: `data/<namespace>/engine_fuel/*.json` (hot-reloaded with `/reload`). No need for a specific fuel source block — the engine drains the fluid from any fluid tank in the engine's neighbourhood via its capability (zero internal fluid cache, e.g. the [Quick-Fill Fluid Tank](fuel.md#quick-fill-fluid-tank) works).

### Supported fuels

The mod ships the following fuels in `data/ccpe/engine_fuel/*.json` (you need the corresponding mod installed for its fluids to exist; see the notes):

| Fuel | Source mod | `consumption` (mb/s per chamber) | `heat` | `stress` |
|---|---|---|---|---|
| Diesel | Create: Diesel Generators (`createdieselgenerators:diesel`) | 1.0 | ×1.25 | ×1.25 |
| Biodiesel | Create: Diesel Generators (`createdieselgenerators:biodiesel`) | 1.0 | ×1.0 | ×1.0 |
| Gasoline | Create: Diesel Generators (`createdieselgenerators:gasoline`) | 1.0 | ×1.0 | ×1.0 |
| Ethanol | Create: Diesel Generators (`createdieselgenerators:ethanol`) | 1.0 | ×0.5 | ×0.5 |
| Plant Oil | Create: Diesel Generators (`createdieselgenerators:plant_oil`) | 1.0 | ×0.5 | ×0.5 |
| Coral | Create Propulsion: Simulated (`createpropulsion:coral`) | 1.0 | ×1.25 | ×1.25 |
| Turpentine | Create Propulsion: Simulated (`createpropulsion:turpentine`) | 1.5 | ×1.0 | ×1.0 |
| Levitite Blend | Aeronautics (`aeronautics:levitite_blend`) | 1.25 | ×0.5 | ×0.75 |

How the multipliers matter:

- **`stress`** scales the chamber's **8192 SU** base — Diesel / Coral produce **10240 SU** per chamber, while Ethanol / Plant Oil produce only **4096 SU**.
- **`heat`** scales the heat generated into the temperature model (see [Temperature & Cooling Model](temperature.md)) — Diesel / Coral run hotter, Ethanol / Plant Oil run cooler.
- **`consumption`** is the burn rate per chamber (mb/s) — Turpentine burns **1.5 mb/s** (faster consumption at the same power), all others 1.0.
- **One fuel at a time**: the engine picks the first usable fuel in its source scan order — with several tanks around, the scan order decides which one burns.
- These are only the bundled entries — the engine accepts **any** `engine_fuel/*.json` in any datapack, so other mods' fluids can be added the same way.

## Output & consumption model

- **Stress capacity** = running chambers × 8192 × fuel `stress` × throttle.
- **Fuel burn** = lever × economy factor × cold penalty (see below), × fuel `consumption`.
- **Heat** = running chambers × fuel `heat` × `heatFactor(actual mixture)` × throttle.

## Mixture

`setMixture(0.6..1.4)` (default 1.0) only affects **fuel cost and temperature** — never stress or speed:

- **Single linear heat factor for both sides**: `heatFactor = 1 − (m−1)` (i.e. `2 − m`), slope −1, no convex curve, no floor clamp.
- **Lean (< 1.0)** = fuel saving, but hotter. Heat factor rises linearly: `m=0.8 → ×1.20`.
- **Rich (> 1.0)** = spend fuel to cool. Heat factor falls linearly: `m=1.2 → ×0.80`, `m=1.4 → ×0.60` (no floor).
- **Altitude auto-rich/lean**: the carburettor meters by intake air volume — thinner air at altitude naturally enriches (up to ×1.25), high pressure below sea level naturally leans (down to ×0.75) — the *actual* mixture is `lever × autoRichness(pressure)`, where `autoRichness = 1 + 0.45×(1−pressure)` clamped to [0.75, 1.25]. Auto-rich/lean only affects heat (×0.75 heat at full auto-rich), it never costs fuel. At Y≈260 the auto-rich is ≈×1.25, so pulling the lever to ≈0.8 gives an *actual* mixture of ≈1.0 (altitude compensation, see economy).

## Economy factor

The **economy factor** is an AND-gated, time-unlocked discount (0.75–1.0):

- Requires **both** `|T − 155°C| ≤ 10` **and** `0.8 ≤ actual mixture ≤ 1.1` (flat-bottom window).
- Staying inside the window for **15 s** ramps the factor in to **×0.75** (25% fuel saving); leaving it decays back in **6 s**. Holding the conditions is what matters — briefly passing through earns nothing.
- If the mixture is wrong there is **no penalty**, just no reward.
- The economy factor multiplies consumption **only** — it never feeds back into heat.

## Temperature & cooling

> For the full heat-generation and dissipation equations, altitude → temperature and altitude → pressure curves, see [Temperature & Cooling Model](temperature.md).

- **Newton cooling model**: heat generated minus heat dissipated (ambient + core + cooling ducts, scaled by ram air at speed and air pressure at altitude), integrated against thermal mass.
- **Environment temperature** varies by height in the overworld (sea level 20°C → clouds 0°C → world top −40°C); the Nether is a constant **155°C** at all heights, the End a constant **0°C**.
- **Ram air cooling**: 1.0 below 10 m/s, ramping linearly to **×2.0 at 30 m/s** — flying fast cools the engine for free.
- **Overcooling penalty** (`cold`): below 100°C the fuel cost rises: `cold = 1 + max(0, 100−T)/100` (≈×1.8 at 20°C). Warm up at low throttle before pushing it — this is the "small-throttle warm-up" gameplay.
- **Overheat**: T ≥ **220°C** hard-stops the engine (no fuel burned); it resumes at T ≤ **200°C** (hysteresis). Overheating is the only thing that shuts a fluid engine down besides throttle 0 / no fuel.

## Status display

Hover the chamber with goggles (full engine status):

```
Engine Status
Status: Stopped / Cold / Normal / Efficient / Getting hot / Overheated
Temperature: XX.X°C
Throttle: 50%
Fuel: ×0.60
Heat Factor: ×1.08
```

| Temperature | Status | Colour |
|---|---|---|
| — (not running) | Stopped (throttle 0 / no fuel / no water) | grey |
| < ~97°C | Cold (fuel penalty active) | blue |
| 97 ~ 145°C | Normal | green |
| 145 ~ 165°C | **Efficient** (economy band \|T−155\|≤10) | cyan |
| 165 ~ 200°C | Normal | green |
| 200 ~ <220°C | Getting hot | gold |
| ≥ 220°C | Overheated (resumes at 200°C) | red |

The **Fuel** line shows the final fuel-cost multiplier = lever × economy × cold; **Heat Factor** = `heatFactor(lever × altitude auto-rich)`.

!!! note "Overheating thresholds are fixed by the engine design"
    155°C economy target / 100°C overcooling threshold / 220°C overheat are engine constants — they do **not** change with fuel or throttle (like a real engine's design point / thermostat).
