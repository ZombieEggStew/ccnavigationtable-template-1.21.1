# Steam Power Chamber

![steam_power_chamber](../img/steam_power_chamber.png)

The **Steam Power Chamber** (`ccpe:steam_power_chamber`) attaches to an [Engine Core](engine-core.md) and generates power from **water + fuel** — each chamber contributes **4096 SU**. This is the "simple and stable" half of the engine system: a closed boiler that self-regulates (**its temperature is independent of load**), a consumption model that follows the network load, and residual-heat output during the cooling transition.

## Placement

- Right-click the chamber against any face of an engine core (it attaches to the face it is placed against), with a placement ghost previewing the attachment.
- **Fluid and steam chambers are mutually exclusive on one engine** — the two types cannot be mixed.

## How it works

1. **Fuel & water sources** — place a [Quick-Fill Fuel Vault](fuel.md#quick-fill-fuel-vault) (solid fuel) and/or a fluid tank with a burnable bucket fuel (e.g. lava), plus a water tank, near the engine. The controller auto-draws from any neighbouring storage via capability (zero internal cache).
2. **Ignite** — with water and fuel available, the boiler starts burning.
3. **Warm-up** — below 100°C the engine burns fuel but **does not output stress** (this is `isWarmingUp()`).
4. **Power** — once T ≥ 100°C and throttle > 0, the engine outputs **4096 SU of stress per chamber** (**capacity is constant, independent of throttle**; the throttle only sets the speed).

## Closed boiler — never overheats

- The boiler self-regulates and pins at **155°C** (saturation temperature) — independent of air pressure, environment or ram air, so the steam engine is **altitude-proof**.
- **The temperature is independent of load**: as long as the throttle is not 0 and fuel is actually burning (a reserve exists), the temperature converges to and pins at 155°C; the load only decides *how fast it burns*, not *whether it burns*.
- Once the fuel is **exhausted** the heating stops and the boiler starts Newton cooling (it stops below 100°C) — so *staying hot costs fuel*, and no fuel can never produce free power.
- There is **no overheat**, no mixture lever, no economy zone and no cooling duct requirement.
- If the water runs out, the engine stops burning (dry-fire protection) and cools by Newton cooling.

## Fuel

The steam engine uses only **vanilla-form fuels** (no datapack needed):

- **Fluid fuel first**: bucket items with a vanilla furnace burn time > 0 (e.g. a lava bucket = 20000 ticks). The engine drains the fluid and converts it by burn time.
- **Solid fuel fallback**: when no fluid fuel is available, the engine draws solid items with vanilla `burnTime > 0` (e.g. coal = 1600 ticks) from a neighbouring Quick-Fill Fuel Vault.
- **Parallel burning**: `N` chambers burn `N` fuels at once (one per chamber, all start and end together). A batch is drawn as `N × K` items (K = configurable batch multiplier, default 1.0); if the current vault has fewer than `N×K`, the engine switches to the next vault rather than splitting a stack.
- **Water**: 1 mb/s per chamber (full-load rate) × burn intensity (below), drawn in batches from neighbouring water tanks.

## Consumption ∝ load

**Load** is the stress network's "stress used / capacity available" (a steam engine's available capacity is the sum of 4096 SU for every running chamber). The core goggle's **Load** line shows the actual utilization.

- Fuel, water and fluid all scale **with the load**: the higher the load, the faster it burns and the more water it uses (fuel endurance now depends on load instead of scaling with the throttle).
- **Idle floor (20%)**: at zero load (nothing attached downstream) the engine still burns at 20% and uses water at the same rate (a real engine's idle fuel consumption), so the boiler never goes out.
- Throttle **0 = stopped** — nothing is drawn, burned or consumed.
- The goggles fuel line shows the **wall-clock time remaining**: `burnTicks / (max(load, 0.2) × 20)` (e.g. "Coal x3 (6m 40s)") — the countdown matches real time; at throttle 0 the countdown is hidden (stopped).

## Residual-heat operation (the cooling transition)

Holding 155°C itself costs fuel — the moment the fuel stops, the boiler starts to cool. So "residual heat" output only exists during that cooling transition:

- Running condition = **throttle > 0 AND T ≥ 100°C AND water available**; the engine stops as soon as the temperature drops below 100°C.
- Fuel exhausted → heating stops → Newton cooling; until it cools below 100°C the engine still outputs at the capacity of **all** steam chambers (the residual-heat transition).
- It stops only when the **water runs out** (dry-fire protection) or the boiler cools below **100°C**.
- Combustion countdown is independent of the fuel source: if you remove the fuel vault, already-drawn fuel keeps burning to the end.
- In the Nether the ambient temperature is fixed at 155°C (above the 100°C working line), so water alone keeps the engine running indefinitely there.

## Goggle display

Hover the chamber with goggles:

```
Engine Status
Status: Normal / Warming up / Stopped
Temperature: XX.X°C
Fuel: Lava / Coal x3 (6m 40s) / None
```

- **Status** — Normal / Warming up (gold) / Stopped. Throttle and Load are only shown on the [Engine Core](engine-core.md) goggles.
- **Fuel** — fluid fuel shows just its name (green); solid fuel shows "Name xN" (N = number of steam chambers, parallel burning) plus a wall-clock time in English brackets; with no reserve it shows red "None".
