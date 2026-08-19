# Create 蓝图（Schematic）NBT 经验记录

> 背景：PeripheralExtender + RedstoneTransceiver 在 Create 蓝图保存/部署中配置丢失或陈旧的问题排查记录。
> 涉及方块：`PeripheralExtenderBlockEntity`、`RedstoneTransceiverBlockEntity`。

## 核心事实（每条都是踩过的坑）

### 1. 蓝图保存路径不会调用 `writeSafe`

- Create 保存蓝图（quill 保存、InstantSchematic 二次保存）走的是 **原版 `StructureTemplate.fillFromWorld` → `BlockEntity.saveWithFullMetadata` → `saveAdditional`**，全程**不经过** `PartialSafeNBT.writeSafe`。
- `writeSafe` 只在**部署路径**被调用：`BlockHelper.prepareBlockEntityData`（Schematicannon 打印 / 蓝图放置），通过 `PartialSafeNBT` 分支。
- 推论：想让蓝图 .nbt 文件干净（不含运行时字段），**必须在 `saveAdditional` 层面排除运行时字段**，光实现 `writeSafe` 没用。部署结果正确 ≠ 文件干净。

### 2. `cachedAttachedNBT` 与 `cachedAttachedCompoundTag` 是两个不同的缓存

- `cachedAttachedNBT`：写入 `saveAdditional` / `getUpdateTag`，GUI 显示用（`PeripheralExtenderScreen.getLiveNBT`），**只在 `refreshAndGet`（GUI 轮询）时刷新**。
- `cachedAttachedCompoundTag`：供 Lua API 读取（`PeripheralExtenderAPI`），**在 `serverTick` 的 `refreshAllCaches` 里刷新**。
- 坑：之前 tick 只刷后者，导致存档/蓝图里的 `AttachedNBT` 是 GUI 轮询时的**陈旧快照**（曾出现：extender 的 AttachedNBT 显示 transceiver 有幽灵物品，而 transceiver 当时实际是空的）。
- 修复：`refreshAllCaches` 用一个快照同时刷两个字段。

### 3. quill 保存读的是**客户端** BE

- `SchematicAndQuillHandler.saveSchematic` 用 `Minecraft.getInstance().level`（客户端世界）→ `StructureTemplate.fillFromWorld` 读的是客户端 BE。
- 客户端 BE 数据只有两条更新通道：区块加载、`getUpdatePacket()`（默认返回 **null**，`sendBlockUpdated` 推不动数据）。
- 坑：BE 没重写 `getUpdatePacket`、且数据变更后没 `sendBlockUpdated` 时，客户端 BE 一直陈旧 → 保存出旧配置。
- 修复：两个 BE 补 `getUpdatePacket()`（`ClientboundBlockEntityDataPacket.create(this)`，与 `MonitorBlockEntity` 同款）；`setBannerData` / `setLoadMode` 补 `level.sendBlockUpdated(pos, state, state, 3)`。

### 4. 保存时机与服务器重启

- 配置（频道/幽灵物品）改完后要**先落盘再保存蓝图**：服务器重启会回滚未落盘的自动存档（自动存档 ~30s 间隔），导致"明明配好了，保存出来却是空的"。
- 排错时先做交叉验证：客户端保存（`run/schematics/*.nbt`）与服务器二次保存（`run/schematics/uploaded/<player>/*.nbt`）内容一致 = 服务端真实状态如此，不是保存丢数据。
- 判断幽灵物品是否真的配置成功，看世界存档（`run/saves/<world>/region/*.mca`）里 BE 的 `BannerData`。

## 调试工具（可复用）

- `temp/dump_nbt.py`：解析 gzip 压缩的 .nbt（蓝图文件）。用法：`python temp/dump_nbt.py run/schematics/xxx.nbt`
- `temp/dump_mca2.py`：解析 Anvil 区域文件中的方块实体。用法：`python temp/dump_mca2.py run/saves/<world>/region/r.0.0.mca <chunkX> <chunkZ>`
