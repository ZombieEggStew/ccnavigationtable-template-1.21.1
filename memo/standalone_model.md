# Minecraft Standalone 模型烘焙备忘

> 参考: control-panels 的 `PreLoadedModel` / `PreLoadedModelHandler` 模式

## 核心原则
在 BER 中渲染不属于方块 `blockstate` 变体的独立模型时，必须通过 `ModelEvent` 两步烘焙，不能直接 `getModelManager().getModel()`。

## 完整流程

### Step 1: 预加载类（静态持有 BakedModel）
```java
public class MyPreloadedModels {
    private static final Map<MyType, BakedModel> MODELS = new EnumMap<>(MyType.class);

    public static void init() {} // 触发类加载

    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(location));
    }

    public static void bakingCompleted(ModelEvent.BakingCompleted event) {
        MODELS.put(type, event.getModels().get(ModelResourceLocation.standalone(location)));
    }

    public static BakedModel getModel(MyType type) { return MODELS.get(type); }
}
```

### Step 2: 客户端构造注册事件
```java
public MyClientMod(ModContainer container, IEventBus modEventBus) {
    MyPreloadedModels.init();
    modEventBus.addListener(MyPreloadedModels::registerAdditional);
    modEventBus.addListener(MyPreloadedModels::bakingCompleted);
}
```

### Step 3: BER 中使用
```java
BakedModel model = MyPreloadedModels.getModel(type);
modelRenderer.renderModel(poseStack.last(), 
    buffer.getBuffer(Sheets.solidBlockSheet()), // 注意：不是 RenderType.solid()
    null, model, 1f, 1f, 1f, light, overlay);
```

## 踩坑记录
- ❌ 直接 `getModelManager().getModel(new ModelResourceLocation(id, ""))` → 紫黑块
- ❌ `RenderType.solid()` → 紫黑块（块实体渲染需用 `Sheets` 的 buffer）
- ✅ `ModelResourceLocation.standalone(location)` 仅在 `ModelEvent` 上下文中有意义
