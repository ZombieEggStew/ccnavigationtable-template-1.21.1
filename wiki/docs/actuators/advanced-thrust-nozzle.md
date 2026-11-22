# Advanced Thrust Nozzle

![advanced_thrust_nozzle](../img//advanced_thrust_nozzle.png)

> A nozzle that can point **anywhere inside a 15° cone**

The **Advanced Thrust Nozzle** (`ccpe:advanced_thrust_nozzle`) is the upgraded version of the directional thrust nozzle. It mounts exactly the same way — on the airflow face of a Create `encased_fan`, still powered by the fan's RPM — except that the **nozzle body can tilt around a pivot at its base**. Thrust follows the **tilted** axis, so a single nozzle can push a craft in any direction inside the cone.

Tilting is driven by CC:Tweaked Lua in **two frames and three flavours**: **polar** (azimuth + tilt angle), **joystick** (`x`/`y` = `-1..1`), and **thrust semantics** (`x`/`y`/`z` = desired thrust direction) — the last one is what control code should use. None of them teleport: the nozzle sweeps to the new direction at a constant speed **in the deflection plane**, and the render, thrust, and exhaust/intake/contrail particles all animate together.

> ⚠️ Polar and joystick describe **where the nozzle deflects in the model frame** (i.e. where the exhaust points), **not where the thrust points** — thrust is the opposite, and the two lateral components do **not** share a sign. Control scripts should use `setThrustDirection(x, y, z)`; the reason is in "Frames: why joystick ≠ thrust" below.

## Compared to the directional nozzle

| | Directional nozzle `ccpe:thrust_nozzle` | Advanced thrust nozzle `ccpe:advanced_thrust_nozzle` |
|---|---|---|
| Thrust direction | FACING only | Anywhere inside a **15° cone** around FACING |
| Output per RPM | 1 pN/RPM | **2 pN/RPM** (same fan, twice the output) |
| Lua control | none (fan RPM only) | `setDeflection` / `setDirection` / `setThrustDirection` / `setThrustScale` |
| Channel | — | **Physics-body scoped**, addressable from Lua |
| Right-click GUI | — | Channel wheel |
| Exhaust / contrail / dye / wrench | ✅ | ✅ all inherited — and they follow the tilted axis |

## Placement

- It must occupy the **airflow face** of an `encased_fan` (same `canSurvive` check as `create:nozzle` and the directional nozzle). FACING is decided solely by the fan behind it.
- The fan's own thrust is **always 0** once a nozzle is installed there, so the nozzle is the only thrust source — nothing stacks.
- **Right-click with an empty hand** opens the channel GUI; **right-click with a dye** recolors the contrail particles only (goggles show the current color in the tooltip).
- **Sneak-right-click with a wrench** removes it instantly (drops go straight into your inventory). A normal wrench right-click does **not** rotate it — the only legal FACING is the fan's airflow face.

## Channel & addressing

The channel number is the nozzle's **address inside the physics body**, sharing one channel space with the [short-range signal linker](../sensor-system/short-range-linker.md) and the control desk (1:1 per channel within a body).

- The lowest free number on the chain is **assigned automatically** on placement; when two devices on the same chain claim one channel, the later one **defers** to the next free number.
- Devices register **only on physics bodies** (Sable sub-level, including the constraint chain). A nozzle sitting on the ground is not registered: `getChannel()` returns `-1` and the GUI shows "Must be placed on a physics body".
- The assignment is re-checked every 20 ticks, so bearings linking/unlinking (which merges or splits channel spaces) can push the number along.
- Leaving the physics body resets the channel to `-1`; re-mounting assigns a new one.
- Unlike the linker, the nozzle GUI has **no "load physics body" switch** — the nozzle holds no force-load ticket.

Address it from Lua through the same `ccpe.sensor_system` module:

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)        -- the nozzle on channel 0
print(n.getChannel(), n.getMaxTilt()) -- 0  15
```

## Lua API

Peripheral type: `advanced_thrust_nozzle` (obtained via `ccpe.sensor_system.getPeripheral(channel)`).

| Method | Returns | Description |
|---|---|---|
| `setDeflection(azimuthDeg, tiltDeg)` | boolean | **Polar form**: azimuth around the FACING axis (0–360, negatives wrap), tilt clamped to `0..15`. Only the target is written; the nozzle sweeps there in a straight line. Returns `false` for non-finite input |
| `setDirection(x, y)` | boolean | **Joystick form**: `x`/`y` ∈ `-1..1`, where the **direction** is the azimuth and the **length** maps (circularly) to the tilt angle (length 0 = straight, 1 = full 15°). Inputs are clamped to `-1..1` each and then to the unit circle, so `(1,1)` equals "45° direction at full tilt". Target-only, same straight-line motion |
| `getDeflection()` | table | `{azimuth, tilt, targetAzimuth, targetTilt, x, y, targetX, targetY}`: the first four are the **actual** values right now (different from the targets while moving), the targets come last. Azimuths are normalized to 0–360 (meaningless — reported as 0 — when the point sits at dead center); `x`/`y` are the actual deflection point in degrees (`tilt · (cos, sin) azimuth`), directly comparable with `setDirection` |
| `getMaxTilt()` | number | Tilt clamp (currently `15`), handy for normalizing scripts |
| `isMoving()` | boolean | Whether it is still animating (deflection or thrust scale not settled) |
| `getThrustDirection(x, y)` | table / nil | **Read-only thrust-semantics mirror**: takes the same arguments as `setDirection` and returns the **settled** thrust direction (unit vector `{x, y, z}` in the plot / body frame). The current output sign is already applied (`setInverted(true)` flips the reported direction). Meant for **self-checks / calibration** — compare it against what the script expects. Reports the steady state for the **target** deflection, not the in-flight value; returns `nil` for non-finite input or off the server |
| `setThrustDirection(x, y, z)` | boolean | **Write in thrust semantics (recommended for control code)**: pass the direction you want the craft **pushed toward** (plot / body frame, no need to normalize) and the deflection is solved for you. Only the component **perpendicular to the FACING axis** is used (for a downward nozzle: `(1,0,0)` = east, `(0,0,1)` = south), and the FACING lateral mirror, fan rotation sign and `setInverted` are all absorbed ⇒ **no re-calibration when you change the craft or the mounting**. Length is clamped to `0..1` and mapped to tilt (1 = full 15°); the axial component is ignored (tilt cannot reverse axial thrust — use `setInverted` for that); a zero vector returns `false` |
| `getProfile()` | table / nil | **Mounting and sign snapshot** (for debugging / logging): `{facing, inverted, speed, axisStep, thrustSign, maxTilt}`. `speed` is the signed RPM of the fan behind it (0 with no air source / not spinning), `axisStep` is `±1` for the FACING axis, and `thrustSign` is `κ = sign(axisStep · speed · inverted)` (impulse = `−axis · κ`; treated as `+1` when `speed = 0`) |
| `setThrustScale(scale)` | boolean | Output scale `0..1` (clamped), multiplied on top of "RPM × air pressure". **Also drives the exhaust and intake particles** (see "Output and particles"); animated along the same motion curve |
| `getThrustScale()` | number | Current output scale (`0..1`, the live value while animating) |
| `setInverted(enabled)` | boolean | **Output reversal (thrust ⇄ suction)**: `true` flips the **sign** of the force along the axis at the same RPM and output scale (forward thrust becomes suction; negative-RPM suction reverses too). **Instant, no animation**; only the sign flips — magnitude (RPM / pressure / output scale) and pointing direction are untouched — and the particle chain swaps with it (**exhaust ⇄ intake**). Stored in NBT / blueprints |
| `isInverted()` | boolean | Whether output is currently reversed (`true` = force along the axis is flipped, i.e. thrust acts as suction) |
| `getChannel()` | number | Channel of this nozzle inside the physics body (`-1` = not on a body / unassigned) |
| `getForcePoint()` | table | The nozzle's **force application point** `{x, y, z}` in the physics body's local frame, relative to the body origin (the centre-of-mass pivot) — use it as the moment arm `r` in `τ = r × F`. The point is the **block centre**, which is exactly where Sable applies the force (`propM*` in the flight recorder uses the same point); the nozzle mouth is this point plus `axis × 0.55` blocks, but the offset is collinear with the force, so both give the same torque. Same frame as `getSensors().pos` (that one is the block corner; this method adds +0.5). Constant while the body moves/rotates; returns `nil` when not on a body |

### Frames: why joystick ≠ thrust

The same nozzle is described by two coordinate systems, and **mixing them is the easiest mistake on this block**:

| Concept | Frame | Meaning |
|---|---|---|
| `setDeflection` azimuth / `setDirection` `x, y` / `getDeflection().azimuth` | **model frame** | Where the **nozzle / exhaust** deflects. With `FACING=down`: model `+X` → plot `+X`, model `+Z` → plot **`−Z`**, model `+Y` → plot `−Y` |
| `setThrustDirection` `x, y, z` / `getThrustDirection` return value | **plot / body frame** | Where the **craft is pushed** — same frame as `getForcePoint()` / `getSensors().pos` |

Three effects stack up so that "joystick" and "thrust" are not a simple negation:

1. **Thrust is the opposite of the exhaust direction** — tilt the exhaust east and the craft is pushed west;
2. the `FACING=down` model frame is **mirrored** against the plot frame: `x` happens to line up while `z` flips. **The `x` doc is accidentally correct in both frames, which is exactly what misleads people** into assuming `y` is too;
3. the output sign `κ = sign(axisStep · fan RPM · inverted)` flips everything once more.

The net result (`FACING=down`, negative RPM, not inverted, i.e. `κ = +1`) in the plot frame is:

```
thrust = (−stick x, +stick y)        -- the two components do not share a sign
```

This is not a typo: flipping the axis requires a 180° rotation, and its determinant on the perpendicular plane is always −1 (it is a **reflection**, not a rotation), so the lateral mapping between "stick" and "thrust" **always** contains one mirror — the two components can never share a sign.

> Hand-negating therefore only works for **four downward nozzles on the same wiring**. `setThrustDirection(x, y, z)` moves all of those signs into Java so control code never hard-codes them; when in doubt, read the real signs with `getProfile()` instead of deriving them.

### Example

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)

-- polar form: azimuth 90° at full 15° tilt (sweeps there in ~0.19 s)
n.setDeflection(90, 15)
n.setThrustScale(0.5)                  -- half output (thrust and exhaust/intake halve together, also animated)
print(n.getDeflection().tilt, n.getThrustScale())

-- wait until it settles
while n.isMoving() do sleep(0.05) end
print(n.getDeflection().azimuth, n.getDeflection().targetAzimuth) -- equal

-- joystick form: direction = azimuth, length = tilt (goes straight)
n.setDirection(1, 0)                   -- full tilt toward model +X (= setDeflection(0, 15))
n.setDirection(0, -1)                  -- full tilt toward model −Z (= setDeflection(270, 15))
n.setDirection(0.5, 0)                 -- toward +X at half tilt (7.5°)
n.setDirection(0, 0)                   -- back to straight

-- [recommended] thrust semantics: x / y / z = the direction you want to be pushed
n.setThrustDirection(1, 0, 0)          -- full push east (downward nozzle; ignores FACING / inv / RPM)
n.setThrustDirection(0, 0, 1)          -- full push south
n.setThrustDirection(0.1, 0, 0)        -- push east at 1.5° (0.1 = 1.5 / 15, handy for small-angle calibration)
n.setThrustDirection(0, 1, 0)          -- the axial direction of a downward nozzle ⇒ straight out (axial part ignored)

-- self-check / calibration: given a stick input, where does thrust actually point?
local d = n.getThrustDirection(1, 0)   -- for a downward nozzle with κ=+1: {x≈-0.259, y≈0.966, z≈0}
print(d.x, d.y, d.z)

-- mounting snapshot: print this first when debugging
local prof = n.getProfile()            -- {facing="down", inverted=false, speed=-256, axisStep=-1, thrustSign=1, maxTilt=15}
print(prof.facing, prof.speed, prof.thrustSign)

-- output reversal (thrust ⇄ suction): instant, exhaust switches to intake
n.setInverted(true)                    -- force flips along the axis
print(n.isInverted())                  -- true
n.setInverted(false)                   -- back to normal direction

-- force application point (body frame, for torque): the body origin ≈ COM, so use it as r directly
local p = n.getForcePoint()            -- {x=…, y=…, z=…}; nil when not on a body
-- torque τ = r × F, where F is this nozzle's thrust in the body frame (along the tilted axis,
-- sign set by RPM / setInverted)
```

For `setDeflection` / `setDirection`, azimuth 0° is the **model's** **+X** direction and 90° is **+Z** (`(cos, sin)` maps to `(x, y)`), and the whole frame rotates with the block's FACING; `setThrustDirection` / `getThrustDirection` use the **plot / body frame** instead (see "Frames: why joystick ≠ thrust" above).


## Thrust model

```
thrust = fan RPM × air pressure × thrust scale × 2
```

- **Full output on a vector nozzle = 2 pN/RPM**; pressure comes from `DimensionPhysicsData` (1.0 at sea level, dropping with altitude).
- Sign convention (uniform across all six FACINGs): **thrust is always opposite the airflow direction**, and the airflow direction = tilted axis × `κ` (`κ = axisStep · RPM sign · inverted`). Thanks to the `axisStep` compensation every mounting collapses to one rule: **a vertical nozzle always pushes down at positive RPM and up at negative RPM**, whichever way it is mounted; horizontal nozzles likewise push toward the negative side of their axis. `κ > 0` renders exhaust, `κ < 0` renders intake. When unsure, read `axisStep` / `speed` / `thrustSign` from `getProfile()`.
- Thrust is **never occlusion-checked** — a blocked nozzle still pushes (same as the directional nozzle).
- The thrust scale **also drives the exhaust and intake particles** (see below), but **not the high-altitude contrail**.
- `setInverted(true)` only flips the **sign** (thrust ⇄ suction): magnitude and every other parameter stay identical, and the particle chain swaps from exhaust to intake in the same step.

| Fan RPM | Pressure | `setThrustScale` | Thrust |
|---|---|---|---|
| 256 RPM | 1.0 | 1.0 | **512 pN** |
| 256 RPM | 1.0 | 0.5 | 256 pN |
| 128 RPM | 1.0 | 1.0 | 256 pN |
| 256 RPM | 0.5 | 1.0 | 256 pN |


## Tuning constants

| What you want to change | Constant | Current value |
|---|---|---|
| Deflection speed (animation and real thrust together) | `AdvancedThrustNozzleBlockEntity.DEFLECTION_DEG_PER_SECOND` | 160°/s (= 8°/tick) |
| Vector nozzle output | `AdvancedThrustNozzleBlockEntity.THRUST_OUTPUT_MULTIPLIER` | 2.0 (2 pN/RPM) |
| Thrust-scale ramp | `AdvancedThrustNozzleBlockEntity.THRUST_SCALE_PER_TICK` | 0.25/tick |
| Maximum tilt angle | `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` | 15° |
| Tilt pivot | `AdvancedThrustNozzleRenderer.PIVOT` | (8, 5, 8) px |
| Selection / collision box | `AdvancedThrustNozzleBlock.BASE_SHAPE` | `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)` |

