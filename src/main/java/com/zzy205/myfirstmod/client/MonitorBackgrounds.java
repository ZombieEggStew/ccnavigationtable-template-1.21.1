package com.zzy205.myfirstmod.client;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.monitor.MonitorBackground;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** 客户端运行目录 ccpe_res/monitor_bg 中的可选 Monitor 背景。 */
public final class MonitorBackgrounds {

    private static final Path DIRECTORY = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("ccpe_res").resolve("monitor_bg");
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final List<String> KEYS = new ArrayList<>();

    private MonitorBackgrounds() {}

    /** 扫描外部图片，并在纹理管理器中注册。仅在客户端渲染线程调用。 */
    public static void reload() {
        TEXTURES.clear();
        KEYS.clear();

        try {
            Files.createDirectories(DIRECTORY);
            try (Stream<Path> files = Files.list(DIRECTORY)) {
                files.filter(Files::isRegularFile)
                        .filter(MonitorBackgrounds::isSupportedImage)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(MonitorBackgrounds::loadTexture);
            }
        } catch (IOException exception) {
            CCPeripheralExtender.LOGGER.warn("[Monitor] Could not scan external background directory {}", DIRECTORY, exception);
        }
        CCPeripheralExtender.LOGGER.info("[Monitor] Loaded {} external background image(s) from {}", KEYS.size(), DIRECTORY);
    }

    public static List<String> keys() {
        return List.copyOf(KEYS);
    }

    public static ResourceLocation getTexture(String key) {
        return TEXTURES.get(key);
    }

    private static boolean isSupportedImage(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg");
    }

    private static void loadTexture(Path path) {
        String fileName = path.getFileName().toString();
        String key = MonitorBackground.customKey(fileName);
        if (key == null || TEXTURES.containsKey(key)) {
            CCPeripheralExtender.LOGGER.warn("[Monitor] Ignoring external background with unsupported or duplicate name: {}", fileName);
            return;
        }

        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                    CCPeripheralExtender.MOD_ID, "dynamic/monitor_bg/" + Integer.toUnsignedString(key.hashCode(), 36));
            Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
            TEXTURES.put(key, texture);
            KEYS.add(key);
        } catch (IOException exception) {
            CCPeripheralExtender.LOGGER.warn("[Monitor] Could not load external background image {}", path, exception);
        }
    }
}