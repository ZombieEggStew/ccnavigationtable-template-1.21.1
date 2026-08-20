# Knob Module

![Knob Module](../img/knob.png)

Angles are in **degrees**, range 0..360.

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
