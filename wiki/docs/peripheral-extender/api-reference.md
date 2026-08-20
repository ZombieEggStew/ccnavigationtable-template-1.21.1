# Peripheral Extender Lua API Reference

Complete API documentation for the Peripheral Extender module in CC:Tweaked integration.

## Module Import

```lua
local pe = require("ccpe.pe")
```

All functions require a valid channel number (1-9999). Functions return `nil` on error instead of throwing exceptions.

---

## NBT Data Access

### pe.getAll(channel)

Retrieves complete NBT data from the block associated with the specified channel.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table | nil` - Full NBT data table, or `nil` if channel has no data

Example:
```lua
local data = pe.getAll(1)
if data then
    print("Item count: " .. #data.Items)
end
```

### pe.get(channel, path)

Retrieves a specific NBT field using path notation.

Parameters:
- `channel`: number (1-9999) - Channel number
- `path`: string - NBT path (e.g., `"Items[0].count"`, `"Energy"`)

Returns:
- `any | nil` - Field value (type depends on NBT data type), or `nil` if path not found

Example:
```lua
local count = pe.get(1, "Items[0].count")
local itemId = pe.get(1, "Items[0].id")
```

---

## CC:Tweaked Peripheral Proxy

### pe.getPeripheral(channel)

Gets a CC:Tweaked peripheral object for the block at the specified channel. Allows calling all peripheral methods supported by that block.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table | nil` - Peripheral object, or `nil` if block does not support CC:T peripherals

Example:
```lua
-- Assuming channel 2 is attached to a Create rotation speed controller
local controller = pe.getPeripheral(2)
if controller then
    controller.setTargetSpeed(64)
    local speed = controller.getSpeed()
    print("Current speed: " .. speed)
end
```

---

## Wireless Redstone

### pe.setRedstoneOutput(channel, level)

Sends a redstone signal to the sensor at the specified channel.

WARNING: This method executes on the main thread and takes 1 tick to complete.

Parameters:
- `channel`: number (1-9999) - Channel number
- `level`: number (0-15) - Redstone signal strength

Returns:
- void

Example:
```lua
pe.setRedstoneOutput(1, 15)  -- Full signal
pe.setRedstoneOutput(1, 0)   -- Turn off
```

### pe.getRedstoneOutput(channel)

Reads the redstone signal strength sent by the sensor at the specified channel.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Redstone signal strength (0-15)

### pe.getRedstoneInput(channel)

Reads the redstone signal strength received by the sensor at the specified channel.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Redstone signal strength (0-15)

Example:
```lua
local input = pe.getRedstoneInput(1)
if input > 0 then
    print("Redstone signal detected: " .. input)
end
```

---

## Navigation Table Integration

REQUIREMENT: These APIs require the sensor to be attached to a `simulated:navigation_table` block.

### pe.getNavTargetPos(channel)

Gets the target position set in the navigation table (world coordinates).

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` in meters (world coordinates)

Example:
```lua
local target = pe.getNavTargetPos(1)
print(string.format("Target: %.1f, %.1f, %.1f", target.x, target.y, target.z))
```

### pe.getNavSelfPos(channel)

Gets the current position of the contraption containing the navigation table (world coordinates).

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` in meters (world coordinates)

### pe.getNavDistance(channel)

Gets the straight-line distance to the target.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Distance in meters

### pe.getNavRelativeAngle(channel)

Gets the relative bearing angle to the target (clockwise from north).

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Bearing angle in degrees (0-360)

Example:
```lua
local angle = pe.getNavRelativeAngle(1)
print(string.format("Bearing: %.1f°", angle))
```

---

## Physics Data

REQUIREMENT: These APIs require the Sable mod and a physics structure. Velocity-related methods require the sensor to be attached to a `simulated:velocity_sensor` block.

### pe.getPhysicsPos(channel)

Gets the world position of the physics body.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` in meters (world coordinates)

### pe.getPhysicsOrientation(channel)

Gets the rotation of the physics body (quaternion).

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number, w: number}` quaternion

### pe.getPhysicsCenterOfMass(channel)

Gets the world position of the physics body's center of mass.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` in meters (world coordinates)

### pe.getPhysicsMass(channel)

Gets the mass of the physics body containing the attached block.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Mass in kilograms

### pe.getPhysicsChainMass(channel)

Gets the total mass of the physics body chain (including all connected bodies).

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Total mass in kilograms

### pe.getPhysicsGravityForce(channel)

Gets the gravity force acting on the physics body containing the attached block.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Gravity force in pico-newtons (pN)

### pe.getPhysicsChainGravityForce(channel)

Gets the total gravity force acting on the physics body chain.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Total gravity force in pico-newtons (pN)

### pe.getPhysicsVelocity(channel)

Gets the ground velocity of the physics body (relative to ground).

REQUIREMENT: Sensor must be attached to `simulated:velocity_sensor`.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` velocity in meters/second

### pe.getPhysicsAirVelocity(channel)

Gets the airspeed of the physics body (wind velocity subtracted).

REQUIREMENT: Sensor must be attached to `simulated:velocity_sensor`.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` airspeed in meters/second

### pe.getPhysicsAngularVelocity(channel)

Gets the angular velocity of the physics body.

REQUIREMENT: Sensor must be attached to `simulated:velocity_sensor`.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `table` - `{x: number, y: number, z: number}` angular velocity in radians/second

### pe.getAxisVelocity(channel)

Gets the velocity component along the sensor's mounting axis.

The sensor's "mounting axis" is determined by its attached face:
- Sensor on floor: Y-axis (upward)
- Sensor on ceiling: Y-axis (downward)
- Sensor on wall: Horizontal facing direction (N/S/E/W)

When the physics body rotates, the axis rotates with it (body frame). Three orthogonally mounted sensors can measure velocity components in the body's local coordinate system.

REQUIREMENT: Sensor must be attached to `simulated:velocity_sensor`.

Parameters:
- `channel`: number (1-9999) - Channel number

Returns:
- `number` - Axial velocity in meters/second (positive = motion along positive axis direction)

Behavior:
- Returns 0 when velocity < 0.05 m/s (deadzone)
- Returns 0 when physics body is on stationary ground
- Automatically follows physics body rotation

Example:
```lua
-- Assuming sensor is mounted on floor (Y-axis upward)
local vy = pe.getAxisVelocity(1)
if vy > 0 then
    print("Ascending: " .. vy .. " m/s")
elseif vy < 0 then
    print("Descending: " .. math.abs(vy) .. " m/s")
end
```

---

## Error Handling Pattern

All API calls return `nil` on error instead of throwing exceptions. Use conditional checks:

```lua
local data = pe.getAll(1)
if data then
    -- Process data
else
    print("Channel 1 has no data")
end
```

---

## Performance Characteristics

- NBT data refreshes every tick (50ms)
- Single API call takes approximately 0.02ms
- Path-based reads (`pe.get`) are more efficient than full reads (`pe.getAll`)
- For high-frequency polling, cache invariant data where possible
