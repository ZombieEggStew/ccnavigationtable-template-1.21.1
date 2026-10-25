package com.zzy205.myfirstmod.particle;

/**
 * 尾迹粒子的<b>存活计数</b>（客户端单线程使用，故意放在 common 包、只有 int 字段）。
 * <p>
 * 为什么需要它：尾迹寿命是用户自己调的（现在 12000~18000 tick），而同屏粒子数 ≈ 发射密度 × 寿命
 * ⇒ 4 管满载可以轻松堆到几万颗半透明 quad，直接拖垮帧率。这里给一个"存活上限"的安全阀：
 * 计数满了就停止发射，直到旧粒子自然死亡腾出配额。
 * <p>
 * 放在 common 包、只依赖 {@code int}：{@code block.ThrustNozzleBlockEntity} 要读它，
 * 如果直接读 {@code client.particle.ContrailParticle}，专用服务器上会尝试加载客户端类。
 * 计数由 {@code ContrailParticle} 的构造/死亡维护，维度切换时由客户端 tick 重置。
 */
public final class ContrailBudget {

    private static int live;

    private ContrailBudget() {
    }

    /** 当前存活（客户端已生成、尚未移除）的尾迹粒子数 */
    public static int live() {
        return live;
    }

    /** 新粒子生成时调用 */
    public static void onSpawn() {
        live++;
    }

    /** 粒子被移除时调用（带下限保护，避免重复移除把计数打成负数） */
    public static void onDeath() {
        if (live > 0) {
            live--;
        }
    }

    /** 重置（客户端换维度/换世界时清空，否则计数会一直卡在上限） */
    public static void reset() {
        live = 0;
    }
}
