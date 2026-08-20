# Block NBT Reading

The Peripheral Extender's NBT reading feature lets you access the internal data of any block, without the block needing to implement a CC:T peripheral interface.

## Basic Usage

### Reading Full NBT Data

```lua
local pe = require("ccpe.pe")

-- Read all NBT data of the block the channel-1 pe is attached to
local data = pe.getAll(1)

-- Example: NBT structure of a chest
-- {
--   Items = {
--     [1] = { count = 16, slot = 0, id = "minecraft:diamond" },
--     [2] = { count = 32, slot = 1, id = "minecraft:iron_ingot" }
--   },
--   x = 8,
--   z = 9,
--   id = "minecraft:chest",
--   y = 63,
-- }
```

---

### Reading a Specific Path

!!! info "Getting a path quickly"
    In the pe right-click menu, click a line of NBT data to copy that field's path.

    If there is a ▶ symbol, the field is a table; left-click to expand/collapse, right-click to copy the path.

Use path syntax to read only the fields you need, for better efficiency:

```lua
local pe = require("ccpe.pe")

-- Read the ID of the first item
local itemId = pe.get(1, "Items[0].id")
print(itemId)  -- "minecraft:diamond"

-- Read the count of the first item
local count = pe.get(1, "Items[0].count")
print(count)  -- 16

```

#### Path Syntax

NBT paths use JSON-like syntax:

| Syntax | Description | Example |
|---|---|---|
| `.field` | Access an object field | `id` |
| `[index]` | Access an array element (0-based) | `Items[0]` |
| Combined | Chained access | `Items[0].count` |

---


## Performance Tips

!!! tip "Optimization advice"
    1. **Use path reads** — read only the fields you need and avoid transferring the whole NBT structure
    2. **Set a sensible refresh rate** — adjust the `sleep()` time to your actual needs
    3. **Cache invariant data** — such as fixed information like block type or slot count

```lua
-- ❌ Inefficient: read the full NBT every time
while true do
    local data = pe.getAll(1)
    print(data.Items[1].count)
    sleep(0.05)
end

-- ✅ Efficient: read only the field you need
while true do
    local count = pe.get(1, "Items[0].count")
    print(count)
    sleep(0.05)
end
```

## Troubleshooting

### Returns nil
- Check that the channel number is correct
- Make sure the sensor is attached to the block
- Make sure the path syntax is correct (note that arrays start at 0)

### Data does not update
- The data refresh rate is 50ms (1 tick)
- Check the `refresh_interval` setting in the config file

## Next Steps

- [Peripheral Proxy](peripheral-proxy.md) — get CC:T peripherals
- [Complete Lua API Reference](../api-reference.md)
- [Example: Automated Monitoring System](../peripheral-extender/example.md)
