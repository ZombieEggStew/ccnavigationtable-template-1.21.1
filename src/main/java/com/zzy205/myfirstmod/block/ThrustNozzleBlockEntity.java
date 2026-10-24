package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 定向喷管方块实体：把背后电扇的转速换算成<b>纯轴向恒定推力</b>，每个物理 substep 施加到所在物理体上。
 * <p>
 * 参考来源（均为只读参考）：
 * <ul>
 * <li>{@code api/create-1.21.1-6.0.10-280-sources/.../kinetics/fan/NozzleBlockEntity.java}
 *     —— 原版喷嘴只推原版实体（{@code setDeltaMovement}），对 Sable 物理体零作用；</li>
 * <li>{@code references/sable-main/neoforge/.../mixin/compatibility/create/nozzle/block_entity/NozzleHoveringMixin.java}
 *     + {@code mixinhelper/.../NozzleHoveringHelper.java} —— 原版喷嘴真正出力的地方（Sable 的 mixin）：
 *     13 根射线打到表面、按命中距离衰减、{@code magnitude = 5 · scale · softSpeed(>128RPM 软缩放) · 气压}，
 *     实测 256 RPM/1 格 = 319.46 pN；</li>
 * <li>{@code references/sable-main/neoforge/.../fans_provide_force/EncasedFanBlockEntityMixin.java}
 *     —— 无喷嘴时电扇自身的螺旋桨推力；注意其 {@code isActive()} 要求 FACING 前方是空气，
 *     装上喷管后该路推力自动归零 ⇒ 总出力 = 本方块，数值可预测。</li>
 * </ul>
 * 本实现的取舍（用户定稿：纯轴向恒定推力，完全不管表面）：
 * <ul>
 * <li><b>不做射线</b>：不需要命中任何表面，任意高度/距离出力相同（原版喷嘴 6.4 格外归零）；</li>
 * <li><b>不乘气压项</b>：出力与海拔无关（Sable 原版喷嘴乘 {@code DimensionPhysicsData.getAirPressure}）；</li>
 * <li><b>转速线性</b>：不做 Sable 的 {@code >128 RPM 软缩放}，保持 memo §6.5 已验证的"推力∝转速"手感；</li>
 * <li><b>力的大小</b>：{@link #THRUST_PER_RPM} × 转速，256 RPM 时单喷管 800 pN = 原版喷嘴实测
 *     319.46 pN 的 2.5 倍。</li>
 * </ul>
 * 力以点力的形式打进 Sable 的 {@code sable:propulsion} 力组：由 Sable 在物理步末统一施加
 * （{@code ServerSubLevel#applyQueuedForces}），并会被 {@code compat/cc/FlightDataRecorder} 的
 * 力组遥测列记录，方便进游戏标定。点力自带力臂，多个喷管的位置差自动产生力矩。
 */
public class ThrustNozzleBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

    /**
     * 每 RPM 的轴向推力（pN/RPM）。256 RPM → 800 pN/喷管。
     * 标定依据：原版 {@code create:nozzle} 实测 319.46 pN（256 RPM、离地 1 格、4 扇），×2.5 ≈ 800。
     */
    public static final double THRUST_PER_RPM = 800.0D / 256.0D;

    /**
     * 符号约定（<b>已进游戏实测校定</b>：初版按牛顿第三定律实现，实测方向相反，已翻转）。
     * <p>
     * 当前生效的规则（{@code true}）：
     * <ul>
     * <li><b>正转速 ⇒ 机体沿 +FACING（喷口朝向那一侧）受力</b>；负转速 ⇒ 沿 -FACING；</li>
     * <li>喷口 {@code facing=down}（装在车底、喷口朝下）时：<b>正转速 = 把车往下压 / 贴地</b>，
     *     负转速 = 向上推（上升、刹车）；</li>
     * <li>车身倒挂（roll≈180°）时喷口朝世界"上"，同一个正转速就变成"吸向头顶的赛道"，
     *     倒挂吸附改用<b>正转速</b>（旧 {@code create:nozzle} 用负转速）。</li>
     * </ul>
     * 改成 {@code false} 即可整体反向（回到"正转速 = 沿 -FACING"）。
     */
    public static final boolean REVERSE_SIGN = true;

    /** Sable 力组注册表 key（{@code sable:force_groups}）。不直接引用 {@code ForceGroups}：其静态字段
     *  类型引用 veil 的 {@code RegistryObject}，veil 不在编译 classpath（同 {@code FlightDataRecorder}）。 */
    private static final ResourceKey<Registry<ForceGroup>> FORCE_GROUP_REGISTRY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("sable", "force_groups"));
    private static final ResourceLocation PROPULSION_ID =
            ResourceLocation.fromNamespaceAndPath("sable", "propulsion");

    /** 运行时解析出的 {@code sable:propulsion} 力组（解析失败保持 null，下次再试） */
    private @Nullable ForceGroup cachedPropulsion;

    public ThrustNozzleBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.thrust_nozzle_entity.get(), pos, state);
    }

    // ── Sable 物理：每 substep 沿喷口轴向施加恒定推力 ──

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        final float speed = this.getFanSpeed();
        if (speed == 0.0F) {
            return;
        }

        final QueuedForceGroup group = this.propulsionGroup(subLevel);
        if (group == null) {
            return;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        final Vec3i normal = facing.getNormal();
        // 冲量 = 推力 × substep 时长；rpm 带符号，所以 magnitude 也带符号
        final double magnitude = (REVERSE_SIGN ? -1.0D : 1.0D) * THRUST_PER_RPM * speed * timeStep;

        // 力向量与作用点都必须每次新建：QueuedForceGroup.applyImpulseAtPoint 会就地改写传入的
        // position（减去质心求力臂），且 recordPointForce 保留引用（FlightDataRecorder 遥测会读），
        // 复用临时向量会污染已记录的点力。
        final Vector3d impulse = new Vector3d(-normal.getX(), -normal.getY(), -normal.getZ()).mul(magnitude);
        group.applyAndRecordPointForce(JOMLConversion.atCenterOf(this.worldPosition), impulse);
    }

    /** 背后电扇的转速（RPM，带符号）；没有合法气流源 / 未转 → 0 */
    private float getFanSpeed() {
        final Level level = this.level;
        if (level == null) {
            return 0.0F;
        }

        final Direction facing = this.getBlockState().getValue(ThrustNozzleBlock.FACING);
        final BlockEntity behind = level.getBlockEntity(this.worldPosition.relative(facing.getOpposite()));

        if (!(behind instanceof IAirCurrentSource source) || source.getAirflowOriginSide() != facing) {
            return 0.0F;
        }

        return source.getSpeed();
    }

    /** 取 {@code sable:propulsion} 力组在本物理体上的队列 */
    private @Nullable QueuedForceGroup propulsionGroup(final ServerSubLevel subLevel) {
        if (this.cachedPropulsion == null) {
            try {
                final Registry<ForceGroup> registry = subLevel.getLevel().registryAccess()
                        .registry(FORCE_GROUP_REGISTRY).orElse(null);

                if (registry == null) {
                    return null;
                }

                this.cachedPropulsion = registry.get(PROPULSION_ID);
            } catch (final RuntimeException ignored) {
                return null;
            }

            if (this.cachedPropulsion == null) {
                return null;
            }
        }

        return subLevel.getOrCreateQueuedForceGroup(this.cachedPropulsion);
    }
}
