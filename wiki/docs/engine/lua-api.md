# Engine Lua API

The engine is controlled from CC:Tweaked through the `ccpe:engine` peripheral. **Wrap any core segment** — the peripheral lives on the engine's controller and non-controller segments delegate to it:

```lua
local e = peripheral.wrap("front")
-- or: peripheral.find("ccpe:engine")
```

## [Example — Temperature and Mixture Control](../examples/trainer_aircraft_2.md)

## Reading vs writing

- **Read methods** run with `mainThread=false` and read the controller's cached state directly — at most 1 tick stale, and effectively free to poll at high frequency.
- **Write methods** and `getFluidTanks()` run with `mainThread=true` (server-authoritative, scans the world).

## Methods

| Method | mainThread | Returns | Description |
|---|---|---|---|
| `getTemperature()` | false | number | Engine temperature in °C |
| `isOverheated()` | false | boolean | Overheat lock (fluid engines only; hard-stop at T≥220°C, unlock at T≤200°C) |
| `getFluidTanks()` | true | table[] | All connected fluid tanks: `{fluid, amount, remaining, capacity}` (fluid is `nil` when empty) |
| `getThrottle()` | false | number | Throttle 0..1 (default 0.25). **Sets the output speed only** (speed = throttle × 256 rpm; stress capacity is constant; 0 = stopped, no fuel burned; the only on/off control) |
| `getLoad()` | false | number | **Load** 0..1 = stress network's "stress used / capacity available" (**actual value, idle floor not included**; 0 when idle, 0 when stopped). **The driver of fuel burn and heat** — handy for economy scheduling |
| `getStressUsed()` | false | number | Stress used by the network (SU, whole network: Σ machine impact × speed; stopped/unattached → 0) |
| `getStressCapacity()` | false | number | Capacity available on the network (SU, whole network; this engine's own contribution is constant = 8192 per fluid chamber / 4096 per steam chamber) |
| `setThrottle(0..1)` | true | boolean | Set throttle (clamped; returns `false` on invalid input). **Changes speed only**, never the stress capacity; fuel consumption follows the load |
| `getMixture()` | false | number | Mixture lever 0.6..1.4 (fluid engines only; steam is always 1.0). Affects fuel cost and heat **at a given load** |
| `setMixture(x)` | true | boolean | Mixture: fuel × lever, temperature × heat factor; **no gating** (pure storage — no effect on non-fluid engines) |
| `getEffectiveMixture()` | false | number | Actual mixture = lever × altitude auto-rich (used by the economy window and heat feedback; > lever at altitude) |
| `getAutoRichness()` | false | number | Natural altitude mixture = the altitude auto-rich/lean coefficient only (no lever): 1.0 at sea level, up to ×1.25 at high altitude, down to ×0.75 below sea level (auto-lean at high pressure) (steam: always 1.0). Reference for how far to pull the lever — pull to ≈ 1 / getAutoRichness() so m_eff ≈ 1.0 |
| `getFuelEconomyFactor()` | false | number | Economy factor 0.75..1.0 (temperature + mixture both in window, ramps in over 15 s; wrong mixture = no reward, no penalty) |
| `hasAirDuct()` | false | boolean | Whether a cooling air duct is installed (informational; `setMixture`/`setCooling` are ungated — the shutter value is only consumed when a duct is present) |
| `getActiveFuel()` | false | table | Active fuel: fluid `{type="fluid", fluid=<id>, optimalTemp=155}`; steam `{type="steam", optimalTemp=155}`; stopped `{type="none"}` |
| `getCooling()` | false | number | Cooling shutter 0..1 (default 1.0) |
| `setCooling(0..1)` | true | boolean | Shutter: only scales the duct dissipation component, can only reduce; **no gating** (pure storage, consumed only with a duct installed) |
| `isWarmingUp()` | false | boolean | Steam warm-up in progress (T<100°C burns without generating) |

## Status tiers (fluid engine, via `getTemperature()`)

The tier is decided by **load vs cooling**: more load = hotter, better cooling (ducts / shutter / ram air / low altitude) = cooler. To cool down, drop the load or improve the cooling.

| Temperature | Status |
|---|---|
| — (not running) | Stopped |
| < ~97°C | Cold (fuel penalty) |
| 97 ~ 145°C | Normal |
| 145 ~ 165°C | **Efficient** (economy band) |
| 165 ~ 200°C | Normal |
| 200 ~ <220°C | Getting hot |
| ≥ 220°C | Overheated |
