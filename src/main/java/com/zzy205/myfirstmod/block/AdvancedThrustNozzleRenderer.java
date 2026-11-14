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
 * <h3>活塞的机构（用户定稿，别照抄参考实现的自由度）</h3>
 * <ul>
 * <li><b>活塞头 = 底座的固定件（0 自由度）</b>：位置/朝向只跟本方位有关，<b>不吃 tilt、也不上下移动</b>。
 *     未倾斜时头占 x/z 半径 6..8px、y 0..2px（正好贴着底板外缘，与 {@code item.json} 一致）；</li>
 * <li><b>活塞杆 = 装在 block 上的棱柱副（1 自由度）</b>：朝向随 block 一起倾斜，只允许沿自身轴滑动。
 *     滑动量取"让杆的下端一直贴住固定的活塞头"：
 *     <code>s = r·(N·d) + h0·(cosθ − 1)</code>（N = 倾斜后的轴向，d = 本方位半径单位向量，
 *     h0 = {@link #PISTON_LOWER_END_HEIGHT} = 0）。未倾斜 s = 0（杆占 y1..8px、下端插在活塞头里 1px），
 *     倾斜 15° 时 |s| ≈ 1.5px；</li>
 * <li>于是视觉是<b>"固定的头 + 在头里伸缩的杆 + 上下滑过杆顶的 block"</b>：倾角越大杆伸出越长，
 *     杆顶一直在十字臂带里（被挡住），不会穿出方块。</li>
 * </ul>
 * ⚠️ 与参考实现的差别：{@code aeronautics:gyroscopic_propeller_bearing} 是"头顶在转盘上、杆朝下插进固定体"，
 * 我们的模型是"头在固定底板上、杆朝上接可动体"，所以活塞朝上、且头必须是固定件。
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

    /**
     * 铰点离枢轴的半径（px 6.5）—— 活塞头上那个"不动点"。
     * <p>
     * 依据 {@code item.json} 的静态姿态：杆占半径 6..7px（1px 径向）⇒ 其<b>底面中心</b>在半径 6.5px、
     * 高度 y=1px（= 枢轴高度）。两个 partial 模型都按"局部原点 = 这个铰点"建模：
     * <ul>
     * <li>{@code piston_pole.json}：{@code from [-0.5, 0, -1] to [0.5, 7, 1]}（底面对齐 y=0，杆沿 +Y，
     *     局部 +X = 径向朝外）；</li>
     * <li>{@code piston_head.json}：{@code from [-0.5, -1, -3] to [1.5, 1, 3]}（铰点落在头的内部，
     *     X −0.5..+1.5 即距轴 6..8px、Y −1..+1 即 y0..2px、Z −3..+3 即切向 6px）。</li>
     * </ul>
     * 这样渲染时两个部件都<b>不需要任何偏移常量</b>，铰点即模型原点。
     */
    public static final float PISTON_HINGE_RADIUS = 6.5f / 16f;

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

        // ── 2. 四个随动活塞 ──
        // 机构的两个关节（用户定稿）：
        //   · 活塞头 = 底座的固定部件 → **0 自由度**（不倾斜、不上下移动，只按方位取向）；
        //   · 活塞杆 = 底面中心那个点**钉死在活塞头上**（无相对移动），但**可以绕该点转动**：
        //     它随 block 一起倾斜 ⇒ 顶端相对 body 就是上下滑动（body 的十字臂带 y6..10px 把这点滑动藏住）。
        // 两个 partial 模型都按"局部原点 = 这个铰点"建模，所以这里每个部件只要一句 translate + rotate，
        // 没有任何偏移常量（改模型定位不需要动代码）。
        for (int i = 0; i < PISTON_COUNT; i++) {
            final float azimuth = (float) (-Math.PI / 2.0D * i);

            // 铰点（模型空间，随方位绕 Y 旋转；**不吃 tilt** —— 它固定在底座上）
            final Vector3f hinge = new Vector3f(PIVOT)
                    .add(new Vector3f(PISTON_HINGE_RADIUS, 0f, 0f).rotateY(azimuth));

            // 活塞头（0 自由度）：位置/朝向只跟本方位有关
            final SuperByteBuffer head =
                    CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_PISTON_HEAD, state);
            applyFacing(head, facing);
            head.translate(hinge.x, hinge.y, hinge.z).rotateY(azimuth);
            head.light(light).renderInto(ms, vb);

            // 活塞杆（1 个转动自由度）：绕铰点倾斜 —— 底面永远钉在铰点上，
            // 顶端随 tilt 相对 body 上下滑动（正是用户要的机构）
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
