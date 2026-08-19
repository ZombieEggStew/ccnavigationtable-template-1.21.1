---
name: create-style-gui
description: "Use when implementing or changing this mod's Create-style configuration screens, GUI textures, icon buttons, toggle buttons, or configuration sections."
---

# Create-Style GUI

Read `foundation/gui/` and the target screen before editing. Existing reusable controls are `HoverTintIconButton`, `ToggleButton`, and `MyIcons`.

## Required practices

- Create widgets in `init()`, never in `render()`.
- Draw custom background and text before `super.render()` so widgets render on top.
- Override the vanilla background when it would cover custom artwork.
- Reset `GuiGraphics.setColor()` to white after tinted drawing.
- Keep layout as named pixel constants relative to the window origin.
- For a module-specific menu section, use `addSectionWidget()` and a new `ModuleConfigSection` instance per menu.

Use Create `AllIcons` and `AllGuiTextures` for generic controls; reserve `MyIcons` for mod-specific icons. The last `blit` dimensions are source texture dimensions, not destination dimensions.

## Check

- Open the target screen, hover and click each control, then close and reopen to verify persistence.
