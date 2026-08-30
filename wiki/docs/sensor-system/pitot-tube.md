# Pitot Tube

> A lightweight and intelligent way to read aviation data

![pitot_tube](../img/pitot_tube.png)

The **Pitot Tube** (`ccpe:pitot_tube`) is an attachable, **directional** speed-sensor block. When installed on a physics body (Sable sub-level), CC:Tweaked computers on that body can read the **velocity component along the tube's mouth axis** — both ground speed and airspeed — via `ccpe.sensor_system`.

The pitot tube measures **only** the component of motion along its mouth axis, as a **signed** scalar (positive = moving toward the mouth, i.e. air entering the mouth; negative = moving away). A tube aimed 90° to the motion reads ≈ 0 — aim the mouth where you want to measure.

!!! note "Signed readings are a mod convenience"
    A real pitot-static system can only measure the **magnitude** of airspeed — it cannot tell whether the airflow comes from the front or from behind. The sign in these readings is deliberately added by the mod so scripts can distinguish direction (positive = toward the mouth); it has no real-world counterpart.

## Orientation (24 states)

The tube's orientation is described by two block-state properties:

| Property | Values | Meaning |
|---|---|---|
| `facing` | 6 directions | The direction the model's **top face** points; set to the **clicked face** on placement |
| `roll` | 0–3 | Rotation around the top-face normal (0°/90°/180°/270°) |

- **Placement**: right-click a surface — `facing` = the clicked face, `roll` = 0. The tube attaches to the block behind it (opposite the `facing` direction) and drops if that block is removed.
- **Wrench**: right-clicking the model's **top face** (the face in the `facing` direction) rotates the tube around that face (`roll` +1, `facing` unchanged); right-clicking any other face does nothing.
- The 24 combinations cover every orientation of the mouth axis — use the wrench (and the selection box) to aim the mouth along the direction you want to measure.

## Reading reference point

`speed`/`air_speed` are read at the **pitot tube's own position**:

- Velocity = the **world point velocity at the pitot tube** (includes the rotational contribution ω×r; same algorithm as `simulated:velocity_sensor`), projected onto the **mouth axis** (the tube's 24-state orientation, rotated to world by the physics body's pose).
- A body can carry multiple pitot tubes, each with its own independent reading.
- Readings refresh once per tick (at most 1 tick stale); Lua reads perform zero main-thread scheduling, so high-frequency calls are effectively free.

## Pitot-static gate

Speed readings require a complete **pitot-static system**: the physics body (including constraint chains) must have **at least 1 pitot tube AND at least 1 static port** at the same time. Without either one, `getSpeed()/getAirSpeed()` return `nil`, and the pitot entries in `getSensors()` carry `speed = nil, air_speed = nil`.

> Physically, airspeed is derived from the difference between total pressure (pitot tube) and static pressure (static port) — a pitot tube alone cannot produce an airspeed reading.

## Lua API

A computer on the same physics body (including constraint chains) uses `require("ccpe.sensor_system")`:

| Method | Returns | Description |
|---|---|---|
| `getSpeed()` | number / nil | **Ground speed** along the mouth axis of the **most recently placed** pitot tube (m/s, signed; positive = toward the mouth). `nil` when the pitot-static gate fails |
| `getAirSpeed()` | number / nil | **Airspeed** along the mouth axis (m/s, signed; relative to the air, wind subtracted). Same gate; differs from `getSpeed()` only when a wind source is present |
| `getSensors()` | table | Snapshot of all sensors (same tick); pitot entries carry `{type="pitot_tube", pos={x,y,z}, pos_rel={x,y,z}, speed, air_speed}` (`nil` readings when the gate fails) |

Shared methods (`isOnBody()`, `getBodyId()`, ...) behave as documented on the [Static Port](static-port.md) page.

- **Ground vs air speed**: `getSpeed()` uses the world velocity; `getAirSpeed()` uses the velocity **relative to the air** (`Sable.HELPER.getVelocityRelativeToAir`, wind subtracted). Sable registers no wind by itself — without a wind-providing mod (e.g. PMWeather) both return the same value.
- **Dead zone**: |reading| < 0.05 m/s is clamped to 0 (stationary → 0).
- Multiple pitot tubes: `getSpeed()/getAirSpeed()` use the **most recently placed** one (registration order = placement order — see the restart warning below).

```lua
local ss = require("ccpe.sensor_system")

print("onBody:", ss.isOnBody())
print("ground speed along mouth:", ss.getSpeed())
print("airspeed along mouth:    ", ss.getAirSpeed())

local sensors = ss.getSensors()
for i, s in ipairs(sensors) do
    if s.type == "pitot_tube" then
        print(i, "pos:", s.pos.x, s.pos.y, s.pos.z,
              "speed:", s.speed, "air_speed:", s.air_speed)
    end
end
```

## Multiple pitot tubes

Each pitot tube has its own independent `speed/air_speed` reading (same-tick snapshot). `getSensors()` is the way to read a **specific** tube:

```lua
local sensors = ss.getSensors()
for _, s in ipairs(sensors) do
    if s.type == "pitot_tube" and math.abs(s.pos_rel.y - 2) < 0.5 then
        print("that tube's airspeed:", s.air_speed)
    end
end
```

!!! warning "After a server restart, `getSpeed()/getAirSpeed()` may point at a different pitot tube"
    If the body has only one pitot tube, this warning does not apply.

    `getSpeed()/getAirSpeed()` return data from the **most recently placed** pitot tube, determined by registration order (= placement order), which is only valid **within the current session**. After a server restart, pitot tubes re-register in chunk-load order, so these two methods **may point at a different tube**, and the target may differ between restarts.

    If your script must reliably read a **specific** tube, use `getSensors()` and identify it by `pos_rel` (position relative to the current computer) — `pos` (relative to the body origin) drifts when blocks are added to or removed from the body, since the origin (center of mass) moves.
