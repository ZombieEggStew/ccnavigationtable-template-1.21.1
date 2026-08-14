package com.zzy205.myfirstmod.compat.cc;

import com.zzy205.myfirstmod.block.MonitorBlockEntity;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

/**
 * Monitor 的 CC:Tweaked 外设实现。
 * <p>
 * 通过 {@code pe.getPeripheral(ch)} 获取；目前为空外设，暂无 Lua 方法。
 */
public class MonitorPeripheral implements IPeripheral {

    private final MonitorBlockEntity be;

    public MonitorPeripheral(MonitorBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "ccpe:monitor";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (other instanceof MonitorPeripheral that) {
            return this.be.getBlockPos().equals(that.be.getBlockPos());
        }
        return false;
    }

    public MonitorBlockEntity getBlockEntity() {
        return be;
    }
}
