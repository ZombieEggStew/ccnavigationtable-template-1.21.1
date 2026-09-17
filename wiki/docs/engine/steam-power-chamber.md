# Steam Power Chamber

![steam_power_chamber](../img/steam_power_chamber.png)

The **Steam Power Chamber** (`ccpe:steam_power_chamber`) attaches to an [Engine Core](engine-core.md) and generates power from **water + fuel** — each chamber contributes **4096 SU**. This is the "simple and stable" half of the engine system: closed-boiler self-regulation, a real steam-throttle consumption model, and residual-heat operation.

## Placement

- Right-click the chamber against any face of an engine core (it attaches to the face it is placed against), with a placement ghost previewing the attachment.
- **Fluid and steam chambers are mutually exclusive on one engine** — the two types cannot be mixed.

## How it works

1. **Fuel & water sources** — place a [Quick-Fill Fuel Vault](fuel.md#quick-fill-fuel-vault) (solid fuel) and/or a fluid tank with a burnable bucket fuel (e.g. lava), plus a water tank, near the engine. The controller auto-draws from any neighbouring storage via capability (zero internal cache).
2. **Ignite** — with water and fuel available, the boiler starts burning.
3. **Warm-up** — below 100°C the engine burns fuel but **does not output stress** (this is `isWarmingUp()`).
4. **Power** — once T ≥ 100°C and throttle > 0, the engine outputs **4096 SU of stress per chamber**.

## Closed boiler — never overheats

- The boiler self-regulates and pins at **155°C** (saturation temperature) — independent of air pressure, environment or ram air, so the steam engine is **altitude-proof**.
- There is **no overheat**, no mixture lever, no economy zone and no cooling duct requirement.
- If the water runs out, the engine stops burning (dry-fire protection) and cools by Newton cooling.

## Fuel

The steam engine uses only **vanilla-form fuels** (no datapack needed):

- **Fluid fuel first**: bucket items with a vanilla furnace burn time > 0 (e.g. a lava bucket = 20000 ticks). The engine drains the fluid and converts it by burn time.
- **Solid fuel fallback**: when no fluid fuel is available, the engine draws solid items with vanilla `burnTime > 0` (e.g. coal = 1600 ticks) from a neighbouring Quick-Fill Fuel Vault.
- **Parallel burning**: `N` chambers burn `N` fuels at once (one per chamber, all start and end together). A batch is drawn as `N × K` items (K = configurable batch multiplier, default 1.0); if the current vault has fewer than `N×K`, the engine switches to the next vault rather than splitting a stack.
- **Water**: 1 mb/s per chamber × throttle, drawn in batches from neighbouring water tanks.

## Real steam throttle — consumption ∝ throttle

- Fuel, water and fluid all scale **with the throttle**: at 25% throttle the engine burns 1/4 the steam flow, so **fuel lasts 4× longer**.
- Throttle **0 = stopped** — nothing is drawn or burned.
- The goggles fuel line shows the **wall-clock time remaining**: `burnTicks / (throttle × 20)` (e.g. "Coal x3 (1m 20s)") — so the countdown matches real time and stretches at low throttle. At throttle 0 the countdown is not shown (stopped).

## Residual-heat operation

The engine keeps generating while the boiler is still hot even after the fuel runs out:

- Running condition = **throttle > 0 AND T ≥ 100°C AND water available** (fuel is not required once the boiler is hot).
- In the Nether the ambient temperature is fixed at 155°C, so with water alone the engine keeps working indefinitely.
- When the fuel is exhausted but the boiler is still ≥ 100°C and water is available, it keeps working.
- It stops only when the **water runs out** (dry-fire protection) or the boiler cools below **100°C**.
- Combustion countdown is independent of the fuel source: if you remove the fuel vault, already-drawn fuel keeps burning to the end.

## Goggle display

Hover the chamber with goggles:

```
Engine Status
Status: Normal / Warming up / Stopped
Temperature: XX.X°C
Throttle: 50%
Fuel: Lava / Coal x3 (1m 20s) / None
```

- **Status** — Normal / Warming up (gold) / Stopped.
- **Fuel** — fluid fuel shows just its name (green); solid fuel shows "Name xN" (N = number of steam chambers, parallel burning) plus a wall-clock time in English brackets; with no reserve it shows red "None".
