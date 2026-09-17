# Fluid Combustion Chamber

![fluid_combustion_chamber](../img/fluid_combustion_chamber.png)

The **Fluid Combustion Chamber** (`ccpe:fluid_combustion_chamber`) attaches to an [Engine Core](engine-core.md) and burns **datapack fluid fuel** — each chamber contributes **8192 SU** (× the fuel's stress multiplier). This is the "economy management" half of the engine system: fuel cost and temperature are tuned through the **mixture lever**, the throttle and the cooling duct shutter.

## Placement

- Sneak-right-click the chamber against any face of an engine core (the chamber attaches to the face it is placed against). A placement ghost previews the attachment.
- Attach as many chambers as you like around the row of cores; each chamber is a separate input module.
- **Fluid and steam chambers are mutually exclusive on one engine** — the two types cannot be mixed (placement is blocked; the controller arbitrates by majority).
- A chamber attached between two cores belongs to one engine only (never double-counted).

## Fuel

- Fuel comes from **datapacks**: `data/<namespace>/engine_fuel/*.json` (hot-reloaded with `/reload`). No need for a specific fuel source block — the engine drains the fluid from any fluid tank in the engine's neighbourhood via its capability (zero internal fluid cache, e.g. the [Quick-Fill Fluid Tank](fuel.md#quick-fill-fluid-tank) works).
- Each fuel entry defines `consumption` (mb/s per chamber, default 1), `heat` and `stress` multipliers — see [Fuel & Fuel Sources](fuel.md).
- **One fuel at a time per engine**: the controller picks the first usable fuel in its source scan order (no priority field anymore); old `priority` fields in data packs are ignored.

## Output & consumption model

- **Stress capacity** = running chambers × 8192 × fuel `stress` × throttle.
- **Fuel burn** = lever × economy factor × cold penalty (see below), × fuel `consumption`.
- **Heat** = running chambers × fuel `heat` × `heatFactor(actual mixture)` × throttle.

## Mixture — the economy lever

`setMixture(0.6..1.4)` (default 1.0) only affects **fuel cost and temperature** — never stress or speed:

- **Lean (< 1.0)** = fuel saving, but hotter. Heat factor: `1 + 2.0×(1−m)²` (convex on the lean side).
- **Rich (> 1.0)** = spend fuel to cool. Heat factor: `max(0.7, 1−0.5×(m−1))`.
- **Altitude auto-rich**: air gets thinner with height, so the carburettor naturally enriches — the *actual* mixture is `lever × autoRichness(pressure)`, where `autoRichness = 1 + 0.45×(1−pressure)` clamped to [1.0, 1.25]. Auto-richness **only cools** (×0.875 heat at max) — it never increases fuel consumption. At Y≈260 the auto-rich is ≈×1.25, so pulling the lever to ≈0.8 gives an *actual* mixture of ≈1.0 (altitude compensation, see economy).

## Economy factor

The **economy factor** is an AND-gated, time-unlocked discount (0.75–1.0):

- Requires **both** `|T − 155°C| ≤ 10` **and** `|actual mixture − 1| ≤ 0.05` (flat-bottom window).
- Staying inside the window for **15 s** ramps the factor in to **×0.75** (25% fuel saving); leaving it decays back in **6 s**. Holding the conditions is what matters — briefly passing through earns nothing.
- If the mixture is wrong there is **no penalty**, just no reward.
- The economy factor multiplies consumption **only** — it never feeds back into heat.

## Temperature & cooling

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
