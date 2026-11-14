# Aero Bearing

![aero_bearing](../img/my_bearing_item.png)
> 1.0.9 added

The **Aero Bearing** is a Create kinetic block paired with a Sable physics body. Power enters through the **half-shaft on the back face** (axial input, like `create:mechanical_bearing`), and the assembled structure becomes an **independent physics body** (sub-level). The shaft is **through-going**: the input speed continues through the plate into the machinery inside the sub-level (input RPM = output RPM).

## Drive policy

| Item | Behaviour |
|---|---|
| **Power (back-face input RPM)** | Charges stress at impact **4.0** (same as `simulated:swivel_bearing`); passes the speed/stress through the plate to the machinery inside the sub-level; and is the **only** prerequisite for the plate to move. With zero input RPM the plate does **not** move, even if Lua commanded an angle. |
| **Angle command** | Set only by Lua `setTargetAngle()`. The plate does **not** teleport: it walks to the commanded angle at the "plate rotation speed" and then holds there. |
| **Lock mode** | **Locked** by default (as described above). Lua `setLocked(false)` switches to **unlocked**: the joint keeps only passive friction damping (`swivelBearingFriction`), so the plate can be moved freely by aerodynamic loads / collisions, and angle commands are not advanced. Re-locking starts from the current orientation, without a snap. |
| **Plate rotation speed** | Defaults to the **input RPM**, capped at **96 RPM** (a 256 RPM input still walks the plate at 96 RPM). Lua `setRotationSpeed(rpm)` pins it to any 0~96 RPM; once set to a non-zero value it stays pinned, pass `0` to go back to auto. |

> Sequenced angle inputs (`create:sequenced_gearshift`, hand crank, valve handle with `TURN_ANGLE`) do **not** turn the plate.

## Lua API

The peripheral type is `aero_bearing` (wrap by direction, e.g. `peripheral.wrap("right")`, or `peripheral.find("aero_bearing")`).

| Method | Description |
|---|---|
| `setTargetAngle(degrees)` | Set the commanded end angle (the only source of angle). Requires assembly **and** power to actually move. `mainThread=true` |
| `getTargetAngle()` | Current servo reference angle (degrees, currently walking to the target) |
| `getTargetAngleRad()` | Current servo reference angle (radians) |
| `getCommandedAngle()` | Commanded end angle (degrees) |
| `setRotationSpeed(rpm)` | Set the plate rotation speed (**0~96** RPM, clamped, absolute value); `0` = auto (`min(input RPM, 96)`). Returns the applied value. `mainThread=true` |
| `getRotationSpeed()` | Configured plate rotation speed (RPM); `0` means auto |
| `getInputSpeed()` | Input RPM — the power prerequisite for plate motion |
| `isControlMode()` | Always `true` (the angle is always Lua-controlled) |
| `setControlMode(enabled)` | No-op kept for old scripts, always returns `true` |
| `setLocked(enabled)` | Toggle locked / unlocked mode (behaviour identical to `simulated:swivel_bearing`): `true` = PD servo holds the target angle; `false` = friction damping only, freely movable by external forces. Returns the resulting state. `mainThread=true` |
| `isLocked()` | Whether the bearing is locked (default `true`) |
| `isAssembled()` | Whether a sub-level has been assembled |
| `assemble()` | Assemble the structure into a sub-level; returns whether it succeeded |
| `disassemble()` | Disassemble the sub-level back into world blocks; returns whether it succeeded |

```lua
local b = peripheral.wrap("right")

-- Assemble the structure in front of the bearing (the back face needs power,
-- otherwise the plate will not move)
print(b.assemble())            -- true if a structure was assembled

-- Default: the plate walks to the target angle at the input RPM
b.setTargetAngle(90)

-- Pin the plate rotation speed (this bearing now walks at 32 RPM)
b.setRotationSpeed(32)
b.setTargetAngle(-45)

-- Back to "plate speed = input RPM"
b.setRotationSpeed(0)

-- Lock / unlock (when unlocked the plate can be pushed around freely by airflow,
-- like a passive trim tab)
b.setLocked(false)
b.setTargetAngle(-45)          -- commands are held while unlocked, nothing walks
b.setLocked(true)              -- back to locked: starts from the current orientation, no snap

-- Query
print(b.getInputSpeed())       -- e.g. 64.0
print(b.getRotationSpeed())    -- 0.0 (auto)
print(b.getTargetAngle())      -- servo reference angle
print(b.getCommandedAngle())   -- commanded end angle
print(b.isLocked())            -- true
```

## Differences from `simulated:swivel_bearing`

| | swivel_bearing | Aero Bearing |
|---|---|---|
| Power input | Cogwheel meshed from the side | **Direct axial input** — shaft/network connects straight to the bearing axis |
| Stress network | Through-shaft (stress passes through) | **Also through** — the plate is a member of the same kinetic network, so both RPM and stress pass into the sub-level machinery |
| Angle control | Via network speed (incl. sequenced inputs, continuous rotation) | **Only via Lua commanded angle**, reached at a configurable plate rotation speed; does not move without power |
| Locking | Redstone signal + value-box `LockingSetting` | Same physics behaviour (locked = PD servo, unlocked = friction damping), toggled by Lua `setLocked` instead of redstone |
