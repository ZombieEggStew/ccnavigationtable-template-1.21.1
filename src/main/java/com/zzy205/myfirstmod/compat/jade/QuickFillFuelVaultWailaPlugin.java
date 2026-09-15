package com.zzy205.myfirstmod.compat.jade;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.block.QuickFillFuelVaultBlock;
import com.zzy205.myfirstmod.block.QuickFillFuelVaultBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.Accessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.TooltipPosition;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ItemView;
import snownee.jade.api.view.ViewGroup;

import java.util.List;

/**
 * Jade（WAILA）集成：快速装填燃料箱（quick_fill_fuel_vault）内容物 tooltip。
 * <p>
 * 显示格式对齐 {@code create:item_vault}（Jade 通用物品存储视图）：物品图标 + "数量× 名称"。
 * 参考来源：
 * <ul>
 *   <li>JadeAddons {@code snownee.jade.addon.create.TableClothProvider}（服务端+客户端双接口的
 *       IServerExtensionProvider/IClientExtensionProvider 模式）；</li>
 *   <li>Jade 本体 {@code snownee.jade.addon.universal.ItemStorageProvider}（统一渲染：注册到
 *       {@code Block.class} 的 appendTooltip/appendServerData 会把已注册 registerItemStorage 的
 *       provider 数据编码进 serverData 并按 item_vault 同款样式渲染）。</li>
 * </ul>
 * <p>
 * 数据流：服务端 {@link #getGroups} 返回单个 ItemStack（count=实际数量，网络 codec 为 VAR_INT
 * 可超 64）→ 通用 ItemStorageProvider 编码进 serverData → 客户端 {@link #getClientGroups}
 * 转 ItemView → 统一渲染（单物品显示 "图标 + 数量× 名称"；空仓 getGroups 返回 null 不显示）。
 * <p>
 * Jade 为 optional 依赖（mods.toml 已声明），无 Jade 时本类不会被加载。
 */
@WailaPlugin(CCPeripheralExtender.MOD_ID)
public class QuickFillFuelVaultWailaPlugin implements IWailaPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(CCPeripheralExtender.MOD_ID, "quick_fill_fuel_vault");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerItemStorage(VaultItemStorageProvider.INSTANCE, QuickFillFuelVaultBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerItemStorageClient(VaultItemStorageProvider.INSTANCE);
    }

    public enum VaultItemStorageProvider
            implements IServerExtensionProvider<ItemStack>, IClientExtensionProvider<ItemStack, ItemView> {
        INSTANCE;

        @Override
        @Nullable
        public List<ViewGroup<ItemStack>> getGroups(Accessor<?> accessor) {
            if (!(accessor.getTarget() instanceof QuickFillFuelVaultBlockEntity vault) || vault.isEmpty())
                return null;
            // count 携带实际数量（可达 CAPACITY=1024），网络流式编解码为 VAR_INT，可安全传输
            ItemStack stored = vault.getStored().copyWithCount(vault.getStoredCount());
            return List.of(new ViewGroup<>(List.of(stored)));
        }

        @Override
        public List<ClientViewGroup<ItemView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<ItemStack>> groups) {
            return ClientViewGroup.map(groups, ItemView::new, null);
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        /**
         * 优先级低于通用 Extension（ItemStorageProvider.Extension 返回 9999）：
         * getServerExtensionData 按优先级遍历，保证本 provider 先于通用容器处理被检查，
         * 从而对燃料箱总是走本 provider（通用容器处理对无 IItemHandler 的方块返回 null）。
         */
        @Override
        public int getDefaultPriority() {
            return TooltipPosition.BODY;
        }
    }
}
