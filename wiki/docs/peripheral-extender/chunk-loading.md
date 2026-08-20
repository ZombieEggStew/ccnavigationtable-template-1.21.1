# Chunk / Physics Body Loading

Use the scroll wheel at the bottom-right of the pe right-click menu to set the loading mode.

The sensor can keep the area around the target block loaded:

| Mode | Description | Use Case |
|---|---|---|
| Off | No loading | Short-distance use |
| **Load Chunk** | Force-loads the chunk the sensor is in via vanilla `setChunkForced` | Prevents the block's chunk from being unloaded |
| **Load Physics Body** | Registers a force-load ticket + PORTAL ticket with Sable, follows the physics structure as it moves | Prevents the aircraft/physics structure from being unloaded by Sable's distance optimization |

> The "Load Physics Body" mode automatically tracks the movement of the physics structure and dynamically moves the PORTAL ticket to the chunk where the physics body currently is. The bearing connection chain is refreshed every 5 seconds.
