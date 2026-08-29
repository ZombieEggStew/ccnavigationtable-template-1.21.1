package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 风帆轴承 plate（link block）方块实体。
 * <p>
 * 对应 simulated 的 {@code SwivelBearingPlateBlockEntity}：
 * 记录父轴承位置；被玩家破坏时连锁破坏父轴承；装配/拆卸时置
 * {@code beforeAssembly} 标志防止连锁。
 * <p>
 * 参考来源：{@code references/Simulated-Project-main/.../swivel_bearing/link_block/SwivelBearingPlateBlockEntity.java}。
 */
public class MyBearingPlateBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {

    @Nullable
    private BlockPos parent;
    @Nullable
    private UUID parentSubLevelId;
    private boolean assembling;

    public MyBearingPlateBlockEntity(final BlockPos pos, final BlockState state) {
        super(MyModBlockEntities.aero_bearing_plate_entity.get(), pos, state);
    }

    /**
     * 装配/拆卸前调用（防止 remove 连锁破坏）
     */
    public void beforeAssembly() {
        this.assembling = true;
    }

    @Override
    public void remove() {
        // 若方块被玩家破坏（而非装配流程），连锁破坏父轴承
        if (!this.level.isClientSide && !this.assembling) {
            this.destroyBearing();
        }

        super.remove();
    }

    private void destroyBearing() {
        if (this.parent != null && this.getLevel().getBlockState(this.parent).is(MyModBlocks.aero_bearing.get())) {
            this.getLevel().destroyBlock(this.parent, false);
        }
    }

    public void setParent(final MyBearingBlockEntity be) {
        final SubLevel subLevel = Sable.HELPER.getContaining(be);

        this.parent = be.getBlockPos();
        this.parentSubLevelId = subLevel != null ? subLevel.getUniqueId() : null;
    }

    @Override
    public void tick() {
        super.tick();
    }

    // ── Create 应力传播：把父轴承视为自定义连接（同 swivel plate BE 71-88 行） ──

    @Override
    public float propagateRotationTo(final KineticBlockEntity target, final BlockState stateFrom, final BlockState stateTo, final BlockPos diff, final boolean connectedViaAxes, final boolean connectedViaCogs) {
        return this.parent != null && target.equals(this.level.getBlockEntity(this.parent)) ? 1 : super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(final KineticBlockEntity other, final BlockState state, final BlockState otherState) {
        return this.parent != null && other.equals(this.level.getBlockEntity(this.parent));
    }

    @Override
    public List<BlockPos> addPropagationLocations(final IRotate block, final BlockState state, final List<BlockPos> neighbours) {
        if (this.parent != null) {
            neighbours.add(this.parent);
        }

        return super.addPropagationLocations(block, state, neighbours);
    }

    // ── NBT ──

    @Override
    protected void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        if (this.parent != null) {
            compound.put("ParentPos", NbtUtils.writeBlockPos(this.parent));
        }

        if (this.parentSubLevelId != null) {
            compound.putUUID("ParentSubLevelId", this.parentSubLevelId);
        }
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        if (compound.contains("ParentPos")) {
            this.parent = NbtUtils.readBlockPos(compound, "ParentPos").orElse(null);
        }

        if (compound.contains("ParentSubLevelId")) {
            this.parentSubLevelId = compound.getUUID("ParentSubLevelId");
        }
    }

    // ── Sable actor ──

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        // 阶段 4 驱动：每物理 tick 通知父轴承更新伺服系数（同 swivel 122-130 行）
        // 阶段 1-2 锁定：父 BE 的 updateServoCoefficients() 已每 tick 由约束锁定当前角度，
        // 此处保留调用入口，避免阶段 4 遗漏。
        if (this.parent != null) {
            final BlockEntity parentBE = this.level.getBlockEntity(this.parent);

            if (parentBE instanceof final MyBearingBlockEntity bearingBE) {
                bearingBE.updateServoCoefficients();
            }
        }
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        if (this.parent == null) {
            return null;
        }

        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);

        if (this.parentSubLevelId != null) {
            final SubLevel subLevel = container.getSubLevel(this.parentSubLevelId);

            if (subLevel != null) {
                return List.of(subLevel);
            }
        }

        return null;
    }

    public void setParentAssembleNextTick() {
        final BlockEntity be = this.level.getBlockEntity(this.parent);
        if (be instanceof final MyBearingBlockEntity bearingBE) {
            bearingBE.assembleNextTick = true;
        }
    }

    public void fixParentLinkingWhenMoved() {
        if (this.level.isClientSide() || this.parent == null) {
            return;
        }

        final BlockEntity be = this.level.getBlockEntity(this.parent);

        if (be instanceof final MyBearingBlockEntity bearingBE) {
            bearingBE.setPlatePos(this.getBlockPos());

            final ServerSubLevel newSublevel = (ServerSubLevel) Sable.HELPER.getContaining(this);
            if (newSublevel != null) {
                final UUID subLevelID = bearingBE.getSubLevelID();
                final UUID newID = newSublevel.getUniqueId();

                if (newID != subLevelID) {
                    bearingBE.setSubLevelID(newSublevel.getUniqueId());
                    bearingBE.reattachConstraint(newSublevel, true);
                }
            } else {
                bearingBE.setSubLevelID(null);
                bearingBE.reattachConstraint(null, true);
            }
        }
    }
}
