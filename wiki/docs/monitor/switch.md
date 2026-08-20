# Switch Module

# 🤪

The toggle switch (`toggle_switch`) is **latching**: the state stays until changed again. State changes are synced to the client-side rendering.

## Operation
- **Configure module**: hold a wrench and right-click the module, or sneak + right-click, to open the module config interface and configure properties such as module ID and tooltip
- **Remove module**: hold a wrench and sneak + right-click to remove the module

---

Getting a module instance:

```lua
local sw = monitor.getModule(7)   -- 7 is the switch's module ID
```

## sw.getToggleState()

Returns the current latched state (boolean).

```lua
print(sw.getToggleState())  -- false
```

## sw.setToggleState(state)

Sets the latched state. `true` = on (pressed), `false` = off (released).

```lua
sw.setToggleState(true)
sw.setToggleState(false)
```

## sw.toggle()

Flips the latched state (equivalent to a player flipping the lever).

```lua
sw.toggle()
```
