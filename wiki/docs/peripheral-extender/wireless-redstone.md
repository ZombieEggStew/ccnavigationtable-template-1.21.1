## Wireless Redstone

Just like Create's Wireless Redstone Signal terminal, but controlled by channels.

| Method | Description |
|---|---|
| `pe.setRedstoneOutput(ch, 0-15)` | Wireless redstone output, mainThread = true |
| `pe.getRedstoneOutput(ch)` | Read the signal being sent |
| `pe.getRedstoneInput(ch)` | Read the input redstone signal |


```lua
local pe = require("ccpe.pe")

-- Read the redstone signal near the channel-5 pe
local signal = pe.getRedstoneInput(5)
print("Signal: " .. signal)

-- Activate a level-10 redstone signal near the channel-6 pe
pe.setRedstoneOutput(6, 10)

```

## Next Steps

- [Aeronautics Sensor Integration](simulated-integration.md) — read velocity, mass and orientation from the Sable physics engine
