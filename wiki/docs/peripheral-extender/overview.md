# Peripheral Extender Overview

![Peripheral Extender](../img/micro_peripheral_extender.png)

The Peripheral Extender (pe) is the core component of CCPE, integrating multiple ways of reading information and controlling, while also supporting chunk loading and physics body loading.

Channel configuration is saved with Create's schematic system, but be careful about duplicate channels when deploying.

!!! tip "pe"
    From here on, **Peripheral Extender** will be referred to as **pe**.

---

## Guides
1. [Channel Setup](channel-setup.md) — place a pe and set the channel number
2. [NBT Reading](nbt-reading.md) — read block NBT data
3. [Peripheral Proxy](peripheral-proxy.md) — get CC:T peripherals
4. [Wireless Redstone](wireless-redstone.md) — send and receive redstone signals
5. [Aeronautics Sensor Integration](simulated-integration.md) — read velocity, mass and orientation from the Sable physics engine
6. [Chunk / Physics Body Loading](chunk-loading.md) — keep the area around the target block or a physics structure loaded

- [Real-world Example](example.md) — monitor chest capacity, find specific items
---

## API

Full API reference: [Lua API Reference](../api-reference.md)

!!! tip "AI coding assistance"
    [api](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/wiki/docs/api-reference.md)

    ↑ Download this document and send it to an AI assistant (such as ChatGPT, Claude, etc.) to help you quickly write Lua code for the Peripheral Extender.

---
