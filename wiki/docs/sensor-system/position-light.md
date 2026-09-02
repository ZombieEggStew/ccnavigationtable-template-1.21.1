# Position Light

![Position Light](../img/position_light.png)

> Position Light (also known as Navigation Light or Position Light) is a core feature that helps pilots determine the position, direction of travel, and approximate type of other aircraft in low visibility or at night. All aircraft must follow a common basic rule.

>- Left wingtip: Red position light
>- Right wingtip: Green position light
>- Tail: White position light

> This rule originates from maritime navigation, allowing pilots to quickly assess the direction of other aircraft. For example, if you see the left side of another aircraft is red and the right side is green, it means you are flying in the same direction; if it's the opposite (left green, right red), it indicates you are flying towards each other and need to be cautious.

The **Position Light** is an attachable lighting block for physics bodies (Sable sub-levels), available in three colors:

| Block | ID |
|---|---|
| Red Position Light | `ccpe:red_position_light` |
| Green Position Light | `ccpe:green_position_light` |
| White Position Light | `ccpe:white_position_light` |

## Lua control (FMC gate)

The physics body (including constraint chains) must have **at least 1 FMC** (`ccpe:fmc`) and the computer must be on the body. Otherwise the methods below return `0`.

| Method | Returns | Description |
|---|---|---|
| `setLights(color, on)` | number | Switch all lights of one color on/off. `color` = `"red"` / `"green"` / `"white"` / `"all"` (case-insensitive). Returns the number of lights actually changed. |
| `setAllLights(on)` | number | Switch **all** lights (every color) on/off. Equivalent to `setLights("all", on)`. Returns the number of lights actually changed. |

## Example

```lua
local ss = require("ccpe.sensor_system")

local red = ss.setLights("red", true)  -- turn on all red position lights
local all = ss.setAllLights(false)     -- turn everything off
print("red lights on:", red, "turned off:", all)
```
