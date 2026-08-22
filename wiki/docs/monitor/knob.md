# Knob Module

![Knob Module](../img/knob.png)

Angles are in **degrees**. The knob stores a cumulative **absolute angle** (may exceed 360 when a physical limit above 360° is configured); the **normalized angle** wraps it into one full turn (0..360).

## Operation
- **Configure module**: hold a wrench and right-click the module, or sneak + right-click, to open the module config interface and configure properties such as module ID and tooltip
- **Remove module**: hold a wrench and sneak + right-click to remove the module
- **Detent**: enable the detent feature in the config interface to snap to multiples of the configured angle

---

Getting a module instance:

```lua
local knob = monitor.getModule(7)   -- 7 is the knob's module ID
```

## knob.getAngle()

Returns the current angle (number, 0..360).

```lua
print(knob.getAngle())  -- 45.0
```

## knob.setAngle(angle)

Sets the angle (number, degrees). Automatically normalized to 0..360; when detent is enabled, snaps to the nearest detent position.

```lua
knob.setAngle(180)
knob.setAngle(90)   -- with 45° detent enabled, snaps to 90
```

## knob.getNormalizedAngle()

Returns the normalized angle (number, degrees, 0..360) — the absolute angle wrapped into one full turn.

```lua
print(knob.getNormalizedAngle())  -- 45.0
```

## knob.getAbsoluteAngle()

Returns the absolute (cumulative) angle (number, degrees). May exceed 360 when a physical limit above 360° is configured, and may be negative if the knob is rotated backwards without a physical limit.

```lua
print(knob.getAbsoluteAngle())  -- 405.0
```

## knob.getRelativeDetent()

Returns the relative detent position (integer): normalized angle / configured detent step, rounded. Returns 0 when detent is disabled (free rotation).

```lua
-- detent step 90°, knob at 270°: returns 3
print(knob.getRelativeDetent())
```

## knob.getAbsoluteDetent()

Returns the absolute detent position (integer): absolute angle / configured detent step, rounded. Returns 0 when detent is disabled.

```lua
-- detent step 90°, knob at 405°: returns 5
print(knob.getAbsoluteDetent())
```

## knob.getRelativePercent()

Returns the relative percentage (number, 0..100): normalized angle / configured max rotation angle × 100.

```lua
-- max rotation angle 360°, knob at 180°: returns 50.0
print(knob.getRelativePercent())
```

## knob.getAbsolutePercent()

Returns the absolute percentage (number): absolute angle / configured max rotation angle × 100. May exceed 100 (or be negative) when the knob is rotated past the configured max rotation angle (physical limit off).

```lua
-- max rotation angle 360°, knob at 405°: returns 112.5
print(knob.getAbsolutePercent())
```
