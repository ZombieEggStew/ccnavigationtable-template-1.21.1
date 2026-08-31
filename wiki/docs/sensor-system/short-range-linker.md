# Short-Range Signal Linker

> Peripheral access scoped to a single physics body

The **Short-Range Signal Linker** (`ccpe:short_range_linker`) is an attachable block (floor / ceiling / wall, like the Micro Peripheral Extender). When placed on a physics body (Sable sub-level), it registers a **channel** that is only addressable *within that physics body* (including constraint chains). CC:Tweaked computers on the same body can then fetch the peripheral of the block the linker is attached to via `ccpe.sensor_system`.

Unlike the global channels of the Micro Peripheral Extender, linker channels are **per-body**: two aircraft can both use channel `1` without interfering with each other.

## How channels work

- A channel number is the linker's **address within its body**: within one body, each channel maps to exactly one linker (`1:1`).
- If two linkers on the same body claim the same channel, the later one **automatically rolls over** to the next free channel.
- The querying computer needs **no channel of its own** — it just asks for the target linker's channel (zero-config on the computer side).
- Linkers that are **not on any physics body** do not register at all: their GUI shows "Only usable on a physics body" and every Lua lookup returns `nil` (strict semantics).
- Different physics bodies are fully isolated — aircraft A's channel `1` is invisible to aircraft B.

## GUI

Right-click a linker to open its config screen (controls only appear when the linker is on a physics body):

- **Channel roller** — scroll over the channel number to change it (hold Shift to step by 10); channels already occupied by other linkers **on the same body** are skipped.
- **Load Physics Body** toggle — chain-wide shared switch, see below.
- Off a physics body, the screen only shows "Only usable on a physics body".

## Load Physics Body (chain-wide shared switch)

One shared boolean per body chain, mirroring the physics load mode of the Micro Peripheral Extender (Sable force-load + PORTAL tickets):

- Toggling it on **any** linker writes the same value to **every** linker on the chain (last toggle wins) — all GUIs on the chain show the same state.
- **On**: the linker registers Sable force-load + PORTAL tickets for the whole chain, keeping the physics body loaded even when you fly far away.
- **Off**: tickets are released.
- On (re)load or placement, the switch **self-heals via OR**: a linker joining a chain where someone already has it on turns itself on, so blueprint-deployed setups stay consistent.
- Independent of the Micro Peripheral Extender's own load mode; redundant tickets are harmless (Sable deduplicates by ticket type + position).
- Not available when the linker is not on a physics body.
- Persists through Create schematics (blueprint) together with the channel.

## Lua API

Computers on the same physics body (including constraint chains) use `require("ccpe.sensor_system")` — the same module as the sensor blocks:

| Method | Returns | Description |
|---|---|---|
| `getPeripheral(channel)` | peripheral / nil | Peripheral of the block the target linker is attached to (the linker must be placed on the target block; capability query, runs on the main thread) |
| `getRedstoneOutput(channel)` | number | Current redstone output signal (0-15) of the target linker |
| `getRedstoneInput(channel)` | number | Strongest redstone signal (0-15) currently received at the target linker's position |
| `setRedstoneOutput(channel, signal)` | - | Write the target linker's redstone output (automatically clamped to 0-15), updating the block's powered state and adjacent redstone |

- **Scope = the calling computer's physics body** (incl. constraint chains): if the computer is not on any physics body, `getPeripheral` returns `nil` and the redstone reads return `0`.
- Target not found (channel free, or linker unloaded) → same result.
- `getPeripheral` / `setRedstoneOutput` are scheduled onto the server main thread; the redstone reads are cache-driven with zero main-thread scheduling (per-tick refresh, at most 1 tick stale).

```lua
local ss = require("ccpe.sensor_system")

-- fetch the peripheral of the block the linker on channel 1 is attached to
local nav = ss.getPeripheral(1)
if nav then
    print("got peripheral")
end

-- redstone output: powers adjacent redstone wire
ss.setRedstoneOutput(1, 15)

-- redstone input: read the strongest signal at the target linker
print("input at channel 2:", ss.getRedstoneInput(2))
```

## Notes & boundaries

- **Chains change over time**: two bodies joined by a bearing merge their channel spaces (constraint chain); breaking the bearing splits them again. The linker revalidates every 20 ticks and rolls over on conflicts.
- **Blueprint compatibility**: the channel and the shared load switch persist through Create schematics; after deployment the shared switch self-heals via the OR rule.
- Different bodies' channel numbers are independent — always place the linker on the *target* block; the computer side needs nothing.
