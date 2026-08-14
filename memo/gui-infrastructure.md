# Monitor GUI 基础设施 — 技术要点

> 位置：`foundation/gui/`（widget 在 `foundation/gui/widget/`）
> 通用 GUI 实施流程见 `create-style-gui` skill。

## 类清单

| 类 | 基类 | 作用 |
|---|---|---|
| `HoverTintIconButton` | Create `IconButton` | hover 时用 `graphics.setColor(r,g,b)` 给 BUTTON_HOVER 着色 |
| `ToggleButton` | `HoverTintIconButton` | selected/unselected 双图标，选中 BUTTON_DOWN + 图标偏移 1px |
| `MyIcons` | `ScreenElement` | 自绘 64×64 图标精灵表 `textures/gui/icons/my_icons.png`，16×16/格 |

## 关键机制

### HoverTintIconButton 着色
```java
protected void drawBg(GuiGraphics g, AllGuiTextures button) {
    if (button == AllGuiTextures.BUTTON_HOVER) {
        g.setColor(r, g, b, 1.0f);   // 0x80FF80 = 绿
        super.drawBg(g, button);
        g.setColor(1,1,1,1);         // 必须重置！
    } else super.drawBg(g, button);
}
```

### ToggleButton 选中态
- 背景：选中时无条件 `BUTTON_DOWN`（不响应 hover 着色）
- 图标：`shifted()` lambda 包装 selectedIcon，`render(g, x+1, y+1)` 偏移 1px
- 背景不动，仅图标偏移

```java
private static ScreenElement shifted(ScreenElement icon) {
    return (graphics, x, y) -> icon.render(graphics, x + 1, y + 1);
}
```

## ⚠️ 坑（务必记住）

1. **控件必须在 `init()` 创建，绝不能在 `render()` 创建** — render 每帧调用，每帧 new 会丢失状态、堆积 widget。
2. **`g.blit` 最后两个参数是贴图文件实际尺寸**（通常 256×256），不是渲染尺寸。写错会显示整张贴图。
3. **自定义 Screen 要禁用原版渐变背景**：重写 `renderBackground()` 为空，否则暗色渐变盖住自绘背景。
4. **渲染顺序**：背景贴图 → 文字 → `super.render()`（控件）— 控件必须最后画才在上层。
5. **`setColor` 后必须重置**，否则后续所有渲染都被着色污染。

## 用法速查

```java
// 图标
MyIcons.CHANNEL.render(g, x, y);

// 普通按钮（hover 绿）
new HoverTintIconButton(x, y, AllIcons.I_CONFIRM, 0x80FF80);

// 开关按钮
ToggleButton t = new ToggleButton(x, y, MyIcons.LOCK, MyIcons.UNLOCK, 0x80FF80);
t.withCallback(() -> t.setSelected(!t.isSelected()));
```
