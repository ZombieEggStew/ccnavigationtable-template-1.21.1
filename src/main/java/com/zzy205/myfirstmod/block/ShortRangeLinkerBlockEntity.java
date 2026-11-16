package com.zzy205.myfirstmod.block;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.compat.cc.ShortRangeLinkerRegistry;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 短程信号链接器方块实体：物理体作用域频道（onLoad 注册 / setRemoved 注销 / 每 20 tick 复核
 * 冲突顺延）+ 链上共享「加载物理体」开关（bodyLoad，任一链接器切换 → 同步全链，Sable
 * force-load + PORTAL ticket 管理照抄 {@link PeripheralExtenderBlockEntity}）+ 红石输出/输入
 * + 附着方块 NBT 缓存（与 pe 不同：默认关闭，需 Lua {@code enableNbtCache} 显式开启并配置
 * 刷新间隔，默认 20 tick；开启后按间隔缓存附着方块 NBT，供 {@code getNbt/getAllNbt} 读取）。
 */
public class ShortRangeLinkerBlockEntity extends BlockEntity implements PartialSafeNBT {

    /** 频道号（物理体作用域，同链内唯一，冲突自动顺延） */
    private int scrolledValue = 0;

    /** 链上共享的「加载物理体」开关（持久化；同链任一链接器切换 → 同步全链，last-toggle-wins） */
    private boolean bodyLoad = false;

    /** 链内已占用频道号快照（服务端设置，客户端经 updateTag 同步，GUI 滚轮跳过占用用） */
    private int[] occupiedChannels = new int[0];

    /** 红石输出信号 (0-15) */
    private int redstoneOutput = 0;
    /**
     * 是否向<b>附着方块强充能</b>（拉杆语义：只朝附着面那一个方向给 {@code getDirectSignal}）。
     * Lua {@code ss.setStrongPower(channel, enabled)} 控制；对方块态的 POWERED 无影响（那条仍由输出值决定）。
     */
    private boolean strongPower = false;

    // ── 附着方块 NBT 缓存（与 pe 不同：默认关闭，需 Lua enableNbtCache 显式开启；开启后每 nbtCacheInterval tick 刷新） ──

    /** NBT 缓存开关（默认关；持久化，随 NBT/蓝图保存） */
    private boolean nbtCacheEnabled = false;

    /** NBT 缓存刷新间隔（tick，默认 20；持久化；开启时 ≥ 1） */
    private int nbtCacheInterval = 20;

    /** 缓存的附着方块 NBT 快照（服务端主线程刷新，Lua 电脑线程只读；volatile 跨线程可见） */
    private volatile CompoundTag cachedAttachedNBT = new CompoundTag();

    /** 缓存需立即刷新（开启缓存 / 世界加载后置位，服务端 tick 消费一次，保证首个快照及时） */
    private boolean cacheRefreshDirty = false;

    /** 上次刷新缓存的游戏 tick（服务端；间隔自上次刷新起算） */
    private long lastCacheRefreshTick = 0;

    // ── Sable 加载 ticket 状态（bodyLoad=true 时生效，不落盘，重载后重挂） ──
    private boolean sableTicketRegistered = false;
    private UUID sableRootSubLevelId = null;
    private final Set<UUID> connectedSubLevelIds = new HashSet<>();
    private final Map<UUID, ChunkPos> portalTicketChunks = new HashMap<>();

    public ShortRangeLinkerBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.short_range_linker_entity.get(), pos, state);
    }

    // ═══════════════ 频道注册 ═══════════════

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = ShortRangeLinkerRegistry.register(this.scrolledValue, this);
            if (assigned >= 0 && assigned != this.scrolledValue) {
                this.scrolledValue = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();
            // bodyLoad OR 自愈：同链任一开启 → 自己同步为开（共享开关一致）
            boolean shared = ShortRangeLinkerRegistry.anyBodyLoadOn(ShortRangeLinkerRegistry.chainUuidsOf(this));
            if (shared != this.bodyLoad) {
                this.bodyLoad = shared;
                this.setChanged();
            }
            if (this.bodyLoad) {
                tryRegisterSableTicket();
            }
            // NBT 缓存：世界加载后若已开启（持久化恢复），置脏让首个 tick 立即刷新快照
            if (this.nbtCacheEnabled) {
                this.cacheRefreshDirty = true;
            }
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            releaseSableTicket();
            ShortRangeLinkerRegistry.unregisterAll(this);
            this.redstoneOutput = 0;
            this.strongPower = false; // 输出已归零，强充能开关同步失效（重载后经 NBT 恢复）
        }
        super.setRemoved();
    }

    // ═══════════════ 服务端 tick ═══════════════

    /** 由 {@link ShortRangeLinkerBlock#getTicker} 调用：20 tick 复核 + bodyLoad ticket 维持 + NBT 缓存周期刷新 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, ShortRangeLinkerBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!level.isLoaded(pos)) return;

        if (serverLevel.getServer().getTickCount() % 20 == 0) {
            be.revalidate(serverLevel);
        }

        if (be.bodyLoad) {
            if (!be.sableTicketRegistered || be.sableRootSubLevelId == null) {
                be.tryRegisterSableTicket();
            } else {
                be.refreshTickets(serverLevel);
            }
        }

        // NBT 缓存：开启时按间隔刷新（间隔自上次刷新起算；开启/加载后首个 tick 立即刷一份）
        if (be.nbtCacheEnabled) {
            long tick = serverLevel.getServer().getTickCount();
            if (be.cacheRefreshDirty || tick - be.lastCacheRefreshTick >= be.nbtCacheInterval) {
                be.cacheRefreshDirty = false;
                be.lastCacheRefreshTick = tick;
                be.refreshNbtCache();
            }
        }
    }

    /** 20 tick 复核：频道同链冲突顺延 + bodyLoad 共享值 OR 自愈（离体时释放 ticket） */
    private void revalidate(ServerLevel level) {
        int actual = ShortRangeLinkerRegistry.register(this.scrolledValue, this);
        if (actual >= 0 && actual != this.scrolledValue) {
            this.scrolledValue = actual;
            this.setChanged();
        }

        boolean before = this.bodyLoad;
        this.bodyLoad = ShortRangeLinkerRegistry.anyBodyLoadOn(ShortRangeLinkerRegistry.chainUuidsOf(this));
        if (this.bodyLoad != before) {
            this.setChanged();
            if (!this.bodyLoad) releaseSableTicket();
        }
    }

    /**
     * 切换「加载物理体」共享开关：同步到同链全部链接器（last-toggle-wins），各自更新 ticket。
     * 服务端主线程调用（GUI / payload 处理器）。
     */
    public void setBodyLoad(boolean on) {
        if (this.bodyLoad == on) return;
        this.bodyLoad = on;
        this.setChanged();
        if (this.level == null || this.level.isClientSide) return;

        for (ShortRangeLinkerBlockEntity other : ShortRangeLinkerRegistry.linkersOnChain(ShortRangeLinkerRegistry.chainUuidsOf(this))) {
            if (other == this || other.bodyLoad == on) continue;
            other.bodyLoad = on;
            other.setChanged();
            this.level.sendBlockUpdated(other.getBlockPos(), other.getBlockState(), other.getBlockState(), 3);
            if (on) other.tryRegisterSableTicket(); else other.releaseSableTicket();
        }
        if (on) tryRegisterSableTicket(); else releaseSableTicket();
    }

    // ═══════════════ Sable 加载 ticket（照抄 PeripheralExtenderBlockEntity） ═══════════════

    private boolean tryRegisterSableTicket() {
        if (sableTicketRegistered) return true;
        if (this.level == null) return false;

        SubLevel root = SableCompat.getContainingSubLevel(this);
        if (root == null) return false;
        UUID rootId = SableCompat.getSubLevelUUID(root);
        if (rootId == null) return false;

        List<SubLevel> chain = SableCompat.getConnectedChain(root);
        if (chain.isEmpty()) chain = Collections.singletonList(root);
        if (!(this.level instanceof ServerLevel serverLevel)) return false;

        // Layer 1: 对链中所有 SubLevel 注册 Sable force-load ticket
        for (SubLevel sub : chain) {
            if (SableCompat.isSubLevelRemoved(sub)) continue;
            UUID id = SableCompat.getSubLevelUUID(sub);
            if (id == null) continue;
            SableCompat.tryAddForceLoadTicket(this.level, sub, this.worldPosition);
            connectedSubLevelIds.add(id);
        }
        // Layer 2: PORTAL ticket（动态追踪移动）
        refreshPortalTickets(serverLevel, chain);

        sableRootSubLevelId = rootId;
        sableTicketRegistered = true;
        this.setChanged();
        return true;
    }

    private void releaseSableTicket() {
        if (!sableTicketRegistered) return;
        sableTicketRegistered = false;

        // 关服/卸载时跳过与 Sable 的交互，避免访问已关闭的系统
        if (this.level == null || !this.level.isLoaded(worldPosition)) {
            portalTicketChunks.clear();
            connectedSubLevelIds.clear();
            sableRootSubLevelId = null;
            return;
        }
        if (this.level instanceof ServerLevel serverLevel) {
            for (Map.Entry<UUID, ChunkPos> entry : portalTicketChunks.entrySet()) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, entry.getValue(),
                        Config.SENSOR_PORTAL_TICKET_RADIUS.get(), this.worldPosition);
            }
            portalTicketChunks.clear();

            SubLevel sub = SableCompat.getContainingSubLevel(this);
            if (sub != null) {
                for (UUID id : connectedSubLevelIds) {
                    SableCompat.tryRemoveForceLoadTicket(this.level, sub, this.worldPosition);
                }
            }
        }
        connectedSubLevelIds.clear();
        sableRootSubLevelId = null;
    }

    /** 维持 ticket：处理链变化（新建/断开连接）与物理体移动（PORTAL ticket 位置追踪） */
    private void refreshTickets(ServerLevel serverLevel) {
        SubLevel root = SableCompat.getContainingSubLevel(this);
        if (root == null || SableCompat.isSubLevelRemoved(root)) {
            releaseSableTicket();
            return;
        }
        List<SubLevel> chain = SableCompat.getConnectedChain(root);
        if (chain.isEmpty()) chain = Collections.singletonList(root);

        // 同步 connectedSubLevelIds（处理新建/断开的连接）
        Set<UUID> currentIds = new HashSet<>();
        for (SubLevel s : chain) {
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id != null) currentIds.add(id);
        }
        for (UUID oldId : new HashSet<>(connectedSubLevelIds)) {
            if (!currentIds.contains(oldId)) {
                ChunkPos oldChunk = portalTicketChunks.remove(oldId);
                if (oldChunk != null) {
                    serverLevel.getChunkSource().removeRegionTicket(
                            TicketType.PORTAL, oldChunk,
                            Config.SENSOR_PORTAL_TICKET_RADIUS.get(), this.worldPosition);
                }
            }
        }
        connectedSubLevelIds.clear();
        connectedSubLevelIds.addAll(currentIds);

        for (SubLevel s : chain) {
            if (SableCompat.isSubLevelRemoved(s)) continue;
            SableCompat.tryAddForceLoadTicket(this.level, s, this.worldPosition);
        }

        // 每 5 秒强制刷新防止超时 + 移动追踪
        boolean fiveSec = serverLevel.getServer().getTickCount() % 100 == 0;
        if (fiveSec || needsPortalTicketRefresh(serverLevel, chain)) {
            refreshPortalTickets(serverLevel, chain);
        }
    }

    private boolean needsPortalTicketRefresh(ServerLevel level, List<SubLevel> chain) {
        for (SubLevel s : chain) {
            if (SableCompat.isSubLevelRemoved(s)) continue;
            Vec3 worldPos = SableCompat.getSubLevelWorldPos(s);
            if (worldPos == null) continue;
            ChunkPos cur = new ChunkPos(
                    (int) Math.floor(worldPos.x / 16.0),
                    (int) Math.floor(worldPos.z / 16.0));
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id == null) continue;
            ChunkPos last = portalTicketChunks.get(id);
            if (last == null || !last.equals(cur)) return true;
        }
        return false;
    }

    private void refreshPortalTickets(ServerLevel serverLevel, List<SubLevel> chain) {
        int radius = Config.SENSOR_PORTAL_TICKET_RADIUS.get();
        for (SubLevel s : chain) {
            if (SableCompat.isSubLevelRemoved(s)) continue;
            Vec3 worldPos = SableCompat.getSubLevelWorldPos(s);
            if (worldPos == null) continue;
            UUID id = SableCompat.getSubLevelUUID(s);
            if (id == null) continue;

            ChunkPos cur = new ChunkPos(
                    (int) Math.floor(worldPos.x / 16.0),
                    (int) Math.floor(worldPos.z / 16.0));
            ChunkPos last = portalTicketChunks.get(id);
            if (last != null && last.equals(cur)) continue; // 未移动

            if (last != null) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, last, radius, this.worldPosition);
            }
            serverLevel.getChunkSource().addRegionTicket(
                    TicketType.PORTAL, cur, radius, this.worldPosition);
            portalTicketChunks.put(id, cur);
        }
    }

    // ═══════════════ 频道访问 ═══════════════

    public int getScrolledValue() { return scrolledValue; }

    public void setScrolledValue(int val) {
        this.scrolledValue = val;
        this.setChanged();
    }

    public boolean isBodyLoad() { return bodyLoad; }

    /** 从注册表同步链内已占用频道快照到本 BE，并通知客户端 */
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        Set<Integer> occ = ShortRangeLinkerRegistry.occupiedChannels(ShortRangeLinkerRegistry.chainUuidsOf(this));
        this.occupiedChannels = occ.stream().mapToInt(Integer::intValue).toArray();
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    /** 链内已占用频道号数组（客户端 GUI 跳过占用用） */
    public int[] getOccupiedChannels() { return occupiedChannels; }

    /** 指定频道是否被同链其它链接器占用 */
    public boolean isChannelOccupiedByOther(int channel) {
        if (channel == this.scrolledValue) return false; // 自己占用不算
        for (int ch : occupiedChannels) {
            if (ch == channel) return true;
        }
        return false;
    }

    // ═══════════════ 红石 ═══════════════

    public int getRedstoneOutput() { return redstoneOutput; }

    public void setRedstoneOutput(int signal) {
        int clamped = Math.clamp(signal, 0, 15);
        if (this.redstoneOutput == clamped) return;
        this.redstoneOutput = clamped;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            ShortRangeLinkerBlock.updateRedstoneOutput(this.level, this.worldPosition, clamped);
        }
    }

    /** 是否向附着方块强充能（拉杆语义，见 {@link ShortRangeLinkerBlock#getDirectSignal}） */
    public boolean isStrongPower() { return strongPower; }

    /**
     * 设置是否向附着方块强充能（服务端主线程调用，Lua {@code setStrongPower} 经此写）。
     * <p>
     * 只影响 {@code getDirectSignal}（拉杆式的「向附着方块给强充能」），<b>不</b>改变弱信号
     * {@link ShortRangeLinkerBlock#getSignal}（那一条始终六面按输出值给）。开关变化必须通知邻居，
     * 否则被充能的方块/红石粉不会重算信号；方块态（POWERED）不变，故不用 setBlock。
     */
    public void setStrongPower(boolean strong) {
        if (this.strongPower == strong) return;
        this.strongPower = strong;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            ShortRangeLinkerBlock.updateStrongPower(this.level, this.worldPosition);
        }
    }

    /** 读取链接器位置接收到的最强红石信号 (0-15) */
    public int getRedstoneInput() {
        if (this.level == null) return 0;
        return this.level.getBestNeighborSignal(this.worldPosition);
    }

    // ═══════════════ 附着方块 NBT 缓存（Lua enableNbtCache / getNbt / getAllNbt） ═══════════════

    /** NBT 缓存是否已开启（默认关；需 Lua {@code enableNbtCache} 显式开启） */
    public boolean isNbtCacheEnabled() { return nbtCacheEnabled; }

    /** NBT 缓存刷新间隔（tick，默认 20） */
    public int getNbtCacheInterval() { return nbtCacheInterval; }

    /**
     * 开启 / 关闭 / 调整附着方块 NBT 缓存（服务端主线程调用，Lua mainThread=true）。
     * <ul>
     *   <li>{@code ticks <= 0} → 关闭缓存（保留已有快照，读取方法返回 nil/空表）；</li>
     *   <li>{@code ticks >= 1} → 开启缓存并设置刷新间隔（缺省 20）；重复调用仅改间隔。</li>
     * </ul>
     */
    public void setNbtCache(int ticks) {
        if (ticks <= 0) {
            if (!nbtCacheEnabled) return;
            nbtCacheEnabled = false;
        } else {
            nbtCacheEnabled = true;
            nbtCacheInterval = Math.max(1, ticks);
            this.cacheRefreshDirty = true; // 开启后首个 tick 立即刷一份
        }
        this.setChanged();
    }

    /** 缓存的附着方块 NBT 快照（Lua 线程只读；缓存未开启时可能为空） */
    public CompoundTag getCachedAttachedNBT() { return cachedAttachedNBT; }

    /** 刷新附着方块 NBT 快照（必须在服务端主线程调用，由 serverTick 周期触发） */
    private void refreshNbtCache() {
        if (this.level == null || this.level.isClientSide) return;
        this.cachedAttachedNBT = ShortRangeLinkerBlock.getAttachedBlockNBT(this.level, this.getBlockState(), this.worldPosition);
    }

    // ═══════════════ NBT ═══════════════

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putBoolean("BodyLoad", bodyLoad);
        tag.putInt("RedstoneOutput", redstoneOutput);
        tag.putBoolean("StrongPower", strongPower);
        tag.putIntArray("OccupiedChannels", occupiedChannels);
        tag.putBoolean("NbtCacheEnabled", nbtCacheEnabled);
        tag.putInt("NbtCacheInterval", nbtCacheInterval);
        return tag;
    }

    /** 让 sendBlockUpdated 真正把 BE 数据推给客户端（默认返回 null 会导致客户端快照陈旧） */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ScrolledValue")) scrolledValue = tag.getInt("ScrolledValue");
        if (tag.contains("BodyLoad")) bodyLoad = tag.getBoolean("BodyLoad");
        if (tag.contains("RedstoneOutput")) redstoneOutput = tag.getInt("RedstoneOutput");
        if (tag.contains("StrongPower")) strongPower = tag.getBoolean("StrongPower");
        if (tag.contains("OccupiedChannels")) occupiedChannels = tag.getIntArray("OccupiedChannels");
        if (tag.contains("NbtCacheEnabled")) nbtCacheEnabled = tag.getBoolean("NbtCacheEnabled");
        if (tag.contains("NbtCacheInterval")) nbtCacheInterval = Math.max(1, tag.getInt("NbtCacheInterval"));

        // ── 蓝图 / 结构部署的时序修正 ──
        // Create 的 BlockHelper.placeSchematicBlock 顺序是 setBlock（建 BE ⇒ 立刻 onLoad）→
        // 再 loadWithComponents（走到这里）。也就是说 onLoad 那一刻用的是**默认频道 0**，
        // 蓝图里记的真实频道到这里才被读出来：若等到 20 tick 的例行 revalidate 才纠正，
        // 期间①Lua 在旧号上能查到本链接器、在新号上查不到；②同链一次部署多个链接器时，
        // 期间抢号的「顺延」结果会被回写进 scrolledValue（onLoad / revalidate 都会回写），
        // 把蓝图里记的地址永久改掉。
        // ⇒ 读到真实频道后立刻复核一次：每个方块在部署当 tick 就落回自己的地址，
        //   下一个方块的 onLoad 也就不会踩到已经被占的号。
        // 世界加载路径下 level 此时尚未就绪（null）⇒ 跳过，交给 onLoad 处理。
        if (this.level instanceof ServerLevel serverLevel) {
            this.revalidate(serverLevel);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // OccupiedChannels 是运行时快照（onLoad/refresh 重建），ticket 状态不落盘（重载后重挂），
        // 都不写入世界存档与蓝图（避免把陈旧运行时快照带进 .nbt）
        tag.putInt("ScrolledValue", scrolledValue);
        tag.putBoolean("BodyLoad", bodyLoad);
        tag.putInt("RedstoneOutput", redstoneOutput);
        tag.putBoolean("StrongPower", strongPower);
        // NBT 缓存开关与间隔持久化（快照本身是运行时数据不落盘，重载后经 onLoad 置脏首个 tick 重建）
        tag.putBoolean("NbtCacheEnabled", nbtCacheEnabled);
        tag.putInt("NbtCacheInterval", nbtCacheInterval);
    }

    /**
     * Create 原理图 / 蓝图部署（以及 contraption 搬运）用的「安全 NBT」。
     * <p>
     * 只存「配置性质」的状态：频道、链上共享的加载开关、NBT 缓存设置。
     * <ul>
     *   <li>{@code OccupiedChannels} 是运行时快照（onLoad / refresh 重建）⇒ 不写，避免把陈旧快照带进 .nbt；</li>
     *   <li>ticket 状态不写（重载后由 {@code tryRegisterSableTicket} 重挂）；</li>
     *   <li>{@code RedstoneOutput} <b>故意不写</b>：部署后没有任何路径会去 apply 它，
     *       若把它写进蓝图，就会出现「字段有值但方块没充能」的不一致；归零反而与真实信号一致
     *       （与 {@code setRemoved} 里把输出清零的语义同源）。</li>
     *   <li>{@code StrongPower} 同理不写：它只是「怎么给强充能」的修饰开关，输出归零后毫无意义，
     *       部署出来保持默认 false 与 {@code RedstoneOutput} 默认 0 语义一致。</li>
     * </ul>
     */
    @Override
    public void writeSafe(CompoundTag compound, HolderLookup.Provider registries) {
        compound.putInt("ScrolledValue", scrolledValue);
        compound.putBoolean("BodyLoad", bodyLoad);
        compound.putBoolean("NbtCacheEnabled", nbtCacheEnabled);
        compound.putInt("NbtCacheInterval", nbtCacheInterval);
    }

    /** 检测链接器是否在 Sable 物理体上（自身或其附着方块在子次元内，照 PeripheralExtenderBlockEntity） */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        if (SableCompat.getContainingSubLevel(this) != null) return true;
        BlockPos attached = ShortRangeLinkerBlock.getAttachedPos(this.getBlockState(), this.worldPosition);
        return SableCompat.getContainingSubLevel(level, attached) != null;
    }
}
