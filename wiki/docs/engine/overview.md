# Modular Engine System

![engine_core](../img/engine_core.png)

## Background & motivation

The mainstream power sources currently have these problems:

- Create's steam boiler is too bulky
- Create: Aeronautics' portable engines output too little — stress sources are scattered and hard to integrate
- Engines from other mods are hard to fine-control

This mod's **Modular Engine System** offers a new solution: a modular multi-block Create stress-network power source.

| Block | ID | Purpose |
|---|---|---|
| [Engine Core](engine-core.md) | `ccpe:engine_core` | Multi-block skeleton: cores placed in a row form one engine (1×1×N, N≤21); the whole row shares a running state. Only the **controller** outputs and makes decisions |
| [Fluid Combustion Chamber](fluid-combustion-chamber.md) | `ccpe:fluid_combustion_chamber` | Burns **fluid fuels from other mods**; **8192 SU per chamber** (× the fuel's stress multiplier) |
| [Steam Power Chamber](steam-power-chamber.md) | `ccpe:steam_power_chamber` | Burns **water + fuel** to produce steam; **4096 SU per chamber**; simple and stable, never overheats |
| [Cooling Air Duct](cooling-air-duct.md) | `ccpe:cooling_air_duct` | Cooling fins + adjustable shutter |

Engine fuel-supply helper blocks:

| Block | ID | Purpose |
|---|---|---|
| [Quick-Fill Fluid Tank](fuel.md#quick-fill-fluid-tank) | `ccpe:quick_fill_fluid_tank` | 4000 mb single-slot fluid storage |
| [Quick-Fill Fuel Vault](fuel.md#quick-fill-fuel-vault) | `ccpe:quick_fill_fuel_vault` | Single-item-type storage (capacity 1024); the steam chamber's solid fuel source |

## The two engines side by side

| | Fluid engine | Steam engine |
|---|---|---|
| Output | **8192 SU**/chamber (× stress) — **capacity is fixed, independent of throttle** | **4096 SU**/chamber — **capacity is fixed, independent of throttle** |
| Fuel | Pure datapack (`engine_fuel/*.json`) | Pure vanilla (bucket items' furnace burn time + solid items' `burnTime`, auto-drawn from the fuel vault) |
| Mixture / economy zone / shutter | Yes — the core gameplay | None (constant 1.0) |
| Temperature | Newton cooling + overheat/overcooling management (fixed engine thresholds) | Closed boiler self-regulates, pins at **155°C**, never overheats |
| Consumption | Fuel ∝ **stress-network load** (× lever × economy × overcooling) | **Consumption ∝ network load** (idle floor keeps the boiler warm), parallel burning |
| Complexity | High ceiling: fuel-saving / cooling / mixture triple management | Simple and stable: ignite & warm up → generate |

The two chamber types are **physically mutually exclusive**: one engine cannot mix fluid combustion chambers and steam power chambers (placement is blocked; the controller arbitrates by majority).

## Three independent knobs: chambers → capacity, throttle → speed, machines → load

The system decouples into three quantities that never interfere:

| Knob | What it decides | How to change it |
|---|---|---|
| **Chamber count + fuel `stress`** | **Stress capacity** (8192 SU × stress per fluid chamber, 4096 SU per steam chamber) — **constant, independent of throttle** | Add chambers / use better fuel |
| **Throttle** | **Output speed** (speed = throttle × 256 rpm; throttle 0 = stopped) | Lua `setThrottle` |
| **How many machines are attached** | **Load** (= stress used / capacity available, 0~1) — **fuel burn and heat follow it** | Add/remove machines, change speed |

- With nothing attached (load 0) the engine still burns at the **idle floor (20%)** so it stays warm instead of shutting down.
- The higher the load, the faster it burns and the hotter it runs. Holding the load sane and backing it with cooling (ducts, shutter, ram air, low altitude) is the driving skill this engine asks for.
- The **Load** line on the core goggles shows the **actual utilization** (0% when idle); the idle floor is only applied inside the fuel-burn and heat calculations.

## Page index

- [Engine Core](engine-core.md) — networking, placement, output model
- [Fluid Combustion Chamber](fluid-combustion-chamber.md) — datapack fuel, mixture and economy gameplay
- [Steam Power Chamber](steam-power-chamber.md) — water + fuel, warm-up, residual-heat operation
- [Cooling Air Duct](cooling-air-duct.md) — cooling fins and the shutter
- [Temperature & Cooling Model](temperature.md) — how heat is generated and dissipated (altitude → temperature & pressure)
- [Fuel & Fuel Sources](fuel.md) — datapack fuel, vanilla fuel, quick-fill tank / fuel vault
- [Lua API](lua-api.md) — the `ccpe:engine` peripheral reference
- [Example](../examples/trainer_aircraft_2.md) — a complete example using the fluid combustion chamber, automatically controlling engine temperature and mixture
