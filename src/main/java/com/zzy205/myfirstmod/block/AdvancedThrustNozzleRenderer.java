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
 * <h3>活塞的机构（2026-11 第二轮定稿，别照抄参考实现的自由度）</h3>
 * <ul>
 * <li><b>活塞头 = 底座的固定件（0 自由度）</b>：它本来就钉在底板上、与底座没有相对移动，所以
 *     已并进 {@code base.json}（不再有 {@code piston_head.json} / 也不再在这里画）：
 *     未倾斜时头占 x/z 半径 6..8px、y 4..6px（坐在底板 y0..4 的上表面）；</li>
 * <li><b>活塞杆 = body 的子件（1 个自由度 = 沿 body 自身轴的滑动）</b>：姿态与 body 完全同源
 *     （同一个 tilt、同一个枢轴 {@link #PIVOT}）⇒ <b>相对 body 没有旋转</b>，径向永远钉在臂槽槽心
 *     {@link #PISTON_HINGE_RADIUS}，只在 body 的 +Y 上滑动；
 *     滑移量由"杆底面恒停在活塞头夹持高度 {@link #PISTON_HINGE_HEIGHT}"反解
 *     （{@link #pistonSlideAlongBodyAxis}，等价于参考实现的 {@code translateDistance} 手法）；</li>
 * <li>于是视觉是<b>"固定的头 + 在 body 臂槽里伸缩的杆 + 随倾斜滑过杆顶的 body"</b>。</li>
 * </ul>
 * ⚠️ 上一版（v3）是"杆底面钉死在铰点上、绕铰点吃 tilt"：朝向仍与 body 平行，但<b>旋转中心不同</b>
 * ⇒ 相对位移里混进 {@code (I−R)(H−P)} 的横向分量（15° 时最多 ≈1px）。杆径向只有 1px 粗、
 * 臂槽每边只有 1px 余量，所以那 1px 会蹭出 body 侧壁 / 扎进臂槽边缘，看上去像"杆在乱飘"——
 * 这就是 2026-11 第二轮把它改成"body 子件 + 轴向滑移"的原因。<b>不要再改回绕铰点转。</b>
 * <p>
 * ⚠️ 与参考实现的差别：{@code aeronautics:gyroscopic_propeller_bearing} 是"头顶在转盘上、杆朝下插进固定体"，
 * 我们的模型是"头在固定底板上、杆朝上接可动体"，所以活塞朝上、且头必须是固定件（两边互为镜像，
 * 但"沿固定轴算滑移量把杆卡在另一侧"这条数学是对称的）。
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
     * 活塞杆轴线离中轴的半径（px 6.5）—— 杆在 <b>body 坐标系</b>里的径向位置（= body 上那道臂槽的槽心）。
     * <p>
     * 依据 {@code item.json} 的静态姿态：杆占半径 6..7px（1px 径向）、切向 2px ⇒ 轴线在半径 6.5px；
     * 高度见 {@link #PISTON_HINGE_HEIGHT}。partial 模型 {@code piston_pole.json} 按"局部原点 = 杆底面中心、
     * 局部 +X = 径向朝外"建模：{@code from [-0.5, 0, -1] to [0.5, 7, 1]}。
     * <p>
     * 活塞头（同样的 6..8px 半径、切向 6px）已并进 {@code base.json}（固定件），由 blockstate 静态渲染，这里不管。
     */
    public static final float PISTON_HINGE_RADIUS = 6.5f / 16f;

    /**
     * 杆底面在 <b>body 坐标系</b>里的基准高度（px 5）—— 也就是杆底面被"卡"在活塞头夹持高度的那一层。
     * <p>
     * {@code item.json} 里杆占 y5..12px、活塞头占 y4..6px ⇒ 杆底面插进头里 1px、头上还留 0.15px 余量：
     * 滑移量按"杆底面始终停在这个固定高度"反解（见 {@link #pistonSlideAlongBodyAxis}），
     * 于是杆<b>不会共面 z-fighting</b>（比贴头的下表面 y=4 高 1px）。
     */
    public static final float PISTON_HINGE_HEIGHT = 5f / 16f;

    /** 活塞杆模型长度（px 7）：{@code piston_pole.json} 的 {@code from/to} 沿 +Y 正好 7px */
    public static final float PISTON_LENGTH = 7f / 16f;

    /**
     * 杆顶在 <b>body 坐标系</b>里的上限（px 13）—— 十字臂带（y9..13px）的上表面；超过就会从臂槽顶端露出一截。
     * <p>
     * 滑移量在"朝本活塞方向满锥角"这一侧会达到 4.85px（= 6.5px·sin15° 被 raise 项顶起来），
     * 那不光是杆顶冒出 0.85px，还会在臂槽外看见杆的端面。这里把它夹到 4px：
     * 代价是那一侧的杆底只比夹持面低 0.8px（仍在活塞头 y4..6 里，看不见），换来任何倾角都不外露。
     */
    public static final float PISTON_TOP_LIMIT = 13f / 16f;

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
        // 机构（2026-11 第二轮，照 aeronautics:gyroscopic_propeller_bearing 的"沿固定轴算滑移量"手法）：
        //   · 活塞头 = 底座的固定部件 → 0 自由度，已并进 base.json（blockstate 静态渲染，这里不画）；
        //   · 活塞杆 = **body 的子件**：姿态与 body 完全同源（同一个 tilt、同一个枢轴），
        //     相对 body 只有 **1 个轴向滑动自由度**（沿 body 自己的 +Y），横向位置钉死在臂槽槽心。
        //     ⇒ 杆永远平行于臂槽、永不蹭出 body 侧壁（这正是上一版"绕铰点转"的毛病：
        //        两者旋转中心不同 ⇒ 相对位移里混进最多 1px 的横向量，杆只有 1px 粗，肉眼就花了）。
        //   · 滑移量由"杆底面始终停在活塞头的夹持高度 PISTON_HINGE_HEIGHT"反解（pistonSlideAlongBodyAxis）：
        //     等价于参考实现的 translateDistance = p·n_tilted / (n_fixed·n_tilted)。
        final Vector3f tiltedAxis = new Vector3f(0f, 1f, 0f).rotate(tilt);

        for (int i = 0; i < PISTON_COUNT; i++) {
            final float azimuth = (float) (-Math.PI / 2.0D * i);

            // 杆轴线在 body 坐标系里的径向偏移（随方位绕 Y 转）
            final Vector3f radial = new Vector3f(PISTON_HINGE_RADIUS, 0f, 0f).rotateY(azimuth);
            // 这个径向偏移被 tilt 转过之后在"世界竖直方向"上抬高/压低的量
            final float tiltedRise = new Vector3f(radial).rotate(tilt).y;
            final float slide = pistonSlideAlongBodyAxis(tiltedRise, tiltedAxis.y);

            final SuperByteBuffer pole =
                    CachedBuffers.partial(MyModPartialModels.ADVANCED_THRUST_NOZZLE_PISTON_POLE, state);
            applyFacing(pole, facing);
            // 与 body 完全相同的倾斜（同一个 tilt、同一个枢轴）⇒ 相对 body 无旋转
            pole.rotateAround(tilt, PIVOT.x, PIVOT.y, PIVOT.z);
            // body 坐标系里的落位：径向钉死 + 只沿 +Y 滑动
            pole.translate(PIVOT.x + radial.x, PIVOT.y + slide, PIVOT.z + radial.z);
            pole.rotateY(azimuth);
            pole.light(light).renderInto(ms, vb);
        }
    }

    /**
     * 杆沿 body 轴向的滑移量（块单位，正 = 沿 body 的 +Y 伸出）。
     * <p>
     * 约束：杆底面在<b>世界/底座坐标系</b>里的高度恒等于 {@link #PISTON_HINGE_HEIGHT}（活塞头夹持那一层）。
     * 杆底面高度 = {@code PIVOT.y + tiltedRise + slide · tiltedAxis.y}（枢轴 + 径向偏移被倾斜后的竖直分量 + 滑移的竖直分量），
     * 解出 {@code slide} 即可。倾斜为 0 时 {@code tiltedRise = 0}、{@code tiltedAxis.y = 1} ⇒ {@code slide = PISTON_HINGE_HEIGHT − PIVOT.y}。
     * <p>
     * 这就是参考实现 {@code GyroscopicPropellerBearingRenderer} 第 77 行
     * {@code translateDistance = translatedPos.dot(contraptionNormal) / normal.dot(contraptionNormal)} 的镜像写法
     * （那边沿固定轴把活塞"卡"在倾斜盘上，这边沿 body 轴把杆"卡"在固定的活塞头上）。
     * <p>
     * 最后再夹一道 {@link #PISTON_TOP_LIMIT}：保证杆顶永远留在十字臂带里（只有一侧满锥角时会生效，
     * 生效时杆底只沉 0.8px，仍在活塞头内部）。
     */
    private static float pistonSlideAlongBodyAxis(final float tiltedRise, final float tiltedAxisY) {
        // 锥角 ≤ 15° ⇒ cos15° ≈ 0.966，兜底只是防 NaN（0 以外不可能出现）
        final float denom = Math.max(0.5f, tiltedAxisY);
        final float slide = (PISTON_HINGE_HEIGHT - PIVOT.y - tiltedRise) / denom;
        return Math.min(slide, PISTON_TOP_LIMIT - PIVOT.y - PISTON_LENGTH);
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
