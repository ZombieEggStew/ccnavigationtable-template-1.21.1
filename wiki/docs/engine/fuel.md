# Fuel & Fuel Sources

The engine system has **two completely separate fuel systems** — the fluid combustion chamber uses pure datapack fuel, the steam power chamber uses pure vanilla fuel. They never mix.

| | Fluid engine (combustion chamber) | Steam engine (power chamber) |
|---|---|---|
| Fuel source | Datapack `engine_fuel/*.json` | Pure vanilla — bucket items' furnace burn time + solid items' `burnTime` |
| How to add fuel | Write a datapack JSON, `/reload` | Use vanilla furnace fuel rules (lava buckets, coal, etc.) |
| Source storage | Any fluid tank with the fluid (e.g. Quick-Fill Fluid Tank) | Water tank + fluid fuel tank and/or Quick-Fill Fuel Vault (solid) |

---

## Fluid combustion chamber fuel (datapack)

Fluid chamber fuels live in `data/<namespace>/engine_fuel/*.json` and are hot-reloaded with `/reload`:

```json
{ "fluid": "minecraft:water", "consumption": 1.0, "heat": 1.0, "stress": 1.0 }
```

| Field | Meaning |
|---|---|
| `fluid` | Fluid id to burn |
| `consumption` | **full-load** mb/s per chamber (default 1.0). Actual burn rate = this value × burn intensity(max(load, 0.2)) × lever × economy × cold |
| `heat` | Heat multiplier for the temperature model |
| `stress` | Stress multiplier on the 8192 SU base |

- **No priority field** — when several fuels are usable, the controller picks the **first one in source scan order** (the order the engine scans neighbouring tanks). Old datapacks with a `priority` field are ignored.
- **No temperature fields** — overheat (220°C), overcooling (100°C) and the economy target (155°C) are fixed engine constants.
- One fuel at a time per engine (single-fuel rule, chosen by source scan order, never mixed).

## Steam engine fuel (pure vanilla)

No datapack needed — vanilla furnace rules apply:

- **Fluid fuel**: any bucket item with a vanilla furnace burn time > 0 (e.g. lava bucket = 20000 ticks). Add fuel the vanilla way (set the bucket item's furnace burn time — the engine shares the same fuel registry as the furnace).
- **Solid fuel**: any item with vanilla `burnTime > 0` (coal = 1600 ticks, etc.), stored in a Quick-Fill Fuel Vault near the engine, auto-drawn by the controller.
- **Fluid fuel is preferred**; solid fuel is only drawn when no fluid fuel is available.
- **How fast it burns depends on the load**: `max(load, 0.2)` burn ticks per second (0 load = the 20% idle floor keeping it warm; throttle 0 = 0). `consumption` and `burnTime` are all "full-load" figures.

---

## Coolants (water cooling module — not fuel)

The [Water Cooling Module](water-cooling-module.md) uses a separate datapack table, `data/<namespace>/coolant/*.json` (also hot-reloaded with `/reload`), completely independent of fuel:

```json
{ "fluid": "minecraft:water", "efficiency": 1.0, "boilMultiplier": 1.0 }
```

- `fluid`: coolant id — **the table is the module's filling whitelist** (anything else cannot be inserted).
- `efficiency`: cooling efficiency multiplier (default 1.0), multiplied straight into the water dissipation.
- `boilMultiplier`: boil-off multiplier (default 1.0), scales evaporation above 200°C.
- Bundled: `minecraft:water` (1.0) and `aeronautics:levitite_blend` (1.5).
- **Coolant is never treated as fuel or boiler water**: the controller's source scan skips water cooling modules, so a water-filled module is not drained by a steam engine and a levitite-blend-filled one is not burned by a fluid combustion chamber.

---

## Quick-Fill Fluid Tank

![quick_fill_fluid_tank](../img/quick_fill_fluid_tank.png)

The **Quick-Fill Fluid Tank** (`ccpe:quick_fill_fluid_tank`) is a 6-sided attachable block with **4000 mb single-slot fluid storage**:

- **Right-click with a fluid container** to pour the fluid into the tank.
- **Right-click with an empty bucket** to fill one bucket from the tank.
- Goggle tooltip like Create's fluid tank.
- Exposes a fluid capability, so the engine drains water / fluid fuel directly from it.

## Quick-Fill Fuel Vault

![quick_fill_fuel_vault](../img/quick_fill_fuel_vault.png)

The **Quick-Fill Fuel Vault** (`ccpe:quick_fill_fuel_vault`) is a 6-sided attachable step block with a **single-item-type inventory (capacity 1024 = 16 stacks × 64), no GUI**:

- **Right-click with an item** to store the whole stack (the vault accepts only one item type total).
- **Sneak + right-click empty-handed** to take out one stack (the item's max stack size, or all if fewer remain).
- A successful deposit/withdraw plays an open-lid animation (auto-closes after a short time).
- Breaking the block drops its contents.
- Exposes an item capability, so the steam engine draws solid fuel from it automatically (parallel burning, `N×K` per batch, switching vaults when one runs low).

---

## Batch refills (how the engine draws)

The engine has **zero internal fluid cache** — it draws into a small **reserve** from source storages via capability on each batch refill:

- **Batch size = chamber count × K**, where **K** is the configurable batch multiplier (`engineSourceBatchMultiplier`, default 1.0, range 0.25–64; cached once when entering a world).
- The engine draws from the **head of its source list**; if the head tank/vault is empty, removed or holds the wrong content, it **immediately advances to the next source** (same tick, zero downtime).
- The reserve is refilled the same tick it runs out, so the stress network never flickers.
- How long a batch lasts **depends on the burn intensity**: at full load a batch is consumed in about 1 second (at the configured batch multiplier K); idling at 20% takes about 5 times as long.
