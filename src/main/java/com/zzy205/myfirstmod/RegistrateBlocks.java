package com.zzy205.myfirstmod;

import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.zzy205.myfirstmod.block.PositionLightBlock;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import org.joml.Vector3f;

/**
 * 用 Registrate 注册的方块（与手写 DeferredRegister 的 MyModBlocks 并存）。
 * <p>
 * 参考来源：
 * <ul>
 *   <li>CreateDeco 的 CageLamps.build / BlockStateGenerator.cageLamp
 *       （references/CreateDeco-1.21-neo）——blockstate 变体与薄模型生成逻辑照搬；</li>
 *   <li>Registrate 1.3.0 源码（references/Registrate-MC1.21-1.3.0+67-sources）——API 用法核实。</li>
 * </ul>
 * 注册链执行即进入 Registrate 内部注册表（AbstractRegistrate.accept 立即 put），
 * 实际入册由 Registrate 在 RegisterEvent 时统一完成。
 */
public class RegistrateBlocks {

	static {
		// ⚠️ 根因修复：Registrate 的 defaultCreativeModeTab 默认是 CreativeModeTabs.SEARCH（AbstractRegistrate
		// 第 233 行），会给【每个】通过 Registrate 注册的物品自动 .tab(SEARCH)，经
		// BuildCreativeModeTabContentsEvent 往搜索标签重复 accept 物品 → 服务器启动时崩溃
		// （IllegalArgumentException: Itemstack ... already exists in the tab's list）。
		// 置 null 关闭自动挂接；物品进创造标签统一走 MyModCreativeModeTabs.displayItems 手动 accept。
		CCPeripheralExtender.REGISTRATE.defaultCreativeTab((ResourceKey<CreativeModeTab>) null);
	}

	/**
	 * 三色航行灯（红/绿/白）。每条注册链同时产出：
	 * blockstate（6 朝向 × 亮灭 + 旋转）、两个薄变体模型（亮/灭）、物品模型、掉落自身战利品表、
	 * mineable/pickaxe 标签。
	 * <p>
	 * 合成配方不走 datagen，手写维护在 src/main/resources/data/ccpe/recipe/（与其他方块一致）。
	 * <p>
	 * 注意：不用 Registrate 的 .tab() 挂创造标签（见上方 static 块的根因说明）；
	 * 在 MyModCreativeModeTabs.displayItems 手动 accept（与其他方块一致，已验证安全）。
	 */
	public static final BlockEntry<PositionLightBlock> RED_POSITION_LIGHT =
		positionLight("red_position_light", new Vector3f(1.0F, 0.0F, 0.0F), "red");
	public static final BlockEntry<PositionLightBlock> GREEN_POSITION_LIGHT =
		positionLight("green_position_light", new Vector3f(0.0F, 1.0F, 0.0F), "green");
	public static final BlockEntry<PositionLightBlock> WHITE_POSITION_LIGHT =
		positionLight("white_position_light", new Vector3f(1.0F, 1.0F, 1.0F), "white");

	/** 在 mod 构造器中调用：触发本类静态初始化（执行上方注册链）。 */
	public static void init() {
		RED_POSITION_LIGHT.getClass();
	}

	/**
	 * 单色航行灯注册链模板。
	 * {@code color} 为切换粒子的颜色；{@code textureColor} 决定 blockstate/模型生成的贴图后缀
	 * （如 "red" → red_on/red_off）。
	 */
	private static BlockEntry<PositionLightBlock> positionLight(String name, Vector3f color, String textureColor) {
		return CCPeripheralExtender.REGISTRATE
			.block(name, p -> new PositionLightBlock(p, color))
			.properties(p -> p
				.noOcclusion()
				.strength(0.5F)
				.sound(SoundType.LANTERN)
				.lightLevel(state -> state.getValue(BlockStateProperties.LIT) ? 15 : 0))
			.blockstate((ctx, prov) -> positionLightBlockstate(ctx, prov, textureColor))
			.tag(BlockTags.MINEABLE_WITH_PICKAXE)
			.simpleItem()
			.register();
	}

	/**
	 * 生成 blockstates/&lt;name&gt;.json（FACING × LIT 变体）与
	 * models/block/&lt;name&gt;.json / &lt;name&gt;_off.json（薄变体，仅换灯体贴图）。
	 * 旋转方案照搬 CreateDeco BlockStateGenerator.cageLamp：UP 为基准（x=0），DOWN x=180，水平四向 x=90 + y 旋转。
	 */
	private static void positionLightBlockstate(DataGenContext<Block, PositionLightBlock> ctx, RegistrateBlockstateProvider prov, String textureColor) {
		prov.getVariantBuilder(ctx.get()).forAllStates(state -> {
			int y = 0;
			int x = 90;
			switch (state.getValue(BlockStateProperties.FACING)) {
				case NORTH -> y = 0;
				case SOUTH -> y = 180;
				case WEST -> y = -90;
				case EAST -> y = 90;
				case DOWN -> x = 180;
				default -> x = 0; // UP
			}
			boolean lit = state.getValue(BlockStateProperties.LIT);
			ModelFile model = prov.models().withExistingParent(
					ctx.getName() + (lit ? "" : "_off"),
					prov.modLoc("block/position_light/position_light"))
				.texture("0", prov.modLoc("block/position_light/" + textureColor + (lit ? "_on" : "_off")))
				.texture("1", prov.modLoc("block/position_light/base"))
				.texture("particle", prov.modLoc("block/position_light/" + textureColor + "_off"));
			return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
		});
	}
}
