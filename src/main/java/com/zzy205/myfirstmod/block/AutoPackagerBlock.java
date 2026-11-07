package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自动打包机（{@code ccpe:auto_packager}）—— 与 {@code create:packager} 功能完全一致，
 * 唯一差别是 <b>不接收红石信号</b>：不再由红石上升沿驱动 {@code activate()}，
 * 而是由 {@link AutoPackagerBlockEntity} 按可调间隔自动打包。
 * <p>
 * 结构照抄 Create 的 {@link PackagerBlock}（继承），保留 FACING / LINKED / POWERED 三个状态属性：
 * <ul>
 * <li>FACING —— "所连接的容器"就是 FACING 反方向贴着的容器（父类 targetInventory 语义）；</li>
 * <li>LINKED —— 旁边有没有 create:stock_link（库存系统联动）；</li>
 * <li>POWERED —— 保留但恒为 false（本方块不读红石；属性本身无法从父类移除，
 *     父类构造器里 {@code registerDefaultState(defaultBlockState().setValue(POWERED, false))}
 *     依赖它存在，删掉会抛 IllegalArgumentException）。</li>
 * </ul>
 * 参考来源：api/create-1.21.1-6.0.10-280-sources/com/simibubi/create/content/logistics/packager/PackagerBlock.java
 */
public class AutoPackagerBlock extends PackagerBlock {

    public AutoPackagerBlock(Properties properties) {
        super(properties);
    }

    /** 父类的实现硬编码 {@code AllBlockEntityTypes.PACKAGER}，这里换成本 mod 的方块实体类型。 */
    @Override
    public BlockEntityType<? extends PackagerBlockEntity> getBlockEntityType() {
        return MyModBlockEntities.auto_packager_entity.get();
    }

    /** 放置时强制 POWERED=false：本方块不接红石，避免贴着红石信号放置时冻结在"通电"模型上。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(POWERED, false);
    }

    /**
     * 重写父类的红石响应：父类会在信号上升沿 {@code cycle(POWERED)} 并调用 {@code activate()}，
     * 这里只保留"通知目标容器缓存失效"这一必要动作，红石信号被彻底忽略。
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                boolean isMoving) {
        if (level.isClientSide)
            return;

        InvManipulationBehaviour behaviour = BlockEntityBehaviour.get(level, pos, InvManipulationBehaviour.TYPE);
        if (behaviour != null)
            behaviour.onNeighborChanged(fromPos);
    }
}
