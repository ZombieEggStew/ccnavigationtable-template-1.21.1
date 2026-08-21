package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import com.zzy205.myfirstmod.item.MyModItems;
import com.zzy205.myfirstmod.monitor.ModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Monitor 相关网络包处理。
 * <ul>
 *   <li>{@link SyncGridPayload} — 服务端→客户端：棋盘网格状态同步</li>
 *   <li>{@link MonitorChannelPayload} / {@link MonitorBackgroundPayload} / {@link MonitorTransformPayload} — 频道 / 背景 / 可动变换</li>
 *   <li>{@link PlaceModulePayload} / {@link RemoveModulePayload} — 模块放置 / 移除（含物品消耗与返还）</li>
 *   <li>{@link ModulePressPayload} / {@link ModuleKnobRotatePayload} / {@link ModuleConfigPayload} — 按钮 / 旋钮 / 配置</li>
 *   <li>{@link PlaceScreenPayload} / {@link RemoveScreenPayload} — 屏幕放置 / 移除</li>
 * </ul>
 */
public final class MonitorPacketHandlers {

    private MonitorPacketHandlers() {}

    public static void register(PayloadRegistrar registrar) {
        // 服务端→客户端：同步 Monitor 棋盘网格状态
        registrar.playToClient(
                SyncGridPayload.TYPE,
                SyncGridPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var be = PacketHelper.findBE(level, payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.getGridState().load(level.registryAccess(), payload.gridTag());
                    }
                }
        );

        // 客户端→服务端：保存 Monitor 全局频道
        registrar.playToServer(
                MonitorChannelPayload.TYPE,
                MonitorChannelPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.monitorPos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.setChannel(payload.channel());
                    }
                }
        );

        // 客户端→服务端：保存 Monitor 背景
        registrar.playToServer(
                MonitorBackgroundPayload.TYPE,
                MonitorBackgroundPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.monitorPos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.setBackground(payload.background());
                    }
                }
        );

        // 客户端→服务端：保存正式 Monitor 的可动变换（俯仰 / 偏航 / 偏移）
        registrar.playToServer(
                MonitorTransformPayload.TYPE,
                MonitorTransformPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.monitorPos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.setAngles(payload.pitch(), payload.yaw(), payload.offset());
                    }
                }
        );

        // 客户端→服务端：Monitor 放置模块
        registrar.playToServer(
                PlaceModulePayload.TYPE,
                PlaceModulePayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var be = PacketHelper.findBE(level, payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        var type = ModuleType.byName(payload.moduleTypeName());
                        if (type != null) {
                            int id = be.tryPlaceModule(payload.gridX(), payload.gridY(), type);
                            if (id >= 0 && !ctx.player().isCreative()) {
                                ctx.player().getMainHandItem().shrink(1);
                            }
                        }
                    }
                }
        );

        // 客户端→服务端：Monitor 移除模块
        registrar.playToServer(
                RemoveModulePayload.TYPE,
                RemoveModulePayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var be = PacketHelper.findBE(level, payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        String removedType = be.tryRemoveModule(payload.moduleId());
                        if (removedType != null && !ctx.player().isCreative()) {
                            ItemStack stack = MyModItems.monitorModuleStack(ModuleType.byName(removedType));
                            if (!stack.isEmpty() && !ctx.player().getInventory().add(stack)) {
                                Block.popResource(level, payload.pos(), stack);
                            }
                        }
                    }
                }
        );

        // 客户端→服务端：Monitor 模块按钮按下/释放/切换
        registrar.playToServer(
                ModulePressPayload.TYPE,
                ModulePressPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        var mod = be.getGridState().getModule(payload.moduleId());
                        boolean isToggle = mod != null && mod.type() == ModuleType.TOGGLE_SWITCH;
                        if (payload.pressed()) {
                            if (isToggle) be.toggleModule(payload.moduleId());
                            else be.pressModuleByPlayer(payload.moduleId());
                        } else {
                            if (!isToggle) be.releaseModuleByPlayer(payload.moduleId());
                        }
                    }
                }
        );

        // 客户端→服务端：旋钮旋转角度
        registrar.playToServer(
                ModuleKnobRotatePayload.TYPE,
                ModuleKnobRotatePayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.rotateKnob(payload.moduleId(), payload.angle());
                    }
                }
        );

        // 客户端→服务端：模块 / 屏幕的 ID 与配置修改
        registrar.playToServer(
                ModuleConfigPayload.TYPE,
                ModuleConfigPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var be = PacketHelper.findBE(ctx.player().level(), payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        be.applyModuleConfig(payload.name(), payload.oldId(), payload.newId(), payload.config());
                    }
                }
        );

        // 客户端→服务端：放置屏幕（两点矩形选择，可多个共存）
        registrar.playToServer(
                PlaceScreenPayload.TYPE,
                PlaceScreenPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var be = PacketHelper.findBE(level, payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        int id = be.addScreen(
                                payload.gridX1(), payload.gridY1(),
                                payload.gridX2(), payload.gridY2()
                        );
                        if (id >= 0 && !ctx.player().isCreative()) {
                            ctx.player().getMainHandItem().shrink(1);
                        }
                    }
                }
        );

        // 客户端→服务端：移除指定格子的屏幕（扳手拆卸）
        registrar.playToServer(
                RemoveScreenPayload.TYPE,
                RemoveScreenPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    var level = ctx.player().level();
                    var be = PacketHelper.findBE(level, payload.pos(), MonitorBlockEntity.class);
                    if (be != null) {
                        if (be.removeScreenAt(payload.gridX(), payload.gridY()) && !ctx.player().isCreative()) {
                            ItemStack stack = new ItemStack(MyModItems.MODULE_SCREEN.get());
                            if (!ctx.player().getInventory().add(stack)) {
                                Block.popResource(level, payload.pos(), stack);
                            }
                        }
                    }
                }
        );
    }
}
