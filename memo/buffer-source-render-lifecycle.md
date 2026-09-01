# BufferSource 渲染生命周期：SuperByteBuffer "Not building!" 崩溃经验

> 状态：**已修复并进游戏验证通过**（2026-09-01）。
> 现象：打开航空学（Simulated/Aeronautics）**图纸界面（Diagram）**时崩溃 `java.lang.IllegalStateException: Not building!`；
> 根因：1.21.1 `MultiBufferSource.BufferSource` 的 **sharedBuffer 共享机制** + `ControlDeskRenderer` 「提前持 buffer、延迟使用」的危险写法。

## 崩溃现场

- 崩溃报告：`run/crash-reports/...client.txt`，描述 `Rendering Block Entity`，方块 `ccpe:my_control_desk`。
- 关键栈（debug.log 全链）：
  ```
  BufferBuilder.ensureBuilding → beginVertex → addVertex          "Not building!"
  ShadeSeparatingSuperByteBuffer.renderInto(ShadeSeparatingSuperByteBuffer.java:193)
  ControlDeskRenderer.renderPart(ControlDeskRenderer.java:254)
  ControlDeskRenderer.renderSafe(97)
  SafeBlockEntityRenderer.render(28)
  BlockEntityRenderDispatcher.render → tryRender
  VanillaSubLevelBlockEntityRenderer.renderSingleBE
  SubLevelRenderDispatcher$BlockEntityRenderer.renderBlockEntities
  SimpleSubLevelGroupRenderer.renderGroup(184)          ← 航空学 Diagram 的 sub-level BE 渲染
  DiagramScreen.draw → renderContents → init            ← 打开图纸界面
  DiagramOpenPacket.handle
  ```
- **识别 sub-level 的线索**：崩溃方块的坐标（`20481032, 129, 20489222`）是 Sable plot 的偏移坐标（正常世界坐标到不了 2000 万），说明该 BE 在**装配体（sub-level）里**。

## 根因（1.21.1 源码）

`net.minecraft.client.renderer.MultiBufferSource$BufferSource`（源码见
`build/moddev/artifacts/neoforge-<ver>-sources.jar`）在 1.21 重构为 **sharedBuffer 机制**：

```java
protected final Map<RenderType, BufferBuilder> startedBuilders = new HashMap<>();
@Nullable protected RenderType lastSharedType;   // 同一时刻只允许一个「共享 RenderType」在构建

public VertexConsumer getBuffer(RenderType renderType) {
    BufferBuilder bufferbuilder = this.startedBuilders.get(renderType);
    if (bufferbuilder != null && !renderType.canConsolidateConsecutiveGeometry()) {
        this.endBatch(renderType, bufferbuilder);   // 拿过的非合并 RenderType 会被立即 endBatch
        bufferbuilder = null;
    }
    if (bufferbuilder != null) return bufferbuilder;
    ...
    if (this.lastSharedType != null) {
        this.endBatch(this.lastSharedType);         // ← 关键：换共享 RenderType 时，无条件结束上一个共享 buffer！
    }
    bufferbuilder = new BufferBuilder(this.sharedBuffer, ...);
    this.lastSharedType = renderType;
    ...
}
```

要点：
- 只有少数 **fixed** RenderType（translucent 等）有独立 buffer；**cutoutMipped / solidBlockSheet / text 等都共用同一个 `sharedBuffer`**。
- `getBuffer` 一个**新的**共享 RenderType 时，会无条件 `endBatch` 上一个共享 RenderType 的 buffer —— **即使那个 buffer 还被人拿着用**（变成 not building）。
- （旧版 1.19.x 的 `MAX_BUFFERS=4` 触发全量 endBatch 机制已不存在，别按旧版理解。）

### 触发链

1. `ControlDeskRenderer.renderSafe` 第 79 行（旧代码）**提前** `vb = bufferSource.getBuffer(cutoutMipped())` 持有；
2. 之后 monitor_2 屏幕 / 桌顶模块渲染 `getBuffer(solidBlockSheet)`（共享 RenderType）→ **`lastSharedType(cutoutMipped)` 被强制 endBatch → `vb` 变 not building**；
3. 等到 renderPart 用 `vb` 时，`ShadeSeparatingSuperByteBuffer.renderInto` 直接 `builder.addVertex(...)`（要求构建中）→ 崩。

### 为什么主世界不崩、只有图纸界面崩

- 主世界 Flywheel 可用 → `shellInstanced = VisualizationManager.supportsVisualization(level)` 为 true → `renderSafe` 第 94 行提前 `return`，`vb` 根本没人用（白拿一个被杀掉的 buffer，无影响）；
- **图纸界面里 `level` 是 `ClientSubLevel`，Flywheel 不支持 → `shellInstanced=false` → 走全量 BER 渲染 → 用 `vb` → 崩**。

### 为什么其他渲染器不崩

对照项目内渲染器，安全模式只有一种：
- `AicRenderer` / `InsRenderer`：`getBuffer` 后**同一方法内立即** `renderInto`（中间无其他 getBuffer）；
- `MyBearingRenderer` / `TransmissionPeripheralRenderer`：`renderInto(ms, buffer.getBuffer(...))` **现场获取、拿到即用**；
- `ControlDeskRenderer`（旧）：**提前拿、延迟用、中间穿插其他 getBuffer** —— 唯一踩中。

## 修复

**原则：SuperByteBuffer 的 `renderInto` 传入的 VertexConsumer 必须是「刚 getBuffer 出来、构建中」的，且拿到后立即消费，中间绝不穿插其他 getBuffer。**

改动（仅 `src/main/java/com/zzy205/myfirstmod/block/ControlDeskRenderer.java`）：
- 删除 `renderSafe` 开头提前持有的 `VertexConsumer vb`；
- 所有部件渲染方法（renderPart/renderPedal/renderJoystick/renderJoystick2/renderThrottle*/renderThrottle2*/renderJoystick2Part）签名 `VertexConsumer vb` → `MultiBufferSource bufferSource`；
- 每个 `renderInto(ms, ...)` 现场 `bufferSource.getBuffer(RenderType.cutoutMipped())`；
- 删除不再使用的 `import com.mojang.blaze3d.vertex.VertexConsumer;`。

编译验证：`./gradlew.bat classes`。游戏验证：打开图纸界面不再崩，图纸里 control desk 部件/屏幕/模块显示正常，主世界渲染无变化。

## 排查方法论（可复用）

1. **崩溃报告先看调用链全貌**（debug.log 里的完整栈比 crash-report 截断版有用）：注意 `DiagramScreen`、`SimpleSubLevelGroupRenderer` 这类 GUI 里渲染世界的路径，以及 Sable 的 sub-level 渲染（`VanillaSubLevelBlockEntityRenderer`、`SimpleSubLevelGroupRenderer`）。
2. **「Not building!」= 传入的 BufferBuilder 不在 begin 状态** → 查谁动了 buffer 的生命周期 → 读 `MultiBufferSource$BufferSource` 源码（不要凭旧版 `MAX_BUFFERS` 记忆）。
3. **对照项目内已正常工作的同类实现**（AicRenderer 等），差异即根因。
4. **查 Minecraft 本体源码的正确途径**：
   - ✅ `build/moddev/artifacts/neoforge-<版本>-sources.jar`（NeoGradle ModDev 生成的反混淆源码，本项目 21.1.228 已有）；
   - ✅ `.research/mc-src/`、`.research/mc-render-src/`（个人调研源码，覆盖不全）；
   - ❌ Yarn（Fabric 映射项目）只有类/方法名映射 + javadoc，**没有方法体**；要看实现必须下 `-v2-sources.jar` 或走 NeoForge 的 sources jar；
   - 反混淆 jar 生成：`./gradlew genSources` 任务在本项目不存在，直接用 `build/moddev/artifacts/` 里已有的。
5. **Sodium 排除法**：Sodium 的 `BufferBuilderMixin` 只做内存级顶点写入优化（`VertexBufferWriter`），不改 `building` 状态维护 —— 别一开始怀疑 Sodium。

## 关联

- 崩溃触发方：航空学（simulated）1.3.2 `SimpleSubLevelGroupRenderer.renderGroup`（Diagram 图纸界面渲染 sub-level BE，复用世界 `renderBuffers.bufferSource()`）；
- 参考源码：`references/Simulated-Project-main/.../util/SimpleSubLevelGroupRenderer.java`、
  `api/sable-common-1.21.1-2.0.3-sources/.../mixinhelpers/sublevel_render/vanilla/VanillaSubLevelBlockEntityRenderer.java`、
  `build/moddev/artifacts/neoforge-21.1.228-sources.jar` 内 `MultiBufferSource.java`、
  `api/ponder-neoforge-1.0.82+mc1.21.1-sources/.../render/ShadeSeparatingSuperByteBuffer.java`。
