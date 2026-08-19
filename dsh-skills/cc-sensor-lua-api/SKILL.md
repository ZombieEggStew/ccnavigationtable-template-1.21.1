---
name: cc-sensor-lua-api
description: "Use when implementing the planned CC:Tweaked global sensor Lua API, channel registry, NBT query path, caching, chunk loading, or Sable compatibility."
---

# CC:Tweaked Sensor Lua API

This is an unimplemented design, not a description of current behavior. Confirm current source names and CC:Tweaked API signatures before changing code.

## Design constraints

- Use global `ILuaAPI`, not `IPeripheral`, for wireless computer access.
- Mark world-reading Lua functions with `@LuaFunction(mainThread = true)`.
- Keep Layer 1 direct fields separate from NBT path queries and full-NBT conversion.
- Cache serialized NBT by server `gameTime`; read-only calls must not call `setChanged()` or sync packets.
- Register/unregister a channel-to-sensor mapping in lifecycle methods and resolve duplicate channels deterministically with a warning.
- Any force-loaded chunk must be released in `setRemoved()`; optional Sable integration must be reflection-safe and non-fatal when absent.

## Check

- Test a missing channel, missing attached BE, nested/list NBT paths, repeated same-tick reads, reload/unload, and force-load cleanup.
- Confirm access is restricted to server-thread APIs.
