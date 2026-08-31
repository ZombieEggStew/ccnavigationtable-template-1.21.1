package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.ShortRangeLinkerBlock;
import com.zzy205.myfirstmod.block.ShortRangeLinkerBlockEntity;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ccpe.link}：短程信号链接器 Lua API（全局 API，作用域 = 调用电脑所在物理体）。
 * <p>
 * 与 pe / Monitor / 控制台的全局频道体系完全独立：频道只在调用电脑所在物理体
 * （Sable 约束链）内寻址，链内「频道 → 链接器」一对一（冲突自动顺延，见
 * {@link ShortRangeLinkerRegistry}）；不同物理体上的相同频道号互不可见。
 * <ul>
 * <li>{@link #getPeripheral(int)}（mainThread=true）：取本体内频道 {@code channel}
 *     的链接器附着方块外设（Capability 查询）；</li>
 * <li>{@link #getRedstoneOutput(int)} / {@link #getRedstoneInput(int)}（mainThread=false）：
 *     目标链接器红石输出 / 输入；</li>
 * <li>{@link #setRedstoneOutput(int, int)}（mainThread=true）：写目标链接器红石输出
 *     并更新方块 POWERED。</li>
 * </ul>
 * 作用域解析照 {@link SensorSystemAPI#resolveSubLevel()}：
 * {@code computer.getLevel() + computer.getPosition()} → {@link SableCompat#getContainingSubLevel}
 * → {@link SableCompat#getConnectedChain} 得链 UUID 集合 → 在链内查频道。
 * 电脑不在任何物理体上 → 一律 nil（严格语义，与「非物理体不链接」一致）。
 * <p>
 * Lua 端通过 {@code require("ccpe.link")} 使用：
 * <pre>{@code
 * local link = require("ccpe.link")
 * local sensor = link.getPeripheral(1)   -- 本机物理体上频道 1 的链接器附着的外设
 * link.setRedstoneOutput(1, 15)          -- 目标链接器输出 15，相邻红石线亮
 * print(link.getRedstoneInput(2))        -- 目标链接器位置的红石输入
 * }</pre>
 */
public class ShortRangeLinkerAPI implements ILuaAPI {

    private final IComputerSystem computer;

    public ShortRangeLinkerAPI(IComputerSystem computer) {
        this.computer = computer;
    }

    @Override
    public String[] getNames() {
        return new String[0];
    }

    @Override
    public @Nullable String getModuleName() {
        return "ccpe.link";
    }

    // ═══════════════ 作用域解析（照 SensorSystemAPI.resolveSubLevel） ═══════════════

    /**
     * 调用电脑所在物理体（含约束链）的全部子次元 UUID 集合；
     * 电脑不在任何物理体上返回空集合（严格语义：非物理体不链接）。
     * <p>
     * 注：mainThread=false 的红石读方法在电脑线程调用（与 pe 的红石读一致，
     * Sable 侧为 plot 网格定长数组查找，低风险）；若将来出现线程问题，
     * 可改为 {@link #update()} 主线程缓存链 UUID（照 SensorSystemAPI 高频缓存模式）。
     */
    private Set<UUID> resolveChainUuids() {
        Set<UUID> ids = new HashSet<>();
        try {
            SubLevel sub = SableCompat.getContainingSubLevel(computer.getLevel(), computer.getPosition());
            if (sub == null) return ids;
            for (SubLevel s : SableCompat.getConnectedChain(sub)) {
                UUID id = SableCompat.getSubLevelUUID(s);
                if (id != null) ids.add(id);
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    /** 调用电脑所在链内频道 {@code channel} 的链接器；电脑不在物理体上 / 频道空闲 / 目标已卸载 → null */
    private @Nullable ShortRangeLinkerBlockEntity linkerOnChain(int channel) {
        Set<UUID> chain = resolveChainUuids();
        if (chain.isEmpty()) return null;
        return ShortRangeLinkerRegistry.get(chain, channel);
    }

    // ═══════════════ Lua API ═══════════════

    /**
     * 本体内频道 {@code channel} 的链接器所附着方块的外设（IPeripheral）。
     * <p>
     * 寻址模型：{@code channel} 是<b>目标链接器的地址</b>（同体内 1:1，冲突自动顺延），
     * 不是调用方自己的频道——链接器只放在目标块上，电脑侧零配置。
     * <p>
     * 作用域 = 调用电脑所在物理体（含约束链）：电脑不在任何物理体上、
     * 频道未被同体链接器占用、或附着方块无 CC:T 外设时返回 nil。
     *
     * @param channel 目标链接器的频道号
     * @return 目标链接器附着方块的外设；未命中返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Object getPeripheral(int channel) {
        ShortRangeLinkerBlockEntity linker = linkerOnChain(channel);
        if (linker == null) return null;
        Level level = linker.getLevel();
        if (level == null) return null;
        BlockState state = linker.getBlockState();
        BlockPos attachedPos = ShortRangeLinkerBlock.getAttachedPos(state, linker.getBlockPos());
        BlockEntity attached = level.getBlockEntity(attachedPos);
        // 附着方块自身就是 CC:T 外设（如 Monitor）→ 直接返回；否则走 Capability 查询
        if (attached instanceof IPeripheral p) return p;
        return level.getCapability(PeripheralCapability.get(), attachedPos, sideFromAttachedView(state));
    }

    /**
     * 目标链接器当前的红石输出信号（0-15，只读，mainThread=false）。
     * 未命中（电脑不在物理体上 / 频道空闲）返回 0。
     */
    @LuaFunction
    public final int getRedstoneOutput(int channel) {
        ShortRangeLinkerBlockEntity linker = linkerOnChain(channel);
        return linker != null ? linker.getRedstoneOutput() : 0;
    }

    /**
     * 目标链接器位置当前接收到的最强红石信号（0-15，只读，mainThread=false）。
     * 未命中返回 0。
     */
    @LuaFunction
    public final int getRedstoneInput(int channel) {
        ShortRangeLinkerBlockEntity linker = linkerOnChain(channel);
        return linker != null ? linker.getRedstoneInput() : 0;
    }

    /**
     * 写目标链接器的红石输出（0-15，越界自动钳位），并更新方块 POWERED 状态
     * （相邻红石线 / 红石机械随之响应；mainThread=true）。
     */
    @LuaFunction(mainThread = true)
    public final void setRedstoneOutput(int channel, int signal) {
        ShortRangeLinkerBlockEntity linker = linkerOnChain(channel);
        if (linker != null) linker.setRedstoneOutput(Math.clamp(signal, 0, 15));
    }

    // ═══════════════ 工具 ═══════════════

    /** 从附着方块的视角看链接器所在的面（照 PeripheralExtenderAPI.getSensorSide） */
    private static Direction sideFromAttachedView(BlockState state) {
        return switch (state.getValue(ShortRangeLinkerBlock.FACE)) {
            case FLOOR -> Direction.UP;      // 链接器在地面 → 附着方块在下方 → 从附着方块看是 UP
            case CEILING -> Direction.DOWN;  // 链接器在天花板 → 附着方块在上方 → 从附着方块看是 DOWN
            case WALL -> state.getValue(ShortRangeLinkerBlock.FACING);
        };
    }
}
