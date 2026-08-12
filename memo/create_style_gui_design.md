# Create 风格 GUI 设计方案说明书

> 基于 Aeroworks 模组 GUI 实现的分析，提炼可复用的 Create 风格 UI 架构模式。
> 适用于：模块化设备配置界面、多层级控制面板、类似 CC:T 外设的配置 GUI。

---

## 一、整体架构：三层 Screen + Widget + 资源

```
┌──────────────────────────────────────────────┐
│  Screen 层（3 种模版）                         │
│  ├─ ConsoleScreen     概览列表（直接 extends Screen）
│  ├─ ModuleScreen      详情编辑（extends AbstractSimiContainerScreen）
│  └─ ModuleConfigScreen 精细参数（extends Screen）
├──────────────────────────────────────────────┤
│  Widget 层（复用 Create 的 IconButton）         │
│  ├─ HoverTintIconButton  带彩色 hover 的按钮     │
│  ├─ ToggleButton         状态切换按钮            │
│  └─ WrappingScrollInput  循环滚动输入            │
├──────────────────────────────────────────────┤
│  资源层（Create 原生 + 自绘混合）                 │
│  ├─ AllIcons             复用 Create 图标        │
│  ├─ AllGuiTextures       复用 Create 按钮背景     │
│  ├─ AeroworksGuiTextures 自绘面板/行/绑定框精灵   │
│  └─ AeroworksIcons       自绘 16×16 图标精灵表    │
└──────────────────────────────────────────────┘
```

**核心理念**：UI 背景面板用自绘纹理（品牌化），图标按钮复用 Create 的 `AllIcons`（减少素材工作量），按钮背景完全复用 Create 的 `AllGuiTextures.BUTTON/BUTTON_HOVER/BUTTON_DOWN`。

---

## 二、Screen 层：三种模版

### 2.1 概览列表 — `ConsoleScreen`（extends Screen）

**用途**：展示所有已安装模块的列表，点击进入详情。

**特点**：
- 固定窗口尺寸：`198×153`，居中 `(width-198)/2, (height-153)/2`
- 纯 `Screen`，无需 Container/Menu（无物品栏交互）
- 手动管理 Scroll 和渲染裁剪

**关键代码结构**：
```java
public class ConsoleScreen extends Screen {
    // 固定窗口
    private static final int WINDOW_WIDTH = 198, WINDOW_HEIGHT = 153;
    private int windowLeft, windowTop;

    // 列表区域
    private static final int LIST_BOX_X = 4, LIST_BOX_Y = 16;
    private static final int LIST_BOX_W = 182, LIST_BOX_H = 106;

    // 行参数
    private static final int ROW_HEIGHT = 28, ROW_GAP = 2, SCROLL_STEP = 30;

    private List<Row> rows;
    private int scroll;
    private float renderedScroll;  // 平滑滚动动画值

    @Override
    protected void init() {
        this.windowLeft = (width - 198) / 2;
        this.windowTop = (height - 153) / 2;
        // 右下角"完成"按钮
        HoverTintIconButton saveButton = new HoverTintIconButton(
            windowLeft + 198 - 33,   // X = 右边缘 - 按钮宽 - 间距
            windowTop + 153 - 24,    // Y = 底边缘 - 按钮高 - 间距
            AllIcons.I_CONFIRM,      // 复用 Create 的确认图标
            0x80FF80                 // 绿色 hover 着色
        );
        saveButton.withCallback(this::onClose);
        addRenderableWidget(saveButton);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderedScroll = ScrollAnimation.approach(renderedScroll, scroll);
        // 裁剪列表区域
        g.enableScissor(listLeft, listTop, listLeft + LIST_BOX_W, listTop + LIST_BOX_H);
        for (var row : rows) {
            renderRow(g, row, rowY, hovered);
        }
        g.disableScissor();
    }
}
```

### 2.2 详情编辑 — `ModuleScreen`（extends AbstractSimiContainerScreen）

**用途**：编辑单个模块的通道绑定、频率、名称等。

**特点**：
- 继承 Create 的 `AbstractSimiContainerScreen<ModuleMenu>`（自带玩家物品栏）
- 窗口尺寸由 `ModuleMenu.imageWidth/Height` 定义：`251×247`
- 行列可变高度（单通道 30px，成对轴 52px）
- 通过 `addRenderableWidget()` 注册按钮

**按钮布局示例（底部栏 Y=top+129）**：
```java
// 旋转按钮（左）
new IconButton(left + 9, y, AllIcons.I_ROTATE_CCW)
// 清除按钮（中右）
new HoverTintIconButton(left + 198, y, AllIcons.I_TRASH, 0xFF8080)
// 确认按钮（右）
new HoverTintIconButton(left + 226, y, AllIcons.I_CONFIRM, 0x80FF80)
```

### 2.3 精细参数 — `ModuleConfigScreen`（extends Screen）

**用途**：单个通道的精细参数（输出范围、死区、灵敏度等）。

**特点**：
- 固定宽度 190，高度动态计算（标题栏 15 + 行区 + 底部栏 30）
- 使用 9-slice 水平拉伸的标签框/值框
- 行高 30px，行间距 1px

---

## 三、Widget 系统：基于 Create 的 IconButton

### 3.1 按钮体系继承链

```
Create IconButton                         ← 提供背景、hover、点击状态
  ├─ HoverTintIconButton (Aeroworks)     ← 彩色 hover 着色
  └─ ToggleButton (Aeroworks)            ← 选中/锁定状态
```

### 3.2 HoverTintIconButton — 绿色高亮核心技术

```java
public class HoverTintIconButton extends IconButton {
    private final float hoverR, hoverG, hoverB;

    public HoverTintIconButton(int x, int y, ScreenElement icon, int hoverRgb) {
        super(x, y, icon);  // icon 可以是 AllIcons 或自定义 ScreenElement
        this.hoverR = (hoverRgb >> 16 & 0xFF) / 255.0f;
        this.hoverG = (hoverRgb >> 8 & 0xFF) / 255.0f;
        this.hoverB = (hoverRgb & 0xFF) / 255.0f;
    }

    @Override
    protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
        if (button == AllGuiTextures.BUTTON_HOVER) {
            graphics.setColor(hoverR, hoverG, hoverB, 1.0f);   // 着色！
            super.drawBg(graphics, button);                      // 绘制 Create 按钮背景
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);         // 重置颜色
            return;
        }
        super.drawBg(graphics, button);  // 正常状态用 Create 默认渲染
    }
}
```

**原理**：
1. `IconButton` 自动检测鼠标是否 hover → 调用 `drawBg(AllGuiTextures.BUTTON_HOVER)`
2. `HoverTintIconButton` 拦截 hover 调用 → `graphics.setColor(r,g,b,1)` 着色
3. 按钮背景精灵（`BUTTON_HOVER`）来自 Create 的 `textures/gui/icons.png`，是一张灰色高亮纹理
4. 着色叠加后，产生绿色/红色高亮效果

**常用颜色**：
| 颜色值 | 效果 | 用途 |
|--------|------|------|
| `0x80FF80` | 半透明绿色 | 确认/完成按钮 |
| `0xFF8080` | 红色 | 删除/清除按钮 |

### 3.3 ToggleButton — 状态按钮

```java
public class ToggleButton extends IconButton {
    private boolean selected, locked;

    @Override
    protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
        if (locked) {
            graphics.setColor(1.0f, 0.55f, 0.55f, 1.0f);  // 锁定 = 红色
            super.drawBg(graphics, AllGuiTextures.BUTTON);
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        // 选中状态 → 用 BUTTON_DOWN（按下外观）
        AllGuiTextures effective = (selected && button != AllGuiTextures.BUTTON_HOVER)
            ? AllGuiTextures.BUTTON_DOWN : button;
        super.drawBg(graphics, effective);
    }
}
```

---

## 四、资源策略：Create 原生 + 自绘混合

### 4.1 原则

| 资源类型 | 来源 | 原因 |
|----------|------|------|
| **按钮背景** | `AllGuiTextures.BUTTON/BUTTON_HOVER/BUTTON_DOWN` | 复用 Create 成熟的 18×18 按钮 9-slice |
| **通用图标** | `AllIcons.I_CONFIRM/I_TRASH/I_ROTATE_CCW` 等 | 避免重复绘制 |
| **面板/行背景** | 自绘 `aeroworks:textures/gui/controls/*.png` | 品牌化外观 |
| **领域特定图标** | 自绘 `aeroworks:textures/gui/icons/icons.png` | Create 没有的图标 |

### 4.2 自绘 GUI 纹理（ScreenElement 模式）

```java
public enum AeroworksGuiTextures implements ScreenElement {
    CONSOLE_BACKGROUND("controls/console", 0, 0, 198, 153),
    CONSOLE_ROW       ("controls/console", 0, 157, 174, 28),
    MODULE_ROW        ("controls/module",  0, 154, 235, 30),
    // ...

    public final ResourceLocation location;  // aeroworks:textures/gui/controls/xxx.png
    public final int startX, startY, width, height, texWidth, texHeight;

    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(location, x, y, startX, startY, width, height, texWidth, texHeight);
    }
}
```

**关键**：实现 Catnip 的 `ScreenElement` 接口，使自定义纹理可以像 `AllGuiTextures` 一样使用。

### 4.3 9-Slice 水平拉伸

```java
public void renderHStretched(GuiGraphics g, int x, int y, int targetWidth,
                              int leftCap, int rightCap) {
    // 左端（固定宽度）
    g.blit(location, x, y, startX, startY, leftCap, height, texWidth, texHeight);
    // 中间（水平拉伸）
    g.blit(location, x + leftCap, y, targetWidth - leftCap - rightCap, height,
           startX + leftCap, startY, width - leftCap - rightCap, height, texWidth, texHeight);
    // 右端（固定宽度）
    g.blit(location, x + targetWidth - rightCap, y,
           startX + width - rightCap, startY, rightCap, height, texWidth, texHeight);
}
```

用途：绑定框（左右端 8/5px）、标签框（两端 2px）、值框（两端 5/7px）。

### 4.4 自绘图标精灵表

```java
public class AeroworksIcons implements ScreenElement {
    public static final ResourceLocation ATLAS =
        ResourceLocation.fromNamespaceAndPath("aeroworks", "textures/gui/icons/icons.png");
    // 256×256 精灵表，每格 16×16
    public static final AeroworksIcons LOCK   = new AeroworksIcons(0, 0);  // 列0行0
    public static final AeroworksIcons UNLOCK = new AeroworksIcons(1, 0);  // 列1行0

    private final int u, v;  // 精灵表中的像素坐标

    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(ATLAS, x, y, u, v, 16, 16, 256, 256);
    }
}
```

### 4.5 独立 PNG 图标（非精灵表）

用于 16×16 独立图标（如 `keyboard.png`、`mouse.png`）：
```java
private static ScreenElement iconBlit(String name) {
    ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("aeroworks",
        "textures/gui/icons/" + name + ".png");
    return (graphics, x, y) -> graphics.blit(loc, x, y, 0, 0, 16, 16, 16, 16);
}
```

---

## 五、布局系统：手动坐标 + 常量

### 5.1 核心原则

Aeroworks **没有**自动布局引擎。所有定位使用相对于 `windowLeft/windowTop` 的手动像素常量。

**为什么不用自动布局**：
- MC GUI 是固定尺寸的像素精确设计，不是响应式网页
- 手动坐标更直观，出问题时容易定位
- Create 自身也是这种风格

### 5.2 定位模式

**确认按钮（右下角固定）**：
```java
// 相对于窗口边缘
int btnX = windowLeft + WINDOW_WIDTH - BUTTON_PADDING_RIGHT;
int btnY = windowTop + WINDOW_HEIGHT - BUTTON_PADDING_BOTTOM;
```

**列表区域（窗口内部）**：
```java
int listX = windowLeft + LIST_INSET_LEFT;
int listY = windowTop + TITLE_BAR_HEIGHT;
int listW = WINDOW_WIDTH - LIST_INSET_LEFT - LIST_INSET_RIGHT;
int listH = WINDOW_HEIGHT - TITLE_BAR_HEIGHT - BOTTOM_BAR_HEIGHT;
```

**行内元素**：
```java
int iconX = rowLeft + ITEM_OFFSET_X;
int labelX = rowLeft + LABEL_X;
```

### 5.3 布局常量组织方式

```java
// ✅ 推荐：Screen 类内部 private static final 常量
public class MyScreen extends Screen {
    private static final int WINDOW_WIDTH = 198;
    private static final int WINDOW_HEIGHT = 153;
    private static final int LIST_TOP = 16;
    private static final int ROW_HEIGHT = 28;
    private static final int BUTTON_SAVE_X_OFFSET = 33;
    // ...
}
```

---

## 六、滚动系统：ScrollAnimation.approach()

### 6.1 核心实现

```java
public class ScrollAnimation {
    static float approach(float rendered, int target) {
        float remaining = (float)target - rendered;
        if (Math.abs(remaining) < 0.5f) return target;  // 吸附阈值
        float frameTicks = Minecraft.getInstance().getTimer().getRealtimeDeltaTicks();
        return (float)target - remaining * (float)Math.pow(0.3f, frameTicks);
    }
}
```

**特点**：
- 指数衰减平滑（衰减因子 0.3）
- 基于帧时间（`getRealtimeDeltaTicks`），不受帧率影响
- 差值 < 0.5px 时直接吸附到目标

### 6.2 在 Screen 中使用

```java
private int scroll;              // 目标滚动偏移（整数，按 SCROLL_STEP 递增）
private float renderedScroll;    // 渲染用平滑值（浮点数）

// 每帧更新
renderedScroll = ScrollAnimation.approach(renderedScroll, scroll);

// 渲染时用 renderedScroll 计算位置
int rowTop = (int)(listTop + row.index * (ROW_HEIGHT + ROW_GAP) - renderedScroll);

// 鼠标滚轮修改 scroll
scroll = Mth.clamp(scroll - (int)Math.signum(scrollY) * SCROLL_STEP, 0, maxScroll);

// 裁剪可视区域
g.enableScissor(listLeft, listTop, listLeft + listW, listTop + listH);
```

### 6.3 滚动步长

| 场景 | 值 | 原因 |
|------|-----|------|
| ConsoleScreen 列表 | 30 | 行高 28 + 间距 2 |
| ModuleScreen 列表 | 30 | 单行 30 / 轴对 52（步长统一） |

---

## 七、交互反馈：悬停高亮模式

### 7.1 按钮悬停（Create 原生 + 着色）

```
IconButton 检测 hover
  → drawBg(BUTTON_HOVER)
    → HoverTintIconButton 拦截
      → setColor(r,g,b) → 画 BUTTON_HOVER 纹理 → resetColor
```

### 7.2 行悬停（自绘矩形）

```java
// 半透明白色覆盖行区域
if (row == hoveredRow) {
    g.fill(rowLeft, rowTop, rowLeft + ROW_WIDTH, rowTop + ROW_HEIGHT, 0x30FFFFFF);
}
```

### 7.3 绑定框/图标悬停

```java
// 绑定捕获模式 → 红色高亮；普通悬停 → 半透明白色
int tint = capturing ? 0x80FF0000 : 0x30FFFFFF;
g.fill(x, y, x + w, y + h, tint);
```

---

## 八、快速上手指南

### 新增一个 Screen 的步骤

1. **创建 Screen 类**
   - 概览列表 → `extends Screen`
   - 带物品栏 → `extends AbstractSimiContainerScreen<YourMenu>`
   - 窗口尺寸用 `private static final int W/H`

2. **定义自绘纹理**（如果有新背景面板）
   - 画好 PNG → 放入 `textures/gui/`
   - 在 `AeroworksGuiTextures` 枚举中添加条目
   - 如需 9-slice → 加 `renderHStretched()` 方法

3. **添加按钮**
   ```java
   HoverTintIconButton btn = new HoverTintIconButton(
       x, y, AllIcons.I_CONFIRM, 0x80FF80);
   btn.withCallback(this::onClose);
   btn.setToolTip(Component.translatable("gui.xxx.done"));
   addRenderableWidget(btn);
   ```

4. **实现滚动**（如果内容超出可视区）
   - `private int scroll; private float renderedScroll;`
   - `renderedScroll = ScrollAnimation.approach(...)`
   - `g.enableScissor(...)` 裁剪
   - `mouseScrolled` 处理滚轮

5. **如需图标**：优先查 `AllIcons`，没有则自绘 `AeroworksIcons`

### 按钮图标对照表

| 功能 | Create 图标 | 自绘 |
|------|-----------|------|
| 确认/完成 | `AllIcons.I_CONFIRM` | — |
| 删除/清除 | `AllIcons.I_TRASH` | — |
| 旋转 | `AllIcons.I_ROTATE_CCW` | — |
| 设置齿轮 | `AllIcons.I_CONFIG_OPEN` | — |
| 锁/解锁 | — | `AeroworksIcons.LOCK/UNLOCK` |
| 键盘/鼠标 | — | `iconBlit("keyboard/mouse")` |
