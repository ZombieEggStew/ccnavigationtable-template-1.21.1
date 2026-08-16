1.0.1
- Added `getPhysicsChainMass` and `getPhysicsChainGravityForce` methods to retrieve the total mass and gravity of the entire physics body chain.
  (Testing revealed that after assembly with a swivel bearing, the bearing's mass is counted twice, inflating the chain's total mass.)

- Fixed peripheral extender to correctly retrieve physics data when attached to blocks without a block entity.

- Fixed an issue where channel 0 could be manually selected and duplicated when already occupied.

- Removed test blocks and test items.

1.0.2
- Added transmission peripheral : a rotation speed controller designed specifically for CC:T control

1.0.3
- Added recipe for transmission peripheral

1.0.4
- Added optional integration with Aeroworks: Toggle Throttle Quadrant module (4 toggle latches)

- Added screen (screen module) character/rectangle rendering with CC:T Lua API:
  write / clear / setCursorPos / setTextScale / setTextColour / setZIndex /
  setOverflowMode / drawRect / clearRects / getSize
  (text has no background colour; use drawRect for backgrounds; write/drawRect accept an optional z layer)