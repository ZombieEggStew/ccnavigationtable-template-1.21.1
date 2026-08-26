# Throttle 2

![throttle 2](../img/throttle_2.png)

The Throttle 2 installs onto a [Control Desk](overview.md) and is a **collective lever**: the handle **rotates** around its pivot (model-space `(4,2,8)`) instead of sliding. Its angle is **continuous**, ranging from **0° (bottom, as placed) to +30° (full pull-up)**.

> It occupies the full 14×6 desk-top grid — the only legal placement is center `(8,12)` with 0°/180° rotation only — so it is **mutually exclusive** with the [Throttle](throttle.md) and Monitor 2 (only one of them can be installed at a time).

## Angle Control

- Hold the **pull-up** key (default `Space`) to raise the handle (angle +); hold the **pull-down** key (default `Left Ctrl`) to lower it (angle −).
- **Full-deflection time** (ticks): how long you must hold a key to reach the full +30° — default **20**, range 1..100 (20 ticks = 1 second). While held, the angle advances by `30° / full-deflection-time` each tick.
- When no key is held, the behavior depends on the **return switch**:
  - **Return off** (default): the handle **locks in place** — like a mechanical collective, it stays where it is (latch, no auto-return).
  - **Return on**: the handle returns to the **neutral 15°** (half of 30°) at the **return time** rate — default **2** ticks, range 0..100 (`0` = return disabled).

## Key Bindings

| Action | Default key |
|---|---|
| Pull up (angle +) | `Space` |
| Pull down (angle −) | `Left Ctrl` |

Both bindings, the full-deflection time and the return switch/time are configurable **per-desk** in the module settings menu (open the [desk config menu](overview.md#configuration-menu) and click the "Throttle 2" row). They are **independent** from the regular [Throttle](throttle.md) bindings — both controls can be installed on different desks and configured separately.

## Lua API

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local th2 = desk.getModule("throttle_2")   -- nil if no throttle 2 installed
```

### th2.getAxis()

Returns the normalized lever position (number, **0..1**) = angle / 30°: `0` = bottom (as placed), `1` = full pull-up.

```lua
print(th2.getAxis())   -- 0..1
```

### th2.getCenterAxis()

Returns the lever position relative to the **neutral 15°** (number, **-1..1**) = (angle − 15°) / 15°: `-1` = bottom (0°), `0` = neutral (15°, the resting point in return mode), `+1` = full pull-up (30°). Most useful when the return switch is on.

```lua
print(th2.getCenterAxis())   -- -1..1
```

### th2.setAngle(degrees)

Directly sets the lever angle (degrees, **0..30**, out-of-range values are clamped). Runs on the main thread (`mainThread = true`) and writes the **server-authoritative** angle, then broadcasts it.

> Note: while a player is operating the desk from a linked seat (input lease active), the server simulation advances the angle every tick and **overrides** this call. With no player input (return off = latch), the setting persists until the next key press / return.

```lua
th2.setAngle(20)   -- move the handle to 20°
```

All read methods are `mainThread = false` — safe to poll at high frequency.

## Example

```lua
local pe = require("ccpe.pe")
local desk = pe.getPeripheral(4)
local th2 = desk.getModule("throttle_2")

while true do
    local axis   = th2.getAxis()          -- 0..1
    local center = th2.getCenterAxis()    -- -1..1
    print(("axis %.2f  center %.2f"):format(axis, center))
    os.sleep(0.05)
end
```
