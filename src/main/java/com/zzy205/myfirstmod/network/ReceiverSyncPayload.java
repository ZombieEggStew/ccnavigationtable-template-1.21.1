package com.zzy205.myfirstmod.network;

import com.zzy205.myfirstmod.CCNavigationtable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 瀹㈡埛绔啋鏈嶅姟绔細淇濆瓨 receiver 鐨勫叏锟?banner 鏁版嵁锛堥锟?+ 骞界伒鐗╁搧锛夊拰鍔犺浇妯″紡锟?
 */
public record ReceiverSyncPayload(BlockPos pos, CompoundTag data, int loadMode) implements CustomPacketPayload {

    public static final Type<ReceiverSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CCNavigationtable.MOD_ID, "redstone_transceiver_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReceiverSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ReceiverSyncPayload::pos,
                    ByteBufCodecs.COMPOUND_TAG, ReceiverSyncPayload::data,
                    net.minecraft.network.codec.ByteBufCodecs.INT, ReceiverSyncPayload::loadMode,
                    ReceiverSyncPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
