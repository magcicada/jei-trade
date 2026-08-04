package net.xuwu.jei_trade;

import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side catalog used by the JEI advanced recipe manager plugin. */
public final class ClientTradeCatalog {
    private static final Map<String, TradeRecipe> ENTRIES = new LinkedHashMap<>();
    private static boolean fallbackBuilt;
    private static boolean serverAuthoritative;
    private static TradeCatalogBuilder.Session fallbackSession;

    private ClientTradeCatalog() {
    }

    public static synchronized void replace(List<TradeRecipe> recipes) {
        ENTRIES.clear();
        for (TradeRecipe recipe : recipes) add(recipe);
        fallbackSession = null;
        fallbackBuilt = true;
        serverAuthoritative = true;
    }

    public static synchronized void ensureFallback(Level level) {
        if (!fallbackBuilt && !serverAuthoritative && level != null && fallbackSession == null) {
            fallbackSession = TradeCatalogBuilder.session(level);
        }
    }

    /** Disables the client-side fallback once a live server connection can provide the catalog. */
    public static synchronized void preferServerCatalog() {
        if (serverAuthoritative) return;
        ENTRIES.clear();
        fallbackSession = null;
        fallbackBuilt = true;
    }

    public static synchronized void advanceFallback(Level level) {
        ensureFallback(level);
        if (serverAuthoritative || fallbackSession == null) return;
        if (fallbackSession.advance(2_000_000L)) {
            addAll(fallbackSession.snapshot());
            fallbackSession = null;
            fallbackBuilt = true;
        }
    }

    public static synchronized void observeMerchant(Merchant merchant, int level) {
        if (merchant == null || merchant.getOffers() == null) return;
        net.minecraft.resources.ResourceLocation entityId = null;
        net.minecraft.resources.ResourceLocation professionId = null;
        List<net.minecraft.resources.ResourceLocation> workstations = List.of();
        if (merchant instanceof net.minecraft.world.entity.Entity entity) {
            entityId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (merchant instanceof VillagerDataHolder holder) {
                var profession = holder.getVillagerData().getProfession();
                professionId = net.minecraftforge.registries.ForgeRegistries.VILLAGER_PROFESSIONS.getKey(profession);
                if (entity.level() != null) workstations = TradeCatalogBuilder.findWorkstations(entity.level(), profession);
            }
        }
        for (MerchantOffer offer : merchant.getOffers()) {
            add(TradeRecipe.fromOffer(entityId, professionId, workstations, level, offer));
        }
    }

    public static synchronized void observeOffers(MerchantOffers offers, int level) {
        if (serverAuthoritative || offers == null) return;
        for (MerchantOffer offer : offers) {
            add(TradeRecipe.fromOffer(null, null, List.of(), level, offer));
        }
    }

    public static synchronized void addAll(List<TradeRecipe> recipes) {
        for (TradeRecipe recipe : recipes) add(recipe);
    }

    public static synchronized void add(TradeRecipe recipe) {
        ENTRIES.merge(recipe.fingerprint(), recipe, TradeRecipe::merge);
    }

    public static synchronized List<TradeRecipe> snapshot() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static synchronized List<TradeRecipeGroup> snapshotGroups() {
        return TradeRecipeGroup.buildPages(snapshot());
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        fallbackSession = null;
        fallbackBuilt = false;
        serverAuthoritative = false;
    }

    public static synchronized boolean isServerAuthoritative() {
        return serverAuthoritative;
    }
}
