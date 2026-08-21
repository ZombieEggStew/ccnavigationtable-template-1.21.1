package com.zzy205.myfirstmod.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 网络包处理的小工具。
 * <p>
 * 各 payload handler 共用：按坐标查找指定类型的 BlockEntity，
 * 消除重复的 {@code level.getBlockEntity(pos) + instanceof} 骨架。
 */
public final class PacketHelper {

    private PacketHelper() {}

    /** 按坐标查找指定类型的 BlockEntity；不存在或类型不匹配返回 null。 */
    @Nullable
    public static <B extends BlockEntity> B findBE(Level level, BlockPos pos, Class<B> type) {
        BlockEntity be = level.getBlockEntity(pos);
        return type.isInstance(be) ? type.cast(be) : null;
    }
}
