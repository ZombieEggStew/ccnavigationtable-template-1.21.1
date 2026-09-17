# Engine System

![engine_core](../img/engine_core.png)

The **Engine System** is a modular, multi-block Create stress-network power source. 

| Block | ID | What it does |
|---|---|---|
| [Engine Core](engine-core.md) | `ccpe:engine_core` | Multi-block skeleton: cores form one engine (1×1×N, N ≤ 21) sharing a single running state; only the **controller** generates power and makes decisions |
| [Fluid Combustion Chamber](fluid-combustion-chamber.md) | `ccpe:fluid_combustion_chamber` | Burns **datapack fluid fuel**; **8192 SU per chamber** (× fuel stress multiplier) |
| [Steam Power Chamber](steam-power-chamber.md) | `ccpe:steam_power_chamber` | Burns **water + fuel** to make steam; **4096 SU per chamber**; simple and stable, never overheats |
| [Cooling Air Duct](cooling-air-duct.md) | `ccpe:cooling_air_duct` | Cooling fins (dissipation) + an adjustable cooling shutter (`setCooling`) |

Support blocks for feeding the engine:

| Block | ID | What it does |
|---|---|---|
| [Quick-Fill Fluid Tank](fuel.md#quick-fill-fluid-tank) | `ccpe:quick_fill_fluid_tank` | 4000 mb single-slot fluid storage; water / fluid fuel source for the engine |
| [Quick-Fill Fuel Vault](fuel.md#quick-fill-fuel-vault) | `ccpe:quick_fill_fuel_vault` | Single-item-type storage (capacity 1024); solid fuel source for steam chambers |

## Two engine types

| | Fluid engine | Steam engine |
|---|---|---|
| Output | **8192 SU** per chamber (× stress) | **4096 SU** per chamber |
| Fuel | Pure datapack (`engine_fuel/*.json`) | Pure vanilla (bucket items' furnace burn time + solid item burnTime, auto-drawn from fuel vaults) |
| Mixture / economy zone / shutter | Yes — the core gameplay | No (always 1.0) |
| Temperature | Newton cooling with overheat / overcooling management (fixed thresholds) | Closed boiler self-regulates, pinned at **155°C**, never overheats |
| Consumption | Fuel ∝ throttle (lever × economy × cold penalty) | **Consumption ∝ throttle** (real steam throttle: low throttle saves fuel), parallel burning |
| Complexity | High ceiling: fuel saving / cooling / mixture management | Simple and stable: ignite → warm up → power |

The two chamber types are **physically exclusive**: a fluid combustion chamber and a steam power chamber cannot be mixed on the same engine (placement is blocked, and the controller arbitrates by majority).

## One lever to fly

- **Throttle** (`setThrottle(0..1)`) is the only control you need: it scales **both** the stress output and the speed (fixed-pitch propeller model — speed = throttle × 256 rpm, 0~256 linear).
- Throttle **0 = stopped, no fuel burned**.
- The engine keeps running while overloaded (like Create generators — the network shows a red "Network overloaded" warning, but the engine does not shut down).

## Temperature & economy (fluid engine)

- The fluid engine is an **economy management game**: fuel = lever × economy factor × cold penalty; heat = `heatFactor(lever × altitude auto-rich)`.
- **Lean = fuel saving but hotter**; **rich = spend fuel to cool**.
- Economy factor needs **both** temperature in the 155°C band **and** an actual mixture m_eff ≈ 1.0, sustained for 15 s → ramps to **×0.75** (leaving the window decays it in 6 s).
- **Altitude auto-rich is free cooling** — it only affects heat, never fuel consumption.

## Steam engine (simple & stable)

- Fill a fuel vault → the engine ignites → **warm-up** (below 100°C it burns but does not generate) → power.
- **Real steam throttle**: consumption ∝ throttle — 25% throttle makes fuel last 4× longer.
- **Residual-heat operation**: when the fuel runs out but the boiler is still hot (T ≥ 100°C) and water is available, the engine keeps producing at full output; it only stops when water runs out (dry-fire protection) or it cools below 100°C.
- Never overheats, altitude-proof.

## Page index

- [Engine Core](engine-core.md) — multi-block assembly, placement, power output
- [Fluid Combustion Chamber](fluid-combustion-chamber.md) — datapack fuel, mixture & economy gameplay
- [Steam Power Chamber](steam-power-chamber.md) — water + fuel, warm-up, residual heat
- [Cooling Air Duct](cooling-air-duct.md) — cooling fins & shutter
- [Fuel & Fuel Sources](fuel.md) — datapack fuel, vanilla fuel, quick-fill tanks & vaults
- [Lua API](lua-api.md) — `ccpe:engine` peripheral reference
