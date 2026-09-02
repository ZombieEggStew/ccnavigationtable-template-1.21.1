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
