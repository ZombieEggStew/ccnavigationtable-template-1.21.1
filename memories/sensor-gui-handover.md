# Sensor GUI 开发交接 — 2026-07-29

## 项目
NeoForge 1.21.1 mod: `ccnavigationtable` (Minecraft 1.21.1, JDK 21)

## 关键文件清单

### Screen/GUI 层
- `screen/MySensorScreen.java` — 主 GUI，包含：
  - 九宫格 NBT 窗口（`renderNbtWindow`），纹理 `test_gui.png`
  - **可折叠 NBT 树形视图**（`NbtTreeNode` 内部类）：
    - 递归构建树，默认全部收起，点击 `▶ key` 展开/折叠
    - 叶子节点同行显示 `key: value`（数字=金色，字符串=绿色，数组=橙色，化合物=白色）
    - 嵌套缩进 `depth*8px`，展开路径记录在 `expandedPaths`（Set）中防 tick 重置
    - 滚轮滚动（`nbtScrollOffset`），区域=`TEXT_START_Y`~165,X=`WIN_X+4`~`WIN_X+WIN_W-4`
    - NBT 文本区透明背景 `0x18000000`，底部截断线 `lineY>=165`，顶部裁剪 `lineY<=TEXT_START_Y`
  - 滚轮驱动数值 `scrolledValue`（int, 0~9999, ±1/Shift±10）
  - 滚轮选择菜单 `selectIndex`（选项: "关闭"/"接收"/"发送"）
  - 两个 tooltip：`renderValueTooltip`（频道选择）+ `renderSelectionTooltip`（选择模式）
  - 【JEI 拖放】`ghostSlot0Bounds/ghostSlot1Bounds`（Rect2i）+ `updateGhostSlot(slot,item)`
- `screen/MySensorMenu.java` — 菜单：
  - 幽灵物品槽：**槽位0**=`GHOST_SLOT_X=167`，**槽位1**=`GHOST_SLOT_2_X=188`，Y=`36`（间隔3px）
  - 玩家槽位 2~37（shift 快捷移动适配：背包 2~28 ↔ 快捷栏 29~37）
  - `clicked()` 拦截 `slotId>=0 && slotId<2` 的 PICKUP/THROW → `handleGhostSlotClick()`
  - 左键手持物品→放副本(count=1)，右键/Q→清空，`sendItemUpdate(slotIndex,stack)`
  - **防止 -999 崩溃**：`clicked()` 加 `slotId >= 0` 检查
- `screen/GhostItemSlot.java` — 幽灵物品槽（`mayPickup/mayPlace=false`，`getItem/set`走回调）
- `screen/MyModMenus.java` — MenuType 用 `IMenuTypeExtension.create()`
- `compat/jei/AddonJEIPlugin.java` — JEI `IGhostIngredientHandler<MySensorScreen>`，拖放到两个槽位

### BlockEntity
- `block/MySensorBlockEntity.java`
  - `cachedAttachedNBT` — 附着方块 NBT 缓存
  - `scrolledValue`（int, 默认0）+ `selectIndex`（int, 默认0）
  - **`displayItem` + `displayItem2`** — 两个幽灵槽物品，持久化于 save/load/updateTag
  - 索引方法：`getDisplayItem(int slot)` / `setDisplayItem(int slot, ItemStack)`（0或1）
  - `refreshAndGet()` — 对外读取接口（含 Sable 子次元坐标修正）

### 网络包
- `network/SensorNbtPayload.java` — S2C: `{BlockPos, CompoundTag}`
- `network/SensorFilterPayload.java` — C2S: `{BlockPos, int scrolledValue, int selectIndex}`
- **`network/SensorItemPayload.java`** — C2S: `{BlockPos, ItemStack, int slotIndex}`
  - ⚠️ 用 `ItemStack.OPTIONAL_STREAM_CODEC`（不是 `STREAM_CODEC`），否则空物品崩编码
  - 服务端处理器调 `sensorBE.setDisplayItem(payload.slotIndex(), payload.item())`

### NBT 数据刷新
- 轮询间隔可配：`Config.SENSOR_NBT_POLL_INTERVAL`（默认20 tick, 0=禁用）
- 客户端 `containerTick` → `handleInventoryButtonClick(id=0)` → 服务端 `clickMenuButton` → `refreshAndGet` → `PacketDistributor.sendToPlayer(SensorNbtPayload)` → 客户端 `setCachedAttachedNBT`

### 背包纹理
- `create:textures/gui/player_inventory.png`（Create 提供）
- `BACKPACK_TOP=194`, `BACKPACK_WIDTH=175`, `BACKPACK_HEIGHT=108`

### Sable 子次元坐标修正
- `MySensorBlock.tryAddRealWorldPos()` — 反射调用 `Sable.HELPER.getContaining()` + `projectOutOfSubLevel()`
- 直接替换 NBT 中 x/y/z 为真实世界坐标

## 布局速查
```
imageWidth=256, imageHeight=194+108+6=308
WIN_X=32, WIN_W=192, WIN_TOP=10, WIN_BOTTOM=184
NBT 纹理: top(32,0,192,18), mid(32,26,192,19), bottom(32,55,192,8)
覆盖层: (32,92,192,30) at WIN_TOP+20

NBT 文本区: TEXT_START_X=40, TEXT_START_Y=70, 底部截断=165
  - 文本区透明背景 fill(36,70~188,165, 0x18000000)
  - 滚动区域 = 背景区域 (X:36~220, Y:70~165)

幽灵槽0: X=167, Y=36  (GHOST_SLOT_X, GHOST_SLOT_Y)
幽灵槽1: X=188, Y=36  (GHOST_SLOT_2_X = 167+18+3)
槽位布局: 玩家背包 SLOT_X=48, INV_Y=212, HOTBAR_Y=270
```

## NBT 树形视图速查
- **节点类型判断**: `isLeaf() = !(CompoundTag || ListTag)`
- **默认状态**: 全部收起（`expanded=false`），用户点击展开后路径记入 `expandedPaths`
- **展开/折叠**: `mouseClicked` → `findNodeAtY(relY)` → toggle → `expandedPaths` add/remove
- **颜色**: 数字=0xFFFFD700, 字符串=0xFF55FF55, 数组=0xFFFFAA55, 默认=0xFFE0E0E0
- **前缀**: 展开=▼, 折叠=▶, 叶子=4空格对齐
- **缩进**: depth*8px
- **⚠️ 每 tick `formatNBTForDisplay` 重建树，必须用 `expandedPaths` 恢复状态

## 翻译键
- `gui.ccnavigationtable.sensor_nbt` / `.empty`
- `gui.ccnavigationtable.sensor_select_mode` / `.sensor_channel`
- `gui.ccnavigationtable.scroll_to_select` / `.scroll_to_change` / `.shift_scroll_faster`

## 编译
- JDK 21 路径: `C:\Program Files\Java\jdk-21.0.10`
- 编译: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"; .\gradlew.bat compileJava`

## ⚠️ 关键陷阱备忘
1. **ItemStack.STREAM_CODEC 不允许空物品** → 用 `ItemStack.OPTIONAL_STREAM_CODEC`
2. **slotId=-999 崩溃** → `clicked()` 必须加 `slotId >= 0` 检查（AbstractContainerScreen 传哨兵值）
3. **NBT 树 tick 重置** → 每帧 `formatNBTForDisplay` 重建树，必须用 `expandedPaths` Set 恢复
4. **滚动偏移污染总高度** → `nbtTotalLines = lineY - (TEXT_START_Y - nbtScrollOffset)` 排除偏移