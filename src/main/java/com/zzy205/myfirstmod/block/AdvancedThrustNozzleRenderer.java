package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 矢量喷管（{@code ccpe:advanced_thrust_nozzle}）的动态部件渲染。
 * <p>
 * <b>只走原版 BER，不注册 Flywheel Visual</b>（照 {@link MyBearingPlateRenderer} 的先例）：
 * 没有 Visual 时 Flywheel 不会跳过原版 BE 渲染，所以这里<b>不能</b>加
 * {@code VisualizationManager.supportsVisualization} 的提前返回，否则装了 Flywheel 就什么都不画了。
 * <p>
 * 层级（外 → 内）：
 * <ol>
 * <li><b>FACING 旋转</b>：与 {@code blockstates/advanced_thrust_nozzle.json} 的 x/y 旋转
 *     <b>严格一致</b>（变换链照抄已验证的 {@link ServoBearingRenderer}：水平朝向先绕 Y 转
 *     {@code horizontalAngle(反朝向)}，再绕 X 转 {@code -90 - verticalAngle}）；</li>
 * <li><b>绕枢轴的倾斜</b>：枢轴 = 模型空间 {@code (8,1,8)px}（用户定稿），
 *     {@code rotateAround(tilt, PIVOT)} 等价于参考实现的
 *     {@code translate(+pivot) → rotateCentered(tilt) → translate(−pivot)} 手法；</li>
 * <li><b>各部件自身的平移 / 方位旋转</b>。</li>
 * </ol>
 * 固定底板由 blockstate 静态模型渲染（{@code models/block/advanced_thrust_nozzle/base}），这里不画。
 *
 * <h3>活塞随动的三个要点</h3>
 * <ul>
 * <li>活塞头<b>贴在倾斜面上</b>：挂点 = {@code PIVOT + R_tilt·(半径向量)}（头的局部原点在半径 6px，
 *     头自身占 6..8px、杆占 6..7px —— 与 {@code item.json} 里 groups 的静态摆位一致）。
 *     ⚠️ 参考实现用的是"半径向量投影到倾斜面再沿轴推"的写法，而且符号是反的（12° 下只差 0.3px 看不出来），
 *     这里直接用旋转后的挂点，几何上精确；</li>
 * <li>活塞头<b>朝向随 block 一起倾斜</b>（{@code T(headPos) · R_tilt · R_azimuth}）；</li>
 * <li>活塞杆<b>保持竖直</b>（不随倾斜），水平位置固定在自己的方位，高度跟着头走 —— 所以看起来是
 *     "头在杆上滑动"，杆顶部一直缩在十字臂里不会穿出去。杆模型占 y −8..−1px，上移 9px 后正好落在
 *     头的上方 y2..9px；参考实现是杆朝下（它的固定体在下面），我们的固定体在下、可动体在上，所以朝上。</li>
 * </ul>
 *
 * <h3>阶段一的测试姿态</h3>
 * 还没有角度控制逻辑：倾斜姿态由"绕锥面进动"的时间函数生成（极角 {@link #TEST_TILT_DEG} 固定、
 * 方位角匀速转），方便一次进游戏就能看出四个活塞是否随动、六向 facing 是否都对、以及倾角大了会不会
 * 穿进底板。阶段二把 {@link #currentTilt(AdvancedThrustNozzleBlockEntity, float)} 换成读 BE 的
 * 服务端权威方向即可，渲染链本身不用改。
 *
 * <p>参考来源（只读）：{@code references/Simulated-Project-main/aeronautics/.../gyroscopic_propeller_bearing/}
 * 的 {@code GyroscopicPropellerBearingRenderer}（枢轴倾斜 + 四活塞位置/朝向手法）、
 * {@code GyroscopicPropellerBearingBlockEntity}（{@code tiltQuat} / 锥角钳位 12° / previous+插值）。
 */
public class AdvancedThrustNozzleRenderer extends SafeBlockEntityRenderer<AdvancedThrustNozzleBlockEntity> {

    // ═══════════════ 几何常量（模型空间，块单位；px 值见注释） ═══════════════

    /** 倾斜枢轴（px (8,1,8)）：block 部分绕它转 */
    public static final Vector3f PIVOT = new Vector3f(8f / 16f, 1f / 16f, 8f / 16f);

    /** 活塞所在半径（px 6）：头的局部原点落在这里，头自身占半径 6..8px、杆占 6..7px */
    public static final float PISTON_RADIUS = 6f / 16f;

    /** 活塞杆相对自身局部原点沿 +Y 的上移量（px 9）：杆模型 y −8..−1 ⇒ 上移后 y 2..9（与 item.json 一致） */
    public static final float POLE_UP_SHIFT = 9f / 16f;

    /** 活塞组数（四方位，i = 0..3 → 绕 Y 依次 −90°） */
    private static final int PISTON_COUNT = 4;

    // ═══════════════ 阶段一测试姿态（进游戏可直接改这两个数） ═══════════════

    /**
     * 测试倾角（度）：喷口轴向偏离法线的角度。
     * <p>
     * 15° 是保守值：枢轴在 {@code y=1px}（底板中部），倾角再大裙边/下沿就会切进底板板面
     * （{@code 6px × sin θ}，20° 起可见，30° 明显）。阶段二做锥角钳位时以它为上界参考。
     */
    public static final float TEST_TILT_DEG = 15f;

    /** 方位角进动速度（度/tick）：60°/秒绕法线一圈需要 6 秒，纪要看四个活塞依次升降 */
    public static final float TEST_PRECESSION_DEG_PER_TICK = 6f;

    public AdvancedThrustNozzleRenderer(final BlockEntityRendererProvider.Context context) {
        // SafeBlockEntityRenderer 无参构造；保留 Context 参数以匹配 BlockEntityRendererProvider 工厂
    }

    @Override
    protected void renderSafe(final AdvancedThrustNozzleBlockEntity be, final float partialTicks,
                              final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        // ⚠️ 不要在这里做 Flywheel 提前返回（本方块没有 Visual，BER 是唯一渲染来源）
        final BlockState state = be.getBlockState();
        final Direction facing = state.getValue(ThrustNozzleBlock.FACING);
        final VertexConsumer vb = buffer.getBuffer(RenderType.cutout());
        final Quaternionf tilt = currentTilt(be, partialTicks);

        // ── 1. 可动 block 部分（筒身 + 十字臂 + 顶环 + 裙边），绕枢轴倾斜 ──
        final SuperByteBuffer blockPart =
                CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_BLOCK, state);
        applyFacing(blockPart, facing);
        blockPart.rotateAround(tilt, PIVOT.x, PIVOT.y, PIVOT.z);
        blockPart.light(light).renderInto(ms, vb);

        // ── 2. 四个随动活塞（头贴倾斜面 + 杆保持竖直） ──
        for (int i = 0; i < PISTON_COUNT; i++) {
            final float azimuth = (float) (-Math.PI / 2.0D * i);

            // 本方位的半径向量（模型空间）：i=0 指向 +X，依次绕 Y 转 −90°
            final Vector3f radial = new Vector3f(PISTON_RADIUS, 0f, 0f).rotateY(azimuth);
            // 倾斜后 block 上的挂点方向（高度分量自动等于"贴倾斜面"所需的抬升量）
            final Vector3f lifted = new Vector3f(radial).rotate(tilt);
            final Vector3f headPos = new Vector3f(PIVOT).add(lifted);

            // 活塞头：位置在倾斜面上，朝向 = 倾斜 + 本方位（Local +X 朝外 = 半径方向）
            final SuperByteBuffer head =
                    CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_PISTON_HEAD, state);
            applyFacing(head, facing);
            head.translate(headPos.x, headPos.y, headPos.z)
                    .rotate(tilt)
                    .rotateY(azimuth);
            head.light(light).renderInto(ms, vb);

            // 活塞杆：不吃 tilt（保持竖直）；水平位置固定在本方位、高度跟着头走（= 头在杆上滑动）
            final Vector3f polePos = new Vector3f(PIVOT.x + radial.x, headPos.y, PIVOT.z + radial.z);
            final SuperByteBuffer pole =
                    CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_PISTON_POLE, state);
            applyFacing(pole, facing);
            pole.translate(polePos.x, polePos.y, polePos.z)
                    .translate(0f, POLE_UP_SHIFT, 0f)
                    .rotateY(azimuth);
            pole.light(light).renderInto(ms, vb);
        }
    }

    /**
     * 与 {@code blockstates/advanced_thrust_nozzle.json} 严格一致的 FACING 旋转（模型空间 → 世界）。
     * <p>
     * 逐步照抄已在游戏里验证过的 {@link ServoBearingRenderer}（同样是 up 不转 / down x180 /
     * north x90 / east·south·west = x90 + y90·180·270 的六变体约定）。
     * {@code SuperByteBuffer} 的变换是"先调用 = 外层"，所以这里必须先于部件自身的变换调用。
     */
    private static void applyFacing(final SuperByteBuffer buf, final Direction facing) {
        if (facing.getAxis().isHorizontal()) {
            buf.rotateCentered(AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite())), Direction.UP);
        }
        buf.rotateCentered(AngleHelper.rad(-90 - AngleHelper.verticalAngle(facing)), Direction.EAST);
    }

    /**
     * 当前倾斜姿态：把模型空间 +Y 转到目标轴向的<b>最小弧</b>四元数。
     * <p>
     * 阶段一 = 纯客户端测试姿态（绕锥面进动）；阶段二改成读 {@link AdvancedThrustNozzleBlockEntity}
     * 的目标方向（previous/current 帧间插值）即可，调用点不用变。
     */
    private static Quaternionf currentTilt(final AdvancedThrustNozzleBlockEntity be, final float partialTicks) {
        // 用 Catnip 的渲染时间（自带 partialTicks，帧间平滑）而不是每次新建计数值：
        // 与 MyBearingPlateRenderer 里给传动杆取角度是同一个来源。
        final Level level = be.getLevel();
        final double time = level == null ? 0.0D : (double) AnimationTickHolder.getRenderTime(level);
        final float azimuthDeg = (float) ((time * TEST_PRECESSION_DEG_PER_TICK) % 360.0D);
        final float polarRad = (float) Math.toRadians(TEST_TILT_DEG);
        final float azimuthRad = (float) Math.toRadians(azimuthDeg);

        // 目标轴向（模型空间，y 向上 = 喷口原朝向）
        final Vector3f axis = new Vector3f(
                (float) (Math.sin(polarRad) * Math.cos(azimuthRad)),
                (float) Math.cos(polarRad),
                (float) (Math.sin(polarRad) * Math.sin(azimuthRad)));
        return new Quaternionf().rotationTo(0f, 1f, 0f, axis.x, axis.y, axis.z);
    }
}
