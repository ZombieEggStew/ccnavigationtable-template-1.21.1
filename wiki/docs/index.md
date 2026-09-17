# CCPE — CC Peripheral Extender

!!! info "Welcome"
    CCPE (CC Peripheral Extender) is a mod for Minecraft NeoForge, built for Create: Aeronautics and ComputerCraft: Tweaked users, providing powerful wireless peripheral control.

    **No Mixin** — This mod does not use Mixin, so it is unlikely to cause compatibility issues with other mods.

![img](img/overview.png)

## ✨ Core Features

### [📡 Peripheral Extender](peripheral-extender/overview.md)

A versatile remote terminal that supports:

- **NBT data reading** — access block data wirelessly via channels
- **Peripheral proxy** — call CC:T peripheral methods remotely
- **Wireless redstone** — send and receive redstone signals
- **Navigation table integration** — get the aircraft's position, bearing and distance
- **Physics data** — read velocity, mass and orientation from the Sable physics engine
- **Chunk loading** — keep the area around the target block or a physics structure loaded

### [🤪 Modular Monitor](monitor/overview.md)
A 12×10 grid of module slots, freely controllable from Lua, satisfying interaction and information display needs in various scenarios.

### [🪑 Control Desk](control-desk/overview.md)
A seat-driven modular control console that supports:

- **Sit & drive** — sit on a Create seat to enter control mode and drive all linked consoles with your keyboard
- **Modular controls** — install [foot pedals](control-desk/pedal.md), a [joystick](control-desk/joystick.md) and a [throttle](control-desk/throttle.md), with analog travel and gear modes
- **Lua access** — read control states in real time via the CC:T Lua API (`ccpe:control_desk`)

### [📻 Redstone Transceiver](redstone-transceiver/overview.md)
Directly read and send Create Redstone Link signals without stacking redstone link blocks next to your computer.

### [🎛️ Electronic Transmission](electronic-transmission/overview.md)
A rotation speed controller optimized for CC:T control, avoiding the network cascade issues of Create's vanilla controller.

### [🍌 Aero Bearing](aero-bearing/overview.md)
A Sable-physics bearing with direct axial power input. In **Lua Control mode** the rotation angle is set directly via Lua, skipping the stress-network angle accumulation — position your sail/control surface exactly.

### [🔧 Engine System](engine/overview.md)
A modular, multi-block Create power source for aircraft, driven by a **single throttle lever**:

- **[Engine Core](engine/engine-core.md)** — cores snap into one engine (up to 21 blocks) sharing a running state; speed = throttle × 256 rpm
- **[Fluid Combustion Chamber](engine/fluid-combustion-chamber.md)** — datapack fuel, 8192 SU per chamber, mixture/economy gameplay
- **[Steam Power Chamber](engine/steam-power-chamber.md)** — water + vanilla fuel, 4096 SU per chamber, never overheats, residual-heat operation
- **[Cooling Air Duct](engine/cooling-air-duct.md)** — cooling fins & adjustable shutter
- Lua control via `ccpe:engine` (no redstone)

### [🛰️ Sensor System](sensor-system/overview.md)
Aviation sensors for physics bodies (Sable sub-levels), all readable from Lua via `ccpe.sensor_system`:

- **[Static Port](sensor-system/static-port.md)** — pressure & altitude readings at the port's own position
- **[Pitot Tube](sensor-system/pitot-tube.md)** — directional speed sensor, ground speed & airspeed along the tube's mouth axis
- **[INS](sensor-system/ins.md)** — attitude indicator: pitch / roll / yaw, position, orientation quaternion and angular velocity
- **[FMC](sensor-system/fmc.md)** — physics data: mass, gravity force, center of mass; plus the attached block's Create stress network (remaining / capacity stress)
- **[AIC](sensor-system/aic.md)** — one block that counts as both an INS and an FMC

---
## 🚀 Quick Start

[Examples & Tutorials](examples/trainer_aircraft.md) — see real-world use cases


## 🔗 Related Links

- [GitHub Repository](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1)
- [Issue Tracker](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/issues)
- [Changelog](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/changeLog.md)
