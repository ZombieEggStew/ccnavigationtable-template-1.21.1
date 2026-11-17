# ComplementaryReimagined ACT（Advanced Color Tracing）彩色光兼容指南（航行灯）

> 目标：让 `ccpe:red_position_light` / `green_` / `white_` 三色航行灯在 Iris +
> ComplementaryReimagined（r5.8.1）的 **Performance Settings → Advanced Color Tracing**
> 开启时，发出对应颜色的光照投射到环境（类比原版红石火把的红光）。
>
> **结论先行：mod 侧（Java）无需任何改动**；兼容 = 给用户的光影包目录打 3 处小补丁
> （`block.properties` + 2 个 glsl 文件）。本文给出原理与可复制粘贴的补丁内容。

## 参考来源

- `references/ComplementaryReimagined_r5.8.1`：`shaders/block.properties`、
  `shaders/lib/voxelization/lightVoxelization.glsl`、`shaders/program/shadowcomp.glsl`、
  `shaders/lib/colors/blocklightColors.glsl`、`shaders/lib/lighting/mainLighting.glsl`、
  `shaders/program/shadow.glsl`
- `references/Iris-1.21.1`：`shaderpack/materialmap/BlockMaterialMapping.java`、
  `shaderpack/materialmap/BlockEntry.java`、`mixin/vertices/MixinBufferBuilder.java`、
  `shaderpack/IdMap.java`、`api/v0/IrisApi.java`、`api/v0/item/IrisItemLightProvider.java`
- `.research/iris-coloured-lights`（第三方库，佐证：Iris 下彩色方块光只能靠光影包侧实现）

## ACT 彩色光的机制（r5.8.1，三段式）

1. **体素化**（`shadow.glsl` 顶点着色器 → `UpdateVoxelMap`）：
   阴影 pass 渲染地形时，把每个顶点的 material ID（= 光影包 `block.properties` 给方块的编号，
   经 Iris 的 `mc_Entity` 顶点属性传入）写入 3D 体素图。
   **没在 `block.properties` 里登记的方块 material ID 为 0，被 `mat < 10000` 直接跳过，
   体素按空气处理 → 不产生任何 ACT 光**。
2. **泛洪填充**（`shadowcomp.glsl` 计算着色器，`voxel_img` → `floodfill_img`）：
   - 体素值 == 1（实心块）→ 不透光；
   - 体素值 == 0 或 >= 200（空气/半透明）→ 从邻居扩散光；
   - **其余值（2~199）一律视为光源**，颜色 = `GetSpecialBlocklightColor(voxel)`（
     `blocklightColors.glsl` 里**写死的颜色表**，如 mat 35 = 红石火把 → 红），
     强度 = `pow2(color.rgb)`，alpha > 0 表示「额外加光、不完全依赖原版光级」。
3. **应用**（`mainLighting.glsl`）：`specialLighting` 再乘以原版方块光照等级；
   alpha > 0 时把光照强度抬到 10 级。

**关键结论**：一块方块的 ACT 光颜色 100% 由光影包侧决定（block.properties 的登记 +
两张写死的 ID→颜色映射表）。**这个 Iris 版本（1.21.1 分支）没有任何给 mod 用的
「方块彩色光」Java API**——公共 API 只有 `IrisItemLightProvider`（手持物品动态光，
与 ACT 无关）。因此无法从 Java 侧注册方块颜色。

## 为什么 mod 侧无需改动（航行灯已满足前置条件）

| 前置条件 | 现状（`RegistrateBlocks.java` / `PositionLightBlock.java`） |
|---|---|
| 方块会发原版光（ACT 强度按原版光级缩放） | LIT=true 时 `lightLevel` 15 ✓ |
| 走地形渲染、会进阴影 pass 被体素化 | 普通方块模型（无 BlockEntityRenderer）✓ |
| 注册名稳定、可被 block.properties 精确匹配 | `ccpe:red_position_light` 等；Iris `BlockEntry.parse` 支持 `:lit=true` 属性过滤 ✓ |

## 补丁内容（用户光影包目录，3 个文件）

> 光影包若是 zip 安装，先解压成文件夹再改；改完在 Iris 里重载光影（光影选择界面按 Enter）。

### 1) `shaders/block.properties` —— 登记方块 material ID

插到 `block.10988=copper_lantern ...` 那一行之后、`block.20000=` 之前：

```
# === ccpe 航行灯（ACT 彩色光兼容补丁；见 memo/complementary-act-coloured-light.md）===
block.10990=ccpe:red_position_light:lit=true
block.10992=ccpe:green_position_light:lit=true
block.10994=ccpe:white_position_light:lit=true
block.10996=ccpe:red_position_light:lit=false
block.10998=ccpe:green_position_light:lit=false
block.11000=ccpe:white_position_light:lit=false
```

- 只把 **lit=true** 状态映射为光源 ID（10990/10992/10994）；`lit=false` 映射为
  10996/10998/11000（偶数、GetVoxelIDs 无匹配 → 落回 `return 1` 实心：**不透光、不发光**）。
- 不映射的方块状态（如不存在的组合）material ID 为 0 → 按空气处理（光可穿透薄灯体，小瑕疵，
  可接受）。ID 均选偶数（奇数 < 30000 会被当非实体跳过）、≥ 10000（才会被体素化），
  且不与 GetVoxelIDs 现有 if-case 冲突（10990+ 均空闲，已验证）。

### 2) `shaders/lib/voxelization/lightVoxelization.glsl` —— `GetVoxelIDs()` 加映射

在函数末尾 `return 1; // Standard Block` **之前**插入：

```glsl
		// ccpe 航行灯（ACT 彩色光兼容补丁）：material ID → 光源色 ID（98 红 / 99 绿 / 100 白，见 blocklightColors.glsl）
		if (mat == 10990) return 98;
		if (mat == 10992) return 99;
		if (mat == 10994) return 100;
```

（10996/10998/11000 不在这里返回，落回 `return 1` 实心。）

### 3) `shaders/lib/colors/blocklightColors.glsl` —— `GetSpecialBlocklightColor()` 加颜色

在函数末尾 `return vec4(blocklightCol * 20.0, 0.0);` **之前**插入：

```glsl
	// ccpe 航行灯（ACT 彩色光兼容补丁）：mat 98 红 / 99 绿 / 100 白。
	// 颜色值越大光传得越远、越占主导（参考：红石火把 (4, 0.1, 0.1)）；alpha > 0 = 原版光级外额外加光
	if (mat == 98) return vec4(vec3(3.0, 0.2, 0.2), 0.15);  // ccpe:red_position_light
	if (mat == 99) return vec4(vec3(0.2, 3.0, 0.2), 0.15);  // ccpe:green_position_light
	if (mat == 100) return vec4(vec3(3.0, 3.0, 3.0), 0.15); // ccpe:white_position_light
```

- mat 98/99/100 在颜色表里原本空闲（85~97 全是 `vec4(0.0)` 占位），新增不冲突；
  泛洪填充对 2~199 一律按光源处理，无需改 `shadowcomp.glsl`。

## 验证清单（进游戏）

1. 应用补丁 → Iris 重载光影。
2. 设置确认：**Advanced Color Tracing 开启**（依赖 Real-Time Shadows 开启）；
   ACT 距离 ≥ 64（光只在 `min(COLORED_LIGHTING_INTERNAL, shadowDistance×2)` 内传播）。
3. 深色地面旁放红色航行灯 → 右键点亮 → 预期：附近地面/墙面出现红色泛光（类比红石火把）。
   灭灯 → 红光消失。
4. 没效果时的排查顺序：
   - block.properties 被光影包更新覆盖（重新应用）；
   - 光影包还是 zip 没解压；
   - 方块 ID 确认（F3 看方块名 / `/setblock ccpe:red_position_light`）；
   - `logs/latest.log` 搜 `block.properties` / `ccpe` 看 Iris 解析警告。

## 注意事项 / 坑

- **光影包更新会覆盖补丁**，需重新应用（可保留本 memo 作为补丁存档）。
- ACT 官方已知：非全方块 / 模组方块可能有轻微漏光（本灯为 5px 薄模型，属预期范围）。
- 本补丁只针对 ComplementaryReimagined **r5.8.1**；其它版本/其它光影需重新适配
  （颜色表与 material ID 结构可能不同）。
- 手持航行灯物品的动态光（`IrisItemLightProvider`）是另一个独立功能，本次不做。
