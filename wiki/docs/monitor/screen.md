# Screen Module

![Screen Module](../img/screen.png)

Screens share the ID namespace with normal modules, and `getType()` returns `"screen"`. Screens support **text rendering** and **graphics drawing**.

- The font size (`setTextScale`) determines how many rows/columns fit on the screen; the row/column count is recomputed automatically with the font size (see `getSize`).
- Text has an overflow mode (`setOverflowMode`) that controls what happens when it exceeds one line.
- Graphics coordinates use "screen-local pixels": origin at the screen's **top-left**, `x` right, `y` down, 1 unit = 1 pixel = 1 cell = 1/16 block.


## Operation
- **Place a screen**: right-click an empty cell as the anchor, then right-click another empty cell — the screen occupies the two cells to form a rectangular area (minimum 2×2).
- **Configure module**: hold a wrench and right-click the module, or sneak + right-click, to open the module config interface and configure properties such as module ID and tooltip
- **Remove module**: hold a wrench and sneak + right-click to remove the module


## Text Rendering

### screen.write(text, z?)

Writes text at the cursor position (supports `\n` line breaks, ignores `\r`). Each character's position is positioned directly with `drawRect` coordinates:
writing one character advances the cursor right by one glyph width (`fontSize × 1.0 × 8`); `\n` returns to the line start and moves down one line (`fontSize × 1.2 × 8`).
When reaching the right edge of the screen, `setOverflowMode` applies (default `"wrap"`).

The optional parameter `z` specifies the layer for the characters written this time (higher = more in front); when omitted, uses the default layer set by `setZIndex`.

```lua
screen.write("Hello\nCCPE")
screen.write("Top", 2)          -- layer 2
```

### screen.clear()

Clears the screen's text and all shapes (rectangles/lines/circles), and resets the cursor to `(0, 0)`.

### screen.setCursorPos(x, y) / screen.getCursorPos()

Sets/reads the cursor position, using exactly the same coordinate system as the first two parameters of `drawRect`:
origin at the top-left of the screen's inner area, X right, Y down, 1 unit = 1/128 block.
`getCursorPos` returns two values, `x, y`.

```lua
screen.setCursorPos(0, 0)          -- top-left of the inner area
local x, y = screen.getCursorPos()
```

### screen.setTextScale(scale) / screen.getTextScale()

Sets/reads the font size of the whole screen (glyph height, MC pixels, 1px = 1/16 block). The font size only affects the glyph size and advance of `write` calls afterwards,
and does not affect the position of already written text (old characters still render at their own positions).

```lua
screen.setTextScale(0.35)
print(screen.getTextScale())  -- 0.35
```

### screen.setTextColour(colour) / screen.getTextColour()

Sets/reads the foreground colour (0xRRGGBB). Text has **no background colour** — use `drawRect` yourself for backgrounds.

```lua
screen.setTextColour(0x00FF00)
```

### screen.setZIndex(z) / screen.getZIndex()

Sets/reads the default layer used by subsequent `write` / `drawRect` calls when no z is explicitly given (default 0, higher = more in front, negative values pushed behind the panel).

```lua
screen.setZIndex(2)
screen.write("Hello")           -- uses default layer 2
screen.drawRect(0, 0, 4, 4, 0xFF0000, true, 1)   -- also uses layer 2
```

### screen.setOverflowMode(mode) / screen.getOverflowMode()

Sets/reads how text overflowing one line width is handled:

| mode | Meaning |
|---|---|
| `"truncate"` | Truncate directly, discard the overflow |
| `"ellipsis"` | Truncate a bit more, append `"..."` |
| `"wrap"` | Wrap to the next line (default) |

```lua
screen.setOverflowMode("ellipsis")
```

## Graphics Drawing

### screen.drawRect(x, y, width, height, colour, solid, lineWidth, z?)

Draws a rectangle on the screen. Coordinates share the same system as text/cursor.

- `x, y`: top-left corner (1/128 block, 0 = left/top edge of the inner area, increasing right/down)
- `width, height`: width and height (1/128 block)
- `colour`: colour (0xRRGGBB)
- `solid`: `true` = filled, `false` = outline only
- `lineWidth`: line width (1/128 block, only applies to outlines)
- `z`: layer (higher = more in front, when omitted uses the default layer set by `setZIndex`)

```lua
screen.drawRect(0, 0, 2, 2, 0xFF0000, true, 1)        -- filled red square, default layer
screen.drawRect(1, 1, 1, 1, 0x00FF00, false, 0.2)     -- green outline, default layer
screen.drawRect(0, 0, 8, 8, 0x0000FF, true, 1, 5)     -- layer 5, on top of the others
```

### screen.drawLine(x1, y1, x2, y2, colour, lineWidth, z?)

Draws a line segment. Coordinates share the same system as `drawRect`.

- `x1, y1` / `x2, y2`: start/end points (1/128 block)
- `colour`: colour (0xRRGGBB)
- `lineWidth`: line width (1/128 block)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
screen.drawLine(0, 0, 8, 8, 0xFFFFFF, 0.5)
```

### screen.drawCircle(cx, cy, radius, colour, solid, lineWidth, segments?, z?)

Draws a circle (approximated with a regular polygon). Coordinates share the same system as `drawRect`.

- `cx, cy`: center (1/128 block)
- `radius`: radius (1/128 block)
- `colour`: colour (0xRRGGBB)
- `solid`: `true` = filled circle, `false` = ring
- `lineWidth`: line width (1/128 block, only applies when `solid=false`)
- `segments`: approximation segments (default 32, minimum 3, more = rounder)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
screen.drawCircle(8, 8, 4, 0xFFFF00, true, 1)          -- filled circle
screen.drawCircle(8, 8, 4, 0x00FF00, false, 0.2, 48)   -- 48-segment ring
```

### screen.drawPoint(x, y, colour, z?)

Draws a point (equivalent to a 1×1 unit filled rectangle). Coordinates share the same system as `drawRect`.

- `x, y`: top-left coordinates (1/128 block)
- `colour`: colour (0xRRGGBB)
- `z`: layer (higher = more in front, when omitted uses the `setZIndex` default layer)

```lua
screen.drawPoint(4, 4, 0xFF0000)
```

### screen.clearRects()

Clears all drawn rectangles (does not affect text or other shapes).

### screen.clearShapes()

Clears all shapes (rectangles + lines + circles + points), does not affect text.

!!! tip "Layer reminder"
    The larger the `z`, the more in front, but each +1 moves forward roughly 0.01px; **z around `[-1, 10]` is recommended**. Setting it too large will visibly separate layers and look wrong from the side.

## Size

### screen.getSize()

Returns the number of whole character rows/columns that fit in the screen's inner area at the current font size (reference value; text is actually positioned by coordinates and is not limited by this), returning two values `cols, rows`.

```lua
local cols, rows = screen.getSize()
print(cols, rows)
```
