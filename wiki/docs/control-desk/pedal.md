# Foot Pedal

The Foot Pedal installs a **pair of pedals** (left and right) onto a [Control Desk](overview.md). While you sit on a linked seat in operation mode, holding a pedal's press key pushes the pedal down (it translates forward, **+z**), holding the lift key raises it (**-z**), and releasing both returns it to the middle.

Pedal travel is **analog** — pressing is a smooth ramp to full deflection, not a step.

## Default Keys

| Pedal | Press down | Lift up |
|---|---|---|
| Left | `Q` | `E` |
| Right | `E` | `Q` |

The four key bindings are configurable **per-desk** in the module settings menu (open the [desk config menu](overview.md#configuration-menu) and click the "Foot Pedal" row).

## Module Settings

- **Return time** (ticks, default 2, range 0..100, shared by both pedals) — how long a released pedal takes to return to the middle; `0` disables returning (the pedal stays where it is).
- **Full-deflection time** (ticks, default 2, range 1..100, shared) — how long holding the press/lift key takes to reach full travel (smaller = faster). 20 ticks = 1 second.

## Lua API

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local pedal = desk.getModule("pedal")   -- nil if no pedal installed
```

### pedal.getLeftPedal() / pedal.getRightPedal()

Returns the pedal's analog position (number, **-1..1**): `+1` = fully pressed down, `-1` = fully lifted, `0` = middle.

```lua
print(pedal.getLeftPedal())   -- -1 .. 1
```

### pedal.getPedalDifference()

Returns the difference between the two pedals (number, **-2..2**): **right − left**. Positive = right pedal pressed deeper; negative = left pedal pressed deeper.

```lua
print(pedal.getPedalDifference())
```

### pedal.isLeftPedalDown() / pedal.isRightPedalDown()

Returns `true` while the pedal is on the pressed side (axis value > 0, including the remaining travel while returning).

### pedal.isLeftPedalUp() / pedal.isRightPedalUp()

Returns `true` while the pedal is on the lifted side (axis value < 0).

All methods are `mainThread = false` — safe to poll in a loop on the computer.

## Example: Differential Pedals

A classic use for a pedal pair is a differential throttle: the **average** of both pedals is the overall throttle, and `getPedalDifference()` is the turning amount.

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local pedal = desk.getModule("pedal")

while true do
    local left, right = pedal.getLeftPedal(), pedal.getRightPedal()
    local throttle = (left + right) / 2          -- -1..1, overall forward/back
    local turn     = pedal.getPedalDifference()  -- -2..2, right minus left
    print(("throttle %.2f  turn %.2f"):format(throttle, turn))
    os.sleep(0.05)
end
```
