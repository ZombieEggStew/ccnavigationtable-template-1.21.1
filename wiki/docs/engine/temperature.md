# Temperature, Altitude & Cooling Model

The engine temperature is a continuous value computed **every tick on the server** by the engine controller using a **Newton cooling model**, then persisted in the controller's NBT. This page explains exactly how heat is generated and how it is dissipated — including the **altitude → temperature** and **altitude → pressure** relationships that drive it.

> Applies to the **fluid engine**. The **steam engine** does not use this model — its closed boiler self-regulates (see [Steam Power Chamber](steam-power-chamber.md)).

## The model at a glance

```
heat gain      Q_heat = Σ running chambers × heat load × fuel.heat × BASE_HEAT_FLUID × heatFactor(m_eff)
               heat load = max(actual network load, idle floor 0.2)
boil cooling   Q_evap = 1.5 × (T − 200)          <- non-zero only above 200°C; subtracted from the net gain, never inside K_total
heat loss      P_loss = K_total × (T − T_amb)
                 K_total = (K_CORE×length + K_AMB×running chambers + K_DUCT×ducts×shutter) × ram(speed) × f(pressure)
                                + k_water        <- water cooling module: no ram air, no pressure

integration    T += (Q_heat − Q_evap − P_loss) / C_th / 20      (per tick; C_th = C_TH_BASE × length)
clamp          T in [T_amb, OVERHEAT_TEMP × 1.2]
overheat       T ≥ 220°C → hard stop; resume at T ≤ 200°C (hysteresis)
coolant        running: 1 mB per 8 s per working module; boiling: whenever T > 200°C, 3.0×(T−200) mB/s per module
```

The engine reaches equilibrium when **heat gain − boil cooling = heat loss** — every change in **load**, altitude or cooling moves the equilibrium temperature. The throttle only affects it indirectly, by raising the machines' stress demand (and therefore the load).

## Heat generation (Q_heat)

Only running fluid chambers generate heat, and only while the engine is running and not overheated:

```
Q_heat = running chambers × heat load × fuel.heat × BASE_HEAT_FLUID(30) × heatFactor(m_eff)
```

| Term | Meaning |
|---|---|
| `running chambers` | number of burning fluid combustion chambers |
| `heat load` | = max(actual network load, idle floor 0.2) — **heat is proportional to load**, i.e. "downstream machines' stress used / engine capacity available" |
| `fuel.heat` | the fuel's `heat` multiplier from its datapack entry (see [Fuel](fuel.md)) |
| `BASE_HEAT_FLUID` | **30 °C/s** — the fluid chamber's base heat |
| `heatFactor(m_eff)` | the mixture heat factor (below) |

### Mixture heat factor

`m_eff = lever × autoRichness(pressure)` (see the [Fluid Combustion Chamber](fluid-combustion-chamber.md) page for the mixture lever and altitude auto-rich):

- **Single linear formula for both sides**: `heatFactor = 1 − (m_eff − 1)` (i.e. `2 − m_eff`), slope −1, no convex curve, no floor clamp.
- **Lean** (`m_eff < 1`): `heatFactor > 1`, hotter as you lean (`m_eff=0.8 → ×1.20`).
- **Rich** (`m_eff ≥ 1`): `heatFactor < 1`, cooler as you enrich (`m_eff=1.4 → ×0.60`).
- **Altitude auto-rich/lean is free heat control**: the carburettor meters by intake air volume — at altitude it naturally enriches (`autoRichness` up to ×1.25) which only cools (`heatFactor` down to ≈0.75 at full auto-rich); below sea level high pressure naturally leans (down to ×0.75) which only heats — it never costs fuel.

The heat factor only scales **heat** — it never feeds back into fuel cost (economy factor multiplies fuel consumption only).

### Load vs equilibrium temperature

**Load** is the stress network's "stress used / capacity available" (see the "Load" section of the [Fluid Combustion Chamber](fluid-combustion-chamber.md) page).

- Attaching more machines, or raising the speed (each machine's stress is ∝ speed), raises the load and therefore the equilibrium temperature.
- At zero load the engine still generates heat at the **idle floor (20%)** (a real engine's idle fuel consumption), so an idling engine settles at a "cool but warm" equilibrium instead of dropping to ambient.
- **Whether full load is sustainable depends on the cooling** — exactly the real rule that *maximum continuous power < maximum power*.

Example: sea level, static, **4 fluid chambers + 2 cooling air ducts** (`k_total = 0.38`, `C_th = 4`, `BASE_HEAT_FLUID = 30`):

| Load | Equilibrium T | Notes |
|---|---|---|
| 0% (idle, heat uses 20%) | ≈83°C | just below the 100°C working line |
| 25% | ≈99°C | just crosses the working line |
| 43% | ≈156°C | economy band 145~165 |
| 63% | ≈219°C | overheat threshold 220 |
| 100% | ≈336°C (clamped to 264) | full load while static inevitably overheats |

With the same 4+2 setup: at **30 m/s airspeed (ram ×2)** full load only reaches ≈178°C; at **Y≈260 (pressure factor ≈0.53, roughly half the heat transfer)** a 25% load already reaches ≈169°C and a 34% load hits the 220°C overheat line — at altitude you must lower the speed or attach fewer machines.

Formula: `T_eq = T_amb + running chambers × 30 × heat load / k_total`.

## Heat dissipation (K_total)

```
K_total = (K_CORE×length + K_AMB×running chambers + K_DUCT×ducts×shutter) × ram(speed) × f(pressure) + k_water
```

| Term | Meaning |
|---|---|
| `K_CORE` | **0.02 per core segment** — the engine's own passive cooling. **Always active, even when stopped** (that is why a stopped engine slowly cools down). |
| `K_AMB` | **0.05 per running chamber** — ambient convection over the running chambers. Stopped chambers do not count (a stopped engine only cools via `K_CORE`). |
| `K_DUCT` | **0.05 per cooling air duct** — the duct fins. Scaled by the cooling **shutter** (`setCooling`, see [Cooling Air Duct](cooling-air-duct.md)). |
| `shutter` | `coolingStrength` (0..1, default 1.0) — only shrinks the duct component (real cowl flaps). |
| `ram(speed)` | **Ram-air cooling**: 1.0 below 10 m/s, linear ramp to **×2.0 at 30 m/s** — flying fast cools the engine for free. Static (non-physics) blocks = 1.0. |
| `f(pressure)` | **Pressure factor**: `pressure^0.8` (clamped to a floor of 0.25). At altitude the air is thin → heat transfer is worse → **cooling is worse at altitude**. |
| `k_water` | **Water cooling module contribution** (below), **added outside the brackets** — immune to ram air and pressure. |

### Water cooling module (liquid cooling)

```
k_water = K_WATER(0.33) × Σ_modules [ min(amount / 4000, 1) × coolant efficiency ] × loop opening
```

- **Immune to ram air and pressure**: a liquid loop does not care about the air — so it backs up exactly the three cases where ducts are weakest (static, low speed, high altitude).
- **Amount curve**: full effect at ≥ 4000 mB; linear falloff below 4000 mB; 0 at 0 mB (smooth, no threshold flicker).
- **Loop opening**: Lua `setWaterCooling(0..1)` (default 1.0) only scales `k_water`; 0 = closed loop (and no evaporation either).
- **Boiling adds extra cooling** (above 200°C): `Q_evap = 1.5 × (T − 200)` — the latent heat carried away by vapour is subtracted from the net gain and **never enters `K_total`**, so it ignores ram air and pressure too. It buys **emergency** operation at high load, not a permanent mode: one full tank plus 4 full-load fluid chambers settles at ≈210°C and lasts **≈4.3 minutes** (the plateau is locked by that slope, the runtime by the drain rate).
- **Fluid engines only**: a steam engine pins its temperature at `BOILER_T_OPT`, so water cooling has no effect on it and consumes no coolant.
- Full details on the [Water Cooling Module](water-cooling-module.md) page.

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
- The same pressure also drives the **altitude auto-rich/lean** (`autoRichness = 1 + 0.45×(1−pressure)`, clamped to [0.75, 1.25]; auto-leans below sea level at high pressure) — see the [Fluid Combustion Chamber](fluid-combustion-chamber.md) page.

### Altitude gameplay summary

At altitude three things change at once:

| Effect | Direction | Result |
|---|---|---|
| Colder ambient (higher up) | helps cooling | lower equilibrium T |
| Thinner air → pressure factor drops | hurts cooling | higher equilibrium T |
| Auto-rich (free cooling) | helps cooling | heatFactor drops, no fuel cost |

The two cooling effects partially cancel; auto-rich is the player's free altitude-compensation tool (pull the mixture lever to `m_eff ≈ 1.0` to also earn the economy factor).

**Cooling capacity is the cap on how many machines you can drive**: heat is ∝ load, so wherever cooling is poor (static, high altitude, few ducts, shutter closed) the sustainable load is low. Keeping the load inside what the cooling can carry is how you cruise at altitude.

## Overheat & hysteresis (fluid engine)

- **T ≥ 220°C** → the engine hard-stops (no fuel burned) and sets `overheated`.
- **T ≤ 200°C** → it unlocks and can restart (hysteresis prevents rapid on/off cycling around the threshold).
- All thresholds (155°C economy target / 100°C overcooling / 220°C overheat) are **fixed engine constants** — they do not change with fuel, throttle or load, like a real engine's design point / thermostat.
- **Thermal tripping**: when the load sits at the top and the cooling cannot keep up, the engine cycles "overheat stop → cool to 200°C → generate again → overheat". That is thermal protection, not a bug — fix it by lowering the load (fewer machines / lower speed) or improving cooling (more ducts, open the shutter, fly faster, go lower).
