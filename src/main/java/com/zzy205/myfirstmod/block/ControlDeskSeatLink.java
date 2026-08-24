package com.zzy205.myfirstmod.block;

import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * controlDesk × Create 坐垫联动判定工具（按 memo/control-desk-seat.md 定案：以坐垫为中心，现查零持久化）。
 * <ul>
 *   <li>判定① 联动存在：坐垫四邻（N/E/S/W 紧邻 1 格）内存在至少一个 controlDesk —— 这些 controlDesk 全部进入联动（最多 4 个）</li>
 *   <li>判定② 玩家在操作：玩家骑乘在 Create 坐垫（{@link SeatEntity}）上</li>
 *   <li>操作模式 = ① + ② 同时成立；客户端、服务端各自独立现查，天然无陈旧状态</li>
 * </ul>
 * 参考来源：Create SeatBlock/SeatEntity（references/Create-mc1.21.1-dev/.../seat/）。
 */
public final class ControlDeskSeatLink {

    private static final Direction[] CARDINALS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private ControlDeskSeatLink() {}

    /**
     * 判定②：返回玩家当前骑乘的 Create 坐垫方块位置；未骑乘坐垫返回 null。
     * 判定依据：{@code player.getVehicle() instanceof SeatEntity}（坐垫实体固定在坐垫方块中心）。
     */
    public static BlockPos seatPosOf(Entity entity) {
        if (entity != null && entity.getVehicle() instanceof SeatEntity seat) {
            return seat.blockPosition();
        }
        return null;
    }

    /**
     * 判定①：坐垫四邻（N/E/S/W 紧邻 1 格）内所有 controlDesk 的方块实体（最多 4 个，按 N/E/S/W 顺序）。
     * 空列表 = 该坐垫未与任何 controlDesk 联动。
     */
    public static List<ControlDeskBlockEntity> findLinkedDesks(Level level, BlockPos seatPos) {
        List<ControlDeskBlockEntity> desks = new ArrayList<>(4);
        for (Direction dir : CARDINALS) {
            BlockPos neighbor = seatPos.relative(dir);
            if (level.getBlockState(neighbor).getBlock() instanceof ControlDeskBlock
                    && level.getBlockEntity(neighbor) instanceof ControlDeskBlockEntity desk) {
                desks.add(desk);
            }
        }
        return desks;
    }

    /** 操作模式 = 判定①（坐垫四邻有至少一个 controlDesk）+ 判定②（玩家骑乘坐垫）。 */
    public static boolean isOperating(Level level, Player player) {
        BlockPos seatPos = seatPosOf(player);
        return seatPos != null && !findLinkedDesks(level, seatPos).isEmpty();
    }
}
