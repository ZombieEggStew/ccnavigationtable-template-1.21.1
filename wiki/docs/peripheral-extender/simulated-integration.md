# Aeronautics Sensor Integration

## Navigation Table Integration

**Attach to a Create: Aeronautics navigation table** to read target data.

| Method | Returns | Description |
|---|---|---|
| `pe.getNavTargetPos(ch)` | `{x, y, z}` | Target world coordinates |
| `pe.getNavSelfPos(ch)` | `{x, y, z}` | Own world coordinates |
| `pe.getNavDistance(ch)` | `number` | Distance to target (meters) |
| `pe.getNavRelativeAngle(ch)` | `number` | Bearing angle (degrees, 0~360) |


## Velocity Sensor Integration

| Method | Returns | Description |
|---|---|---|
| `getPhysicsVelocity(ch)` | `{x, y, z}` | Ground velocity (m/s)|
| `getPhysicsAirVelocity(ch)` | `{x, y, z}` | Airspeed, wind subtracted (m/s)|
| `getPhysicsAngularVelocity(ch)` | `{x, y, z}` | Angular velocity (rad/s)|
| `getAxisVelocity(ch)` | `number` | Velocity component along the sensor's mounting axis (m/s)|


## Physics Data Reading

Requires the pe to be attached to any block on a physics body.

| Method | Returns | Description |
|---|---|---|
| `getPhysicsPos(ch)` | `{x, y, z}` | World coordinates of the physics body (m)|
| `getPhysicsOrientation(ch)` | `{x, y, z, w}` | Rotation quaternion of the physics body |
| `getPhysicsCenterOfMass(ch)` | `{x, y, z}` | World coordinates of the physics body's center of mass |
| `getPhysicsMass(ch)` | `number` | Mass of the physics body the pe is attached to (kg)|
| `getPhysicsChainMass(ch)` | `number` | Total mass of the physics body chain (kg)|
| `getPhysicsGravityForce(ch)` | `number` | Gravity of the physics body the pe is attached to (pN)|
| `getPhysicsChainGravityForce(ch)` | `number` | Total gravity of the physics body chain (pN)|

Testing has shown that a physics bearing assembled onto a physics body counts gravity twice.

So the value of `getPhysicsChainGravityForce` may not match what you calculate manually.

If you need gravity to participate in precise calculations, feel free to use `getPhysicsChainGravityForce`.
