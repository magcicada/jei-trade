package net.xuwu.jei_trade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.xuwu.jei_trade.client.TradeJeiPlugin;

@Mod.EventBusSubscriber(modid = Jei_trade.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientTradeEvents {
    private ClientTradeEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) ClientTradeCatalog.ensureFallback(minecraft.level);
        if (minecraft.screen instanceof MerchantScreen screen) {
            MerchantMenu menu = screen.getMenu();
            ClientTradeCatalog.observeOffers(menu.getOffers(), menu.getTraderLevel());
        }
        TradeJeiPlugin.refreshRecipes();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTradeCatalog.clear();
    }
}
