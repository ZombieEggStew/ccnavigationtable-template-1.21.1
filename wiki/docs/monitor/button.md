# Button Module

![Button Module](../img/button_1_item.png)

The button itself is **momentary**: pressing and releasing are separate, and `isPressed` reads the current pressed state.
It also offers three sets of capabilities — "player click detection", "player interaction lock" and "light strip control" — which can be combined into custom behaviors such as a latching button.
`press()` / `release()` play the corresponding button sound — **the sound is triggered by the button's actual action (movement)**, not by the player's raw mouse input.

## Operation
- **Configure module**: hold a wrench and right-click the module, or sneak + right-click, to open the module config interface and configure properties such as module ID and tooltip
- **Remove module**: hold a wrench and sneak + right-click to remove the module

---

## Basics: Press / Release / State

### btn.press()

Presses the button and plays the press sound (the light strip lights up with the press in "auto mode").

```lua
btn.press()
```

### btn.release()

Releases the button and plays the release sound (the light strip turns off with the release in "auto mode").

```lua
btn.release()
```

### btn.isPressed()

Returns whether the button is currently pressed (boolean).

```lua
btn.press()
print(btn.isPressed())  -- true
btn.release()
print(btn.isPressed())  -- false
```

## Player Click Detection

!!! note
    Used to distinguish between "player clicks" and "Lua-call press/release". Only actual player clicks (client → server interaction packets) update the states below; `btn.press()` is not counted.

### btn.wasClicked()

Returns whether the button has been **pressed** by a player since the last read (only triggers on the press edge 0→1; releasing the mouse does not trigger; the flag is automatically cleared after reading, suitable for edge detection).

```lua
while true do
    if btn.wasClicked() then
        print("Player clicked the button")
    end
    os.sleep(0.05)
end
```

### btn.getClickCount()

Returns the cumulative number of player clicks (each player press +1; Lua's `press()` is not counted).

```lua
local last = btn.getClickCount()
while true do
    local now = btn.getClickCount()
    if now ~= last then
        print("New clicks", now - last)
        last = now
    end
    os.sleep(0.05)
end
```

### btn.clearClicked()

Clears the "unread click" flag (without reading it).

```lua
btn.clearClicked()
```

## Player Interaction Lock (Lua fully controls the button)

### btn.setPlayerControl(enabled)

Sets the player interaction toggle.

- `true` (default): players can press/release the button as usual
- `false`: the button is fully controlled by Lua — player clicks **do not** change the pressed state and **do not** directly play sounds, but they still update `wasClicked()` / `getClickCount()` (sounds are triggered by `press()` / `release()`, following the button's actual action)

```lua
btn.setPlayerControl(false)
```

### btn.getPlayerControl()

Returns whether player interaction is currently allowed (boolean, default `true`).

```lua
print(btn.getPlayerControl())  -- true
```

## Light Strip Control

### btn.setLight(level)

Sets the light strip brightness (0..1) and automatically switches to "code-controlled" mode (after this, player interaction no longer changes the light strip).

- `0` = off, `1` = brightest

```lua
btn.setLight(1)   -- turn on
btn.setLight(0)   -- turn off
```

### btn.getLight()

Returns the light strip brightness set by Lua (0..1, default 0).

```lua
print(btn.getLight())  -- 1.0
```

### btn.setLightControl(codeControlled)

Sets whether the light strip is code-controlled.

- `true`: light strip brightness only changes with `setLight` (player interaction does not affect it)
- `false` (default): auto mode, the light strip lights up/down with the pressed state

```lua
btn.setLightControl(true)
btn.setLightControl(false)
```

### btn.isLightControlled()

Returns whether the light strip is currently code-controlled (boolean).

```lua
print(btn.isLightControlled())  -- false
```

## Examples

### Example 1: Lua-controlled latching button (light strip follows automatically)

Do not control the light strip (keep the default "auto mode"); only use the player interaction lock to take over the button behavior, turning the momentary button into a "click to toggle" latching button: the light strip automatically lights up with the pressed state.

```lua
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)
local btn = monitor.getModule(0)

-- Lock player interaction: player clicks no longer directly change the button state,
-- the script fully decides press/release
btn.setPlayerControl(false)

local latched = false

while true do
    if btn.wasClicked() then
        latched = not latched
        if latched then
            btn.press()    -- press: plays press sound, light strip turns on automatically
        else
            btn.release()  -- release: plays release sound, light strip turns off automatically
        end
    end
    sleep(0.05)
end
```

### Example 2: Latching button where the light strip shows state (button stays momentary)

Do not control the press/release behavior (keep the momentary button player-interactable); only use the light strip to show the latched state: each click toggles the light strip, which no longer follows the momentary press.

```lua
local pe = require("ccpe.pe")
local monitor = pe.getPeripheral(3)
local btn = monitor.getModule(0)

-- Hand the light strip to Lua (setLight automatically switches to code-controlled mode, no longer follows presses)
btn.setLight(0)

local latched = false

while true do
    if btn.wasClicked() then
        latched = not latched
        if latched then
            btn.setLight(1)   -- light strip on = on
        else
            btn.setLight(0)   -- light strip off = off
        end
    end
    sleep(0.05)
end
```
