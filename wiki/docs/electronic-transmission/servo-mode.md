# Servo Mode

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
- **Target changes during a move apply at the next segment boundary** (each
  segment is at most 179°) instead of reversing mid-move. At 96 RPM a segment
  takes ~6 ticks, so the reaction delay is at most ~0.3 s.
- **Power loss resumes**: if input power drops while moving, the servo stops;
  when power returns it keeps going toward the target (it does not forget it).
- **Flicker-safe**: re-attaching to the rotation network is deferred until
  Create's flicker score is below the threshold, so rapid repositioning cannot
  destroy the block.
- **Goggle tooltip**: wearing Create goggles shows the current mode — in
  transmission mode the ratio / target speed and the output speed; in servo
  mode the current and target angle.

## Why 96 RPM?

In positioning, the speed only affects how long the move takes — the total
rotation is fixed by the `SequenceContext`. Above 96 RPM a 180° move would
finish in ~2 ticks, giving no visible benefit while stressing the rotation
network, so the effective speed is capped at **96 RPM**:

- `setServoSpeed(128)` → clamped to 96.
- If the input power exceeds 96 RPM, the effective speed is also capped to 96.

## Example

```lua
local t = peripheral.find("ccpe:transmission_peripheral")

t.setServoMode(true)       -- enter servo mode and re-home to 0° (no rotation)
t.setServoSpeed(0)         -- move at the input speed
t.setServoAngle(90)        -- rotate the output shaft to +90°
print(t.getServoAngle())   -- 90.0 (server-authoritative)

t.setServoAngle(-45)       -- move back through the shortest path

t.resetServo()             -- current position becomes 0°, no rotation
print(t.getServoAngle())   -- 0.0

t.setServoSpeed(96)        -- max speed; setServoSpeed(128) would be clamped to 96
t.setServoMode(false)      -- back to transmission mode
```
