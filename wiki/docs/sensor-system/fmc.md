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

These methods require **an FMC (`ccpe:fmc`) installed on the body**; the INS-gated attitude/physics methods are documented on the [Inertial Navigation System](ins.md) page.
