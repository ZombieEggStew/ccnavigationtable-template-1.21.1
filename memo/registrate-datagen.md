# Registrate + Datagen 新增方块流程（红色航行灯实战记录）

> 本文记录用 Registrate 注册方块 + datagen 生成资源的完整流程，以及过程中踩到的两个坑。
> 实战案例：`ccpe:red_position_light` 红色航行灯（参考 CreateDeco 笼灯）。

## 参考来源

- `references/CreateDeco-1.21-neo`：`CageLampBlock.java`（方块行为）、`api/CageLamps.java` + `BlockStateGenerator.cageLamp()`（Registrate 注册链与 blockstate 生成）
- `references/Registrate-MC1.21-1.3.0+67-sources`：Registrate 1.3.0 完整源码（API 用法核实）

## 文件清单（本次新增/修改）

| 文件 | 说明 |
|---|---|
| `block/PositionLightBlock.java` | 方块类：6 向贴附、红石点亮（LIT）、右键反相（INVERTED）、水浸（vanilla `SimpleWaterloggedBlock`）、切换粒子；`codec()` 返回 null（对齐参考） |
| `RegistrateBlocks.java` | Registrate 注册链（`block→properties→blockstate→tag→simpleItem→register`，三色灯共用参数化模板 `positionLight()`）+ blockstate 生成回调；**静态块里关掉自动创造标签挂接（见坑②）**。合成配方不走 datagen，手写维护（见下文「配方手写」） |
| `CCPeripheralExtender.java` | `REGISTRATE = Registrate.create(MOD_ID)`（内部自动挂载注册/数据生成事件）+ 构造器 `RegistrateBlocks.init()` |
| `item/MyModCreativeModeTabs.java` | `displayItems` 手动 `output.accept(RegistrateBlocks.RED_POSITION_LIGHT.get())` |
| 模型/贴图 | `models/block/position_light/position_light.json`（Blockbench 几何，`#0`灯体/`#1`底座纹理槽）+ `textures/block/position_light/{base,red_on,red_off}.png` |
| lang | `assets/ccpe/lang/en_us.json` / `zh_cn.json` 手写 `Red Position Light` / `红色航行灯` |

## 配方手写（2026-09 起生效）

- 航行灯三色（red/green/white_position_light）的配方**不再由 datagen 生成**，手写维护在 `src/main/resources/data/ccpe/recipe/`（与项目其它 23 个方块配方一致）。
- 手写配方**不带配套 advancement**（`src/main/resources/data/ccpe/advancement` 不存在）——配方照常可合成，只是没有「解锁配方」弹窗/配方书提示；这是项目全体的既有行为。
- 注册链里不要加 `.recipe(...)`；若已加，runData 会在 `src/generated/resources/data/ccpe/recipe/` 生成副本并与手写文件在 jar 里重复，记得删除。

## 完整流程

```
1. 写方块类 + Registrate 注册链（blockstate 变体旋转逻辑抄 BlockStateGenerator.cageLamp：
   UP 基准 x=0，DOWN x=180，水平四向 x=90 + y 旋转；薄模型只换灯体贴图）
2. ./gradlew.bat classes          # 编译检查
3. ./gradlew.bat runData          # 生成资源到 src/generated/resources
4. 删除 src/generated/resources/assets/ccpe/lang/   # 必做！见坑①
5. ./gradlew.bat build            # 打包验证
6. ./gradlew.bat runClient        # 进游戏验证
```

## 坑①：datagen 生成的语言文件会盖掉手写 lang

- Registrate 的 `ProviderType.LANG` 是**无条件运行**的（`ProviderType.LANG` 静态注册进 `RegistrateDataProvider.TYPES`），且 `RegistrateLangProvider` **只输出 Registrate 收集的条目、不合并手写文件**，还会顺带生成 `en_ud.json`（倒置英文）。
- `src/generated/resources` 的优先级高于 `src/main/resources` → 生成的（空）`en_us.json` 会 shadow 手写文件。
- **处理**：lang 一律手写维护，每次 runData 后删除 `src/generated/resources/assets/ccpe/lang/`。
- 若嫌麻烦可在 build.gradle 的 resources 里用 `srcDir(fileTree('src/generated/resources') { exclude('assets/ccpe/lang/**') })` 一劳永逸（注意 exclude 会同时作用到手写目录，需用 fileTree 形式只过滤生成目录）。

## 坑②：Registrate 自动挂接创造标签导致服务器启动崩溃（重点）

- **现象**：`./gradlew runClient` 启动到开世界时服务器崩：
  `IllegalArgumentException: Itemstack 1 ccpe:red_position_light already exists in the tab's list`，
  报错包裹在 `BuildCreativeModeTabContentsEvent` 分发中（`ModContainer.acceptEvent`）。
- **排查路径**：崩溃堆栈里 `ItemBuilder.lambda$tab$4(ItemBuilder.java:184)` + `AbstractRegistrate.lambda$onBuildCreativeModeTabContents$2` → 说明 `.tab()` 相关代码在跑，即使注册链只写了 `.simpleItem()`。
- **根因**（Registrate 源码 `AbstractRegistrate.java`）：
  - 第 233 行：`private ResourceKey<CreativeModeTab> defaultCreativeModeTab = CreativeModeTabs.SEARCH;` —— **默认非 null，是搜索标签**。
  - 第 1053 行：每个 ItemBuilder 创建时自动 `builder.tab(defaultCreativeModeTab)` → 每个 Registrate 物品都被挂上 `.tab(SEARCH)`。
  - NeoForge 21.1 的 `EventHooks.onCreativeModeTabBuildContents`：先跑 displayItems 生成器把物品填进 `parentEntries/searchEntries`，再 fire 事件；搜索标签生成器本来就包含全部物品 → 事件里再 `accept` 同一物品 → `assertNewEntryDoesNotAlreadyExists` 抛异常。
  - 所以崩溃发生在**搜索标签**（与自定义标签无关），移除 `.tab(自定义标签)` 无效。
- **修复**：`RegistrateBlocks` 静态块里 `REGISTRATE.defaultCreativeTab((ResourceKey<CreativeModeTab>) null);` 关闭自动挂接（要放在注册链执行之前）；创造标签统一走 `MyModCreativeModeTabs.displayItems` 手动 accept（与其他 14 个方块一致的安全路径）。
- **结论**：本环境（NeoForge 21.1.228 + Registrate 1.3.0）下 Registrate 的 `.tab()`/`BuildCreativeModeTabContentsEvent` 路径不可靠，**一律手动 displayItems**；`defaultCreativeTab(null)` 对所有未来 Registrate 条目生效。

## 坑③：datagen 环境要最小化 mod

- NeoForge dev datagen（`forgedatadev` 启动目标）会像客户端一样加载 `run/mods` 里的**全部 mod**并应用客户端 mixin。
- 纯客户端 mod（MouseTweaks 构造 NPE、sodium 客户端 mixin 等）会导致 datagen 卡死/冻结（进程 CPU 归零、日志无输出）。
- **处理**：跑 runData 前把非必需 mod 移到备份目录，只留必需依赖（本项目：create、create-aeronautics-bundled、cc-tweaked、sable、jei 可选）；生成完移回。
- 判断 datagen 是否在正常跑：看 Gradle 守护进程日志 `D:\.gradle\daemon\9.2.1\daemon-*.out.log` 里的 `Launching target 'forgedatadev'` + `DatagenModLoader: Initializing Data Gatherer for mods [ccpe]`。

## Registrate 1.3.0 要点速查（源码核实）

- `Registrate.create(modid)` 内部自动 `registerEventListeners`（查 ModList 取 mod 事件总线），无需手动挂。
- `doDatagen` = `DatagenModLoader.isRunningDataGen()` 惰性判断；datagen 运行时自动注册 GatherDataEvent 监听，**无需自己写 datagen 类**。
- `BlockBuilder.create()` 自动带 `defaultBlockstate() + defaultLoot()（掉落自身） + defaultLang()`。
- `BlockEntry.get()` 在 RegisterEvent 前调用会炸——`init()` 只触发类加载即可（`accept()` 在链执行时立即入注册表）。
- 注册链执行即注册（`accept()` 立即 put），实际入册由 Registrate 在 RegisterEvent 时统一完成。
- `DirectionalBlock` 子类的 `createBlockStateDefinition` **不要调 super**（父类也会加 FACING，重复添加抛异常），自行把全部属性加齐（对齐参考 CageLampBlock）。
