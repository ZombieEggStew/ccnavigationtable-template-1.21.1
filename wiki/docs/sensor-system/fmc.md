# Flight Management Computer

![fmc](../img/fmc_item.png)

> A core capability of the Flight Management System (FMS) is estimating the aircraft's current **mass and centre of gravity in real time from flight data**, rather than relying only on numbers the crew entered beforehand.

>- **Mass estimation**: in level flight, lift must equal weight. The FMS infers the current mass from the actual flight response — angle of attack, pitch trim and thrust: the heavier the aircraft, the more angle of attack or thrust is needed to hold the same flight path.
>- **CG estimation**: the centre of gravity determines the required trim state, so the FMS can infer it from the elevator/stabilizer trim position; combining both yields an estimate of the aircraft's mass and CG.
>- **Continuous correction**: before takeoff the crew enters the takeoff weight (fuel + payload); in flight the FMS keeps refining the estimate using fuel flow and measured flight characteristics.

> Airbus displays the real-time estimated gross weight (GW) and centre of gravity (CG) on the MCDU performance page, dynamically updating the optimum cruise altitude, fuel predictions and approach speeds without the pilot having to enter an exact weight; Boeing's FMS has a similar weight-estimation mechanism.

## FMC gate

The physics body must have **at least 1 FMC** (`ccpe:fmc`) installed, otherwise all the methods below return `nil`. They also return `nil` when the computer is not on a body, or the underlying physics data is unavailable.

| Method | Returns | Description |
|---|---|---|
| `getPhysicsCenterOfMassRel()` | table / nil | Center of mass **relative to the block center of the most recently placed FMC** (AIC counts as FMC), body-local `{x, y, z}` |
| `getPhysicsChainCenterOfMassRel()` | table / nil | Total center of mass of the whole chain, body-local `{x, y, z}` **relative to the block center of the most recently placed FMC** (AIC counts as FMC) |
| `getPhysicsMass()` | number / nil | Mass of the computer's physics body (kg) |
| `getPhysicsChainMass()` | number / nil | Total mass of the body **including all constraint chains** (kg) |
| `getPhysicsGravityForce()` | number / nil | Gravity force of the body (pN = mass × 11) |
| `getPhysicsChainGravityForce()` | number / nil | Total gravity force of the whole chain (pN = chain mass × 11) |
| `getStressRemaining()` | number / nil | Remaining stress (su) of the Create stress network the **most recently placed FMC's attached block** belongs to (negative when overstressed) |
| `getStressCapacity()` | number / nil | Total stress capacity (su) of that network |

## Center of mass semantics

`getPhysicsCenterOfMassRel()` returns the **body-local** (plot-frame) offset of the center of mass from the **block center of the most recently placed FMC** on the body (including constraint chains; with several FMCs/AICs, the last one placed wins):

```
COM relative to FMC block center = (COM relative to the physics body origin) − (FMC block center relative to the physics body origin)
```

The FMC reference point is its **`BlockPos` (corner) plus half a block (`+0.5`)**, i.e. the center of the block cell, not the block corner. Both offsets go through the Sable conversion `plot − rotationPoint` (same frame as the `pos` field in `getSensors()`), so the result **does not change** as the body moves or rotates — stable for identifying where the center of mass sits on the vehicle (e.g. how far forward/up it is from the FMC's center).

!!! note "Body origin = center of mass"
    Sable keeps the physics body origin (`rotationPoint`) in sync with the center of mass at runtime, so the first term above is ≈ 0 and this value is ≈ **the FMC block center's own offset from the body origin, negated** (i.e. where the COM sits relative to the FMC's center).

- Use `getOrientation()` to rotate this vector into the world frame if needed.

### Chain center of mass

`getPhysicsChainCenterOfMassRel()` returns the **total center of mass** of the whole physics chain (including constraint connections such as bearings; always including the computer's body), as a body-local offset **relative to the block center of the most recently placed FMC** on the body (including constraint chains; with several FMCs/AICs, the last one placed wins):

```
chain COM rel. to FMC block center = (chain COM rel. to the computer's body origin) − (FMC block center rel. to the computer's body origin)
```

The first term is the mass-weighted average of each body's COM in world Σ(mᵢ·comᵢ)/Σmᵢ, inverse-transformed into the computer's plot frame − rotationPoint; the second term uses the same reference point as `getPhysicsCenterOfMassRel()` (the FMC's `BlockPos` corner plus half a block). The result is in the same frame as `pos` in `getSensors()` and stable under body motion/rotation.

Sable has no built-in chain COM API (`MergedMassTracker` only merges a single body itself plus the contraptions in its plot); this mod computes the value on the server every tick. Gated exactly like `getPhysicsChainMass()` (the body — including constraint chains — must have ≥ 1 FMC).

## Gravity

Gravity force is a **scalar** (magnitude, pointing down) computed as:

```
gravity (pN) = mass (kg) × 11
```

`getPhysicsGravityForce()` uses the mass of the computer's own body; `getPhysicsChainGravityForce()` uses the chain total (see `getPhysicsChainMass()`).

## Attached block stress network

`getStressRemaining()` and `getStressCapacity()` read the **Create stress network** of the block the **most recently placed FMC** (AIC counts as FMC) is attached to:

- **attached block** — the block on the FMC's support face (FMC: the face determined by its `FACE`/`FACING` blockstate; AIC: the block behind its `FACING` direction). The attached block must be a Create **kinetic block** (`KineticBlockEntity`, e.g. a gearbox, shaft or propeller bearing), otherwise both methods return `nil`.
- **`getStressCapacity()`** — total capacity of the network, in stress units (su).
- **`getStressRemaining()`** — remaining stress, `capacity − current stress` (su). Negative when the network is **overstressed**.

Both methods are gated exactly like the rest of the FMC methods (the body — including constraint chains — must have ≥ 1 FMC, and the computer must be on a body). The reading refreshes once per tick.

## Example

```lua
local ss = require("ccpe.sensor_system")

if not ss.isOnBody() then
    error("computer not on a physics body")
end

print("mass (kg):        ", ss.getPhysicsMass())
print("chain mass (kg):  ", ss.getPhysicsChainMass())
print("gravity (pN):     ", ss.getPhysicsGravityForce())
print("chain gravity(pN):", ss.getPhysicsChainGravityForce())

-- Center of mass relative to the most recently placed FMC (body-local, stable under rotation)
local com = ss.getPhysicsCenterOfMassRel()
if com then
    print("COM rel to FMC:", string.format("x=%.2f y=%.2f z=%.2f", com.x, com.y, com.z))
end

-- Create stress network of the block the last FMC is attached to (su)
print("stress capacity (su): ", ss.getStressCapacity())
print("stress remaining (su):", ss.getStressRemaining())
```
## Propeller speed tool

The FMC also provides a propeller speed solver (also FMC-gated): given the desired thrust and current flight state, it inverts the propeller physics model to compute the rotation speed the propeller bearings should output. The tool depends on aeronautics' propeller physics config.

### initPropeller(N, S)

Must be called once before use:

```lua
-- N = number of propellers (Propeller Bearings)
-- S = number of power blocks per propeller (sails / sym sym sails / wool blocks)
local ok = ss.initPropeller(N, S)
```

| Argument | Description |
|---|---|
| `N` | Number of propellers (≥ 1) |
| `S` | Number of power blocks per propeller (≥ 1) |

Returns `true` on success; returns `false` when the **body (including constraint chains) has no FMC** (gate fails) or the arguments are invalid.

### getPropellerRPM(F, P, V, θ?)

```lua
-- F = desired thrust; P = air pressure (sea level = 1.0); V = velocity (m/s)
-- θ = angle between the propeller plane and the velocity direction (degrees, optional, default 0)
local rpm = ss.getPropellerRPM(F, P, V, thetaDeg)
```

Formula (inverted from the aeronautics thrust/airflow model):

```
R = F / (P × S^1.5 × N × T) + V × sin(θ) / (S^0.5 × A)
```

where **T** (Propeller Bearing Thrust, default 0.2) and **A** (Propeller Bearing Airflow, default 0.05) come from the aeronautics config (`aeronautics > server > Physics`). The values are cached once when entering the game (server start) and when an FMC is placed/loaded (static cache, not refreshed per tick) — after changing the config in-game, re-enter the world or re-place an FMC for the new values to take effect.

Returns the required speed R; returns `nil` when not initialized, when the gate fails (no FMC), or when arguments are invalid (e.g. `P ≤ 0`).

> Feed `getPressure()` (static port reading) as pressure and `getSpeed()`/`getAverageSpeed()` (pitot tube readings) as velocity to build a closed-loop thrust controller.

## Altitude ↔ pressure conversion tools

Also FMC-gated (and therefore also available with an AIC), the sensor system provides two pure conversion utilities between **world altitude Y** and **air pressure**, using exactly the same atmosphere model as the game physics (and the static port reading `getPressure()`):

| Method | Returns | Description |
|---|---|---|
| `getPressureFromAltitude(Y)` | number / nil | Air pressure (fraction of sea level, sea level = 1.0) at world altitude `Y` |
| `getAltitudeFromPressure(P)` | number / nil | World altitude `Y` that has air pressure `P` (inverse of the above) |

Both are gated exactly like the FMC methods (the body — including constraint chains — must have ≥ 1 FMC, with AIC counting as FMC; the computer must be on a body), otherwise they return `nil`. They are **pure math** (`mainThread = false`): they read a cached copy of the atmosphere curve and never touch the world on the computer thread.

### Calculation formula

The game atmosphere is `P(Y) = basePressure × pressureCurve(Y)`, where the curve is a **piecewise cubic Hermite** interpolation over anchor points loaded from the dimension's `dimension_physics` datapack:

```
getPressureFromAltitude(Y) = basePressure × Hermite(anchors, Y)
```

Default overworld anchors (`basePressure = 1.0`, sea level = 63):

| Altitude Y | Value | Slope |
|---|---|---|
| −38.37 | 1.5 (underground clamp) | −0.006 |
| 63 | 1.0 | −0.004 |
| 263 | 0.4493 | −0.001797 |
| 280 | 0.4198 | −0.001679 |
| 320 | **0** (build limit) | −0.02099 |

Between −38 m and ~280 m this is numerically identical to the simple exponential `P ≈ e^(−0.004·(Y − 63))`. Above 280 m the curve bends down toward **0 at the build limit (Y = 320)** and stays 0 above it — at the default overworld ceiling there is **no air** (no lift / drag / thrust). The curve is **not analytically invertible** (piecewise cubic), so the inverse is computed numerically:

```
getAltitudeFromPressure(P) = bisection on [dimension.minY, minY + logicalHeight]  →  Y with  P(Y) ≈ P
```

The bisection is monotonic and exact to double precision, so the two methods round-trip: `getAltitudeFromPressure(getPressureFromAltitude(Y)) ≈ Y`. The altitude datum is **world Y** — identical to `getAltitude()`.

### What is cached

To keep `mainThread = false` thread-safe, the curve parameters are copied once into a **static volatile snapshot**:

- `basePressure`
- the anchor points `{altitude, value, slope}` (5 triples by default)
- the bisection bounds `[minY, minY + logicalHeight]`

The snapshot is refreshed **once** — at server start (using the overworld) and whenever an **FMC or AIC is placed/loaded** (`onLoad`) — mirroring the propeller config cache, **not** every tick. The gate (does this body have an FMC/AIC right now) is still checked every tick.

!!! note "Cache freshness"
    After `dimension_physics` datapack changes (`/reload`), re-place/reload an FMC or AIC (or restart the world) to refresh the snapshot. The cache is global (one curve shared by all computers): with multiple bodies in different dimensions the last-loaded dimension's curve wins.

## Sail aero tools

Also FMC-gated (and therefore also available with an AIC), the sensor system provides two pure-math utilities that compute the **lift** and **directionless drag** a sail block produces under the game's aero model for a given air pressure and speed — handy for sizing wings/tails and for design math (trim, cruise-speed estimates) without needing live flight data.

The formulas mirror Sable's `BlockSubLevelLiftProvider.sable$contributeLiftAndDrag()` (evaluated once per physics substep, per sail block). Both tools assume **n·v = 0** — the airflow is perpendicular to the sail normal, i.e. **no normal velocity component** (level flight). Under this condition the normal (parallel) drag is zero, so the outputs reduce to lift + directionless drag only:

| Method | Returns | Description |
|---|---|---|
| `getSailLiftAndDrag(P, V)` | table / nil | Regular sail (Create `SailBlock`): `{lift, drag, lift_impulse, drag_impulse}` |
| `getSymmetricSailDrag(P, V)` | table / nil | Symmetric sail (Simulated `SymmetricSailBlock`): `{drag, drag_impulse}` |

Arguments and conventions:

- **`P`** — air pressure (fraction of sea level, sea level = 1.0, same semantics as `getPressure()`); `P ≤ 0` → `nil`.
- **`V`** — speed magnitude (m/s, same units as `getSpeed()`); negative values are taken as `|V|`.
- Gated exactly like the other FMC tools (the body — including constraint chains — must have ≥ 1 FMC, AIC counting as FMC; the computer must be on a body), otherwise `nil`. Pure math (`mainThread = false`), zero main-thread scheduling.

### Returned values

Each field comes in **two time bases**:

| Field | Meaning |
|---|---|
| `lift` / `drag` | **Per-second equivalent force** = `k·P·|V|` — substep-independent, same scale as the in-game diagram (impulse × 60), comparable to thrust readings |
| `lift_impulse` / `drag_impulse` | **Per-physics-substep impulse** = `k·P·|V|·Δt` — the raw value Sable actually adds to the linear impulse; same scale as the flight-data recorder's CSV force-group columns |

### Formulas

Regular sail (Create `SailBlock`, all Sable defaults):

```
lift = k3 × P × |V| = 0.475 × P × |V|
drag = k2 × P × |V| = 0.06888202261 × P × |V|
```

Symmetric sail (Simulated `SymmetricSailBlock`: `k3 = 0`, `k1 = 1.75`; `k2` not overridden):

```
drag = k2 × P × |V| = 0.06888202261 × P × |V|
```

The two sails share **k2 = 0.06888202261** (Sable's default, `(−0.75 + √(0.75² + 0.475²)) / 2` — exactly the minimum damping that keeps the default lift from diverging). The normal-drag coefficient **k1** (0.75 regular / 1.75 symmetric) never appears here because it multiplies `(n·v)`, which is 0 by the tool's condition. With n·v = 0 the lift also takes its **maximum** for the given speed (`|V − parallel drag| = |V|`); any incidence/yaw component would only reduce it.

The impulse variants multiply by **Δt = 1/20/substepsPerTick** (Sable `PhysicsConfigData.substepsPerTick`, configurable 1–10, default 2 → Δt = 0.025 s). The per-second `lift`/`drag` are independent of that config.

### What is cached

The coefficients (k2, k3) are hard-coded constants in the mod. The substep time **Δt** is cached once — at server start and whenever an FMC or AIC is placed/loaded (`onLoad`), exactly like the atmosphere-curve snapshot; if it cannot be read, the Sable default (2 substeps/tick → Δt = 0.025 s) is kept. The gate is still checked every tick.

### Example

```lua
local ss = require("ccpe.sensor_system")

-- Regular sail (wing): lift + directionless drag at P = 0.47, 60 m/s
local sail = ss.getSailLiftAndDrag(0.47, 60)
print("lift (N):     ", sail.lift)          -- 0.475 × P × V  (per-second force)
print("drag (N):     ", sail.drag)          -- 0.06888202261 × P × V
print("lift impulse: ", sail.lift_impulse)  -- per physics substep (CSV scale)
print("drag impulse: ", sail.drag_impulse)  -- per physics substep (CSV scale)

-- Symmetric sail (tail / rudder): pure drag
local sym = ss.getSymmetricSailDrag(0.47, 60)
print("sym drag (N): ", sym.drag)
print("sym impulse:  ", sym.drag_impulse)
```

> Feed `getPressure()` as `P` and `getSpeed()` (or `getAverageSpeed()`) as `V` to evaluate the sails at the current flight state; multiply by the number of sail blocks to size a whole wing/tail.

## Universal drag tool

Also FMC-gated (and therefore also available with an AIC), the sensor system provides a pure-math utility that computes the **equivalent force of the universal (speed) drag** — the constant velocity damping Rapier applies to every sublevel rigid body. It is not part of any force group, so the Contraption Diagram and the flight-data-recorder CSV never show it; this tool makes it computable for design math (net-force balance: thrust − sail drag − universal drag ≈ 0).

The formula mirrors the continuous approximation of the per-substep damping `v ← v/(1+d·Δt)`:

```
dv/dt = −d·v  →  equivalent force F = −m·d·v   (magnitude = m × d × |V|)
```

| Method | Returns | Description |
|---|---|---|
| `getUniversalDragForce(m, V)` | number / nil | Equivalent universal-drag force scalar = `m × d × |V|` |

Arguments and conventions:

- **`m`** — mass (kg, same units as `getPhysicsMass()` / `getPhysicsChainMass()`); `m ≤ 0` → `nil`.
- **`V`** — speed magnitude (m/s, same units as `getSpeed()`); negative values are taken as `|V|`.
- **`d`** — the universal-drag coefficient, **default 0.09** (Sable `DimensionPhysics.DEFAULT_UNIVERSAL_DRAG`), overridable per dimension via the `dimension_physics` datapack's `"universal_drag"` field.
- Gated exactly like the other FMC tools (the body — including constraint chains — must have ≥ 1 FMC, AIC counting as FMC; the computer must be on a body), otherwise `nil`. Pure math (`mainThread = false`), zero main-thread scheduling.

Unlike the sail tools the result is a single per-second force (the continuous approximation is already time-normalized, no substep Δt involved) — it does **not** scale with air pressure, only with mass and speed.

### What is cached

The coefficient **`d`** is cached once — at server start and whenever an FMC or AIC is placed/loaded (`onLoad`), exactly like the atmosphere-curve snapshot; if it cannot be read, the Sable default (0.09) is kept. The gate is still checked every tick.

### Example

```lua
local ss = require("ccpe.sensor_system")

-- Equivalent universal drag at the current mass and speed (d = 0.09 by default)
local drag = ss.getUniversalDragForce(ss.getPhysicsChainMass(), 60)
print("universal drag (N):", drag)   -- m × 0.09 × V
```

> Combine with `getPhysicsMass()`/`getPhysicsChainMass()` for mass and `getSpeed()` (or `|v|`) for speed to close the force balance: `thrust − sail drag − universal drag ≈ 0` in steady cruise. Sanity check with recorded values (e.g. m ≈ 45.25 kg, v ≈ 62.6 m/s → F ≈ 255 N).
