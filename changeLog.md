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
- wiki coming soon

- Added the Monitor: a modular instrument panel with a 12×10 module grid.
  Module items (Button 1×1, Toggle Switch 1×1, Knob 2×2, Screen of any size) are
  placed by right-clicking the monitor face — and removed with a Wrench.

- Added the `ccpe:monitor` CC:Tweaked peripheral

- Added new redstone transceiver Lua methods: setFrequency / getFrequency /
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
