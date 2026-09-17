# Temperature, Altitude & Cooling Model

The engine temperature is a continuous value computed **every tick on the server** by the engine controller using a **Newton cooling model**, then persisted in the controller's NBT. This page explains exactly how heat is generated and how it is dissipated — including the **altitude → temperature** and **altitude → pressure** relationships that drive it.

> Applies to the **fluid engine**. The **steam engine** does not use this model — its closed boiler self-regulates (see [Steam Power Chamber](steam-power-chamber.md)).

## The model at a glance

```
heat gain      Q_heat = Σ running chambers × throttle × fuel.heat × BASE_HEAT_FLUID × heatFactor(m_eff)
heat loss      P_loss = K_total × (T − T_amb)
                 K_total = (K_CORE×length + K_AMB×running chambers + K_DUCT×ducts×shutter) × ram(speed) × f(pressure)

integration    T += (Q_heat − P_loss) / C_th / 20      (per tick; C_th = C_TH_BASE × length)
clamp          T in [T_amb, OVERHEAT_TEMP × 1.2]
overheat       T ≥ 220°C → hard stop; resume at T ≤ 200°C (hysteresis)
```

The engine reaches equilibrium when **heat gain = heat loss** — every throttle / altitude / cooling change moves the equilibrium temperature.

## Heat generation (Q_heat)

Only running fluid chambers generate heat, and only while the engine is running and not overheated:

```
Q_heat = running chambers × throttle × fuel.heat × BASE_HEAT_FLUID(30) × heatFactor(m_eff)
```

| Term | Meaning |
|---|---|
| `running chambers` | number of burning fluid combustion chambers |
| `throttle` | 0..1 — heat is proportional to throttle |
| `fuel.heat` | the fuel's `heat` multiplier from its datapack entry (see [Fuel](fuel.md)) |
| `BASE_HEAT_FLUID` | **30 °C/s** — the fluid chamber's base heat |
| `heatFactor(m_eff)` | the mixture heat factor (below) |

### Mixture heat factor

`m_eff = lever × autoRichness(pressure)` (see the [Fluid Combustion Chamber](fluid-combustion-chamber.md) page for the mixture lever and altitude auto-rich):

- **Lean** (`m_eff < 1`): `heatFactor = 1 + 2.0×(1 − m)²` — convex, burns hotter as you lean (discourages "always lean").
- **Rich** (`m_eff ≥ 1`): `heatFactor = max(0.7, 1 − 0.5×(m − 1))` — rich mixture absorbs heat and cools (floor 0.7).
- **Altitude auto-rich is free cooling**: at altitude the carburettor naturally enriches (`autoRichness` up to ×1.25), so `m_eff > lever` — the auto-rich part only cools (`heatFactor` down to ≈0.875), it never costs fuel.

The heat factor only scales **heat** — it never feeds back into fuel cost (economy factor multiplies fuel consumption only).

## Heat dissipation (K_total)

```
K_total = (K_CORE×length + K_AMB×running chambers + K_DUCT×ducts×shutter) × ram(speed) × f(pressure)
```

| Term | Meaning |
|---|---|
| `K_CORE` | **0.02 per core segment** — the engine's own passive cooling. **Always active, even when stopped** (that is why a stopped engine slowly cools down). |
| `K_AMB` | **0.05 per running chamber** — ambient convection over the running chambers. Stopped chambers do not count (a stopped engine only cools via `K_CORE`). |
| `K_DUCT` | **0.05 per cooling air duct** — the duct fins. Scaled by the cooling **shutter** (`setCooling`, see [Cooling Air Duct](cooling-air-duct.md)). |
| `shutter` | `coolingStrength` (0..1, default 1.0) — only shrinks the duct component (real cowl flaps). |
| `ram(speed)` | **Ram-air cooling**: 1.0 below 10 m/s, linear ramp to **×2.0 at 30 m/s** — flying fast cools the engine for free. Static (non-physics) blocks = 1.0. |
| `f(pressure)` | **Pressure factor**: `pressure^0.8` (clamped to a floor of 0.25). At altitude the air is thin → heat transfer is worse → **cooling is worse at altitude**. |

## Ambient temperature (altitude → temperature)

The engine reads the ambient temperature `T_amb` from its **world altitude Y**:

- **Physics bodies** use the body's world position Y (the sub-level origin); **static blocks** use the block's own Y.
- **Overworld** — piecewise-linear, Minecraft-scaled:

| Altitude Y | Ambient temperature |
|---|---|
| Y ≤ 63 (sea level) | constant **20°C** |
| 63 → 200 (cloud layer) | linear **20°C → 0°C** |
| 200 → 320 (world top) | linear **0°C → −40°C** |
| Y ≥ 320 | constant **−40°C** |

- **Nether**: constant **155°C at all heights** — a hot environment, equal to the economy target.
- **End**: constant **0°C at all heights** — a cold, still environment.

!!! note "Why Minecraft-scaled, not real lapse rate"
    The real troposphere lapse rate (≈0.0065°C/m) would only drop ~1.7°C across the whole 63→320 world height — almost no altitude effect. The Minecraft-scaled curve (sea level → clouds → world top) makes altitude actually matter for engine cooling.

## Pressure factor (altitude → pressure)

The pressure factor reuses the same air model as the avionics sensors (Sable's dimension atmosphere curve — anchors with piecewise cubic Hermite interpolation):

- Default overworld anchors: `(−38, 1.5) / (63, 1.0) / (263, 0.4493) / (280, 0.4198) / (320, 0)` — sea level = **1.0 atmosphere**, Y ≥ 320 = 0.
- `pressureFactor = max(pressure, 0.25)^0.8`.
- The same pressure also drives the **altitude auto-rich** (`autoRichness = 1 + 0.45×(1−pressure)`, clamped to [1.0, 1.25]) — see the [Fluid Combustion Chamber](fluid-combustion-chamber.md) page.

### Altitude gameplay summary

At altitude three things change at once:

| Effect | Direction | Result |
|---|---|---|
| Colder ambient (higher up) | helps cooling | lower equilibrium T |
| Thinner air → pressure factor drops | hurts cooling | higher equilibrium T |
| Auto-rich (free cooling) | helps cooling | heatFactor drops, no fuel cost |

The two cooling effects partially cancel; auto-rich is the player's free altitude-compensation tool (pull the mixture lever to `m_eff ≈ 1.0` to also earn the economy factor).

## Overheat & hysteresis (fluid engine)

- **T ≥ 220°C** → the engine hard-stops (no fuel burned) and sets `overheated`.
- **T ≤ 200°C** → it unlocks and can restart (hysteresis prevents rapid on/off cycling around the threshold).
- All thresholds (155°C economy target / 100°C overcooling / 220°C overheat) are **fixed engine constants** — they do not change with fuel or throttle, like a real engine's design point / thermostat.
