package net.xuwu.jei_trade;

import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side catalog used by the JEI advanced recipe manager plugin. */
public final class ClientTradeCatalog {
    private static final Map<String, TradeRecipe> ENTRIES = new LinkedHashMap<>();
    private static volatile List<TradeRecipeGroup> GROUPS = List.of();
    private static volatile long REVISION;
    private static boolean serverAuthoritative;
    private static MerchantOffers lastObservedOffers;
    private static int lastObservedSignature;
    private static int lastObservedLevel;

    private ClientTradeCatalog() {
    }

    public static synchronized void replace(List<TradeRecipe> recipes) {
        ENTRIES.clear();
        if (recipes != null) {
            for (TradeRecipe recipe : recipes) mergeEntry(recipe);
        }
        serverAuthoritative = true;
        resetObservedOffers();
        rebuildGroups();
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
        boolean changed = false;
        for (MerchantOffer offer : merchant.getOffers()) {
            changed |= mergeEntry(TradeRecipe.fromOffer(entityId, professionId, workstations, level, offer));
        }
        if (changed) rebuildGroups();
    }

    public static synchronized void observeOffers(MerchantOffers offers, int level) {
        if (serverAuthoritative || offers == null) return;
        int signature = offerSignature(offers, level);
        if (offers == lastObservedOffers && signature == lastObservedSignature
                && level == lastObservedLevel) return;
        lastObservedOffers = offers;
        lastObservedSignature = signature;
        lastObservedLevel = level;

        boolean changed = false;
        for (MerchantOffer offer : offers) {
            changed |= mergeEntry(TradeRecipe.fromOffer(null, null, List.of(), level, offer));
        }
        if (changed) rebuildGroups();
    }

    public static synchronized void addAll(List<TradeRecipe> recipes) {
        boolean changed = false;
        for (TradeRecipe recipe : recipes) changed |= mergeEntry(recipe);
        if (changed) rebuildGroups();
    }

    public static synchronized void add(TradeRecipe recipe) {
        if (mergeEntry(recipe)) rebuildGroups();
    }

    public static synchronized List<TradeRecipe> snapshot() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static synchronized List<TradeRecipeGroup> snapshotGroups() {
        return GROUPS;
    }

    public static synchronized void clear() {
        boolean changed = !ENTRIES.isEmpty() || !GROUPS.isEmpty() || serverAuthoritative;
        ENTRIES.clear();
        serverAuthoritative = false;
        resetObservedOffers();
        if (changed) rebuildGroups();
    }

    public static synchronized boolean isServerAuthoritative() {
        return serverAuthoritative;
    }

    public static long revision() {
        return REVISION;
    }

    private static boolean mergeEntry(TradeRecipe recipe) {
        if (recipe == null) return false;
        String key = recipe.fingerprint();
        TradeRecipe previous = ENTRIES.get(key);
        if (previous == null) {
            ENTRIES.put(key, recipe);
            return true;
        }

        TradeRecipe merged = TradeRecipe.merge(previous, recipe);
        if (merged == previous) return false;
        ENTRIES.put(key, merged);
        return true;
    }

    private static void rebuildGroups() {
        GROUPS = List.copyOf(TradeRecipeGroup.buildPages(new ArrayList<>(ENTRIES.values())));
        REVISION++;
    }

    private static void resetObservedOffers() {
        lastObservedOffers = null;
        lastObservedSignature = 0;
        lastObservedLevel = 0;
    }

    private static int offerSignature(MerchantOffers offers, int level) {
        int signature = 31 + level;
        for (MerchantOffer offer : offers) {
            signature = 31 * signature + offer.getCostA().hashCode();
            signature = 31 * signature + offer.getCostB().hashCode();
            signature = 31 * signature + offer.getResult().hashCode();
            signature = 31 * signature + offer.getMaxUses();
            signature = 31 * signature + offer.getDemand();
            signature = 31 * signature + offer.getSpecialPriceDiff();
            signature = 31 * signature + Float.floatToIntBits(offer.getPriceMultiplier());
            signature = 31 * signature + (offer.shouldRewardExp() ? 1 : 0);
        }
        return signature;
    }
}
