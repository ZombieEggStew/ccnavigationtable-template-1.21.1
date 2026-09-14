package com.zzy205.myfirstmod.item;

import com.zzy205.myfirstmod.CCPeripheralExtender;
import com.zzy205.myfirstmod.RegistrateBlocks;
import com.zzy205.myfirstmod.block.MyModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class MyModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> MY_MOD_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB , CCPeripheralExtender.MOD_ID);

    public static final Supplier<CreativeModeTab> MY_MOD_TAB_SUPPLIER =
            MY_MOD_TAB.register("my_mod_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.ccpe.my_mod_tab"))
            .icon(() -> new ItemStack(MyModBlocks.micro_peripheral_extender.get()))
            .displayItems((parameters, output) -> {
                // ── 物品栏分区排序（参考航空学 Simulated / create-propulsion-simulated 的分区思想，
                //    零 Mixin 纯排序版：按 5 个分区顺序输出，vanilla 保序显示；后续如需分区空行再加 mixin 补空）──
                // 1. 航电系统
                output.accept(MyModBlocks.static_port);
                output.accept(MyModBlocks.pitot_tube);
                output.accept(MyModBlocks.ins);
                output.accept(MyModBlocks.fmc);
                output.accept(MyModBlocks.aic);
                output.accept(RegistrateBlocks.RED_POSITION_LIGHT.get()); // 红色航行灯
                output.accept(RegistrateBlocks.GREEN_POSITION_LIGHT.get()); // 绿色航行灯
                output.accept(RegistrateBlocks.WHITE_POSITION_LIGHT.get()); // 白色航行灯
                output.accept(MyModBlocks.short_range_linker);
                // 2. 模块化发动机
                output.accept(MyModBlocks.engine_core); // 发动机核心（Create 动力源骨架）
                output.accept(MyModBlocks.fluid_combustion_chamber); // 流体燃烧室（活塞动态渲染）
                output.accept(MyModBlocks.steam_power_chamber); // 蒸汽动力室（活塞动态渲染）
                output.accept(MyModBlocks.cooling_duct); // 冷却风道（12 态，单模型）
                // 3. 控制台
                output.accept(MyModBlocks.my_control_desk);
                output.accept(MyModItems.CONTROL_PEDAL);
                output.accept(MyModItems.CONTROL_JOYSTICK);
                output.accept(MyModItems.CONTROL_JOYSTICK_2);
                output.accept(MyModItems.CONTROL_THROTTLE);
                output.accept(MyModItems.CONTROL_THROTTLE_2);
                output.accept(MyModItems.CONTROL_MONITOR_2);
                // 4. 监视器
                output.accept(MyModBlocks.monitor);
                output.accept(MyModItems.MODULE_BUTTON_1);
                output.accept(MyModItems.MODULE_TOGGLE_SWITCH);
                output.accept(MyModItems.MODULE_KNOB);
                output.accept(MyModItems.MODULE_SCREEN);
                // 5. 其他
                output.accept(MyModBlocks.micro_peripheral_extender);
                output.accept(MyModBlocks.redstone_transceiver);
                output.accept(MyModBlocks.transmission_peripheral);
                output.accept(MyModBlocks.aero_bearing);
                output.accept(MyModBlocks.quick_fill_fluid_tank); // 快速装填流体储罐（纯静态）
                output.accept(MyModBlocks.fluid_port);
                output.accept(MyModBlocks.trailing_wheel_mount); // 从动轮悬架（单轮无动力）
            })
            .build());
    public static void register(IEventBus modEventBus) {
        MY_MOD_TAB.register(modEventBus);
    }
}
