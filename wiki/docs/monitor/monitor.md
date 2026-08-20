# Monitor

![Monitor](../img/my_monitor_item.png)

After obtaining the Monitor peripheral instance (see [Overview](overview.md) for how to get it), you can call the following three categories of methods:

| Category | Methods |
|---|---|
| Module / screen query | `getCellModule` / `getModule` |
| Background plane drawing (text + graphics) | `write` / `clear` / `setCursorPos` / `getCursorPos` / `setTextScale` / `getTextScale` / `setTextColour` / `getTextColour` / `setZIndex` / `getZIndex` / `setOverflowMode` / `getOverflowMode` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `clearRects` / `clearShapes` / `getSize` |
| Sound | `playNiceSound` / `playSound` |

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

## Monitor Background Plane Drawing

The Monitor has a built-in **background plane** display area — you can write text and draw rectangles/lines/circles directly on the panel without installing a screen module. It shares the same text/graphics engine (`ScreenText`) and coordinate system as the screen module; see [Screen Module](screen.md) for the exact rendering semantics.

- **Display area**: the Monitor panel is 14×12px; after removing the 1px border on each side, the inner area is **12×10px** (exactly matching the 12×10 module grid).
- **Coordinate units**: 1/128 block (`1px = 8 units`), origin at the top-left of the inner area, `x` right, `y` down — identical to `drawRect` and `setCursorPos`.
- **Font size units**: MC pixels (`1px = 1/16 block`), default `0.5`, range `0.05..8.0`, only affects characters written afterwards.
- **Text**: foreground colour only (default `0xFFFFFF`), **no background colour** — use `drawRect` yourself when you need a background.
- **Layer z**: higher = more in front, default `0`; negative values are pushed behind the panel.
- **Overflow**: text reaching the right edge is handled per `setOverflowMode`, default `"wrap"`.

!!! tip "Layer reminder"
    The larger the `z`, the more in front, but each +1 moves forward roughly 0.01px; **z around `[-1, 10]` is recommended**. Setting it too large will visibly separate layers from the side.

### monitor.write(text, z?)

Writes text at the cursor position on the background plane (supports `\n` line breaks, ignores `\r`). Each character's position is positioned directly with `drawRect` coordinates:
writing one character advances the cursor right by one glyph width (`fontSize × 1.0 × 8`); `\n` returns to the line start and moves down one line (`fontSize × 1.2 × 8`).
When reaching the right edge of the inner area, `setOverflowMode` applies (default `"wrap"`).

- `text`: the text to write
- `z`: optional, layer for the characters written this time (higher = more in front); when omitted, uses the default layer set by `setZIndex`

```lua
monitor.write("Hello\nCCPE")
monitor.write("Top", 2)          -- layer 2
```

### monitor.clear()

Clears all text and shapes (rectangles/lines/circles) on the background plane, and resets the cursor to `(0, 0)`.

### monitor.setCursorPos(x, y) / monitor.getCursorPos()

Sets/reads the cursor position, using exactly the same coordinate system as the first two parameters of `drawRect`:
origin at the top-left of the inner area, X right, Y down, 1 unit = 1/128 block (negative values clamp to 0).
`getCursorPos` returns two values, `x, y`.

```lua
monitor.setCursorPos(0, 0)          -- top-left of the inner area
local x, y = monitor.getCursorPos()
```

### monitor.setTextScale(scale) / monitor.getTextScale()

Sets/reads the font size of the whole background plane (glyph height, MC pixels, `1px = 1/16 block`, range `0.05..8.0`).
The font size only affects the glyph size and advance of `write` calls afterwards; it does not affect the position of already written text (old characters still render at their own positions).

```lua
monitor.setTextScale(0.5)
print(monitor.getTextScale())  -- 0.5
```

### monitor.setTextColour(colour) / monitor.getTextColour()

Sets/reads the foreground colour (0xRRGGBB, default `0xFFFFFF`). Text has **no background colour** — use `drawRect` yourself for backgrounds.

```lua
monitor.setTextColour(0x00FF00)
```

### monitor.setZIndex(z) / monitor.getZIndex()

Sets/reads the default layer used by subsequent `write` / `drawRect` calls when no z is explicitly given (default `0`, higher = more in front, negative values pushed behind the panel).

```lua
monitor.setZIndex(2)
monitor.write("Hello")           -- uses default layer 2
monitor.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- also uses layer 2
```

### monitor.setOverflowMode(mode) / monitor.getOverflowMode()

Sets/reads how text overflowing one line width is handled (unknown names fall back to `"wrap"`):

| mode | Meaning |
|---|---|
| `"truncate"` | Truncate directly, discard the overflow |
| `"ellipsis"` | Truncate a bit more, append `"..."` |
| `"wrap"` | Wrap to the next line (default) |

```lua
monitor.setOverflowMode("ellipsis")
print(monitor.getOverflowMode())  -- ellipsis
```

### monitor.drawRect(x, y, width, height, colour, solid, lineWidth, z?)

Draws a rectangle on the background plane. Coordinates share the same system as text/cursor.

- `x, y`: top-left corner (1/128 block, 0 = left/top edge of the inner area, increasing right/down)
- `width, height`: width and height (1/128 block, negative values clamp to 0)
- `colour`: colour (0xRRGGBB)
- `solid`: `true` = filled, `false` = outline only
- `lineWidth`: line width (1/128 block, only applies to outlines)
- `z`: layer (higher = more in front, when omitted uses the default layer set by `setZIndex`)

```lua
monitor.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)        -- filled red square, default layer
monitor.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2)     -- green outline, default layer
monitor.drawRect(0, 0, 8, 8, 0x0000FF, true, 1, 5)     -- layer 5, on top of the others
```

### monitor.drawLine(x1, y1, x2, y2, colour, lineWidth, z?)

Draws a line segment. Coordinates share the same system as `drawRect`.

- `x1, y1` / `x2, y2`: start/end points (1/128 block)
- `colour`: colour (0xRRGGBB)
- `lineWidth`: line width (1/128 block)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
monitor.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### monitor.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)

Draws a circle (approximated with a regular polygon). Coordinates share the same system as `drawRect`.

- `cx, cy`: center (1/128 block)
- `radius`: radius (1/128 block)
- `colour`: colour (0xRRGGBB)
- `solid`: `true` = filled circle, `false` = ring
- `lineWidth`: line width (1/128 block, only applies when `solid=false`)
- `segments`: approximation segments (default 32, minimum 3, more = rounder)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
monitor.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- filled circle
monitor.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48-segment ring
```

### monitor.drawPoint(x, y, colour, z?)

Draws a point (equivalent to a 1×1 unit filled rectangle). Coordinates share the same system as `drawRect`.

- `x, y`: top-left coordinates (1/128 block)
- `colour`: colour (0xRRGGBB)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
monitor.drawPoint(4, 4, 0xFF0000)
```

### monitor.clearRects()

Clears all drawn rectangles (does not affect text or other shapes).

### monitor.clearShapes()

Clears all shapes (rectangles + lines + circles + points), does not affect text.

### monitor.getSize()

Returns the number of whole character rows/columns that fit in the background plane's inner area at the current font size (reference value; text is actually positioned by coordinates and is not limited by this), returning two values `cols, rows`.
At the default font size `0.5`, it is roughly `24 × 16`.

```lua
local cols, rows = monitor.getSize()
print(cols, rows)
```

---

## Thread Model (mainThread)

| Method | mainThread |
|---|---|
| `getCellModule` / `getModule` | ✅ `true` (queries also go through the server main thread) |
| `write` / `clear` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `clearRects` / `clearShapes` / `playNiceSound` / `playSound` | ✅ `true` |
| `getCursorPos` / `getTextScale` / `getTextColour` / `getZIndex` / `getOverflowMode` / `getSize` | ❌ `false` (read directly on the computer thread, low latency) |

---

## Monitor Custom Background Images

- **Directory**: put images in the `ccpe_res/monitor_bg/` folder in the game root directory. This folder is a sibling of `mods/` and `resourcepacks/`; only the first level of this folder is scanned, subdirectories are not.
- **Supported formats**: `.png`, `.jpg` and `.jpeg` are supported, extensions are case-insensitive.
- **Filename rules**: filenames must start with a letter or digit and may only contain lowercase/uppercase letters, digits, underscores, hyphens and dots, e.g. `test_bg.png`, `cockpit-01.jpg`. Files that don't follow the rules are ignored.
- **Option name**: the client scans images at startup and appends the filenames to the background switching options in the Monitor right-click menu. Custom background names in the menu show the filename, e.g. `test_bg.png`.
- **Persistence key**: filenames are converted to lowercase and saved with a `custom/` prefix. E.g. `Test_BG.PNG` is saved as `custom/test_bg.png`.
- **Load timing**: images load at client startup; after adding, deleting or replacing images you must restart the client for them to be rescanned.
- **Missing handling**: if a custom background file saved on a Monitor no longer exists, rendering falls back to the default background.
