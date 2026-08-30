# Flight Management Computer

![fmc](../img/fmc_item.png)

## FMC gate

The physics body must have **at least 1 FMC** (`ccpe:fmc`) installed, otherwise all the methods below return `nil`. They also return `nil` when the computer is not on a body, or the underlying physics data is unavailable.

| Method | Returns | Description |
|---|---|---|
| `getPhysicsCenterOfMassRel()` | table / nil | Center of mass **relative to the current computer**, body-local `{x, y, z}` |
| `getPhysicsMass()` | number / nil | Mass of the computer's physics body (kg) |
| `getPhysicsChainMass()` | number / nil | Total mass of the body **including all constraint chains** (kg) |
| `getPhysicsGravityForce()` | number / nil | Gravity force of the body (pN = mass × 11) |
| `getPhysicsChainGravityForce()` | number / nil | Total gravity force of the whole chain (pN = chain mass × 11) |

## Center of mass semantics

`getPhysicsCenterOfMassRel()` returns the **body-local** (plot-frame) offset of the center of mass from the computer:

```
重心相对电脑 = (重心相对物理体原点的偏移) − (电脑相对物理体原点的偏移)
```

- Same frame as the `pos_rel` field in `getSensors()` — **it does not change** as the body moves or rotates, which makes it stable for identifying where the center of mass sits on the vehicle (e.g. how far forward/up it is from the cockpit).
- Use `getOrientation()` to rotate this vector into the world frame if needed.

## Gravity

Gravity force is a **scalar** (magnitude, pointing down) computed as:

```
gravity (pN) = mass (kg) × 11
```

`getPhysicsGravityForce()` uses the mass of the computer's own body; `getPhysicsChainGravityForce()` uses the chain total (see `getPhysicsChainMass()`).

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

-- Center of mass relative to this computer (body-local, stable under rotation)
local com = ss.getPhysicsCenterOfMassRel()
if com then
    print("COM rel to comp:", string.format("x=%.2f y=%.2f z=%.2f", com.x, com.y, com.z))
end
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
