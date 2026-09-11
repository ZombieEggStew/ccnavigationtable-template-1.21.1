# Aerodynamics: Real vs Game

> How regular sails (Create `SailBlock`) and symmetric sails (Simulated Symmetric Sail) produce lift and drag in this game, and why real-world aerodynamics cannot be copied over. The formulas and coefficients come from the Sable / Simulated sources (`BlockSubLevelLiftProvider.sable$contributeLiftAndDrag()`), the same model behind the [Flight Management Computer](../sensor-system/fmc.md) tools `solveSailLift` / `solveSailDirectionlessDrag`.

## Game aero ≠ Real aero: three fundamental differences

Keep these three in mind — every "placement rule" follows from them, not from aviation theory:

1. **Force directions are body-fixed; they do not flip with the airflow**
   Real life: wing lift is perpendicular to the airflow and continuously redirects with angle of attack. Game: regular sail lift always points along the sail normal `+n`, symmetric sail drag along `±n`, directionless damping along `-v`. → The sail's orientation decides the force direction: upside down, wing lift pushes downward.

2. **Force magnitude ∝ local airflow speed (linear), not v²**
   Real life: dynamic pressure q = ½ρv². Game: each sail F ≈ k·P·Δt·v. → Aero forces decay linearly at low speed; at zero speed there is no aero force at all.

3. **The angle-of-attack (α) effect comes only from the (n·v) term, and only symmetric-sail normal drag is an "odd function"**
   Regular sail lift **decreases** with (n·v)² (even function, symmetric in ±α); symmetric sail normal drag is the linear (n·v) term (**odd function** — nose-up and nose-down perturbations produce equal and opposite restoring forces).

→ Corollary: the real-world rule "center of gravity ahead of the aerodynamic center → static stability" relies on wing lift **growing** with α. In the game, regular sail lift is symmetric in ±α and **shrinks**, so that rule cannot be copied. True **two-way static stability** comes from an odd-function surface (symmetric sail normal drag) placed behind the center of gravity — it acts like a weathervane / α-spring that pulls the body back into alignment with the airflow.

---

## Common skeleton: computed per sail per physics substep

Every physics substep Δt, for every sail, the algorithm has the same shape (parameters differ — see the next two sections):

1. **Normal n**: regular sail = `FACING` opposite direction; symmetric sail = `AXIS` positive direction. Inside a contraption, first rotate it via `localPose` into the sub-level frame.
2. **Local airflow speed** (taken at the sail block center):
   ```
   v = R⁻¹( body linear velocity V + angular velocity ω × (block center − sub-level origin) )
   ```
   → Sails far from the COM "feel" the rotation and automatically generate aero damping.
3. **Air pressure P** at that position: `DimensionPhysicsData.getAirPressure(...)`, decaying with altitude per the dimension pressure curve (overworld ~300 m ≈ 0.39).
4. **Normal drag** (along the sail normal): magnitude = `k1·|n·v|·P·Δt`, applied to the body negated → cancels the velocity component along the sail normal.
5. **Directionless drag** (linear damping, opposite v): magnitude = `k2·|v|·P·Δt`, always cancels velocity.
6. **Lift** (regular sails only — see below): direction always along `+n`, magnitude = `k3·|TEMP|·P·Δt`.
7. **Application point** = sail block center (pos+0.5); **moment τ = (application point − COM) × force** → the farther a sail is from the COM, the larger the moment from the same force.

---

## Regular Sail (Create SailBlock): lift + drag

Injected by a Create-compat mixin (`SailBlockMixin`), all parameters at Sable defaults:

| Parameter | Value | Meaning |
|---|---|---|
| Normal n | `FACING` opposite | Axis of lift / normal drag |
| Lift coefficient k3 | 0.475 | Only regular sails produce lift |
| Normal drag coefficient k1 | 0.75 | Drag perpendicular to the sail face |
| Directionless drag coefficient k2 | 0.06888202261 | Linear damping (`(−0.75+√(0.75²+0.475²))/2`, the minimum damping that exactly suppresses default-lift divergence) |

Per physics substep:

1. **Normal drag** (k1 = 0.75): `F_par = n·(n·v)·0.75·P·Δt`, applied to the body negated → cancels the normal component; magnitude = `|n·v|·0.75·P·Δt`.
2. **Directionless drag** (k2): opposite v, magnitude = `|v|·0.06888·P·Δt`.
3. **Lift** (the regular sail's core output, k3 = 0.475):
   - First remove the part already eaten by normal drag: `TEMP = v − F_par vector`
   - Magnitude = `|TEMP|·0.475·P·Δt` (≈ grows with local airflow speed)
   - **Direction = always along n (FACING opposite), with no (n·v)-style sign flip** — the sail is always pushed toward the n side.
4. Application point = sail block center; moment = (application point − COM) × force.

Implications for design:

- A regular sail is a lift-first lifting surface. Lift direction is fixed in the sail's own frame (the n side) and rotates with the body — upside down, n points down and lift pushes down (into the ground); only right-side-up with the n side up is it reliable lift.
- Lift/drag magnitude both ∝ local airflow speed `|v|` (linear velocity + angular velocity × lever arm) → the farther a sail is from the COM, the larger the force and moment from the same body motion (damping, trim and control all rely on this).
- All forces scale with P (dimension base pressure × altitude curve).

---

## Symmetric Sail (SymmetricSailBlock): drag only

Simulated's `SymmetricSailBlock` computes no force itself — it is an implementation of Sable's `BlockSubLevelLiftProvider` interface, with overridden parameters:

| Parameter | Symmetric sail | Regular sail (default) | Meaning |
|---|---|---|---|
| Normal n | `AXIS` positive | `FACING` opposite | Sail face normal |
| Lift coefficient k3 | **0 (overridden)** | 0.475 | Symmetric sails produce no lift |
| Normal drag coefficient k1 | **1.75 (overridden)** | 0.75 | Drag perpendicular to the face (2.3×) |
| Directionless drag coefficient k2 | 0.06888202261 (not overridden) | 0.06888202261 | Linear damping |

Per physics substep:

1. **Normal drag** (the symmetric sail's main output): magnitude = `|n·v|·1.75·P·Δt`, direction along normal n, **sign following (n·v)**, applied to the body negated → it always "cancels the velocity component along the sail normal".
   - When `n·v = 0` (airflow along the sail face) this term is 0 — the sail "has no effect"; drag only appears once deflected → this is what lets control surfaces / stabilizers produce control moments.
2. **Directionless drag** (k2): opposite v, magnitude = `|v|·0.06888·P·Δt` (always cancels linear velocity).
3. **Lift = 0** → symmetric sails only produce drag (that is where the name comes from).
4. Application point = sail block center; moment = (application point − COM) × force.

Implications for design:

- A symmetric sail is a pure drag / damping surface: the larger the tail and the farther from the COM, the stronger the pitch/yaw damping and the more "stable" the aircraft — but also the more sluggish.
- **Symmetric sail = "weathervane α-spring"**: normal drag (n·v) is an odd function, so nose-up and nose-down perturbations produce equal and opposite restoring forces — placed behind the COM, **a symmetric-sail tail alone gives two-way static stability**, no "lift center behind COM" needed. Stiffness ∝ `k1·P·V·lever arm` (automatically weaker at altitude / low speed).
- 1.75 is 2.3× the regular sail's normal coefficient (0.75), with zero lift → deflecting a symmetric sail redirects its drag and produces a control moment (official ponder: a tail symmetric sail + rotating bearing at 30° is a "rudder").
- All drag scales with P: editing the dimension `dimension_physics` datapack's pressure/altitude curve scales the drag of all sails (and the lift of regular sails) together.

---

## Don't forget: universal drag

The above covers the "sail" forces. Separately, Rapier applies constant velocity damping to every physics body (default d = 0.09), **directly decaying body velocity without going through force groups** — invisible to both the diagram and the flight recorder. The equivalent force:

```
F = −m·d·v   (proportional to mass and speed, NOT scaled by pressure P)
```

At cruise it is usually the **largest drag term** (nearly double the sail drag), so it must be added when balancing forces (thrust − sail drag − universal drag ≈ 0). See the "universal drag" section and the `getUniversalDragForce` tool in the [Flight Management Computer](../sensor-system/fmc.md).

---

## Related pages

- [Flight Management Computer](../sensor-system/fmc.md) — `solveSailLift` / `solveSailDirectionlessDrag` / `getUniversalDragForce` / `solveMaxCruise` tools
- [Trainer Aircraft](trainer_aircraft.md) — a flyable example designed with these rules
