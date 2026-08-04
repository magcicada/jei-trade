package net.xuwu.jei_trade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class TradeSyncPacket {
    private final List<TradeRecipe> recipes;

    public TradeSyncPacket(List<TradeRecipe> recipes) {
        this.recipes = List.copyOf(recipes);
    }

    public static void encode(TradeSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.recipes.size());
        packet.recipes.forEach(recipe -> recipe.write(buf));
    }

    public static TradeSyncPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), Config.MAX_CATALOG_ENTRIES.get());
        List<TradeRecipe> recipes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) recipes.add(TradeRecipe.read(buf));
        return new TradeSyncPacket(recipes);
    }

    public static void handle(TradeSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientTradeCatalog.replace(packet.recipes));
        context.setPacketHandled(true);
    }
}
