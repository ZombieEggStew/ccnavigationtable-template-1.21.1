# 1.0.1
- Added `getPhysicsChainMass` and `getPhysicsChainGravityForce` methods to retrieve the total mass and gravity of the entire physics body chain.
  (Testing revealed that after assembly with a swivel bearing, the bearing's mass is counted twice, inflating the chain's total mass.)

- Fixed peripheral extender to correctly retrieve physics data when attached to blocks without a block entity.

- Fixed an issue where channel 0 could be manually selected and duplicated when already occupied.

- Removed test blocks and test items.

# 1.0.2
- Added transmission peripheral : a rotation speed controller designed specifically for CC:T control

# 1.0.3
- Added recipe for transmission peripheral

# 1.0.4
- Added wiki https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/

- **!?NEWNEW?!** Added the Monitor: a modular instrument panel with a 12×10 module grid.
  Module items (Button 1×1, Toggle Switch 1×1, Knob 2×2, Screen of any size) are
  placed by right-clicking the monitor face — and removed with a Wrench.

- **!?NEWNEW?!** Added new redstone transceiver Lua methods: setFrequency / getFrequency /
  removeChannel / getChannels to manage Create Redstone Link frequencies.

- Fixed PeripheralExtender / RedstoneTransceiver configuration being lost or stale
  in Create schematics (blueprints):
  - `saveAdditional` no longer writes runtime cache fields (AttachedNBT / OccupiedChannels),
    so schematic .nbt files only contain real configuration. Create's schematic save uses the
    vanilla StructureTemplate → saveWithFullMetadata path, which never calls
    PartialSafeNBT.writeSafe (writeSafe only applies at deploy time).
  - serverTick now also refreshes cachedAttachedNBT (previously only
    cachedAttachedCompoundTag was refreshed), eliminating stale attached-NBT snapshots
    in saves and client sync.
  - Added getUpdatePacket() to both block entities and sendBlockUpdated after banner /
    load-mode changes, keeping client block-entity data fresh (quill schematic saves read
    client-side block entities).


# 1.0.5
- Fixed dedicated server crash: the Toggle Switch item referenced client-only rendering
  classes (BlockEntityWithoutLevelRenderer) at registration, failing mod load on dedicated
  servers. The item now uses a static OBJ model (Knob-style) — server-safe.

# 1.0.6
- Added servo mode to the Transmission Peripheral

# 1.0.7
- Reworked screen rendering; the monitor background can no longer be drawn on.
- Added physical rotation limits to the Knob.
- Fixed outline rendering on moving physics bodies (preview no longer lags or ghosts).

# 1.0.8
- getRelativePercent,getAbsolutePercent return 0-100 -> 0-1
- **!?NEWNEW?!** Added control desk system, including two joysticks, two throttles, one dual pedal, one screen, expansion dock and baffle.

# 1.0.9
- Added isAxisXPositive/isAxisXNegative/isAxisYPositive/isAxisYNegative for joystick.
- Added reaim mode for transmission peripheral.
- **!?NEWNEW?!** Added the Aero Bearing, Check the wiki for more details.
- **!?NEWNEW?!** Added a new sensor system, Check the wiki for more details.

# 1.1.0
- Added the Aviation Integrated Computer (AIC), Check the wiki for more details.

# 1.1.1
- **IMPORTANT!!!** Control desk channels are now **physics-body-scoped**, Check the wiki for more details(https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/control-desk/overview/).
- **!?NEWNEW?!** Added the Fluid Port
- **!?NEWNEW?!** Added the Short Range Linker, Check the wiki for more 
- Fixed aero bearing not adjusting its mass when assembled properly
- Fixed crash "Not building!" when opening the Diagram screen (Aeronautics) with a control desk inside an assembled physics body (Sable sub-level). 
- Interactions can no longer pass through the Monitor.
- Added an optional color parameter to screen.writeField.
- Added drawCells / drawText / setVisible / isVisible (Lua methods).
- getPhysicsCenterOfMassRel is now relative to the FMC block center; added getPhysicsChainCenterOfMassRel.
- monitor_2 can no longer be washed away by water. When dismantling a control desk carrying a monitor_2 with modules, the modules now drop to the ground.

# 1.1.2
- Added the position light.
- ccpe.sensor_system.getAngularVelocity()：world frame → Body-frame
- Added the trailing wheel mount

# 1.1.3
- Added free mode for the Throttle.
- Added altitude-pressure conversion tools, sail aero tools, the universal drag tool and the max-cruise-altitude solver to the FMC. Check the wiki for more details.
- Added the Flight Data Recorder system.
- Added getVelocity / getAngleRates to the INS.
- Added steering to the trailing wheel mount. Check the wiki for the control method.
- Improved the Examples & Tutorials section of the wiki.

# 1.1.4
- fluid port 0.5kg->0.25kg
- Added the quick fill fluid tank and quick fill fuel vault.
- Added the modular engine
- Added the joystick_3
- Added the servo_bearing

- Fixed cross-thread visibility on the trailing wheel mount: `steeringSignal` / `chasingYaw` / `lastChasingYaw` are now `volatile`.
  (The server thread writes them while CC Lua computer threads read them via `getSteering`/`getSteeringAngle`, and the client render thread reads `chasingYaw` through `getLerpedYaw`.)
- Fixed a torn-snapshot window in the altitude-pressure conversion: the atmosphere curve snapshot (base pressure, anchors, Y bounds) is now published as a single immutable record through one `volatile` reference instead of four separate `volatile` fields.
- Fixed `TrailingWheelMountPeripheral.equals` comparing only BlockPos: it now compares the block entity identity, so a peripheral at the same coordinates in another dimension (or a re-placed block entity after a chunk reload) is no longer treated as the same peripheral.
- `_analyze_flight6.py`: guarded the half-period / full-period calculation against an empty extrema list — short or strongly damped flights no longer raise `ZeroDivisionError`.
- `_verify_pressure.py`: the hard-coded `_8_delaytest` detail log is now optional (skipped with a notice when missing) instead of failing the whole analysis.
- Flight Data Recorder docs now consistently describe the actual **FMC/AIC/INS** scope (INS-only bodies are recorded too) and correct the "default on" wording to "default off": Config comment, class Javadoc, en_us / zh_cn tooltips, and both wiki pages.

# 1.1.5
- Mixture now has a much stronger effect on heat output (linear heat factor `2 − m`, both lean and rich). Check the [wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/temperature/) for more details.
- Widened the fluid engine's economy-factor mixture window: the mixture condition is now `0.8 ≤ actual mixture ≤ 1.1` (was `|actual mixture − 1| ≤ 0.05`).

- The Fluid Combustion Chamber can now burn Aeronautics' Levitite Blend fluid and Create's Chocolate fluid. Check the [wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/fluid-combustion-chamber/) for more details.
- Added `getAutoRichness` Lua method. Check the [wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/lua-api/) for more details.

- engine.getFluidTanks() now mainThread = false

# 1.1.6
- Fixed the control desk throttle clipping through the desk surface

- Monitors can now be hung upside-down from ceilings : only the base and bearing flip, while the screen body (case) stays upright —
  hit-testing, interaction, config menu and removal all keep their ground behavior.

- Added the Monitor Slab, a step-shaped monitor block with a 14×14 surface grid. Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/monitor/monitor-slab/

# 1.1.7
- Transmission peripheral: `setTargetSpeed` now accepts **negative speeds (-256 ~ 256)**  and `setRatio` accepts **negative ratios** — a negative value makes the downstream rotate **opposite to the input**.

  Stress is now charged on the signed difference `|output − input|`, so reversing costs more stress than slowing down in the same direction.

# 1.1.8
- Added the thrust nozzle: Mounts on the front face of an Encased Fan and turns its airflow into axial thrust - far stronger than the fan on its own.

# 1.1.9
- Fixed a dedicated server crash (`NoSuchFieldError`) when placing or breaking the Engine Core, the Fluid Combustion Chamber or the Steam Power Chamber.

1.2.0
- Added the auto packager.

# 1.2.1
- Added the water cooling module. Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/water-cooling-module/

- **IMPORTANT!!!** Reworked how the engine makes power: each chamber now provides a **fixed amount of stress** (8192 SU per fluid combustion chamber, × its fuel multiplier; 4096 SU per steam power chamber) that **no longer changes with the throttle**. The throttle now only sets the output **rotation speed** (0-256 rpm, 0 = stopped).Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/engine-core/

- Engine fuel consumption now follows the **load on the stress network** — how many machines are attached and how hard they are working — instead of the throttle. An engine with nothing attached still burns a little at idle (20%) so that it stays warm instead of shutting down.

- Added `getLoad`, `getStressUsed` and `getStressCapacity` Lua methods to the engine peripheral. Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/lua-api/


# 1.2.2
- **IMPORTANT!!!** Reworked the Aero Bearing:
  - Its shaft now runs **through** the block, so rotation and stress continue straight into the assembled structure (input speed = output speed).
  - Power no longer turns the plate on its own — it only costs stress and drives the machines inside the assembled structure. The plate's angle is set from Lua with `setTargetAngle` only, and it **cannot move while there is no input rotation**. (`setControlMode` is now a no-op, kept so old scripts still run.)
  - Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/aero-bearing/overview/


# 1.2.3
- **NEW!** Added the Advanced Thrust Nozzle: Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/actuators/advanced-thrust-nozzle/

- **NEW!** The fluid engine now comes with **built-in automatic control** — no Lua script needed. It holds the actual mixture at ~0.9 (comfortably inside the economy window), drives the cooling air duct with a PI+D loop, runs the water cooling loop from a heat / cooling **model feedforward**. It is **on by default**, including in existing saves. Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/auto-control/

- **IMPORTANT!!!** Cooling rebalanced: passive cooling is weaker and air cooling is stronger, so a small engine at low load can finally reach the economy band (1 fluid chamber + 2 core segments at 25% load used to sit at ~112°C forever and now settles at ~157°C). The core's own dissipation dropped from `0.02` to `0.01` per segment and chambers' ambient convection from `0.05` to `0.04`, while each cooling air duct now dissipates `0.09` (was `0.05`). An engine **with** ducts feels about the same, but an engine **without** radiators overheats much sooner at high load. Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/temperature/

- Reworked where airflow and altitude apply to cooling:
  - **Ram air now only boosts the cooling air duct** — bare core / chamber surfaces cool by natural convection and no longer get a free ×2 for flying fast (real radiators are what care about the airstream).
  - **Standing still halves the duct's dissipation** (×0.5 at 0 m/s, back to ×1.0 by 10 m/s, up to ×2.0 at 30 m/s), so holding high power on the ground now overheats — just like a real engine. Add water cooling or lower the load for sustained full power while parked.
  - The **water cooling module** gets a small share of both the airflow bonus and the altitude pressure penalty (25% each) and is **not penalised when standing still**, so it stays the answer for ground / low-speed operation.

- The water cooling module dissipates a bit more per module: `0.37` (was `0.33`). Check the wiki for more details: https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/engine/water-cooling-module/

- The water cooling module now **draws on the engine's output**: **512 SU per working module**, configurable with `engineWaterCoolingStress` (`0` = free). **No shaft connection is needed**. It is free when the tank is empty, when the loop is closed with `setWaterCooling(0)`, or on a steam engine (where water cooling does nothing anyway).

- Added `isReversed` / `setReversed(bool)` Lua methods to the engine peripheral: reverses the **output rotation direction** (`e.setReversed(true)`). 

- Added an in-world **hold-right-click panel** on the engine core to pick the output direction (Forward / Reversed). **Hold a Wrench** to open it — with an empty hand or any other item the core side shows no panel and right-click stays normal block placement / item interaction (such as attaching chambers). Works on any core segment and shares its state with the Lua method above.

# 1.2.4
- The engine's **default throttle is now 100% (256 rpm)** .

- Fixed the engine refusing to take fluid out of **Simulated (Aeronautics) fluid containers** — the docking connector in particular.

- Added `getStrongPower` / `setStrongPower` to the `ccpe.sensor_system` Lua API: the Short-Range Signal Linker can now **strong-power the block it is attached to**, like a lever (only toward the support face; strength = its redstone output).

- Added `getEngine` to the `ccpe.sensor_system` Lua API: `local e = ss.getEngine()` hands you the engine's `ccpe:engine` peripheral directly by body scope — no `peripheral.wrap`, no wired-network reachability needed, so an engine far from the computer is fine. It only returns a peripheral when the body (constraint chain included) has **exactly one** engine; with no engine or with several engines (e.g. a twin-engine craft) it returns `nil`. Resolved at call time and cached nowhere, so grab it once at the top of your script and keep it in a local. Costs nothing per tick.

- Added `setInverted(bool)` / `isInverted()` to the Advanced Thrust Nozzle: instant thrust ⇄ suction reversal. The sign of the force along the tilted axis flips (strength, deflection and output scale untouched), exhaust particles switch to intake in the same step, and the state is stored in NBT / blueprints.

# 1.2.5
- Fixed the **Aero Bearing making the stress load climb on every save and load**.
  Saves that were already hit by this keep the leftover until **that** stress network is rebuilt: break and re-place the blocks on it (the bearings, plus the shaft connecting them — and the engine core as well if it sits on the same network). Freshly placed blocks start from a clean slate, so the load drops back to its true value. 

- **Changed** the `getEngine` scope added above to a **single physics body**: `ss.getEngine()` now only looks at the one Sable sub-level the computer sits on (the constraint chain's other sub-levels no longer count). 


# 1.2.6
- Fixed high-altitude contrails still being emitted while an Advanced Thrust Nozzle is running with `setThrustScale(0)`. 

- Widened the contrail altitude band from **Y 160 and above** to **Y 100–320**.

# 1.2.7
- Contrail particles now leave the nozzle with a **burst of initial velocity along the exhaust direction** (random magnitude, direction randomized inside a configurable cone) that decays every tick, so the trail shoots out and then settles into its slow drift. New config: `contrailBurstSpeedMean`, `contrailBurstSpeedSigma`, `contrailBurstSpread`, `contrailBurstDamp`.



