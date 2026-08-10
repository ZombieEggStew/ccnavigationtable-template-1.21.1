package com.zzy205.myfirstmod.monitor;

/**
 * 仪表盘上已安装的模块数据（不可变记录）。
 */
public record MonitorModule(int id, ModuleType type, int gridX, int gridY) {

    public int getWidth() {
        return type.width;
    }

    public int getHeight() {
        return type.height;
    }
}
