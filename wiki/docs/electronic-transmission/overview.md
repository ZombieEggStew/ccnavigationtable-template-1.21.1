# Electronic Transmission

![Electronic Transmission](../img/transmission_peripheral_v.png)

## Transmission Mode

> **What's the difference from create:RotationSpeedController?**

> - When using Create's rotation speed controller as a peripheral and calling `setTargetSpeed()`, it triggers `RotationPropagator.handleRemoved()` which cascades and clears the source of the entire downstream sub-network, leading to unexpected results (e.g. using aeroworks' stepper_servo downstream of the speed controller — changing the speed while activating the stepper motor makes the motor spin erratically).
> - Meanwhile, simulated's analog_transmission is hard to fine-tune.

!!! info "Note"
    This block also has a [**Servo Mode**](servo-mode.md): the output shaft can be positioned at an absolute angle (±180°, shortest path) via Lua.

The **Electronic Transmission** is a Create kinetic transmission controlled purely by the CC:T peripheral. **It does not accept redstone signals** and can only be controlled via Lua. It can be placed in the middle of a stress network to adjust the downstream speed in real time.

| Method | Description |
|---|---|
| `setRatio(ratio)` | Set the gear ratio (≥0), ratio mode `mainThread=true` |
| `getRatio()` | Get the current gear ratio |
| `setTargetSpeed(speed)` | Directly set the downstream speed (0~256.00) `mainThread=true` |
| `getTargetSpeed()` | Get the target speed |

```lua
local t = peripheral.find("ccpe:transmission_peripheral")

-- Ratio mode: downstream = upstream × ratio
t.setRatio(0.5)   -- Slow the downstream to 50%
t.setRatio(3.0)   -- Speed the downstream up 3× (capped at 256 RPM)

-- Target mode: directly set the downstream speed (0~256, 2 decimal places)
t.setTargetSpeed(128.56)
print(t.getTargetSpeed())  -- 128.56

-- Query the current state
print(t.getRatio())
```

## Servo Mode

The **Transmission Peripheral** can act as a servo: instead of relaying rotation
continuously, its output shaft is positioned at an **absolute angle** within ±180°
(single turn, shortest path), controlled purely via Lua.

It still needs input power: the output moves at the input speed (or a
Lua-overridden speed), so without power the servo simply stays still.

## Lua API

| Method | Description |
|---|---|
| `setServoMode(enabled)` | Enable / disable servo mode (`mainThread=true`). **Enabling always re-homes to 0°** (the current position is redefined as 0°, no rotation) — including when already in servo mode. |
| `getServoMode()` | Whether servo mode is currently active |
| `setServoAngle(degrees)` | Position the output shaft at `degrees` (±180, shortest path). When called in transmission mode it auto-enters servo mode (which also re-homes to 0° first). |
| `getServoAngle()` | Current **server-authoritative** angle (accurate; synced every tick) |
| `setServoSpeed(rpm)` | Output speed in RPM (0~96). `0` = use the input speed. Values above 96 are clamped to 96. |
| `getServoSpeed()` | The configured output speed (`0` means "use input speed") |
| `getServoReaim()` | Whether same-direction re-aim is enabled (default `false`) |
| `(1.0.9) setServoReaim(enabled)` | Enable / disable same-direction re-aim. It reacts faster to same-direction target changes but can mis-position under rapidly-changing inputs near the ±180° boundary (see warning below), so it is **off by default**. |
| `resetServo()` | **Re-home**: redefine the current position as 0° and set the target to 0° — **no rotation happens**. If not in servo mode it enters servo mode first. |

While in servo mode, `setRatio` / `setTargetSpeed` are rejected and return
`false`. Call `setServoMode(false)` to return to transmission mode.

## Behavior notes (segment-based motion)

The servo propagates exact angles downstream through a Create
`SequenceContext` segment state machine (same approach as Create Propulsion
Simulated's tilt adapter), which is flicker-safe:

- **±180° is a single position**: `+180°` and `-180°` are the same physical
  point. If the output is already at `-180°` and you call `setServoAngle(180)`,
  it is already there — nothing moves.
- **Same-direction target changes apply immediately (re-aim)**: if you change
  the target *in the same direction* while moving (e.g. 0°→90°, then change to
  120° mid-move), the current segment is extended/shortened to the new target
  on the same tick — no wait for the segment to finish, no flicker.

!!! warning "(1.0.9)Re-aim can mis-position under rapidly-changing inputs"
    With rapidly-changing targets near the ±0° boundary, re-aim canrepeatedly rewrite the segment end and mis-position. Re-aim is **off by default** for this reason.

- **Reversal still waits for the segment to end**: changing the target to the
  *opposite* direction mid-move is deferred to the next segment boundary (each
  segment is at most 179°) instead of reversing mid-move. At 96 RPM a segment
  takes ~6 ticks, so a reversal waits at most ~0.3 s.
- **Power loss resumes**: if input power drops while moving, the servo stops;
  when power returns it keeps going toward the target (it does not forget it).
- **Flicker-safe**: re-attaching to the rotation network is deferred until
  Create's flicker score is below the threshold, so rapid repositioning cannot
  destroy the block.
- **Goggle tooltip**: wearing Create goggles shows the current mode — in
  transmission mode the ratio / target speed and the output speed; in servo
  mode the current angle, target angle, and the re-aim toggle state
  ("Segment Re-aim: On/Off").

## Why 96 RPM?

Above 96 RPM a 180° move would finish in ~2 ticks, which is visually indistinguishable and would trigger certain features that require extra handling, so the effective speed is capped at **96 RPM**.

- `setServoSpeed(128)` → clamped to 96.
- If the input power exceeds 96 RPM, the effective speed is also capped to 96.

## Example

```lua
local t = peripheral.find("ccpe:transmission_peripheral")

t.setServoMode(true)       -- enter servo mode and re-home to 0° (no rotation)
t.setServoSpeed(0)         -- move at the input speed
t.setServoAngle(90)        -- rotate the output shaft to +90°
print(t.getServoAngle())   -- 90.0 (server-authoritative)

t.setServoAngle(135)       -- re-aim mid-move: if still rotating toward 90°,
                            -- the current segment is extended to 135° immediately

t.setServoAngle(-45)       -- move back through the shortest path

t.resetServo()             -- current position becomes 0°, no rotation
print(t.getServoAngle())   -- 0.0

t.setServoSpeed(96)        -- max speed; setServoSpeed(128) would be clamped to 96
t.setServoMode(false)      -- back to transmission mode
```
