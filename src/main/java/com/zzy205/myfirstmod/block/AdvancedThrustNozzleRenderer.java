package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
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
 * <li><b>绕枢轴的倾斜</b>：枢轴 = 模型空间 {@code (8,2,8)px}（底板 y0..4 的中面、也是裙边下沿所在高度），
 *     {@code rotateAround(tilt, PIVOT)} 等价于参考实现的
 *     {@code translate(+pivot) → rotateCentered(tilt) → translate(−pivot)} 手法；</li>
 * <li><b>各部件自身的平移 / 方位旋转</b>。</li>
 * </ol>
 * 固定底板由 blockstate 静态模型渲染（{@code models/block/advanced_thrust_nozzle/base}），这里不画。
 *
 * <h3>活塞的机构（用户定稿，别照抄参考实现的自由度）</h3>
 * <ul>
 * <li><b>活塞头 = 底座的固定件（0 自由度）</b>：它本来就钉在底板上、与底座没有相对移动，所以
 *     <b>2026-11 起已并进 {@code base.json}</b>（不再有 {@code piston_head.json} / 也不再在这里画）：
 *     未倾斜时头占 x/z 半径 6..8px、y 4..6px（坐在底板 y0..4 的上表面）；</li>
 * <li><b>活塞杆 = 装在 block 上的转动副（1 个自由度）</b>：<b>底面中心那个点钉死在活塞头上</b>
 *     （铰点 = 半径 6.5px、高度 y=5px，见 {@link #PISTON_HINGE_RADIUS} / {@link #PISTON_HINGE_HEIGHT}），
 *     姿态随 block 一起吃同一个 tilt 四元数 ⇒ 顶端相对 body 是<b>绕铰点的摆动</b>，
 *     顶端摆幅被 body 的十字臂带（y9..13px，宽 6px）挡住、不会穿出方块；</li>
 * <li>于是视觉是<b>"固定的头 + 绕铰点摆动的杆 + 上下滑过杆顶的 block"</b>。</li>
 * </ul>
 * ⚠️ 与参考实现的差别：{@code aeronautics:gyroscopic_propeller_bearing} 是"头顶在转盘上、杆朝下插进固定体"，
 * 我们的模型是"头在固定底板上、杆朝上接可动体"，所以活塞朝上、且头必须是固定件。
 *
 * <h3>倾斜姿态</h3>
 * 由 {@link AdvancedThrustNozzleBlockEntity#getRenderTiltQuaternion(float)} 提供：
 * <b>服务端</b>恒速线性推进角度（每 tick 广播），<b>客户端</b>再用
 * {@link JoystickTilt#approach} 指数逼近（按帧时间修正）把步进抹成连续动画——与 {@code joystick} 的分层完全一致。
 * 倾斜姿态（方位角 + 锥角）在 BE 内部合成同一个四元数，活塞杆吃同一个四元数 ⇒ 机构不会散架。
 *
 * <p>参考来源（只读）：{@code references/Simulated-Project-main/aeronautics/.../gyroscopic_propeller_bearing/}
 * 的 {@code GyroscopicPropellerBearingRenderer}（枢轴倾斜 + 四活塞位置/朝向手法）、
 * {@code GyroscopicPropellerBearingBlockEntity}（{@code tiltQuat} / 锥角钳位 12° / previous+插值）。
 */
public class AdvancedThrustNozzleRenderer extends SafeBlockEntityRenderer<AdvancedThrustNozzleBlockEntity> {

    // ═══════════════ 几何常量（模型空间，块单位；px 值见注释） ═══════════════

    /** 倾斜枢轴（px (8,2,8)）：block 部分绕它转（底板 y0..4px 的中面，裙边 y2..6px 的下沿就在这一层） */
    public static final Vector3f PIVOT = new Vector3f(8f / 16f, 2f / 16f, 8f / 16f);

    /**
     * 铰点离中轴的半径（px 6.5）—— 活塞杆那个"不动点"的径向距离。
     * <p>
     * 依据 {@code item.json} 的静态姿态：杆占半径 6..7px（1px 径向）、切向 2px ⇒ 其<b>底面中心</b>在半径 6.5px；
     * 高度见 {@link #PISTON_HINGE_HEIGHT}。partial 模型 {@code piston_pole.json} 按"局部原点 = 这个铰点"建模：
     * {@code from [-0.5, 0, -1] to [0.5, 7, 1]}（底面对齐 y=0，杆沿 +Y 7px，局部 +X = 径向朝外），
     * 渲染时<b>不需要任何偏移常量</b>，铰点即模型原点。
     * <p>
     * 活塞头（同样的 6..8px 半径、切向 6px）已并进 {@code base.json}（固定件），由 blockstate 静态渲染，这里不管。
     */
    public static final float PISTON_HINGE_RADIUS = 6.5f / 16f;

    /**
     * 铰点高度（px 5）—— 活塞杆底面所在高度。
     * <p>
     * {@code item.json} 里杆占 y5..12px、活塞头占 y4..6px ⇒ 杆底面插进头里 1px，
     * 绕这个点摆动时底面始终留在头的厚度内（看不见接缝）。
     */
    public static final float PISTON_HINGE_HEIGHT = 5f / 16f;

    /** 活塞组数（四方位，i = 0..3 → 绕 Y 依次 −90°） */
    private static final int PISTON_COUNT = 4;

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
        // 倾斜姿态：客户端每 tick 推进视觉角，这里按 partialTicks 插值上一 tick → 当前 tick
        // （照 create-propulsion-simulated 的 VectorThrusterRenderer：Mth.lerp(partialTick, prev, current)）
        final Quaternionf tilt = be.getRenderTiltQuaternion(partialTicks);

        // ── 1. 可动 block 部分（筒身 + 十字臂 + 顶环 + 裙边），绕枢轴倾斜 ──
        final SuperByteBuffer blockPart =
                CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_BLOCK, state);
        applyFacing(blockPart, facing);
        blockPart.rotateAround(tilt, PIVOT.x, PIVOT.y, PIVOT.z);
        blockPart.light(light).renderInto(ms, vb);

        // ── 2. 四个随动活塞杆 ──
        // 机构的两个件（用户定稿）：
        //   · 活塞头 = 底座的固定部件 → **0 自由度**，已并进 base.json（blockstate 静态渲染，这里不画）；
        //   · 活塞杆 = 底面中心那个点**钉死在活塞头上**（无相对移动），但**可以绕该点转动**：
        //     它随 block 一起倾斜 ⇒ 顶端相对 body 就是摆动（body 的十字臂带 y9..13px 把这点摆动藏住）。
        // 杆的 partial 模型按"局部原点 = 这个铰点"建模，所以只要一句 translate + rotate，没有偏移常量。
        for (int i = 0; i < PISTON_COUNT; i++) {
            final float azimuth = (float) (-Math.PI / 2.0D * i);

            // 铰点（模型空间，随方位绕 Y 旋转；**不吃 tilt** —— 它固定在底座/活塞头上）
            final Vector3f hinge = new Vector3f(PIVOT.x, PISTON_HINGE_HEIGHT, PIVOT.z)
                    .add(new Vector3f(PISTON_HINGE_RADIUS, 0f, 0f).rotateY(azimuth));

            // 活塞杆（1 个转动自由度）：绕铰点倾斜 —— 底面永远钉在铰点上，
            // 顶端随 tilt 相对 body 摆动（正是用户要的机构）
            final SuperByteBuffer pole =
                    CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_PISTON_POLE, state);
            applyFacing(pole, facing);
            pole.translate(hinge.x, hinge.y, hinge.z)
                    .rotate(tilt)
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
}
