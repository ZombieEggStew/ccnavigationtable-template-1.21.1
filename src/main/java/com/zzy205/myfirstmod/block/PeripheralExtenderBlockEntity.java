package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.compat.cc.PeripheralExtenderRegistry;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class PeripheralExtenderBlockEntity extends BlockEntity {

    /** 当前活跃的强制加载传感器数量 */
    private static int activeChunkLoaders = 0;

    private CompoundTag cachedAttachedNBT = new CompoundTag();
    private int scrolledValue = 0;
    /** 幽灵物品槽中展示的物品 */
    private ItemStack displayItem = ItemStack.EMPTY;
    /** 第二个幽灵物品槽 */
    private ItemStack displayItem2 = ItemStack.EMPTY;

    /** 所有已被占用的频道号快照（服务端设置，客户端通过 updateTag 同步） */
    private int[] occupiedChannels = new int[0];

    /** 是否已对本传感器周围 3x3 区块执行强制加载 */
    private boolean chunksForceLoaded = false;

    /** 是否已对传感器附着的 Sable 物理结构注册了 force-load ticket */
    private boolean sableTicketRegistered = false;

    // ════════════════ GUI 加载模式 ════════════════
    /** 0=关闭, 1=加载区块, 2=加载物理体 */
    private int loadMode = 0;

    /** 传感器的 Sable SubLevel UUID（用于 PORTAL ticket 追踪） */
    private UUID sableRootSubLevelId = null;

    /** 所有通过约束连接的 SubLevel UUID（含自身），用于 PORTAL ticket 管理 */
    private final Set<UUID> connectedSubLevelIds = new HashSet<>();

    /** 每个 SubLevel 最后一个 PORTAL ticket 所在的区块 */
    private final Map<UUID, ChunkPos> portalTicketChunks = new HashMap<>();

    /** CC:T 无线红石输出信号 (0-15) */
    private int redstoneOutput = 0;

    // ════════════════ CC:T 快速查询缓存（serverTick 刷新，计算机线程安全读取） ════════════════

    /** 缓存的附着方块 BE 引用 */
    @javax.annotation.Nullable
    private BlockEntity cachedAttachedBE = null;

    /** 缓存的附着方块 NBT 快照（每个 tick 由 saveWithFullMetadata 生成新对象） */
    private CompoundTag cachedAttachedCompoundTag = new CompoundTag();

    /** 缓存的 Sable SubLevel */
    @javax.annotation.Nullable
    private SubLevel cachedSubLevel = null;

    /** 缓存的 NavTable 目标世界坐标 */
    @javax.annotation.Nullable
    private Vec3 cachedNavTargetPos = null;

    /** 缓存的 NavTable 自身世界坐标 */
    private Vec3 cachedNavSelfPos = Vec3.ZERO;

    /** 缓存的 NavTable 距离 */
    private double cachedNavDistance = 0.0;

    /** 缓存的 NavTable 相对角度 */
    private float cachedNavRelativeAngle = 0.0f;


    public PeripheralExtenderBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.micro_peripheral_extender_entity.get(), pos, state);
    }

    // ════════════════════ 频道注册 ════════════════════

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = PeripheralExtenderRegistry.register(this.scrolledValue, this);
            if (assigned != this.scrolledValue) {
                this.scrolledValue = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();

            // 根据 GUI 加载模式决定启用哪种加载
            applyLoadMode();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            releaseThisChunk();
            releaseSableTicket();
            PeripheralExtenderRegistry.unregister(this.scrolledValue, this);
            // 仅清除内部状态，不触发方块更新（避免保存/卸载时 setBlock 死锁）
            this.redstoneOutput = 0;
        }
        super.setRemoved();
    }

    // ════════════════════ 区块强制加载 ════════════════════

    /**
     * 对本传感器所在区块执行 vanilla 强制加载。
     */
    private void forceLoadThisChunk() {
        if (chunksForceLoaded) return;
        if (!Config.SENSOR_CHUNK_LOAD_ENABLED.get()) return;

        int maxLoad = Config.SENSOR_MAX_FORCE_LOAD.get();
        if (maxLoad > 0 && activeChunkLoaders >= maxLoad) return;

        if (level instanceof ServerLevel serverLevel) {
            int cx = worldPosition.getX() >> 4;
            int cz = worldPosition.getZ() >> 4;
            serverLevel.setChunkForced(cx, cz, true);
            chunksForceLoaded = true;
            activeChunkLoaders++;
        }
    }

    /**
     * 释放本传感器之前通过 vanilla 强制加载的区块。
     */
    private void releaseThisChunk() {
        if (!chunksForceLoaded) return;
        // 关服/卸载时方块坐标可能已失效，跳过以避免访问已关闭的系统
        if (level == null || !level.isLoaded(worldPosition)) {
            chunksForceLoaded = false;
            return;
        }

        if (level instanceof ServerLevel serverLevel) {
            int cx = worldPosition.getX() >> 4;
            int cz = worldPosition.getZ() >> 4;
            serverLevel.setChunkForced(cx, cz, false);
            chunksForceLoaded = false;
            activeChunkLoaders--;
        }
    }

    // ════════════════════ Sable 子次元 Ticket ════════════════════

    /**
     * 尝试为传感器附着的 Sable 物理结构注册 force-load ticket。
     * 成功时会同时：
     * <ul>
     *   <li>注册 Sable SubLevel force-load ticket（防止 Sable 距离优化卸载）</li>
     *   <li>对物理结构当前世界位置添加 PORTAL ticket（动态追踪移动）</li>
     *   <li>对轴承等约束连接的子物理结构也添加 ticket</li>
     * </ul>
     *
     * @return true 表示传感器在 Sable 子次元中且所有 ticket 已注册
     */
    private boolean tryRegisterSableTicket() {
        if (sableTicketRegistered) return true;

        // 检查传感器自身或附着方块是否在 Sable 子次元中
        SubLevel rootSubLevel = SableCompat.getContainingSubLevel(this);
        if (rootSubLevel == null && this.level != null) {
            BlockPos attachedPos = PeripheralExtenderBlock.getAttachedPos(this.getBlockState(), this.worldPosition);
            rootSubLevel = SableCompat.getContainingSubLevel(this.level, attachedPos);
        }
        if (rootSubLevel == null) return false;

        UUID rootId = SableCompat.getSubLevelUUID(rootSubLevel);
        if (rootId == null) return false;

        // 获取连接链（自身 + 轴承连接的所有 SubLevel）
        List<SubLevel> chain = SableCompat.getConnectedChain(rootSubLevel);
        if (chain.isEmpty()) chain = Collections.singletonList(rootSubLevel);

        boolean allOk = true;
        if (!(level instanceof ServerLevel serverLevel)) return false;

        // Layer 1: 对所有链中的 SubLevel 注册 Sable force-load ticket
        for (SubLevel subLevel : chain) {
            if (SableCompat.isSubLevelRemoved(subLevel)) continue;
            UUID id = SableCompat.getSubLevelUUID(subLevel);
            if (id == null) continue;

            SableCompat.tryAddForceLoadTicket(this.level, subLevel, this.worldPosition);
            connectedSubLevelIds.add(id);
        }

        // Layer 2: 为每个 SubLevel 添加 PORTAL ticket
        refreshPortalTickets(serverLevel, chain);

        sableRootSubLevelId = rootId;
        sableTicketRegistered = true;
        this.setChanged();
        return true;
    }

    /**
     * 释放之前注册的所有 Sable force-load ticket 和 PORTAL ticket。
     */
    private void releaseSableTicket() {
        if (!sableTicketRegistered) return;
        sableTicketRegistered = false;

        // 关服/卸载时跳过与 Sable 的交互，避免访问已关闭的系统导致死锁
        if (level == null || !level.isLoaded(worldPosition)) {
            portalTicketChunks.clear();
            connectedSubLevelIds.clear();
            sableRootSubLevelId = null;
            return;
        }

        if (level instanceof ServerLevel serverLevel) {
            // 移除所有 PORTAL ticket
            for (var entry : portalTicketChunks.entrySet()) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, entry.getValue(),
                        Config.SENSOR_PORTAL_TICKET_RADIUS.get(), this.worldPosition);
            }
            portalTicketChunks.clear();

            // 尝试移除 Sable force-load ticket
            SubLevel subLevel = SableCompat.getContainingSubLevel(this);
            if (subLevel != null) {
                for (UUID id : connectedSubLevelIds) {
                    SableCompat.tryRemoveForceLoadTicket(this.level, subLevel, this.worldPosition);
                }
            }
        }
        connectedSubLevelIds.clear();
        sableRootSubLevelId = null;
    }

    // ════════════════════ 动态 PORTAL Ticket 追踪 ════════════════════

    /**
     * 服务端 tick：当 Sable 物理结构移动时，自动将 PORTAL ticket 移动到新位置。
     * 由 {@link PeripheralExtenderBlock#getTicker} 的 tick 调用。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PeripheralExtenderBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!level.isLoaded(pos)) return;  // 关服/卸载中跳过

        // ★ 刷新所有 CC:T 快速查询缓存
        be.refreshAllCaches(level, state);

        // SubLevel ticket 管理（原有逻辑）
        if (!be.sableTicketRegistered || be.sableRootSubLevelId == null) return;

        // 每 5 秒强制刷新防止超时
        boolean fiveSecRefresh = serverLevel.getServer().getTickCount() % 100 == 0;

        // 重新获取连接链（物理结构可能在 tick 间新建/断开轴承连接）
        SubLevel rootSubLevel = SableCompat.getContainingSubLevel(be);
        if (rootSubLevel == null || SableCompat.isSubLevelRemoved(rootSubLevel)) {
            // 物理结构已被移除，清理
            be.releaseSableTicket();
            return;
        }

        List<SubLevel> chain = SableCompat.getConnectedChain(rootSubLevel);
        if (chain.isEmpty()) chain = Collections.singletonList(rootSubLevel);

        // 同步 connectedSubLevelIds（处理新建/断开的连接）
        Set<UUID> currentIds = new HashSet<>();
        for (SubLevel sl : chain) {
            UUID id = SableCompat.getSubLevelUUID(sl);
            if (id != null) currentIds.add(id);
        }

        // 清理已断开连接的 SubLevel 的 PORTAL ticket
        for (UUID oldId : new HashSet<>(be.connectedSubLevelIds)) {
            if (!currentIds.contains(oldId)) {
                ChunkPos oldChunk = be.portalTicketChunks.remove(oldId);
                if (oldChunk != null) {
                    serverLevel.getChunkSource().removeRegionTicket(
                            TicketType.PORTAL, oldChunk,
                            Config.SENSOR_PORTAL_TICKET_RADIUS.get(), be.worldPosition);
                }
            }
        }
        be.connectedSubLevelIds.clear();
        be.connectedSubLevelIds.addAll(currentIds);

        // 确保所有链中的 SubLevel 都有 Sable ticket
        for (SubLevel sl : chain) {
            if (SableCompat.isSubLevelRemoved(sl)) continue;
            SableCompat.tryAddForceLoadTicket(level, sl, be.worldPosition);
        }

        // 刷新 PORTAL ticket（移动追踪）
        if (fiveSecRefresh || needsPortalTicketRefresh(serverLevel, chain, be)) {
            be.refreshPortalTickets(serverLevel, chain);
        }
    }

    /**
     * 检查是否有 SubLevel 移动到了新 chunk，需要刷新 PORTAL ticket。
     */
    private static boolean needsPortalTicketRefresh(ServerLevel level, List<SubLevel> chain, PeripheralExtenderBlockEntity be) {
        for (SubLevel sl : chain) {
            if (SableCompat.isSubLevelRemoved(sl)) continue;
            Vec3 worldPos = SableCompat.getSubLevelWorldPos(sl);
            if (worldPos == null) continue;
            ChunkPos curChunk = new ChunkPos(
                    (int) Math.floor(worldPos.x / 16.0),
                    (int) Math.floor(worldPos.z / 16.0));
            UUID id = SableCompat.getSubLevelUUID(sl);
            if (id == null) continue;
            ChunkPos lastChunk = be.portalTicketChunks.get(id);
            if (lastChunk == null || !lastChunk.equals(curChunk)) return true;
        }
        return false;
    }

    /**
     * 为所有链中的 SubLevel 放置/移动 PORTAL ticket。
     */
    private void refreshPortalTickets(ServerLevel serverLevel, List<SubLevel> chain) {
        int radius = Config.SENSOR_PORTAL_TICKET_RADIUS.get();
        for (SubLevel sl : chain) {
            if (SableCompat.isSubLevelRemoved(sl)) continue;
            Vec3 worldPos = SableCompat.getSubLevelWorldPos(sl);
            if (worldPos == null) continue;
            UUID id = SableCompat.getSubLevelUUID(sl);
            if (id == null) continue;

            ChunkPos curChunk = new ChunkPos(
                    (int) Math.floor(worldPos.x / 16.0),
                    (int) Math.floor(worldPos.z / 16.0));
            ChunkPos lastChunk = portalTicketChunks.get(id);

            if (lastChunk != null && lastChunk.equals(curChunk)) continue; // 未移动

            // 移除旧 ticket
            if (lastChunk != null) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, lastChunk, radius, this.worldPosition);
            }
            // 添加新 ticket
            serverLevel.getChunkSource().addRegionTicket(
                    TicketType.PORTAL, curChunk, radius, this.worldPosition);
            portalTicketChunks.put(id, curChunk);
        }
    }

    /** 从注册表同步 occupiedChannels 快照到本 BE，并通知客户端 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        var channels = PeripheralExtenderRegistry.getOccupiedChannels();
        int[] arr = new int[channels.size()];
        int i = 0;
        for (int ch : channels) arr[i++] = ch;
        this.occupiedChannels = arr;
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    /** 获取被占用的频道号数组（客户端 GUI 用它跳过已占用的频道） */
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 检查指定频道是否被其他传感器占用 */
    public boolean isChannelOccupiedByOther(int channel) {
        if (channel == this.scrolledValue) return false; // 自己占用不算
        for (int ch : occupiedChannels) {
            if (ch == channel) return true;
        }
        return false;
    }

    /**
     * 对外暴露的读取接口：刷新并返回附着方块的最新 NBT。
     * 仅在调用时读取，不会后台自动 tick。
     */
    public CompoundTag refreshAndGet(Level level, BlockState state) {
        this.cachedAttachedNBT = PeripheralExtenderBlock.getAttachedBlockNBT(level, state, this.getBlockPos());
        this.setChanged();
        level.sendBlockUpdated(this.getBlockPos(), state, state, 3);
        return this.cachedAttachedNBT;
    }

    /**
     * 获取上次缓存的 NBT（不触发刷新）。
     */
    public CompoundTag getCachedAttachedNBT() {
        return cachedAttachedNBT;
    }

    /**
     * 由网络包处理时设置缓存（仅客户端调用）。
     */
    public void setCachedAttachedNBT(CompoundTag nbt) {
        this.cachedAttachedNBT = nbt;
    }

    /** 文本输入框内容，持久化并同步到客户端 */
    public int getScrolledValue() { return scrolledValue; }

    public void setScrolledValue(int val) {
        this.scrolledValue = val;
        this.setChanged();
    }

    public ItemStack getDisplayItem() {
        return displayItem;
    }

    public void setDisplayItem(ItemStack stack) {
        this.displayItem = stack.copy();
        if (!this.displayItem.isEmpty()) {
            this.displayItem.setCount(1);
        }
        this.setChanged();
    }

    public ItemStack getDisplayItem2() {
        return displayItem2;
    }

    public void setDisplayItem2(ItemStack stack) {
        this.displayItem2 = stack.copy();
        if (!this.displayItem2.isEmpty()) {
            this.displayItem2.setCount(1);
        }
        this.setChanged();
    }

    /** 按索引获取幽灵物品槽（0 或 1） */
    public ItemStack getDisplayItem(int slot) {
        return slot == 1 ? displayItem2 : displayItem;
    }

    /** 按索引设置幽灵物品槽（0 或 1） */
    public void setDisplayItem(int slot, ItemStack stack) {
        if (slot == 1) {
            setDisplayItem2(stack);
        } else {
            setDisplayItem(stack);
        }
    }

    // ════════════════════ 无线红石输出 ════════════════════

    public int getRedstoneOutput() { return redstoneOutput; }

    public void setRedstoneOutput(int signal) {
        int clamped = Math.clamp(signal, 0, 15);
        if (this.redstoneOutput == clamped) return;
        this.redstoneOutput = clamped;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            PeripheralExtenderBlock.updateRedstoneOutput(this.level, this.worldPosition, clamped);
        }
    }

    /** 读取传感器位置接收到的最强红石信号（0-15） */
    public int getRedstoneInput() {
        if (this.level == null) return 0;
        return this.level.getBestNeighborSignal(this.worldPosition);
    }

    // ════════════════ 缓存刷新（serverTick 调用） ════════════════

    void refreshAllCaches(Level level, BlockState state) {
        BlockPos attachedPos = PeripheralExtenderBlock.getAttachedPos(state, this.worldPosition);
        BlockEntity attachedBE = level.getBlockEntity(attachedPos);

        // SubLevel 缓存（始终尝试，不依赖 BE 是否存在——Sable 可处理无 BE 的方块）
        this.cachedSubLevel = SableCompat.getContainingSubLevel(level, attachedPos);

        if (attachedBE != null && !attachedBE.isRemoved()) {
            this.cachedAttachedBE = attachedBE;
            this.cachedAttachedCompoundTag = PeripheralExtenderBlock.getAttachedBlockNBT(level, state, this.worldPosition);

            // NavTable 专用缓存
            if (attachedBE instanceof dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity nav) {
                Vec3 target = nav.getTargetPosition(true);
                this.cachedNavTargetPos = target;
                this.cachedNavSelfPos = nav.getProjectedSelfPos();
                this.cachedNavDistance = nav.distanceToTarget();
                this.cachedNavRelativeAngle = nav.getRelativeAngle();
            } else {
                this.cachedNavTargetPos = null;
                this.cachedNavSelfPos = Vec3.ZERO;
                this.cachedNavDistance = 0.0;
                this.cachedNavRelativeAngle = 0.0f;
            }
        } else {
            this.cachedAttachedBE = null;
            this.cachedAttachedCompoundTag = new CompoundTag();
            this.cachedNavTargetPos = null;
            this.cachedNavSelfPos = Vec3.ZERO;
            this.cachedNavDistance = 0.0;
            this.cachedNavRelativeAngle = 0.0f;
        }
    }

    // ════════════════ 缓存读取（计算机线程安全，mainThread=false） ════════════════

    @javax.annotation.Nullable
    public BlockEntity getCachedAttachedBE() { return cachedAttachedBE; }

    public CompoundTag getCachedAttachedCompoundTag() { return cachedAttachedCompoundTag; }

    @javax.annotation.Nullable
    public SubLevel getCachedSubLevel() { return cachedSubLevel; }

    @javax.annotation.Nullable
    public Vec3 getCachedNavTargetPos() { return cachedNavTargetPos; }

    public Vec3 getCachedNavSelfPos() { return cachedNavSelfPos; }

    public double getCachedNavDistance() { return cachedNavDistance; }

    public float getCachedNavRelativeAngle() { return cachedNavRelativeAngle; }

    // ════════════════════ GUI 加载模式 ════════════════════

    public int getLoadMode() { return loadMode; }

    public void setLoadMode(int mode) {
        int clamped = Math.clamp(mode, 0, 2);
        if (this.loadMode == clamped) return;
        // 先释放旧模式
        releaseThisChunk();
        releaseSableTicket();
        this.loadMode = clamped;
        this.setChanged();
        if (level != null && !level.isClientSide) {
            applyLoadMode();
        }
    }

    /** 根据当前 loadMode 启用对应的加载方式 */
    private void applyLoadMode() {
        switch (loadMode) {
            case 1 -> forceLoadThisChunk();
            case 2 -> tryRegisterSableTicket();
        }
    }

    /** 检测传感器是否在 Sable 物理体上 */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        if (SableCompat.getContainingSubLevel(this) != null)
            return true;
        BlockPos attached = PeripheralExtenderBlock.getAttachedPos(this.getBlockState(), this.worldPosition);
        return SableCompat.getContainingSubLevel(level, attached) != null;
    }

    // 兼容旧方法（菜单 extra data 使用）
    public boolean isChunkLoadEnabled() { return loadMode == 1; }
    public boolean isSableLoadEnabled() { return loadMode == 2; }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        tag.putInt("LoadMode", loadMode);
        tag.putInt("RedstoneOutput", redstoneOutput);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(registries));
        }
        if (!displayItem2.isEmpty()) {
            tag.put("DisplayItem2", displayItem2.save(registries));
        }
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("AttachedNBT")) cachedAttachedNBT = tag.getCompound("AttachedNBT");
        if (tag.contains("ScrolledValue")) scrolledValue = tag.getInt("ScrolledValue");
        if (tag.contains("OccupiedChannels")) occupiedChannels = tag.getIntArray("OccupiedChannels");
        // chunksForceLoaded / sableTicketRegistered 不持久化——世界重载后需重新注册
        if (tag.contains("LoadMode")) {
            loadMode = Math.clamp(tag.getInt("LoadMode"), 0, 2);
        } else {
            // 兼容旧版数据
            if (tag.contains("ChunkLoadEnabled") && tag.getBoolean("ChunkLoadEnabled")) loadMode = 1;
            if (tag.contains("SableLoadEnabled") && tag.getBoolean("SableLoadEnabled")) loadMode = 2;
        }
        if (tag.contains("RedstoneOutput")) redstoneOutput = tag.getInt("RedstoneOutput");
        if (tag.contains("DisplayItem")) {
            displayItem = ItemStack.parse(registries, tag.getCompound("DisplayItem")).orElse(ItemStack.EMPTY);
        }
        if (tag.contains("DisplayItem2")) {
            displayItem2 = ItemStack.parse(registries, tag.getCompound("DisplayItem2")).orElse(ItemStack.EMPTY);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("AttachedNBT", cachedAttachedNBT);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        tag.putInt("LoadMode", loadMode);
        tag.putInt("RedstoneOutput", redstoneOutput);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(registries));
        }
        if (!displayItem2.isEmpty()) {
            tag.put("DisplayItem2", displayItem2.save(registries));
        }
    }
}
