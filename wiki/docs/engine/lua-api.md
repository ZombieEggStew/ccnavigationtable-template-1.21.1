# Engine Lua API

The engine is controlled from CC:Tweaked through the `ccpe:engine` peripheral. **Wrap any core segment** — the peripheral lives on the engine's controller and non-controller segments delegate to it:

```lua
local e = peripheral.wrap("front")
-- or: peripheral.find("ccpe:engine")
```

## Reading vs writing

- **Read methods** run with `mainThread=false` and read the controller's cached state directly — at most 1 tick stale, and effectively free to poll at high frequency.
- **Write methods** and `getFluidTanks()` run with `mainThread=true` (server-authoritative, scans the world).

## Methods

| Method | mainThread | Returns | Description |
|---|---|---|---|
| `getTemperature()` | false | number | Engine temperature in °C |
| `isOverheated()` | false | boolean | Overheat lock (fluid engines only; hard-stop at T≥220°C, unlock at T≤200°C) |
| `getFluidTanks()` | true | table[] | All connected fluid tanks: `{fluid, amount, remaining, capacity}` (fluid is `nil` when empty) |
| `getThrottle()` | false | number | Throttle 0..1 (default 0.25). Scales stress **and** speed together (0 = stopped, no fuel burned; the only on/off control) |
| `setThrottle(0..1)` | true | boolean | Set throttle (clamped; returns `false` on invalid input) |
| `getMixture()` | false | number | Mixture lever 0.6..1.4 (fluid engines only; steam is always 1.0) |
| `setMixture(x)` | true | boolean | Mixture: fuel × lever, temperature × heat factor; **no gating** (pure storage — no effect on non-fluid engines) |
| `getEffectiveMixture()` | false | number | Actual mixture = lever × altitude auto-rich (used by the economy window and heat feedback; > lever at altitude) |
| `getAutoRichness()` | false | number | Natural altitude mixture = the altitude auto-rich coefficient only (no lever): 1.0 at sea level, up to ×1.25 at Y≈260 (steam: always 1.0). Reference for how far to pull the lever — pull to ≈ 1 / getAutoRichness() so m_eff ≈ 1.0 |
| `getFuelEconomyFactor()` | false | number | Economy factor 0.75..1.0 (temperature + mixture both in window, ramps in over 15 s; wrong mixture = no reward, no penalty) |
| `hasAirDuct()` | false | boolean | Whether a cooling air duct is installed (informational; `setMixture`/`setCooling` are ungated — the shutter value is only consumed when a duct is present) |
| `getActiveFuel()` | false | table | Active fuel: fluid `{type="fluid", fluid=<id>, optimalTemp=155}`; steam `{type="steam", optimalTemp=155}`; stopped `{type="none"}` |
| `getCooling()` | false | number | Cooling shutter 0..1 (default 1.0) |
| `setCooling(0..1)` | true | boolean | Shutter: only scales the duct dissipation component, can only reduce; **no gating** (pure storage, consumed only with a duct installed) |
| `isWarmingUp()` | false | boolean | Steam warm-up in progress (T<100°C burns without generating) |

## Example — cruise control

```lua
local e = peripheral.wrap("front")

-- Full startup
e.setThrottle(0.25)          -- 25% throttle: 64 rpm, fuel lasts 4× longer on steam

-- Economy cruise (fluid engine): pull the lever for altitude compensation
local nat = e.getAutoRichness()          -- natural altitude mixture (no lever)
if nat > 1.0 then
    e.setMixture(math.max(0.6, 1 / nat)) -- compensate: m_eff ≈ 1.0
end

-- Watch the temperature
print(string.format("T = %.1f°C  eco = %.2f  fuel = %s",
    e.getTemperature(), e.getFuelEconomyFactor(), e.getActiveFuel().type))
```

**Overload does not stop the engine** — like Create stress sources it keeps running and burning fuel; the goggles show a red "Network overloaded" warning. Shutdown reasons: no fuel / no water, throttle 0, fluid overheat (resumes at ≤ 200°C), steam warm-up incomplete.

## Status tiers (fluid engine, via `getTemperature()`)

| Temperature | Status |
|---|---|
| — (not running) | Stopped |
| < ~97°C | Cold (fuel penalty) |
| 97 ~ 145°C | Normal |
| 145 ~ 165°C | **Efficient** (economy band) |
| 165 ~ 200°C | Normal |
| 200 ~ <220°C | Getting hot |
| ≥ 220°C | Overheated |
