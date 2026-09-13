package com.zzy205.myfirstmod.block;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.zzy205.myfirstmod.compat.cc.SensorSystemAPI;
import com.zzy205.myfirstmod.compat.sable.SableCompat;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.nbt.NBTHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 发动机核心方块实体：Create 动力源骨架 + 多方块组网（P0）+ 燃烧发电（P1：流体燃烧室；P2：蒸汽动力室）。
 * <p>
 * 组网机制参考 CDG {@code ModularDieselEngineBlockEntity}（方案见 memo/engine-module.md）：
 * <ul>
 *   <li>每个核心 BE 记录 {@link #controller}（controller 坐标，自己为 controller 时 null）与
 *       {@link #length}（整条总节数，每个成员存同一值）；</li>
 *   <li>只有 controller 参与发电（{@link #getGeneratedSpeed()} 非 controller 恒 0），
 *       整条核心经 AXIS 贯通传动杆耦合成一个应力网络；</li>
 *   <li>放置/拆除/读档分别触发 {@code ConnectivityHandler.formMulti / splitMulti} 与
 *       {@code Uninitialized} 重组（触发点见 {@link EngineCoreBlock}）；</li>
 *   <li>存档：非 controller 写 {@code Controller}；所有成员写 {@code LastKnownPos}；
 *       controller 写 {@code Height}（= length）；{@code Uninitialized} 标志驱动读档后重新组网。</li>
 * </ul>
 * 燃烧发电（仅 controller tick，零流体缓存）：
 * <ul>
 *   <li>流体燃烧室：每室消耗燃料表流体的 consumption mb/s（fuelDebt 累加器 → 从源罐 drain 1mb）；
 *       容量 = 室数 × 4096 × stress 倍率；</li>
 *   <li>蒸汽动力室：水（1mb/s/室，从源罐 drain）+ 燃料（burnTick 制，1 tick 烧 1 个 burnTick，等价熔炉速率）——
 *       固体燃料从室邻居容器抽取（+物品 burnTime），流体燃料按 burn_ticks_per_bucket 从源罐抽；
 *       水不可用时暂停（不烧）；</li>
 *   <li>转速固定 {@link #GENERATED_SPEED} = 256；总容量 = 两类运行室贡献之和。</li>
 * </ul>
 * 参考来源：CDG {@code ModularDieselEngineBlockEntity}；Create {@code ConnectivityHandler} / {@code IMultiBlockEntityContainer}。
 */
public class EngineCoreBlockEntity extends GeneratingKineticBlockEntity implements IMultiBlockEntityContainer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 调试日志开关：定位"引擎没反应"问题时置 true，定位后改回 false */
    public static boolean DEBUG = true;

    /** 发电转速（RPM）：有运行中的燃烧室且燃料可用时输出（P1 起） */
    public static final float GENERATED_SPEED = 256;

    /** 每个流体燃烧室的基础应力贡献（SU） */
    public static final float BASE_STRESS_PER_CHAMBER = 4096;

    /** 每个蒸汽动力室的基础应力贡献（SU） */
    public static final float STEAM_STRESS_PER_CHAMBER = 3072;

    /** 整条引擎的总节数（每个成员都存同一值，由 ConnectivityHandler.setHeight 维护） */
    protected int length = 1;
    /** controller 坐标；自己是 controller 时为 null */
    protected BlockPos controller;
    /** 存档时记录自身位置，供 chunk 重载后找回 controller */
    protected BlockPos lastKnownPos;
    /** 需要重新组网（放置 / 读档 Uninitialized / 拆解） */
    protected boolean updateConnectivity = false;

    // ---- P1：燃烧/发电状态（仅 controller 计算；Running 同步给客户端驱动音效/动画） ----
    /** 是否运行（有燃烧室 + 燃料可用 + 未过载） */
    protected boolean running = false;
    /** 模块总容量（SU，当前转速下）＝运行燃烧室数 × 4096 × 燃料 stress 倍率 */
    protected float moduleCapacity = 0;
    /** 燃料消耗累加器（0.05mb/t = 1mb/s，≥1 才从源罐 drain 1mb，零缓存） */
    protected float fuelDebt = 0f;
    /** 当前燃料流体（缓存，供 drain 使用） */
    protected FluidStack fuelFluid = FluidStack.EMPTY;
    /** 当前燃料源罐位置（缓存，失效重扫） */
    protected BlockPos fuelSourcePos;
    /** 空转重扫冷却（无燃料时避免每 tick 全量扫描源罐） */
    protected int fuelRescanCooldown = 0;

    // ---- P2：蒸汽室状态（仅 controller 计算；水/蒸汽流体燃料零缓存） ----
    /** 水源罐位置（缓存，失效重扫；蒸汽室共享） */
    protected BlockPos waterSourcePos;
    /** 水源重扫冷却 */
    protected int waterRescanCooldown = 0;
    /** 水消耗累加器（每蒸汽室 1mb/s = 0.05mb/t，≥1 才 drain 1mb） */
    protected float waterDebt = 0f;
    /** 蒸汽室当前流体燃料条目（缓存） */
    protected EngineFuels.Entry steamFuelEntry;
    /** 蒸汽室当前流体燃料（缓存，供 drain 使用） */
    protected FluidStack steamFuelFluid = FluidStack.EMPTY;
    /** 蒸汽室流体燃料源罐位置（缓存，失效重扫） */
    protected BlockPos steamFuelSourcePos;
    /** 蒸汽室流体燃料重扫冷却 */
    protected int steamFuelRescanCooldown = 0;

    // ---- P3：温度/冷却（牛顿冷却模型，方案见 memo/engine-module.md 关键机制 6） ----
    /** 过热阈值（°C）：T ≥ 此值硬停 */
    public static final float OVERHEAT_TEMP = 200f;
    /** 滞回恢复阈值（°C）：T ≤ 此值才允许重启（0.8×OVERHEAT_TEMP） */
    public static final float OVERHEAT_RESUME = 160f;
    /** 海平面环境温度（°C） */
    public static final float T_AMB_SEA = 20f;
    /** 对流层环境温度递减率（°C/m，按海平面高度） */
    public static final float T_AMB_LAPSE = 0.0065f;
    /** 环境温度下限（°C） */
    public static final float T_AMB_FLOOR = -40f;
    /** 流体室基础热（°C/s，100% 效率下每室） */
    public static final float BASE_HEAT_FLUID = 30f;
    /** 蒸汽室基础热倍率（吃水 = 天然冷却，比流体室低 20%） */
    public static final float STEAM_HEAT_FACTOR = 0.8f;
    /** 环境散热系数（°C/s/°C/室）——静态 eff25% 不过热 ⇒ K_AMB ≥ 0.25×H0/ΔT_max */
    public static final float K_AMBIENT = 0.05f;
    /** 每冷却风道散热系数（°C/s/°C）——静态 eff50% + 1风道/室 ⇒ K_AMB+K_DUCT ≥ 0.5×H0/ΔT_max */
    public static final float K_DUCT = 0.05f;
    /** 引擎核心自身散热系数（°C/s/°C/节）：停机时核心仍缓慢散热（不依赖冷却风道），τ ≈ 1/0.02 = 50s */
    public static final float K_CORE = 0.02f;
    /** 冲压冷却满增益（×）：运动 ≥RAM_FULL 时总散热 ×2.0（eff100% + 1风道/室 + 冲压 ⇒ 足够） */
    public static final float RAM_MAX = 2.0f;
    /** 冲压起始速度（m/s）：低于此无增益 */
    public static final float RAM_START = 10f;
    /** 冲压满速（m/s）：达到此速度增益 = RAM_MAX */
    public static final float RAM_FULL = 30f;
    /** 热容基数（°C 变化速率分母；×length：模块越大热得越慢） */
    public static final float C_TH_BASE = 1.0f;
    /** 气压因子下限（防大气顶气压→0 导致冷却归零） */
    public static final float PRESSURE_FLOOR = 0.25f;
    /** 温度同步到客户端的阈值（°C，避免每 tick 发包） */
    public static final float SYNC_TEMP_DELTA = 1f;

    /** 模块温度（°C，controller 持有；NBT 持久化 + 客户端同步显示） */
    protected float temperature = T_AMB_SEA;
    /** 效率（=油门，同时缩出力和热量；P3 默认 0.25，P4 由 Lua 控制） */
    protected float efficiency = 0.25f;
    /** 过热锁定（滞回：T≥OVERHEAT_TEMP 置位，T≤OVERHEAT_RESUME 复位） */
    protected boolean overheated = false;
    /** 上次同步给客户端的温度（差量发包用） */
    protected float lastSyncedTemp = T_AMB_SEA;
    /** 客户端温度显示值（服务端差量采样 → 趋势外推平滑，见 tickClient） */
    @OnlyIn(Dist.CLIENT)
    protected float displayedTemperature = T_AMB_SEA;
    /** 最近两个温度采样点（服务端差量发包到达时滚动，tickClient 沿斜率外推） */
    @OnlyIn(Dist.CLIENT)
    protected float lastTempSample = T_AMB_SEA, newTempSample = T_AMB_SEA;
    @OnlyIn(Dist.CLIENT)
    protected long lastTempSampleTime, newTempSampleTime;

    @OnlyIn(Dist.CLIENT)
    protected EngineSoundInstance soundInstance;

    public EngineCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MyModBlockEntities.engine_core_entity.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (updateConnectivity)
            updateConnectivity();

        if (level.isClientSide) {
            CatnipServices.PLATFORM.executeOnClientOnly(() -> this::tickClient);
            return;
        }
        if (!isController())
            return;

        // ---- P1+P2+P3：燃烧室 → 燃料 → 发电/消耗/温度（零缓存，直接从源罐 drain） ----
        boolean prevRunning = running;
        float prevCapacity = moduleCapacity;

        boolean overstressed = isOverStressed();
        ModuleScan scan = scanModule();
        List<BlockPos> neighbors = scan.neighbors();

        // 过热锁定（滞回）：T≥OVERHEAT_TEMP 停机锁定，T≤OVERHEAT_RESUME 解锁
        if (overheated) {
            if (temperature <= OVERHEAT_RESUME)
                overheated = false;
        } else if (temperature >= OVERHEAT_TEMP) {
            overheated = true;
        }
        boolean heatAllowed = !overheated;

        // 流体燃烧室（P1）：燃料表第一个可用流体；每室 consumption mb/s × 效率
        int runningFluid = 0;
        float fluidCapacity = 0;
        EngineFuels.Entry fluidFuel = heatAllowed ? findFuel(neighbors) : null;
        if (!scan.fluidChambers.isEmpty() && fluidFuel != null && !overstressed) {
            runningFluid = scan.fluidChambers.size();
            fluidCapacity = runningFluid * BASE_STRESS_PER_CHAMBER * fluidFuel.stress();
            fuelDebt += runningFluid * fluidFuel.consumption() * efficiency / 20f;
            while (fuelDebt >= 1f) {
                if (drainFuel(1)) {
                    fuelDebt -= 1f;
                } else {
                    runningFluid = 0;
                    fluidCapacity = 0;
                    fuelDebt = 0;
                    break;
                }
            }
        } else {
            fuelDebt = 0;
        }

        // 蒸汽动力室（P2）：水 + 流体燃料（burnTick 制，水在才烧；水不可用整类暂停；固体燃料抽取暂缓见 tryPullSolidFuel）
        int runningSteam = 0;
        boolean waterOk = !overstressed && heatAllowed && waterAvailable(neighbors);
        EngineFuels.Entry steamFuel = heatAllowed && !scan.steamChambers.isEmpty() ? findSteamFluidFuel(neighbors) : null;
        if (waterOk && steamFuel != null) {
            for (BlockPos sp : scan.steamChambers) {
                if (!(level.getBlockEntity(sp) instanceof SteamPowerChamberBlockEntity be))
                    continue;
                // 流体燃料：每 tick 每室消耗 1000/burn_ticks_per_bucket × 效率 mb（≈效率 个 burnTick/tick），
                // 累计 ≥1mb 从源罐抽；每抽 1mb → burn_ticks_per_bucket/1000 个 burnTick（熔岩 20 tick/mb）
                be.fluidFuelDebt += 1000f / steamFuel.burnTicksPerBucket() * efficiency;
                while (be.fluidFuelDebt >= 1f) {
                    if (drainSteamFluidFuel(1)) {
                        be.fluidFuelDebt -= 1f;
                        be.burnTicks += Math.max(1, Math.round(steamFuel.burnTicksPerBucket() / 1000f));
                        be.setChanged();
                    } else {
                        be.fluidFuelDebt = 0;
                        break;
                    }
                }
                if (be.burnTicks > 0) {
                    be.burnTicks -= efficiency; // 1 个 burnTick 烧 1/efficiency tick（25% 效率 = 4× 时长）
                    if (be.burnTicks <= 0)
                        be.setChanged();
                    runningSteam++;
                }
            }
            if (runningSteam > 0) {
                waterDebt += runningSteam * 0.05f * efficiency; // 1mb/s/室 × 效率 = 0.05mb/t
                while (waterDebt >= 1f) {
                    if (drainWater(1)) {
                        waterDebt -= 1f;
                    } else {
                        waterDebt = 0;
                        break;
                    }
                }
            }
        }

        int runningTotal = runningFluid + runningSteam;
        running = runningTotal > 0 && !overstressed && !overheated;
        moduleCapacity = (fluidCapacity + runningSteam * STEAM_STRESS_PER_CHAMBER) * efficiency;

        // ---- P3：温度更新（牛顿冷却） ----
        float heat = 0;
        if (running && !overheated) {
            if (runningFluid > 0 && fluidFuel != null)
                heat += runningFluid * efficiency * fluidFuel.heat() * BASE_HEAT_FLUID;
            if (runningSteam > 0 && steamFuelEntry != null)
                heat += runningSteam * efficiency * steamFuelEntry.heat() * BASE_HEAT_FLUID * STEAM_HEAT_FACTOR;
        }
        float tAmb = ambientTemp();
        float kTotal = (K_CORE * length + K_AMBIENT * runningTotal + K_DUCT * scan.coolingDucts)
                * ramFactor() * pressureFactor();
        temperature = Mth.clamp(
                temperature + (heat - kTotal * (temperature - tAmb)) / thermalCapacity() / 20f,
                tAmb, OVERHEAT_TEMP * 1.2f);

        if (running != prevRunning || !Mth.equal(moduleCapacity, prevCapacity)
                || Math.abs(temperature - lastSyncedTemp) >= SYNC_TEMP_DELTA) {
            reActivateSource = true;
            setChanged();
            lastSyncedTemp = temperature;
            sendData();
        }

        if (DEBUG) {
            if (running != prevRunning) {
                LOGGER.info("[EngineCore] {} running {} -> {} | fluid={} steam={} fuel={} water={} T={}℃ overheated={} capacity={}",
                        worldPosition, prevRunning, running, runningFluid, runningSteam,
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, temperature, overheated, moduleCapacity);
            } else if (!running && level.getGameTime() % 40 == 0) {
                LOGGER.info("[EngineCore] {} idle | fluid={} steam={} fuel={} water={} T={}℃ overheated={} ducts={} len={} speed={}",
                        worldPosition, scan.fluidChambers.size(), scan.steamChambers.size(),
                        fluidFuel == null ? "NONE" : fluidFuel.fluid(), waterOk, temperature, overheated,
                        scan.coolingDucts, length, getSpeed());
            }
        }
    }

    /** 模块扫描结果：一次遍历收集两类燃烧室 + 冷却风道数 + 去重后的全部邻居（core 成员 6 邻居 ∪ 燃烧室 6 邻居） */
    protected record ModuleScan(List<BlockPos> fluidChambers, List<BlockPos> steamChambers, List<BlockPos> neighbors,
                                int coolingDucts) {}

    /**
     * 扫描本条引擎（全部成员）：统计贴附的流体/蒸汽燃烧室（仅计入背面 FACING 反方向正贴核心的，
     * 避免双计），并收集模块全部邻居（core 成员 ∪ 燃烧室，去重）——水和流体燃料的源罐查找范围。
     */
    protected ModuleScan scanModule() {
        List<BlockPos> fluidChambers = new ArrayList<>();
        List<BlockPos> steamChambers = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> neighbors = new ArrayList<>();
        int coolingDucts = 0;

        Direction.Axis axis = getMainConnectionAxis();
        for (int i = 0; i < length; i++) {
            BlockPos corePos = worldPosition.relative(axis, i);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = corePos.relative(dir);
                BlockState state = level.getBlockState(neighbor);
                if (state.is(MyModBlocks.fluid_combustion_chamber.get())
                        // 燃烧室背面（FACING 反方向）坐标 == 核心坐标，才算贴在这节核心上
                        && neighbor.relative(state.getValue(FluidCombustionChamberBlock.FACING).getOpposite()).equals(corePos)) {
                    fluidChambers.add(neighbor);
                } else if (state.is(MyModBlocks.steam_power_chamber.get())
                        && neighbor.relative(state.getValue(SteamPowerChamberBlock.FACING).getOpposite()).equals(corePos)) {
                    steamChambers.add(neighbor);
                }
                // 冷却风道：贴在核心成员上即计入（blockstate 计数，无 BE；去重防两核心间重复计）
                if (state.is(MyModBlocks.cooling_duct.get()) && seen.add(neighbor))
                    coolingDucts++;
                addNeighbor(seen, neighbors, neighbor);
            }
        }
        // 燃烧室自己的邻居（罐/容器可能贴着燃烧室）
        for (BlockPos chamber : fluidChambers)
            addNeighbors(seen, neighbors, chamber);
        for (BlockPos chamber : steamChambers)
            addNeighbors(seen, neighbors, chamber);
        return new ModuleScan(fluidChambers, steamChambers, neighbors, coolingDucts);
    }

    private void addNeighbor(Set<BlockPos> seen, List<BlockPos> list, BlockPos pos) {
        if (seen.add(pos))
            list.add(pos);
    }

    private void addNeighbors(Set<BlockPos> seen, List<BlockPos> list, BlockPos pos) {
        for (Direction dir : Direction.values())
            addNeighbor(seen, list, pos.relative(dir));
    }

    /** 找流体燃烧室当前可用燃料：优先复用缓存的源罐；失效则按优先级全量重扫（带冷却） */
    protected EngineFuels.Entry findFuel(List<BlockPos> neighbors) {
        if (fuelSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(fuelSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    EngineFuels.Entry entry = EngineFuels.get(stack.getFluid());
                    if (entry != null) {
                        fuelFluid = stack.copy();
                        return entry;
                    }
                }
            }
            fuelSourcePos = null;
        }

        if (fuelRescanCooldown > 0) {
            fuelRescanCooldown--;
            return null;
        }
        fuelRescanCooldown = 10;

        for (EngineFuels.Entry entry : EngineFuels.sortedByPriority()) {
            for (BlockPos neighbor : neighbors) {
                IFluidHandler handler = fluidHandlerAt(neighbor);
                if (handler == null)
                    continue;
                FluidStack stack = handler.getFluidInTank(0);
                if (stack.isEmpty())
                    continue;
                if (BuiltInRegistries.FLUID.getKey(stack.getFluid()).equals(entry.fluid())) {
                    fuelSourcePos = neighbor;
                    fuelFluid = stack.copy();
                    return entry;
                }
            }
        }
        return null;
    }

    // ================= P2：蒸汽室（水 + 燃料） =================

    /** 水是否可用：优先复用缓存的水源罐；失效则重扫（带冷却）。源 = 模块邻居中含原版水的罐 */
    protected boolean waterAvailable(List<BlockPos> neighbors) {
        if (waterSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(waterSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty() && stack.getFluid() == Fluids.WATER)
                    return true;
            }
            waterSourcePos = null;
        }

        if (waterRescanCooldown > 0) {
            waterRescanCooldown--;
            return false;
        }
        waterRescanCooldown = 10;

        for (BlockPos pos : neighbors) {
            IFluidHandler handler = fluidHandlerAt(pos);
            if (handler == null)
                continue;
            FluidStack stack = handler.getFluidInTank(0);
            if (!stack.isEmpty() && stack.getFluid() == Fluids.WATER) {
                waterSourcePos = pos;
                return true;
            }
        }
        return false;
    }

    /** 从水源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainWater(int mb) {
        if (waterSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(waterSourcePos);
        if (handler == null) {
            waterSourcePos = null;
            return false;
        }
        return handler.drain(new FluidStack(Fluids.WATER, mb), IFluidHandler.FluidAction.EXECUTE).getAmount() >= mb;
    }

    /** 找蒸汽室当前可用流体燃料（burn_ticks_per_bucket > 0）：缓存优先，失效重扫（带冷却） */
    protected EngineFuels.Entry findSteamFluidFuel(List<BlockPos> neighbors) {
        if (steamFuelSourcePos != null) {
            IFluidHandler handler = fluidHandlerAt(steamFuelSourcePos);
            if (handler != null) {
                FluidStack stack = handler.getFluidInTank(0);
                if (!stack.isEmpty()) {
                    EngineFuels.Entry entry = EngineFuels.get(stack.getFluid());
                    if (entry != null && entry.burnTicksPerBucket() > 0) {
                        steamFuelEntry = entry;
                        steamFuelFluid = stack.copy();
                        return entry;
                    }
                }
            }
            steamFuelSourcePos = null;
        }

        if (steamFuelRescanCooldown > 0) {
            steamFuelRescanCooldown--;
            return null;
        }
        steamFuelRescanCooldown = 10;

        for (EngineFuels.Entry entry : EngineFuels.sortedByPriority()) {
            if (entry.burnTicksPerBucket() <= 0)
                continue;
            for (BlockPos pos : neighbors) {
                IFluidHandler handler = fluidHandlerAt(pos);
                if (handler == null)
                    continue;
                FluidStack stack = handler.getFluidInTank(0);
                if (stack.isEmpty())
                    continue;
                if (BuiltInRegistries.FLUID.getKey(stack.getFluid()).equals(entry.fluid())) {
                    steamFuelSourcePos = pos;
                    steamFuelEntry = entry;
                    steamFuelFluid = stack.copy();
                    return entry;
                }
            }
        }
        return null;
    }

    /** 从蒸汽室流体燃料源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainSteamFluidFuel(int mb) {
        if (steamFuelSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(steamFuelSourcePos);
        if (handler == null) {
            steamFuelSourcePos = null;
            return false;
        }
        return handler.drain(new FluidStack(steamFuelFluid.getFluid(), mb), IFluidHandler.FluidAction.EXECUTE).getAmount() >= mb;
    }

    /**
     * 从蒸汽室 6 邻居容器抽取 1 个熔炉燃料物品（burnTime > 0），burnTicks += 物品 burnTime；抽到返回 true。
     * <p><b>暂缓（用户要求先做流体燃料熔岩）</b>：当前 tick 未调用；恢复固体燃料时在蒸汽室循环里
     * 先于流体燃料尝试即可（be.burnTicks <= 0 时先 tryPullSolidFuel 再走流体）。</p>
     */
    protected boolean tryPullSolidFuel(SteamPowerChamberBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, neighbor, null);
            if (handler == null)
                continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty())
                    continue;
                int burnTime = stack.getBurnTime(RecipeType.SMELTING);
                if (burnTime <= 0)
                    continue;
                ItemStack extracted = handler.extractItem(slot, 1, false);
                if (extracted.isEmpty())
                    continue;
                be.burnTicks += burnTime;
                be.setChanged();
                return true;
            }
        }
        return false;
    }

    // ================= P3：温度/冷却辅助 =================

    /** 冲压冷却因子：运动体速度 10→30 m/s 线性爬升 1.0→RAM_MAX（<10 无增益，≥30 满增益；静态方块 = 1.0） */
    protected float ramFactor() {
        SubLevel sub = SableCompat.getContainingSubLevel(this);
        if (sub == null)
            return 1f;
        Vec3 v = SableCompat.getWorldLinearVelocity(sub);
        if (v == null)
            return 1f;
        float speed = (float) v.length();
        if (speed <= RAM_START)
            return 1f;
        if (speed >= RAM_FULL)
            return RAM_MAX;
        float t = (speed - RAM_START) / (RAM_FULL - RAM_START);
        return 1f + (RAM_MAX - 1f) * t;
    }

    /** 引擎当前世界高度 Y：运动体上取物理体原点 Y，静态取方块自身 Y */
    protected float engineAltitude() {
        SubLevel sub = SableCompat.getContainingSubLevel(this);
        if (sub != null) {
            Vec3 pos = SableCompat.getSubLevelWorldPos(sub);
            if (pos != null)
                return (float) pos.y;
        }
        return worldPosition.getY();
    }

    /** 气压因子：pressure^0.8（钳位下限 PRESSURE_FLOOR）。高空气压低 → 换热差 → 冷却更差（物理结论见 memo） */
    protected float pressureFactor() {
        double p = SensorSystemAPI.getPressureForEngine(engineAltitude());
        return (float) Math.pow(Math.max(p, PRESSURE_FLOOR), 0.8);
    }

    /** 环境温度（°C）：海平面 20°C，对流层 −0.0065°C/m（按海平面高度），下限 T_AMB_FLOOR */
    protected float ambientTemp() {
        float aboveSea = Math.max(0, engineAltitude() - 63f);
        return Math.max(T_AMB_SEA - T_AMB_LAPSE * aboveSea, T_AMB_FLOOR);
    }

    /** 热容：C_TH_BASE × 节数（模块越大热得越慢） */
    protected float thermalCapacity() {
        return C_TH_BASE * Math.max(1, length);
    }

    /** 从当前源罐 drain 指定量（mb）；源罐失效返回 false */
    protected boolean drainFuel(int mb) {
        if (fuelSourcePos == null)
            return false;
        IFluidHandler handler = fluidHandlerAt(fuelSourcePos);
        if (handler == null) {
            fuelSourcePos = null;
            return false;
        }
        FluidStack drained = handler.drain(new FluidStack(fuelFluid.getFluid(), mb), IFluidHandler.FluidAction.EXECUTE);
        return drained.getAmount() >= mb;
    }

    protected IFluidHandler fluidHandlerAt(BlockPos pos) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickClient() {
        // 温度显示：沿最近两个采样点的斜率外推（服务端差量发包低频 → 外推让显示连续无台阶/平台）
        long now = level.getGameTime();
        if (lastTempSampleTime < newTempSampleTime) {
            float dt = Math.max(1, newTempSampleTime - lastTempSampleTime);
            float slope = (newTempSample - lastTempSample) / dt;
            float extrapolated = newTempSample + slope * Math.max(0, now - newTempSampleTime);
            // 限制外推带，防温度曲线拐弯（接近平衡/停机冷却）时过冲
            float lo = Math.min(lastTempSample, newTempSample) - 10f;
            float hi = Math.max(lastTempSample, newTempSample) + 10f;
            displayedTemperature = Mth.clamp(extrapolated, lo, hi);
        } else {
            displayedTemperature = newTempSample;
        }

        if (isController() && running && !isOverStressed()) {
            Vec3 pos = Vec3.atCenterOf(getBlockPos());
            switch (getMainConnectionAxis()) {
                case X -> pos = pos.add((double) length / 2 - 0.5, 0, 0);
                case Z -> pos = pos.add(0, 0, (double) length / 2 - 0.5);
                case Y -> pos = pos.add(0, (double) length / 2 - 0.5, 0);
            }
            if (soundInstance == null || soundInstance.isStopped()) {
                Minecraft.getInstance().getSoundManager()
                        .play(soundInstance = new EngineSoundInstance(AllSoundEvents.STEAM.getMainEvent(), SoundSource.BLOCKS, pos, 0.1f));
            } else if (soundInstance.active()) {
                soundInstance.keepAlive();
                soundInstance.setPitch(1f);
                soundInstance.setVolume(0.6f);
            }
        } else {
            if (soundInstance != null) {
                soundInstance.fadeOut();
                soundInstance = null;
            }
        }
    }

    /** 是否运行（有燃烧室 + 燃料可用 + 未过载）；服务端 tick 计算，经 NBT 同步给客户端（音效/活塞动画用） */
    public boolean isRunning() {
        return running;
    }

    /** 重新组网：只有 controller 触发 formMulti（服务端执行，客户端直接跳过） */
    public void updateConnectivity() {
        updateConnectivity = false;
        if (level.isClientSide)
            return;
        if (!isController())
            return;
        ConnectivityHandler.formMulti(this);
    }

    // ================= IMultiBlockEntityContainer =================

    @Override
    public BlockPos getController() {
        return isController() ? worldPosition : controller;
    }

    @Override
    public EngineCoreBlockEntity getControllerBE() {
        if (isController() || !hasLevel())
            return this;
        BlockEntity be = level.getBlockEntity(controller);
        if (be instanceof EngineCoreBlockEntity)
            return (EngineCoreBlockEntity) be;
        return null;
    }

    @Override
    public boolean isController() {
        return controller == null || controller.equals(worldPosition);
    }

    @Override
    public void setController(BlockPos controller) {
        if (level.isClientSide && !isVirtual())
            return;
        if (controller.equals(this.controller))
            return;
        this.controller = controller;
        setChanged();
        sendData();
    }

    @Override
    public void removeController(boolean keepContents) {
        if (level.isClientSide)
            return;
        updateConnectivity = true;
        controller = null;
        length = 1;
        reActivateSource = true;
        setChanged();
        sendData();
    }

    @Override
    public BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    @Override
    public void preventConnectivityUpdate() {
        updateConnectivity = false;
    }

    @Override
    public void notifyMultiUpdated() {
        reActivateSource = true;
        setChanged();
    }

    @Override
    public Direction.Axis getMainConnectionAxis() {
        return getBlockState().getValue(EngineCoreBlock.AXIS);
    }

    @Override
    public int getMaxLength(Direction.Axis longAxis, int width) {
        return 21;
    }

    @Override
    public int getMaxWidth() {
        return 1;
    }

    @Override
    public int getHeight() {
        return length;
    }

    @Override
    public void setHeight(int height) {
        length = height;
    }

    @Override
    public int getWidth() {
        return 1;
    }

    @Override
    public void setWidth(int width) {
        // 宽度固定 1（1×1×N 线性柱体，沿 FACING 轴）
    }

    // ================= NBT =================

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        BlockPos controllerBefore = controller;
        int prevHeight = length;

        updateConnectivity = compound.contains("Uninitialized");
        controller = null;
        lastKnownPos = null;

        if (compound.contains("LastKnownPos"))
            lastKnownPos = NBTHelper.readBlockPos(compound, "LastKnownPos");
        if (compound.contains("Controller"))
            controller = NBTHelper.readBlockPos(compound, "Controller");

        if (isController())
            length = compound.getInt("Height");
        running = compound.getBoolean("Running");
        if (compound.contains("Temperature"))
            temperature = compound.getFloat("Temperature");
        if (compound.contains("Efficiency"))
            efficiency = compound.getFloat("Efficiency");

        if (!clientPacket)
            return;

        // 温度采样滚动：新包到达时推进采样窗口（tickClient 沿最近两点斜率外推显示）
        lastTempSample = newTempSample;
        lastTempSampleTime = newTempSampleTime;
        newTempSample = temperature;
        newTempSampleTime = hasLevel() ? level.getGameTime() : 0;

        boolean changeOfController = !Objects.equals(controllerBefore, controller);
        if (changeOfController || prevHeight != length) {
            if (hasLevel())
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 16);
            invalidateRenderBoundingBox();
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        if (updateConnectivity)
            compound.putBoolean("Uninitialized", true);
        if (lastKnownPos != null)
            compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
        if (!isController())
            compound.put("Controller", NbtUtils.writeBlockPos(controller));
        if (isController())
            compound.putInt("Height", length);
        compound.putBoolean("Running", running);
        compound.putFloat("Temperature", temperature);
        compound.putFloat("Efficiency", efficiency);
    }

    /** Goggle 提示：非 controller 委托给 controller；首行标题（Create overlay 视为标题行，其后有间距）→ 内容整体下移一行 */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController()) {
            EngineCoreBlockEntity controller = getControllerBE();
            if (controller == null)
                return false;
            return controller.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }
        tooltip.add(Component.literal("    ")
                .append(Component.translatable("tooltip.ccpe.engine.header")
                        .withStyle(ChatFormatting.WHITE)));

        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);

        float shownTemp = level != null && level.isClientSide ? displayedTemperature : temperature;
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.temperature").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.1f°C", shownTemp))
                        .withStyle(overheated ? ChatFormatting.RED : ChatFormatting.GOLD)));
        if (overheated)
            tooltip.add(Component.literal("     ")
                    .append(Component.translatable("tooltip.ccpe.engine.overheated").withStyle(ChatFormatting.RED)));
        tooltip.add(Component.literal("     ")
                .append(Component.translatable("tooltip.ccpe.engine.efficiency").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Math.round(efficiency * 100) + "%").withStyle(ChatFormatting.AQUA)));
        return true;
    }

    @Override
    public float getGeneratedSpeed() {
        if (!isController() || !running)
            return 0;
        return GENERATED_SPEED;
    }

    /**
     * 应力容量（SU per RPM，Create 语义）：总容量 = 运行燃烧室数 × 4096 × 燃料 stress 倍率，
     * 除以当前转速即为每 RPM 容量（CDG modular engine 同款公式）。
     */
    @Override
    public float calculateAddedStressCapacity() {
        float speed = Math.abs(getGeneratedSpeed());
        if (speed == 0)
            return 0;
        return moduleCapacity / speed;
    }
}
