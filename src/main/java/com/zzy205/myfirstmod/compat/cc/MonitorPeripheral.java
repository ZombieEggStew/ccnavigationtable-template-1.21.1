package com.zzy205.myfirstmod.compat.cc;

import com.simibubi.create.AllSoundEvents;
import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.MonitorModule;
import com.zzy205.myfirstmod.monitor.ScreenText;
import com.zzy205.myfirstmod.network.PlayOrderEffectPayload;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Monitor 的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)} 或 {@code peripheral.wrap(...)} 获取。
 * 提供模块/屏幕查询：{@link #getCellModule(int, int)} / {@link #getModule(int)}，
 * 返回的 {@link ModuleHandle} 即为可在 Lua 侧进一步操作的「模块实例」。
 */
public class MonitorPeripheral implements IPeripheral {

    private final MonitorBlockEntity be;

    public MonitorPeripheral(MonitorBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:monitor";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof MonitorPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public MonitorBlockEntity getBlockEntity() {
        return be;
    }

    // ═══════════════ 模块 / 屏幕查询 ═══════════════

    /**
     * 读取指定格子上的模块实例；若格子被屏幕占用，则返回屏幕实例。
     *
     * <pre>{@code
     * local mod = monitor.getCellModule(3, 4)
     * if mod then print(mod.getId(), mod.getType()) end
     * }</pre>
     *
     * @param x 格子 X 坐标（0..11）
     * @param y 格子 Y 坐标（0..9）
     * @return 该格子上的模块/屏幕实例；空格返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getCellModule(int x, int y) {
        GridState grid = be.getGridState();
        int cell = grid.getCell(x, y);
        if (cell == GridState.SCREEN_CELL_MARKER) {
            GridState.ScreenRegion screen = grid.getScreenAt(x, y);
            return screen != null ? new ScreenModuleHandle(be, screen) : null;
        }
        if (cell < 0) return null;
        return toHandle(grid.getModule(cell));
    }

    /**
     * 通过 ID 获取模块实例；屏幕与模块共用 ID 命名空间，屏幕 ID 也会返回屏幕实例。
     *
     * <pre>{@code
     * local mod = monitor.getModule(7)
     * if mod then print(mod.getType()) end
     * }</pre>
     *
     * @param id 模块/屏幕 ID
     * @return 模块/屏幕实例；不存在返回 nil
     */
    @LuaFunction(mainThread = true)
    public final @Nullable ModuleHandle getModule(int id) {
        MonitorModule module = be.getGridState().getModule(id);
        if (module != null) return toHandle(module);
        GridState.ScreenRegion screen = be.getGridState().getScreenById(id);
        return screen != null ? new ScreenModuleHandle(be, screen) : null;
    }

    /** 把 Java 侧模块记录包装成 Lua 模块实例（经 {@link ModuleHandleRegistry} 分派到对应类型）。 */
    private @Nullable ModuleHandle toHandle(@Nullable MonitorModule module) {
        if (module == null) return null;
        return ModuleHandleRegistry.create(be, module);
    }

    // ═══════════════ monitor 背景平面绘制 ═══════════════

    @LuaFunction(mainThread = true)
    public final void write(String text, Optional<Double> z) {
        be.monitorDisplayWrite(text, z.orElse(null));
    }

    @LuaFunction(mainThread = true)
    public final void clear() {
        be.monitorDisplayClear();
    }

    @LuaFunction(mainThread = true)
    public final void setCursorPos(double x, double y) {
        be.monitorDisplaySetCursor(x, y);
    }

    @LuaFunction
    public final MethodResult getCursorPos() {
        ScreenText text = be.getMonitorDisplayText();
        return MethodResult.of(text.getCursorX(), text.getCursorY());
    }

    @LuaFunction(mainThread = true)
    public final void setTextScale(double scale) {
        be.monitorDisplaySetTextScale(scale);
    }

    @LuaFunction
    public final double getTextScale() {
        return be.getMonitorDisplayText().getTextScale();
    }

    @LuaFunction(mainThread = true)
    public final void setTextColour(int colour) {
        be.monitorDisplaySetTextColour(colour);
    }

    @LuaFunction
    public final int getTextColour() {
        return be.getMonitorDisplayText().getTextColour();
    }

    @LuaFunction(mainThread = true)
    public final void setZIndex(double z) {
        be.monitorDisplaySetZIndex(z);
    }

    @LuaFunction
    public final double getZIndex() {
        return be.getMonitorDisplayText().getZIndex();
    }

    @LuaFunction(mainThread = true)
    public final void setOverflowMode(String mode) {
        be.monitorDisplaySetOverflowMode(mode);
    }

    @LuaFunction
    public final String getOverflowMode() {
        return be.getMonitorDisplayText().getOverflowMode().name;
    }

    @LuaFunction(mainThread = true)
    public final void drawRect(double x, double y, double width, double height,
                               int colour, boolean solid, double lineWidth, Optional<Double> z) {
        be.monitorDisplayDrawRect(x, y, width, height, colour, solid, lineWidth, z.orElse(null));
    }

    @LuaFunction(mainThread = true)
    public final void clearRects() {
        be.monitorDisplayClearRects();
    }

    @LuaFunction(mainThread = true)
    public final void drawLine(double x1, double y1, double x2, double y2,
                               int colour, double lineWidth, Optional<Double> z) {
        be.monitorDisplayDrawLine(x1, y1, x2, y2, colour, lineWidth, z.orElse(null));
    }

    @LuaFunction(mainThread = true)
    public final void drawCircle(double cx, double cy, double radius, int colour,
                                 boolean solid, double lineWidth, Optional<Integer> segments, Optional<Double> z) {
        be.monitorDisplayDrawCircle(cx, cy, radius, colour, solid, lineWidth,
                segments.orElse(32), z.orElse(null));
    }

    @LuaFunction(mainThread = true)
    public final void drawPoint(double x, double y, int colour, Optional<Double> z) {
        be.monitorDisplayDrawRect(x, y, 1.0, 1.0, colour, true, 0.0, z.orElse(null));
    }

    @LuaFunction(mainThread = true)
    public final void clearShapes() {
        be.monitorDisplayClearShapes();
    }

    @LuaFunction
    public final MethodResult getSize() {
        int[] size = be.getMonitorDisplaySize();
        return MethodResult.of((double) size[0], (double) size[1]);
    }

    // ═══════════════ 下单音效 / WiFi 粒子 ═══════════════

    /**
     * 播放 Create 风格的下单音效 + WiFi 粒子（效果位置在方块中心）。
     * 音效在服务端广播给附近玩家；WiFi 粒子无法走粒子网络通道
     * （{@code WiFiParticle.Data} 的流编解码是单例校验），因此广播
     * {@link PlayOrderEffectPayload}，由客户端本地 {@code level.addParticle} 生成。
     *
     * <pre>{@code
     * monitor.playNiceSound()
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void playNiceSound() {
        Level level = be.getLevel();
        if (level == null) return;
        BlockPos pos = be.getBlockPos();
        AllSoundEvents.STOCK_TICKER_REQUEST.playOnServer(level, pos);
        if (level instanceof ServerLevel serverLevel) {
            PacketDistributor.sendToPlayersNear(serverLevel, null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 32.0,
                    new PlayOrderEffectPayload(pos));
        }
    }

    /**
     * 播放指定的 Create 音效（在方块位置广播给附近玩家）。
     * 支持的名称：cardboard_bonk / desk_bell / confirm_2 / fwoomp / stock_ticker_trade
     *
     * <pre>{@code
     * monitor.playSound("desk_bell")
     * monitor.playSound("fwoomp")
     * }</pre>
     *
     * @param sound 音效名称
     * @return 是否找到并播放了该音效（未知名称返回 false）
     */
    @LuaFunction(mainThread = true)
    public final boolean playSound(String sound) {
        AllSoundEvents.SoundEntry entry = switch (sound == null ? "" : sound) {
            case "bonk" -> AllSoundEvents.CARDBOARD_SWORD;
            case "bell" -> AllSoundEvents.DESK_BELL_USE;
            case "confirm" -> AllSoundEvents.CONFIRM_2;
            case "fwoomp" -> AllSoundEvents.FWOOMP;
            case "trade" -> AllSoundEvents.STOCK_TICKER_TRADE;
            case "request" -> AllSoundEvents.STOCK_TICKER_REQUEST;
            default -> null;
        };
        if (entry == null) return false;
        Level level = be.getLevel();
        if (level == null) return false;
        entry.playOnServer(level, be.getBlockPos());
        return true;
    }
}
