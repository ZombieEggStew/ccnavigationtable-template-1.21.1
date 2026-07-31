package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.Config;
import com.zzy205.myfirstmod.compat.cc.SensorRegistry;
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

    /** 褰撳墠娲昏穬鐨勫己鍒跺姞杞戒紶鎰熷櫒鏁伴噺 */
    private static int activeChunkLoaders = 0;

    private CompoundTag cachedAttachedNBT = new CompoundTag();
    private int scrolledValue = 0;
    /** 骞界伒鐗╁搧妲戒腑灞曠ず鐨勭墿锟?*/
    private ItemStack displayItem = ItemStack.EMPTY;
    /** 绗簩涓菇鐏电墿鍝佹Ы */
    private ItemStack displayItem2 = ItemStack.EMPTY;

    /** 鎵€鏈夊凡琚崰鐢ㄧ殑棰戦亾鍙峰揩鐓э紙鏈嶅姟绔缃紝瀹㈡埛绔€氳繃 updateTag 鍚屾锟?*/
    private int[] occupiedChannels = new int[0];

    /** 鏄惁宸插鏈紶鎰熷櫒鍛ㄥ洿 3x3 鍖哄潡鎵ц寮哄埗鍔犺浇 */
    private boolean chunksForceLoaded = false;

    /** 鏄惁宸插浼犳劅鍣ㄩ檮鐫€锟?Sable 鐗╃悊缁撴瀯娉ㄥ唽锟?force-load ticket */
    private boolean sableTicketRegistered = false;

    // 鈺愨晲锟?GUI 鍔犺浇妯″紡 鈺愨晲锟?
    /** 0=鍏抽棴, 1=鍔犺浇鍖哄潡, 2=鍔犺浇鐗╃悊锟?*/
    private int loadMode = 0;

    /** 浼犳劅鍣ㄧ殑 Sable SubLevel UUID锛堢敤锟?PORTAL ticket 杩借釜锟?*/
    private UUID sableRootSubLevelId = null;

    /** 鎵€鏈夐€氳繃绾︽潫杩炴帴锟?SubLevel UUID锛堝惈鑷韩锛夛紝鐢ㄤ簬 PORTAL ticket 绠＄悊 */
    private final Set<UUID> connectedSubLevelIds = new HashSet<>();

    /** 姣忎釜 SubLevel 鏈€鍚庝竴锟?PORTAL ticket 鎵€鍦ㄧ殑鍖哄潡 */
    private final Map<UUID, ChunkPos> portalTicketChunks = new HashMap<>();

    /** CC:T 鏃犵嚎绾㈢煶杈撳嚭淇″彿 (0-15) */
    private int redstoneOutput = 0;


    public PeripheralExtenderBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.micro_peripheral_extender_entity.get(), pos, state);
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?棰戦亾娉ㄥ唽 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            int assigned = SensorRegistry.register(this.scrolledValue, this);
            if (assigned != this.scrolledValue) {
                this.scrolledValue = assigned;
                this.setChanged();
            }
            refreshOccupiedChannels();

            // 鏍规嵁 GUI 鍔犺浇妯″紡鍐冲畾鍚敤鍝鍔犺浇
            applyLoadMode();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            releaseSurroundingChunks();
            releaseSableTicket();
            SensorRegistry.unregister(this.scrolledValue, this);
            // 浠呮竻闄ゅ唴閮ㄧ姸鎬侊紝涓嶈Е鍙戞柟鍧楁洿鏂帮紙閬垮厤淇濆瓨/鍗歌浇锟?setBlock 姝婚攣锟?
            this.redstoneOutput = 0;
        }
        super.setRemoved();
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?鍖哄潡寮哄埗鍔犺浇 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    /**
     * 瀵规湰浼犳劅鍣ㄥ懆锟?3x3 鍖哄潡鎵ц vanilla 寮哄埗鍔犺浇锛堝叡 9 涓尯鍧楋級锟?
     * 浠呭湪浼犳劅鍣ㄤ笉锟?Sable 瀛愭鍏冧腑鏃惰皟鐢拷?
     */
    private void forceLoadSurroundingChunks() {
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
     * 閲婃斁鏈紶鎰熷櫒涔嬪墠閫氳繃 vanilla 寮哄埗鍔犺浇锟?3x3 鍖哄潡锟?
     */
    private void releaseSurroundingChunks() {
        if (!chunksForceLoaded) return;
        // 鍏虫湇/鍗歌浇鏃舵柟鍧楀潗鏍囧彲鑳藉凡澶辨晥锛岃烦杩囦互閬垮厤璁块棶宸插叧闂殑绯荤粺
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

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?Sable 瀛愭锟?Ticket 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    /**
     * 灏濊瘯涓轰紶鎰熷櫒闄勭潃锟?Sable 鐗╃悊缁撴瀯娉ㄥ唽 force-load ticket锟?
     * 鎴愬姛鏃朵細鍚屾椂锟?
     * <ul>
     *   <li>娉ㄥ唽 Sable SubLevel force-load ticket锛堥槻锟?Sable 璺濈浼樺寲鍗歌浇锟?/li>
     *   <li>瀵圭墿鐞嗙粨鏋勫綋鍓嶄笘鐣屼綅缃坊锟?PORTAL ticket锛堝姩鎬佽拷韪Щ鍔級</li>
     *   <li>瀵硅酱鎵跨瓑绾︽潫杩炴帴鐨勫瓙鐗╃悊缁撴瀯涔熸坊锟?ticket</li>
     * </ul>
     *
     * @return true 琛ㄧず浼犳劅鍣ㄥ湪 Sable 瀛愭鍏冧腑涓旀墍锟?ticket 宸叉敞锟?
     */
    private boolean tryRegisterSableTicket() {
        if (sableTicketRegistered) return true;

        // 妫€鏌ヤ紶鎰熷櫒鑷韩鎴栭檮鐫€鏂瑰潡鏄惁锟?Sable 瀛愭鍏冧腑
        SubLevel rootSubLevel = (SubLevel) SableCompat.getContainingSubLevel(this);
        if (rootSubLevel == null && this.level != null) {
            BlockPos attachedPos = PeripheralExtenderBlock.getAttachedPos(this.getBlockState(), this.worldPosition);
            rootSubLevel = (SubLevel) SableCompat.getContainingSubLevel(this.level, attachedPos);
        }
        if (rootSubLevel == null) return false;

        UUID rootId = SableCompat.getSubLevelUUID(rootSubLevel);
        if (rootId == null) return false;

        // 鑾峰彇杩炴帴閾撅紙鑷韩 + 杞存壙杩炴帴鐨勬墍锟?SubLevel锟?
        List<SubLevel> chain = (List<SubLevel>)(List<?>) SableCompat.getConnectedChain(rootSubLevel);
        if (chain.isEmpty()) chain = Collections.singletonList(rootSubLevel);

        boolean allOk = true;
        if (!(level instanceof ServerLevel serverLevel)) return false;

        // Layer 1: 瀵规墍鏈夐摼涓殑 SubLevel 娉ㄥ唽 Sable force-load ticket
        for (SubLevel subLevel : chain) {
            if (SableCompat.isSubLevelRemoved(subLevel)) continue;
            UUID id = SableCompat.getSubLevelUUID(subLevel);
            if (id == null) continue;

            SableCompat.tryAddForceLoadTicket(this.level, subLevel, this.worldPosition);
            connectedSubLevelIds.add(id);
        }

        // Layer 2: 涓烘瘡锟?SubLevel 娣诲姞 PORTAL ticket
        refreshPortalTickets(serverLevel, chain);

        sableRootSubLevelId = rootId;
        sableTicketRegistered = true;
        this.setChanged();
        return true;
    }

    /**
     * 閲婃斁涔嬪墠娉ㄥ唽鐨勬墍锟?Sable force-load ticket 锟?PORTAL ticket锟?
     */
    private void releaseSableTicket() {
        if (!sableTicketRegistered) return;
        sableTicketRegistered = false;

        // 鍏虫湇/鍗歌浇鏃惰烦杩囦笌 Sable 鐨勪氦浜掞紝閬垮厤璁块棶宸插叧闂殑绯荤粺瀵艰嚧姝婚攣
        if (level == null || !level.isLoaded(worldPosition)) {
            portalTicketChunks.clear();
            connectedSubLevelIds.clear();
            sableRootSubLevelId = null;
            return;
        }

        if (level instanceof ServerLevel serverLevel) {
            // 绉婚櫎鎵€锟?PORTAL ticket
            for (var entry : portalTicketChunks.entrySet()) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, entry.getValue(),
                        Config.SENSOR_PORTAL_TICKET_RADIUS.get(), this.worldPosition);
            }
            portalTicketChunks.clear();

            // 灏濊瘯绉婚櫎 Sable force-load ticket
            SubLevel subLevel = (SubLevel) SableCompat.getContainingSubLevel(this);
            if (subLevel != null) {
                for (UUID id : connectedSubLevelIds) {
                    SableCompat.tryRemoveForceLoadTicket(this.level, subLevel, this.worldPosition);
                }
            }
        }
        connectedSubLevelIds.clear();
        sableRootSubLevelId = null;
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?鍔拷?PORTAL Ticket 杩借釜 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    /**
     * 鏈嶅姟锟?tick锛氬綋 Sable 鐗╃悊缁撴瀯绉诲姩鏃讹紝鑷姩锟?PORTAL ticket 绉诲姩鍒版柊浣嶇疆锟?
     * 锟?{@link PeripheralExtenderBlock#getTicker} 锟?tick 璋冪敤锟?
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PeripheralExtenderBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!level.isLoaded(pos)) return;  // 鍏虫湇/鍗歌浇涓烦锟?
        if (!be.sableTicketRegistered || be.sableRootSubLevelId == null) return;

        // 锟?5 绉掑己鍒跺埛鏂伴槻姝㈣秴锟?
        boolean fiveSecRefresh = serverLevel.getServer().getTickCount() % 100 == 0;

        // 閲嶆柊鑾峰彇杩炴帴閾撅紙鐗╃悊缁撴瀯鍙兘锟?tick 闂存柊锟?鏂紑杞存壙杩炴帴锟?
        SubLevel rootSubLevel = (SubLevel) SableCompat.getContainingSubLevel(be);
        if (rootSubLevel == null || SableCompat.isSubLevelRemoved(rootSubLevel)) {
            // 鐗╃悊缁撴瀯宸茶绉婚櫎 锟?娓呯悊
            be.releaseSableTicket();
            return;
        }

        List<SubLevel> chain = (List<SubLevel>)(List<?>) SableCompat.getConnectedChain(rootSubLevel);
        if (chain.isEmpty()) chain = Collections.singletonList(rootSubLevel);

        // 鍚屾 connectedSubLevelIds锛堝鐞嗘柊锟?鏂紑鐨勮繛鎺ワ級
        Set<UUID> currentIds = new HashSet<>();
        for (SubLevel sl : chain) {
            UUID id = SableCompat.getSubLevelUUID(sl);
            if (id != null) currentIds.add(id);
        }

        // 娓呯悊宸叉柇寮€杩炴帴锟?SubLevel 锟?PORTAL ticket
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

        // 纭繚鎵€鏈夐摼锟?SubLevel 閮芥湁 Sable ticket
        for (SubLevel sl : chain) {
            if (SableCompat.isSubLevelRemoved(sl)) continue;
            SableCompat.tryAddForceLoadTicket(level, sl, be.worldPosition);
        }

        // 鍒锋柊 PORTAL ticket锛堢Щ鍔ㄨ拷韪級
        if (fiveSecRefresh || needsPortalTicketRefresh(serverLevel, chain, be)) {
            be.refreshPortalTickets(serverLevel, chain);
        }
    }

    /**
     * 妫€鏌ユ槸鍚︽湁 SubLevel 绉诲姩鍒颁簡锟?chunk锛岄渶瑕佸埛锟?PORTAL ticket锟?
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
     * 涓烘墍鏈夐摼涓殑 SubLevel 鏀剧疆/绉诲姩 PORTAL ticket锟?
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

            if (lastChunk != null && lastChunk.equals(curChunk)) continue; // 鏈Щ锟?

            // 绉婚櫎锟?ticket
            if (lastChunk != null) {
                serverLevel.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL, lastChunk, radius, this.worldPosition);
            }
            // 娣诲姞锟?ticket
            serverLevel.getChunkSource().addRegionTicket(
                    TicketType.PORTAL, curChunk, radius, this.worldPosition);
            portalTicketChunks.put(id, curChunk);
        }
    }

    /** 浠庢敞鍐岃〃鍚屾 occupiedChannels 蹇収鍒版湰 BE锛屽苟閫氱煡瀹㈡埛锟?*/
    public void refreshOccupiedChannels() {
        if (this.level == null || this.level.isClientSide) return;
        var channels = SensorRegistry.getOccupiedChannels();
        int[] arr = new int[channels.size()];
        int i = 0;
        for (int ch : channels) arr[i++] = ch;
        this.occupiedChannels = arr;
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    /** 鑾峰彇琚崰鐢ㄧ殑棰戦亾鍙锋暟缁勶紙瀹㈡埛锟?GUI 鐢ㄥ畠璺宠繃宸插崰鐢ㄧ殑棰戦亾锟?*/
    public int[] getOccupiedChannels() {
        return occupiedChannels;
    }

    /** 妫€鏌ユ寚瀹氶閬撴槸鍚﹁鍏朵粬浼犳劅鍣ㄥ崰锟?*/
    public boolean isChannelOccupiedByOther(int channel) {
        if (channel == this.scrolledValue) return false; // 鑷繁鍗犵敤涓嶇畻
        for (int ch : occupiedChannels) {
            if (ch == channel) return true;
        }
        return false;
    }

    /**
     * 瀵瑰鏆撮湶鐨勮鍙栨帴鍙ｏ細鍒锋柊骞惰繑鍥為檮鐫€鏂瑰潡鐨勬渶锟?NBT锟?
     * 浠呭湪璋冪敤鏃惰鍙栵紝涓嶄細鍚庡彴鑷姩 tick锟?
     */
    public CompoundTag refreshAndGet(Level level, BlockState state) {
        this.cachedAttachedNBT = PeripheralExtenderBlock.getAttachedBlockNBT(level, state, this.getBlockPos());
        this.setChanged();
        level.sendBlockUpdated(this.getBlockPos(), state, state, 3);
        return this.cachedAttachedNBT;
    }

    /**
     * 鑾峰彇涓婃缂撳瓨锟?NBT锛堜笉瑙﹀彂鍒锋柊锛夛拷?
     */
    public CompoundTag getCachedAttachedNBT() {
        return cachedAttachedNBT;
    }

    /**
     * 鐢辩綉缁滃寘澶勭悊鏃惰缃紦瀛橈紙浠呭鎴风璋冪敤锛夛拷?
     */
    public void setCachedAttachedNBT(CompoundTag nbt) {
        this.cachedAttachedNBT = nbt;
    }

    /** 鏂囨湰杈撳叆妗嗗唴瀹癸紝鎸佷箙鍖栧苟鍚屾鍒板鎴风 */
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

    /** 鎸夌储寮曡幏鍙栧菇鐏电墿鍝佹Ы锟?锟?锟?*/
    public ItemStack getDisplayItem(int slot) {
        return slot == 1 ? displayItem2 : displayItem;
    }

    /** 鎸夌储寮曡缃菇鐏电墿鍝佹Ы锟?锟?锟?*/
    public void setDisplayItem(int slot, ItemStack stack) {
        if (slot == 1) {
            setDisplayItem2(stack);
        } else {
            setDisplayItem(stack);
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?鏃犵嚎绾㈢煶杈撳嚭 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

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

    /** 璇诲彇浼犳劅鍣ㄤ綅缃帴鏀跺埌鐨勬渶寮虹孩鐭充俊鍙凤紙0-15锛夛拷?*/
    public int getRedstoneInput() {
        if (this.level == null) return 0;
        return this.level.getBestNeighborSignal(this.worldPosition);
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?GUI 鍔犺浇妯″紡 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲锟?

    public int getLoadMode() { return loadMode; }

    public void setLoadMode(int mode) {
        int clamped = Math.clamp(mode, 0, 2);
        if (this.loadMode == clamped) return;
        // 鍏堥噴鏀炬棫妯″紡
        releaseSurroundingChunks();
        releaseSableTicket();
        this.loadMode = clamped;
        this.setChanged();
        if (level != null && !level.isClientSide) {
            applyLoadMode();
        }
    }

    /** 鏍规嵁褰撳墠 loadMode 鍚敤瀵瑰簲鐨勫姞杞芥柟锟?*/
    private void applyLoadMode() {
        switch (loadMode) {
            case 1 -> forceLoadSurroundingChunks();
            case 2 -> tryRegisterSableTicket();
        }
    }

    /** 妫€娴嬩紶鎰熷櫒鏄惁锟?Sable 鐗╃悊浣撲笂 */
    public boolean isOnPhysicsBody() {
        if (level == null || level.isClientSide) return false;
        if (com.zzy205.myfirstmod.compat.sable.SableCompat.getContainingSubLevel(this) != null)
            return true;
        BlockPos attached = PeripheralExtenderBlock.getAttachedPos(this.getBlockState(), this.worldPosition);
        return com.zzy205.myfirstmod.compat.sable.SableCompat.getContainingSubLevel(level, attached) != null;
    }

    // 鍏煎鏃ф柟娉曪紙鑿滃崟 extra data 浣跨敤锟?
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
        // chunksForceLoaded / sableTicketRegistered 涓嶆寔涔呭寲鈥斺€斾笘鐣岄噸杞藉悗闇€閲嶆柊娉ㄥ唽
        if (tag.contains("LoadMode")) {
            loadMode = Math.clamp(tag.getInt("LoadMode"), 0, 2);
        } else {
            // 鍏煎鏃х増鏁版嵁
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
