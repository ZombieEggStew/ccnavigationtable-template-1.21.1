# Electronic Transmission

![Electronic Transmission](../img/transmission_peripheral_v.png)

> **What's the difference from create:RotationSpeedController?**

> When using Create's rotation speed controller as a peripheral and calling `getTargetSpeed()`, it triggers `RotationPropagator.handleRemoved()` which cascades and clears the source of the entire downstream sub-network, leading to unexpected results (e.g. using aeroworks' stepper_servo downstream of the speed controller — changing the speed while activating the stepper motor makes the motor spin erratically).
> Meanwhile, simulated's analog_transmission is hard to fine-tune.

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
