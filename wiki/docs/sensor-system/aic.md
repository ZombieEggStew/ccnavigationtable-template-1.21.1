# Aviation Integrated Computer

![AIC](../img/AIC.png)

The **Aviation Integrated Computer** (`ccpe:aic`) is an avionics block for physics bodies (Sable sub-levels). Inside a transparent gyro, a compass swings like a pendulum — gravity keeps it level and a north-seeking torque aligns it (the same gravity-pendulum simulation as the [INS](ins.md); in non-natural dimensions the compass wanders randomly instead of pointing north).

## INS + FMC gate

An AIC counts as **both** an INS and an FMC for `ccpe.sensor_system` gating: a physics body (including constraint chains) carrying **at least 1 AIC** is treated as if it had an INS **and** an FMC installed.

In `getSensors()`, an AIC appears as **two entries at the same position**: `{type="ins", pos={x,y,z}, pos_rel={x,y,z}}` and `{type="fmc", pos={x,y,z}, pos_rel={x,y,z}}`.

For the complete method explanation, see the [Inertial Navigation System](ins.md) and [Flight Management Computer](fmc.md) pages.


