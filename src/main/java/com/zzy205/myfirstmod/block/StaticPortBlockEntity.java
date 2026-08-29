package com.zzy205.myfirstmod.block;

import com.zzy205.myfirstmod.compat.cc.BodySensorRegistry;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 静压孔方块实体：把自身位置注册进 {@link BodySensorRegistry}
 * （物理体 UUID → PRESSURE 传感器），供 {@code ccpe.sensor_system} 读取
 * 静压孔处的气压/高度。
 * <p>
 * 生命周期对齐 Peripheral Extender 模式：
 * <ul>
 * <li>{@link #onLoad()}：服务端注册；</li>
 * <li>{@link #setRemoved()}：注销；</li>
 * <li>{@link #tickServer()}：每 20 tick 复核所在物理体 UUID，装配/拆卸/重载后
 *     UUID 变化 → 重注册（兜底 onLoad 时 sub-level 尚未就绪的情况）。</li>
 * </ul>
 */
public class StaticPortBlockEntity extends BlockEntity {

    private UUID registeredBodyId = null;
    private int tickCounter = 0;

    public StaticPortBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.static_port_entity.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            registeredBodyId = containingBodyId();
            BodySensorRegistry.register(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            BodySensorRegistry.unregister(this);
        }
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, StaticPortBlockEntity be) {
        be.tickServer();
    }

    /** 周期性复核所在物理体 UUID；变化则先注销旧条目再按新物理体重注册 */
    private void tickServer() {
        if (++tickCounter % 20 != 0) return;
        UUID current = containingBodyId();
        if (!Objects.equals(current, registeredBodyId)) {
            BodySensorRegistry.unregister(this);
            registeredBodyId = current;
            if (current != null) BodySensorRegistry.register(this);
        }
    }

    private @Nullable UUID containingBodyId() {
        SubLevel sub = SableCompat.getContainingSubLevel(this);
        return sub != null ? SableCompat.getSubLevelUUID(sub) : null;
    }
}
