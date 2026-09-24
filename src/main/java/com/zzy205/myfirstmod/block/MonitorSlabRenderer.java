package com.zzy205.myfirstmod.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zzy205.myfirstmod.client.MonitorSlabGridOverlay;
import com.zzy205.myfirstmod.monitor.GridState;
import com.zzy205.myfirstmod.monitor.ModuleType;
import com.zzy205.myfirstmod.monitor.ScreenText;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * monitor_slab BER — 在面板（地板 = 顶面；贴墙 = 朝向 FACING 的竖直边界）上渲染已放置的模块模型与屏幕 9 宫格。
 * <p>
 * slab 本体由 blockstate 静态模型渲染，本 BER 只画表面内容。模块动画/微调见
 * {@link ModuleRenderBehavior}；旋钮角度文字/按钮标签见 {@link ModuleSurfaceRenderer}；
 * 屏幕 9 宫格/文字见 {@link Screen9GridRenderer}（水平面 {@link Screen9GridRenderer.ScreenPlane#horizontal()}）。
 * <p>
 * 朝向（水平顶面，模型朝向已核对 blockbench 模型；贴墙 = 面板坐标系 {@link MonitorSlabBlockEntity#panelFrame}
 * 单一来源，水平摊平帧 → 面板帧 = Ry(faceYaw)·Rx(−90)，模块/屏幕在面板局部帧内摊平，内容自动朝上）：
 * <ul>
 *   <li>button_1 底座/头部是竖在 XY 面的贴片（前脸 −Z，本地 z 0.625..1）→ 绕 X <b>+90°</b> 平躺，前脸朝外；</li>
 *   <li>toggle_switch / knob 底座原生平躺（前脸 +Y，本地底 y=0）→ 不旋转，直接放面板。</li>
 * </ul>
 * 竖直锚点：button 本地原点在背面（z=0.625..1 向下延伸）→ 锚点 = 面板 + 1px（背面贴面板）；
 * toggle/knob 本地底 y=0 → 锚点 = 面板。旋钮把手旋转轴（本地 Y）摊平后 = 面板法线，拖拽角度语义一致。
 * <p>
 * animProgress 使用 (BlockPos, moduleId) 复合 key，防止不同 slab 之间同 moduleId 的动画进度互相污染。
 */
public class MonitorSlabRenderer implements BlockEntityRenderer<MonitorSlabBlockEntity> {

    /** 每个 slab 独立的动画进度表，外层 key=BlockPos，内层 key=moduleId */
    private final Map<BlockPos, Map<Integer, Float>> animProgress = new HashMap<>();

    public MonitorSlabRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MonitorSlabBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        BlockPos bePos = be.getBlockPos();
        if (!be.hasContent()) return;
        GridState grid = be.getGridState();

        BlockState state = be.getBlockState();
        AttachFace face = state.getValue(MonitorSlabBlock.FACE);
        boolean wall = face == AttachFace.WALL;
        boolean ceiling = face == AttachFace.CEILING;
        // 贴墙：面板变换（translate(panelOrigin) + Ry(faceYaw)·Rx(−90)），模块/屏幕在面板局部帧内摊平；
        // 天花板：**全部用行列式 +1 的旋转**（不要 scale(1,−1,1) 镜像——负缩放翻转绕序，配合背面剔除模型会
        // 「内外翻转」，用户实测；Rx(180) 翻模块朝下、垂直 ScreenPlane 帧 Rx(+90) 翻屏幕朝下）。
        MonitorSlabBlockEntity.PanelFrame frame = MonitorSlabBlockEntity.panelFrame(state); // 三形态都有帧

        var beAnims = animProgress.computeIfAbsent(bePos, k -> new HashMap<>());
        beAnims.keySet().removeIf(id -> !grid.getAllModules().containsKey(id));

        // ── 渲染模块 ──
        for (var mod : grid.getAllModules().values()) {
            var bhv = ModuleRenderBehavior.of(mod.type());
            boolean isKnob = mod.type() == ModuleType.KNOB;

            // 面板局部锚点（块单位）：网格起点 + 格位；模块微调按 monitor 竖面帧映射到面板——
            // offsetX（屏幕水平）→ grid x、offsetY（屏幕垂直）→ grid y；offsetZ（屏幕法线/凸出）在面板
            // 不映射到高度（toggle/knob 会浮起 1px，用户进游戏确认后下沉；button 的 1px 凸出已含在 moduleBaseY）。
            float px = (MonitorSlabBlockEntity.GRID_ORIGIN_X_PX + mod.gridX()) / 16f + bhv.offsetX();
            float pz = (MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX + mod.gridY()) / 16f + bhv.offsetY();
            float py = moduleBaseY(mod.type());

            BakedModel model = MonitorPreloadedModels.getModel(mod.type());
            if (model == null) continue;

            float target;
            if (isKnob) {
                // 拖拽中优先使用客户端视觉角度（卡位微扭动）；否则跟随服务端角度
                Float visual = MonitorSlabGridOverlay.getActiveKnobVisualAngle(bePos, mod.id());
                target = visual != null ? visual : grid.getKnobAngle(mod.id());
            } else {
                target = grid.isPressed(mod.id()) ? 1f : 0f;
            }
            float next = ModuleRenderBehavior.stepAnim(beAnims, mod.id(), isKnob, target,
                    bhv.animPressSpeed(), bhv.animReleaseSpeed());

            poseStack.pushPose();
            if (wall && frame != null) {
                // 贴墙：锚点 = 面板局部 (px, pz, py) 按面板坐标系映射到世界，再套「水平摊平帧 → 面板帧」旋转
                // （Rx(faceXRotDeg=−90) 把模块从水平摊平转到面板法线，Ry(faceYawDeg) 定向；后续 pivot/button 在面板局部帧内照常）。
                double nOff = py - MonitorSlabBlockEntity.PANEL_Y_PX / 16f;
                Vec3 anchor = MonitorSlabBlockEntity.panelLocalToWorld(frame, px, pz, nOff);
                poseStack.translate(anchor.x, anchor.y, anchor.z);
                if (frame.faceYawDeg() != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(frame.faceYawDeg()));
                if (frame.faceXRotDeg() != 0f) poseStack.mulPose(Axis.XP.rotationDegrees(frame.faceXRotDeg()));
            } else if (ceiling && frame != null) {
                // 天花板：锚点 = 面板局部（grid x/y，法线 −Y 凸出 1px），Rx(180) 把水平摊平的模块翻到朝下。
                // Rx(180) 是 det+1 旋转（绕序正常）；它翻转局部 Z（= 世界 −Z），故 facing 与枢轴 v 分量需补偿（见下）。
                double nOff = py - MonitorSlabBlockEntity.PANEL_Y_PX / 16f;
                Vec3 anchor = MonitorSlabBlockEntity.panelLocalToWorld(frame, px, pz, nOff);
                poseStack.translate(anchor.x, anchor.y, anchor.z);
                poseStack.mulPose(Axis.XP.rotationDegrees(180));
            } else {
                poseStack.translate(px, py, pz);
            }
            // 朝向跟随 slab FACING：绕<b>模块足迹中心</b> Y 旋转（与 blockstate 对模型本体的 y 旋转一致）。
            // button/toggle 模型原点在角上（足迹中心 = 本地 (0.5,0.5)），knob 圆盘原点即中心（(0,0)）——
            // 若直接绕锚点（原点）转，模型会整体甩开且随 facing 偏移不同（用户确认症状）；位置不动（网格旋转不变，命中/放置无需旋转）。
            // 贴墙面板本身已按 FACING 定向（frame.faceYawDeg），面板局部帧内不再额外 facing 旋转；
            // 天花板 Rx(180) 翻转局部 Z，facing 需补偿（moduleFacingDeg）；枢轴 p = 足迹中心 (0.5,0,0.5) 在
            // Rx(180) 局部帧里就是 (0.5,0,0.5)，**不要取反**（取反会北偏 1px，用户实测；已推导所有朝向正确）。
            float facingDeg = moduleFacingDeg(state, face);
            float pivotX = modulePivotX(mod.type());
            float pivotZ = modulePivotZ(mod.type());
            if (pivotX != 0f || pivotZ != 0f) {
                poseStack.translate(pivotX, 0f, pivotZ);
            }
            if (facingDeg != 0f) {
                poseStack.mulPose(Axis.YP.rotationDegrees(facingDeg));
            }
            if (pivotX != 0f || pivotZ != 0f) {
                poseStack.translate(-pivotX, 0f, -pivotZ);
            }
            // 朝向校正（水平摊平）：button 贴片竖放（前脸 −Z）→ 绕 X +90° 平躺朝上；toggle/knob 底座已平躺 → 不转
            if (mod.type() == ModuleType.BUTTON_1X1) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
            }

            // 底座
            Screen9GridRenderer.renderModel(poseStack, buffer.getBuffer(Sheets.solidBlockSheet()), model, light, overlay);
            // 额外部件（拉杆/把手/按钮头）。按钮灯带亮度：代码控制时用 Lua 亮度，否则跟随按下动画
            float lightLevel = next;
            if (mod.type() == ModuleType.BUTTON_1X1) {
                lightLevel = grid.isLightCodeControlled(mod.id())
                        ? grid.getLightBrightness(mod.id()) : next;
            }
            bhv.renderExtra(poseStack, buffer, next, lightLevel, light, overlay);
            if (isKnob) {
                ModuleSurfaceRenderer.renderKnobAngle(poseStack, buffer, bePos, mod.id(), light,
                        grid.getKnobAngle(mod.id()), grid.getModuleConfig(mod.id()),
                        ModuleSurfaceRenderer.SLAB);
            }
            if (mod.type() == ModuleType.BUTTON_1X1) {
                ModuleSurfaceRenderer.renderButtonLabel(poseStack, buffer, grid.getButtonLabel(mod.id()), next, light);
            }

            poseStack.popPose();
        }

        // ── 渲染所有屏幕 9 宫格（外框网格对齐、内容朝向跟随 FACING；内容由 Screen9GridRenderer 绕屏幕中心单独旋转）──
        // 贴墙：整体包一层面板坐标系变换（translate(panelOrigin) + Ry(faceYaw)·Rx(faceXRot)），水平 ScreenPlane
        // 在面板局部帧内摊平 → 屏幕面 = 贴墙面板；面板局部帧内内容已朝上（grid y = 世界 +Y），无需平面内旋转（inPlane=0）。
        // 天花板：屏幕内容映射必须 (内容X→+X, 内容Y→+Z, 前脸(模型−Z面)→−Y 朝下)——该帧 (u,v,n) 是左手系，
        // 只能用反射 R_ref = Rx(90)·scale(1,1,−1)（内容平面内是恒等，不镜像文字；深度 → 世界 Y）。
        // 反射翻转绕序：9 宫格模型与字形需 reverseWinding 补偿（ceilingScreenPlane.mirrorWinding()）；
        // 用垂直 ScreenPlane 路径（绘制在帧 XY 平面），z() = −1/16（沿帧 Z = +Y 世界 = 面板下方 1px），
        // 内容 inPlane = −facingInPlaneDeg（绕帧 Z = +Y 世界旋转，与地板绕 −Y 反向同效）。
        for (var screen : grid.getScreenRegions()) {
            if (wall && frame != null) {
                poseStack.pushPose();
                poseStack.translate(frame.panelOrigin().x, frame.panelOrigin().y, frame.panelOrigin().z);
                if (frame.faceYawDeg() != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(frame.faceYawDeg()));
                if (frame.faceXRotDeg() != 0f) poseStack.mulPose(Axis.XP.rotationDegrees(frame.faceXRotDeg()));
                renderScreen(poseStack, buffer, screen, grid.getScreenText(screen.id()), light, overlay,
                        slabPlane(screenInPlaneDeg(state, face), true));
                poseStack.popPose();
            } else if (ceiling && frame != null) {
                poseStack.pushPose();
                poseStack.translate(frame.panelOrigin().x, frame.panelOrigin().y, frame.panelOrigin().z);
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
                poseStack.scale(1f, 1f, -1f);
                renderScreen(poseStack, buffer, screen, grid.getScreenText(screen.id()), light, overlay,
                        ceilingScreenPlane(screenInPlaneDeg(state, face)));
                poseStack.popPose();
            } else {
                renderScreen(poseStack, buffer, screen, grid.getScreenText(screen.id()), light, overlay,
                        slabPlane(screenInPlaneDeg(state, face), false));
            }
        }
    }

    /** 模块面板局部帧内的 facing 旋转（度）：地板 = blockstate y 旋转的负值（俯视顺时针）；贴墙 = 0（面板已按 FACING 定向）；
     *  天花板 = −facingYRotation + 180（Rx(180) 翻转局部 Z，标签 up/right 需此补偿，四个朝向逐项验算过）。 */
    private static float moduleFacingDeg(BlockState state, AttachFace face) {
        return switch (face) {
            case WALL -> 0f;
            case CEILING -> -facingYRotation(state) + 180f;
            default -> facingYRotation(state);
        };
    }

    /** 屏幕内容平面内旋转角（度）：地板 = 俯视顺时针跟随 FACING；贴墙 = 0（面板局部帧内已朝上）；
     *  天花板 = −facingInPlaneDeg（反射帧绕帧 Z = +Y 世界旋转，与地板绕 −Y 反向同效，四朝向验算过）。 */
    private static float screenInPlaneDeg(BlockState state, AttachFace face) {
        return switch (face) {
            case WALL -> 0f;
            case CEILING -> -facingInPlaneDeg(state) % 360f;
            default -> facingInPlaneDeg(state);
        };
    }

    /**
     * 模块底座锚点（块单位）：button 本地原点在背面（本地 z 0.625..1 沿 −Y 延伸）→ 锚点 = 面板 + 1px 使背面贴面板；
     * toggle/knob 本地底 y=0 → 锚点 = 面板（底座落面板，进游戏确认：offsetZ 不叠加，否则浮起 1px）。
     */
    private static float moduleBaseY(ModuleType type) {
        return type == ModuleType.BUTTON_1X1
                ? MonitorSlabBlockEntity.MODULE_SURFACE_Y_PX / 16f
                : MonitorSlabBlockEntity.PANEL_Y_PX / 16f;
    }

    /** 模块足迹中心相对锚点的 x 偏移（块单位）：button/toggle 模型原点在角上（足迹中心 = 本地 (0.5,0.5)）；knob 圆盘原点即中心（(0,0)）。 */
    private static float modulePivotX(ModuleType type) {
        return type == ModuleType.KNOB ? 0f : 0.5f / 16f;
    }

    /** 模块足迹中心相对锚点的 z 偏移（块单位），同 {@link #modulePivotX}。 */
    private static float modulePivotZ(ModuleType type) {
        return type == ModuleType.KNOB ? 0f : 0.5f / 16f;
    }

    /**
     * blockstate 对模型本体的 y 旋转（地板放置：facing=north=0 / east=90 / south=180 / west=270，俯视顺时针），
     * 渲染用其<b>负值</b>（Minecraft 矩阵正角 = 俯视逆时针）。模块绕自身锚点转同样的角度即与本体朝向一致；
     * 符号如与顶面贴图方向相反，进游戏首测翻转（校准点）。
     */
    private static float facingYRotation(BlockState state) {
        return switch (state.getValue(MonitorSlabBlock.FACING)) {
            case EAST -> -90f;
            case SOUTH -> -180f;
            case WEST -> -270f; // ≡ +90°
            default -> 0f;      // NORTH
        };
    }

    /**
     * 屏幕<b>内容</b>平面内旋转角（度，正 = 俯视顺时针 = blockstate y 同向）：
     * 水平面帧里绕本地 Z（= 世界 −Y）转，等价于世界绕 +Y 的负向旋转，故直接取 +y（与模块的
     * {@link #facingYRotation} 相反符号但同为俯视顺时针，两处分别推导、进游戏校准）。
     * 只作用于文字/图形内容（9 宫格外框保持网格对齐，见 {@code Screen9GridRenderer.renderScreen}）。
     */
    private static float facingInPlaneDeg(BlockState state) {
        return switch (state.getValue(MonitorSlabBlock.FACING)) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f; // NORTH
        };
    }

    // ── 屏幕 9 宫格渲染（地板水平顶面 / 贴墙面板 / 天花板底面） ──

    /** 地板 / 贴墙水平 ScreenPlane（块单位，水平面）：网格起点 = 面板内缩 1px；z() 作为 wrap 的 translate y——
     *  地板 = 世界 y（面板 8/16 + 凸出 1px = 9/16）；<b>贴墙 = 面板局部帧内沿法线</b>（面板已含在
     *  panelOrigin，只需凸出 1/16，否则屏幕整体飘出面板约 8px ≈ 半个方块，用户实测）；
     *  内容按 {@code inPlaneDeg} 绕屏幕区域中心旋转（跟随 FACING，位置不动）。每帧按当前 blockstate 构造。 */
    private static Screen9GridRenderer.ScreenPlane slabPlane(float inPlaneDeg, boolean wall) {
        return new Screen9GridRenderer.ScreenPlane() {
            @Override public float originX() { return MonitorSlabBlockEntity.GRID_ORIGIN_X_PX / 16f; }
            @Override public float originY() { return MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX / 16f; }
            @Override public float z() {
                return wall ? MonitorSlabBlockEntity.MODULE_PROTRUDE_PX / 16f
                        : MonitorSlabBlockEntity.MODULE_SURFACE_Y_PX / 16f;
            }
            @Override public boolean horizontal() { return true; }
            @Override public float inPlaneRotationDeg() { return inPlaneDeg; }
        };
    }

    /**
     * 天花板屏幕面（块单位，<b>垂直路径</b> horizontal()=false，配合外层 translate(panelOrigin)+Rx(90)·scale(1,1,−1)
     * 的反射帧使用）：9 宫格在帧的 XY 平面（= 面板平面）绘制；内容前脸（模型 −Z 面）→ 帧 −Z → 世界 −Y（朝下）。
     * z() = <b>−1/16</b>（帧 +Z → 世界 +Y，取负 = 面板下方 1px）；内容按 {@code inPlaneDeg} 绕帧 Z（= +Y 世界）旋转。
     * 反射翻转绕序 → {@link #mirrorWinding()}=true，9 宫格模型与字形反转绕序补偿。
     */
    private static Screen9GridRenderer.ScreenPlane ceilingScreenPlane(float inPlaneDeg) {
        return new Screen9GridRenderer.ScreenPlane() {
            @Override public float originX() { return MonitorSlabBlockEntity.GRID_ORIGIN_X_PX / 16f; }
            @Override public float originY() { return MonitorSlabBlockEntity.GRID_ORIGIN_Z_PX / 16f; }
            @Override public float z() { return -MonitorSlabBlockEntity.MODULE_PROTRUDE_PX / 16f; }
            @Override public float inPlaneRotationDeg() { return inPlaneDeg; }
            @Override public boolean mirrorWinding() { return true; }
        };
    }

    private void renderScreen(PoseStack ps, MultiBufferSource buffer,
                              GridState.ScreenRegion scr, ScreenText text, int light, int overlay,
                              Screen9GridRenderer.ScreenPlane plane) {
        BakedModel corner = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CORNER);
        BakedModel edge   = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_EDGE);
        BakedModel center = MonitorPreloadedModels.getExtra(MonitorPreloadedModels.SCREEN_CENTER);

        Screen9GridRenderer.renderScreen(ps, buffer, corner, edge, center, scr, text, plane, light, overlay);
    }
}
