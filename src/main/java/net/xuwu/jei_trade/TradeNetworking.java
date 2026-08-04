package net.xuwu.jei_trade;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public final class TradeNetworking {
    private static final String PROTOCOL = "1";
    private static int packetId;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Jei_trade.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private TradeNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(packetId++, TradeSyncPacket.class,
                TradeSyncPacket::encode, TradeSyncPacket::decode, TradeSyncPacket::handle);
    }

    public static void send(ServerPlayer player, List<TradeRecipe> recipes) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TradeSyncPacket(recipes));
    }
}
