package net.xuwu.jei_trade;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.trading.Merchant;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = Jei_trade.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerTradeEvents {
    private static final Map<MinecraftServer, TradeServerCatalog> CATALOGS = new WeakHashMap<>();

    private ServerTradeEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getAllLevels().iterator().next();
        TradeServerCatalog catalog = catalog(server);
        catalog.replace(TradeCatalogBuilder.build(level));
        Jei_trade.LOGGER.info("Built {} synchronized villager trade entries", catalog.snapshot().size());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Config.ENABLE_SERVER_CATALOG.get()) return;
        TradeServerCatalog catalog = catalog(player.server);
        if (catalog.snapshot().isEmpty() && player.server.getAllLevels().iterator().hasNext()) {
            catalog.replace(TradeCatalogBuilder.build(player.server.getAllLevels().iterator().next()));
        }
        TradeNetworking.send(player, catalog.snapshot());
    }

    @SubscribeEvent
    public static void onMerchantInteract(PlayerInteractEvent.EntityInteract event) {
        if (!Config.OBSERVE_MERCHANTS.get() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof Merchant merchant)) return;
        player.server.execute(() -> {
            if (merchant.getOffers() == null || merchant.getOffers().isEmpty()) return;
            TradeServerCatalog catalog = catalog(player.server);
            int level = merchant instanceof VillagerDataHolder holder
                    ? holder.getVillagerData().getLevel() : 0;
            java.util.List<TradeRecipe> before = catalog.snapshot();
            java.util.List<TradeRecipe> observed = new java.util.ArrayList<>();
            for (var offer : merchant.getOffers()) {
                net.minecraft.resources.ResourceLocation entityId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
                net.minecraft.resources.ResourceLocation professionId = null;
                java.util.List<net.minecraft.resources.ResourceLocation> workstations = java.util.List.of();
                if (merchant instanceof VillagerDataHolder holder) {
                    var profession = holder.getVillagerData().getProfession();
                    professionId = net.minecraftforge.registries.ForgeRegistries.VILLAGER_PROFESSIONS.getKey(profession);
                    workstations = TradeCatalogBuilder.findWorkstations(target.level(), profession);
                }
                observed.add(TradeRecipe.fromOffer(entityId, professionId, workstations, level, offer));
            }
            if (!catalog.addAll(observed)) return;
            if (catalog.snapshot().size() != before.size()) {
                for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                    TradeNetworking.send(online, catalog.snapshot());
                }
            }
        });
    }

    private static synchronized TradeServerCatalog catalog(MinecraftServer server) {
        return CATALOGS.computeIfAbsent(server, ignored -> new TradeServerCatalog());
    }
}
