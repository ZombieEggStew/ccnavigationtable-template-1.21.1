# Minecraft 1.21.1 NeoForge — Standalone 模型烘焙（仿 control-panels PreLoadedModel）

## 问题
在 BER（BlockEntityRenderer）中渲染独立模型时，直接调用 `Minecraft.getInstance().getModelManager().getModel(location)` 返回紫黑方块（missing model），因为该模型从未被注册到烘焙管线。

## 正确流程（两步）

### 1. `ModelEvent.RegisterAdditional` — 注册模型到烘焙管线
```java
public static void registerAdditional(ModelEvent.RegisterAdditional event) {
    event.register(ModelResourceLocation.standalone(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "block/my_model")));
}
```

### 2. `ModelEvent.BakingCompleted` — 取出烘焙好的 BakedModel
```java
public static void bakingCompleted(ModelEvent.BakingCompleted event) {
    BakedModel model = event.getModels().get(
        ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MOD_ID, "block/my_model")));
}
```

### 3. 在客户端构造中注册事件
```java
public MyClientMod(ModContainer container, IEventBus modEventBus) {
    modEventBus.addListener(MyPreloadedModels::registerAdditional);
    modEventBus.addListener(MyPreloadedModels::bakingCompleted);
}
```

## 渲染时注意事项
- RenderType：用 `Sheets.solidBlockSheet()` 而非 `RenderType.solid()`（块实体渲染标准）
- 用 `mc.getBlockRenderer().getModelRenderer().renderModel()` 渲染

## 参考
- control-panels: `PreLoadedModel.java` + `PreLoadedModelHandler.java` + `ModelEvent` 监听
- 本项目实现: `MonitorPreloadedModels.java` + `MonitorRenderer.java`
