# Changelog

## 2026-08-22 — Screen rendering rework (cell/grid model)

**Breaking changes:**

- Removed the Monitor's **background-plane drawing API** (`monitor.write` / `clear` / `drawRect` / `drawLine` / `drawCircle` / `drawPoint` / `setCursorPos` / `setTextScale` / `setTextColour` / `setZIndex` / `setOverflowMode` / `clearRects` / `clearShapes` / `getSize`). Content can only be drawn on a **screen module**. The Monitor's grid lines and background texture are unchanged.
- Screens now use a **cell/grid model** (LCD framebuffer semantics): `setGrid(cols, rows)` defines the grid; text is written per cell from a cursor (`setCursorPos`/`write`), writing overwrites the cell, and the content size is fixed (no more unbounded growth).
- New screen API: `setGrid` / `getGrid`, `fill`, `draw(batch)` (full-screen atomic replacement), `getSize` now returns the grid size. `setTextScale` is kept as an alias for `setGrid` (with an optional aspect-ratio argument); the `z` parameter of `write` was removed (z is graphics-layer only).
- Old save data with free-positioned screen text (no `cols` field) is reset on load.
- Network sync is now gzip-compressed (`SyncGridPayload`), mitigating the 2 MiB packet limit.
- Lua programs using the old free-positioned text API need to be rewritten to the cell model (see [Screen Module](monitor/screen.md)).

**New rendering (referencing vanilla `SignRenderer`):**

- Render plane is the screen module's outer face; glyphs use `RenderType.textPolygonOffset` (polygon-offset depth separation, no manual z-fighting offsets).
- Cell background quads (`fill`), glyph quads per cell, and a free-positioned graphics layer (rect/line/circle) with z layering.
