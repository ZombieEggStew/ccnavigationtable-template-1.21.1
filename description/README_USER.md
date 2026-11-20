# CCPE — Aircraft Systems

> **Fly-by-wire for Create: Aeronautics.**
> Sensors, actuators, cockpits and engines that speak ComputerCraft: Tweaked — read the whole physical state of a ship and command it at the 20 Hz Minecraft tick.

**[Wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/) · [Examples](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/examples/trainer_aircraft/) · [GitHub](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1) · [Changelog](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/changeLog.md)**

---

## Why this mod exists

Create: Aeronautics gives your contraption real physics: Sable sub-levels with mass, lift, thrust and torque. What it does not give you is a way to **close a control loop**. The native interface is redstone, and redstone was never designed for flight control:

- **Everything is 4-bit.** A gimbal sensor quantises attitude to **3.1° per step** at its default ±45° range (**6.2°** at ±90°). A default altitude sensor spans the entire world height in 15 steps — **25.6 m each**. A velocity sensor gives **0.67 m/s per step** with a 0.05 m/s dead zone, and the sign lives on a different face.
- **The chain is long.** Sensor → redstone link → comparator / analog transmission → mechanical bearing costs **3–4 ticks end to end (150–200 ms typical)**. Every comparator stage you add for a "target minus actual" loop costs another 1–2 ticks, and the error is still 4-bit.
- **There is no control law.** Redstone can express a static ratio, a switch and a timer. It cannot express a quaternion PD, a cascade, feedforward, gain scheduling, a deadband with hysteresis, a watchdog — or a CSV log you can analyse afterwards.

CCPE replaces that chain with `sensor → Lua → actuator` on the **same 20 Hz tick**, but with doubles and real code:

| | Redstone chain | CCPE Lua chain |
|---|---|---|
| Signal | 0–15, 4-bit, unsigned, per-face | double, signed, three axes |
| Attitude reading | 3.1°/step (±45°), 6.2° (±90°) | exact quaternion, no 4-bit steps |
| Altitude reading | 25.6 m/step (default range) | metres, sub-0.1 m in flight |
| Speed reading | 0.67 m/s/step + 0.05 m/s dead zone | m/s, exactly 0 at rest |
| Sensor → actuator | 3–4 ticks (150–200 ms) | 2–3 ticks (100–150 ms) |
| Actuator resolution | 16 speed steps, on/off, open-loop 1° | thrust 0.00–1.00, angle servo |
| Control law | static ratio, switch, comparator | PD / cascade / feedforward / watchdog |

Both chains are capped by Minecraft's 20 Hz tick — **this mod does not beat the tick.** What it removes is the 4-bit quantisation and the 1–2 extra ticks of redstone conversion. Closed-loop bandwidth estimated from the source study: **0.2–0.5 Hz** with redstone versus **0.5–1 Hz** for attitude with CCPE. Position and terminal guidance sit at 0.1–0.3 Hz with CCPE — that limit is the airframe, not the wire.

## What that feels like in flight

Numbers measured in the mod's own flight projects, not estimated:

- **20 Hz control loop** — cycle time 0.048 / 0.050 / 0.052 s (min / median / max) over a full autopilot flight.
- **0.15 s** from a `setThrustScale` step to a visible physical response.
- **±0.06 m** altitude hold.
- **0.42 m** terminal error on a guided approach, **0.2 m** on a Starship-style catch; attitude recovered to cos = 1.00 **1.5 s** after an impact.
- [Demo: a Starship Flight 15 catch](https://www.youtube.com/watch?v=EJPllPvQiYY) — five vector nozzles, one AIC, two computers, flown to the tower and caught by its arms.

None of that is a faster-than-tick trick. It is what 20 Hz looks like when every tick carries real numbers and a real controller.

## Hello, world: a 20 Hz control loop

```lua
local ss   = require("ccpe.sensor_system")
local elev = assert(peripheral.wrap("left"),  "servo bearing (elevator) missing")
local ail  = assert(peripheral.wrap("right"), "servo bearing (aileron) missing")
local eng  = assert(ss.getEngine(),           "need exactly one engine on this body")

elev.assemble()                              -- once, after placing each control surface
ail.assemble()

local TARGET_PITCH, TARGET_ROLL = 5, 0     -- degrees
local TARGET_ALT               = 120       -- metres
local KP, KD, ALT_KP           = 1.5, 0.4, 0.02

while true do
    local a, r   = ss.getAngles(), ss.getAngleRates()   -- free: cache reads
    local alt, v = ss.getAltitude(), ss.getVelocity()
    if not (a and r and alt and v) then error("need an INS and a static port") end

    local pitch = math.max(-25, math.min(25, KP * (TARGET_PITCH - a.pitch) - KD * r.pitchRate))
    local roll  = math.max(-25, math.min(25, KP * (TARGET_ROLL  - a.roll ) - KD * r.rollRate))
    local thr   = math.max(0, math.min(1, 0.5 + ALT_KP * (TARGET_ALT - alt) - 0.5 * v.y))

    -- one parallel block = one tick of writes, however many are in it
    parallel.waitForAll(
        function() elev.setTargetAngle(pitch) end,
        function() ail.setTargetAngle(roll)   end,
        function() eng.setThrottle(thr)       end
    )
    -- no sleep: the block above already costs exactly one tick
end
```

Every `set...` method is queued onto the server main thread and returns at the start of the next tick. Batched in one `parallel.waitForAll`, they all land in that same tick, so the whole loop — read, compute, command — costs exactly **one tick (20 Hz)**. Called one by one, each write would take its own tick (four sequential writes measured 4 Hz); split across two parallel blocks you get 10 Hz; 10–14 writes in one block still run at 20 Hz. This is the pattern every example program uses. Gains and signs are airframe-specific — start small. The full autopilot (auto-roll, auto-throttle, altitude hold) is in the [trainer aircraft example](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/examples/trainer_aircraft/) — with a downloadable world.

## What's in the box

**🛰️ Sense — [Sensor System](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/overview/)**
One Lua module, `require("ccpe.sensor_system")`, body-scoped to the ship your computer is standing on. [Static port](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/static-port/) (pressure, altitude), [pitot tube](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/pitot-tube/) (ground and air speed), [INS](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/ins/) (attitude, quaternion, angular rate, velocity), [FMC](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/fmc/) (mass, gravity, centre of mass, stress, propeller and aero solvers), [AIC](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/aic/) (INS + FMC in one block), plus a [flight data recorder](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/flight-data-recorder/) that logs every tick to CSV. Readings refresh once per tick and cost the main thread nothing.

**🎮 Command — actuators**

- [Advanced Thrust Nozzle](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/actuators/advanced-thrust-nozzle/) — points anywhere inside a 15° cone, 2 pN/RPM, thrust scale and deflection driven from Lua.
- [Aero Bearing](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/aero-bearing/overview/) — Sable-physics bearing with axial power; in Lua mode the angle is written directly.
- [Servo Bearing](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/actuators/servo-bearing/) — pure Lua angle servo, rigid, no power input, no visual crawl.
- [Electronic Transmission](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/electronic-transmission/overview/) — a speed controller built for CC:T: 0.01 RPM steps, reverse, servo mode.
- [Engine System](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/overview/) — modular multi-block power; throttle sets speed, **fuel burn follows the stress load** instead of the throttle.

**🪑 Cockpit**

- [Control Desk](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/control-desk/overview/) — sit down and fly: joysticks, throttles, pedals and screens, all readable from Lua.
- [Modular Monitor](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/monitor/overview/) — a 12×10 grid of buttons, toggles, knobs and screens you place and script yourself.

**🔌 Connect**

- [Peripheral Extender](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/peripheral-extender/overview/) — NBT over channels, remote peripheral calls, wireless redstone, chunk loading.
- [Redstone Transceiver](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/redstone-transceiver/overview/) — read and send Create Redstone Link frequencies without stacking link blocks.
- [Short-Range Signal Linker](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/sensor-system/short-range-linker/) — per-body channels and redstone I/O for physics bodies.


## What it won't do

- **It will not beat the tick.** Everything samples at 20 Hz and the physics still runs at 60 Hz. Usable closed-loop bandwidth is under 2 Hz; high-frequency vibration is still a job for mass and damping.
- **It will not bypass physics.** Thrust, inertia, constraints and collisions are all still Sable's.
- **It will not make redstone obsolete.** For slow trim, limit switches and dead-man hold, redstone is still the right tool.

## Where this came from

CCPE did not start as an aircraft mod. It started as **CC Navigation Table**, a tiny addon that read the coordinates out of the navigation table. Then came **Microcontrollers** — a computer mod distinct from CC:Tweaked, whose Sensor connected wirelessly over channels and read the target block's NBT directly, with no per-mod peripheral adapter. That "universal sensor" idea became the **Peripheral Extender**.

After that, the mods I depended on could not do what I wanted, and waiting for someone else's update felt slower than writing it myself. One block at a time the mod grew the whole chain — **sensors → flight computers → actuators → engines** — until "peripheral extender" no longer described it. So it is now **CCPE — Aircraft Systems** (formerly CC Peripheral Extender).

It is still in development. If something is missing, or you have an idea for a block or an API, open a feature request.

## Requirements

- **NeoForge** 1.21.1
- **CC:Tweaked** 1.118.0+
- **Create** 6.0.10+
- **Simulated** (Create: Aeronautics) 1.3.0+
- **Sable** 2.0.3+

## Links

- [Wiki & full Lua API](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/)
- [Examples: trainer aircraft](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/examples/trainer_aircraft/) · [Starship catch](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/examples/starship/)
- [Issue tracker](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues)
