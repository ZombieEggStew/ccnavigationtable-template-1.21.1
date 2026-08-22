# Monitor

![Monitor](../img/my_monitor_item.png)

After obtaining the Monitor peripheral instance (see [Overview](overview.md) for how to get it), you can call the following three categories of methods:

| Category | Methods |
|---|---|
| Module / screen query | `getCellModule` / `getModule` |
| Sound | `playNiceSound` / `playSound` |

!!! warning "Text and graphics can only be drawn on a screen module"
    The Monitor itself **no longer provides a background-plane drawing API** (the old `write` / `clear` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `clearRects` / `clearShapes` / `getSize` methods were removed).
    To display content on a Monitor, install a **screen module** — all text/graphics APIs live on it, see [Screen Module](screen.md). The Monitor's grid lines and background texture are unchanged.

---

## Operation

- **Configure**: sneak + right-click the Monitor base to open the config interface, where you can configure:
    - Channel: set the Monitor's global channel number, sharing the same channel system as the Peripheral Extender
    - Background: switch the Monitor's background image
    - Rotation & offset: freely set rotation and offset
- **Remove**: sneak + right-click the Monitor base while holding a wrench to remove it. A removed Monitor keeps its modules and settings. Breaking it directly separates the Monitor from its modules

---

## Module / Screen Query

### monitor.getCellModule(x, y)

- **Parameters**: `x` (0..11), `y` (0..9) — grid coordinates
- **Returns**: the module instance (`ModuleHandle`) on that cell; returns the screen instance if the cell is occupied by a screen; returns `nil` for empty/out-of-bounds cells

```lua
local mod = monitor.getCellModule(3, 4)
if mod then
    print(mod.getId(), mod.getType())  -- e.g. 7  toggle_switch
end
```

### monitor.getModule(id)

- **Parameters**: `id` — module/screen ID (modules and screens share the same ID namespace)
- **Returns**: the corresponding module/screen instance; `nil` if it doesn't exist

```lua
local mod = monitor.getModule(7)
if mod then print(mod.getType()) end
```

---


## Sound

### monitor.playNiceSound()

Plays the Create-style order sound + WiFi particles (effect position at the block center, sound is `create:stock_ticker_request`).
The sound is broadcast to nearby players by the server; the WiFi particles go through a custom clientbound packet (`ccpe:play_order_effect`) broadcast to clients within 32 blocks, generated locally on the client (Create's `WiFiParticle` data cannot be encoded through the particle network channel).

```lua
monitor.playNiceSound()
```

### monitor.playSound(sound)

Plays a specified Create sound (broadcast to nearby players at the block position, played by the server, audible to all nearby players).

- **Parameters**: `sound` — sound name string, currently supported:

| Name | Create sound asset | Description |
|---|---|---|
| `"bonk"` | `create:cardboard_bonk` | Cardboard sword "bonk" |
| `"bell"` | `create:desk_bell` | Front desk bell |
| `"confirm"` | `create:confirm_2` | Confirmation "ding" |
| `"fwoomp"` | `create:fwoomp` | Low "woomph" |
| `"trade"` | `create:stock_ticker_trade` | Cash register |
| `"request"` | `create:stock_ticker_request` | Order placed |

- **Returns**: `boolean` — whether the sound was found and played; unknown names return `false` (no Lua error thrown)

```lua
if monitor.playSound("bell") then
    print("Rang!")
end
```

---

## Thread Model (mainThread)

| Method | mainThread |
|---|---|
| `getCellModule` / `getModule` | ✅ `true` (queries also go through the server main thread) |
| `playNiceSound` / `playSound` | ✅ `true` |

---

## Monitor Custom Background Images

- **Directory**: put images in the `ccpe_res/monitor_bg/` folder in the game root directory. This folder is a sibling of `mods/` and `resourcepacks/`; only the first level of this folder is scanned, subdirectories are not.
- **Supported formats**: `.png`, `.jpg` and `.jpeg` are supported, extensions are case-insensitive.
- **Filename rules**: filenames must start with a letter or digit and may only contain lowercase/uppercase letters, digits, underscores, hyphens and dots, e.g. `test_bg.png`, `cockpit-01.jpg`. Files that don't follow the rules are ignored.
- **Option name**: the client scans images at startup and appends the filenames to the background switching options in the Monitor right-click menu. Custom background names in the menu show the filename, e.g. `test_bg.png`.
- **Persistence key**: filenames are converted to lowercase and saved with a `custom/` prefix. E.g. `Test_BG.PNG` is saved as `custom/test_bg.png`.
- **Load timing**: images load at client startup; after adding, deleting or replacing images you must restart the client for them to be rescanned.
- **Missing handling**: if a custom background file saved on a Monitor no longer exists, rendering falls back to the default background.
