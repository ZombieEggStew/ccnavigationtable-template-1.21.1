# Peripheral Proxy

1. Attach a pe to a CC:T peripheral
2. Use getPeripheral to get the peripheral object

!!! info "OP"
    No cables needed, no distance limit, no weight


```lua
local pe = require("ccpe.pe")

local monitor = pe.getPeripheral(10) -- get the peripheral the channel-10 pe is attached to

assert(monitor , "Peripheral not found")
```

## Next Steps

- [Wireless Redstone](wireless-redstone.md) — send and receive redstone signals
