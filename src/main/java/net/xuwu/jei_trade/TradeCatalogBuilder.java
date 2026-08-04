package net.xuwu.jei_trade;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Builds a deterministic catalog from the actual registered villager trade factories. */
public final class TradeCatalogBuilder {
    private static final int RANDOM_VARIANT_SAMPLES = 8;

    private TradeCatalogBuilder() {
    }

    /**
     * Builds the complete catalog synchronously for callers that explicitly need it.
     * Server/client lifecycle code should use {@link Session#advance(long)} instead.
     */
    public static List<TradeRecipe> build(Level level) {
        Session session = new Session(level);
        session.advance(Long.MAX_VALUE / 2);
        return session.snapshot();
    }

    public static Session session(Level level) {
        return new Session(level);
    }

    /** Incremental catalog builder that performs all Minecraft calls on its owning game thread. */
    public static final class Session {
        private final List<ListingTask> tasks = new ArrayList<>();
        private final Map<String, TradeRecipe> entries = new LinkedHashMap<>();
        private int cursor;
        private boolean complete;

        private Session(Level level) {
            Registry<VillagerProfession> professions = level.registryAccess()
                    .registryOrThrow(Registries.VILLAGER_PROFESSION);
            ResourceLocation villagerId = new ResourceLocation("minecraft", "villager");
            for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> professionEntry
                    : VillagerTrades.TRADES.entrySet()) {
                VillagerProfession profession = professionEntry.getKey();
                ResourceLocation professionId = professions.getKey(profession);
                if (professionId == null) continue;
                List<ResourceLocation> workstations = findWorkstations(level, profession);
                for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry
                        : professionEntry.getValue().int2ObjectEntrySet()) {
                    int levelNumber = levelEntry.getIntKey();
                    VillagerTrades.ItemListing[] listings = levelEntry.getValue();
                    Villager villager = EntityType.VILLAGER.create(level);
                    if (villager == null) continue;
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, levelNumber));
                    for (int index = 0; index < listings.length; index++) {
                        tasks.add(new ListingTask(villager, listings[index], villagerId, professionId,
                                workstations, levelNumber, seedFor(professionId, levelNumber, index)));
                    }
                }
            }

            ResourceLocation wanderingTraderId = new ResourceLocation("minecraft", "wandering_trader");
            WanderingTrader trader = EntityType.WANDERING_TRADER.create(level);
            if (trader != null) {
                for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry
                        : VillagerTrades.WANDERING_TRADER_TRADES.int2ObjectEntrySet()) {
                    int levelNumber = levelEntry.getIntKey();
                    VillagerTrades.ItemListing[] listings = levelEntry.getValue();
                    for (int index = 0; index < listings.length; index++) {
                        tasks.add(new ListingTask(trader, listings[index], wanderingTraderId, null,
                                List.of(), levelNumber, seedFor(wanderingTraderId, levelNumber, index)));
                    }
                }
            }
        }

        public boolean advance(long budgetNanos) {
            if (complete) return true;
            if (cursor >= tasks.size()) {
                complete = true;
                return true;
            }

            long deadline = budgetNanos >= Long.MAX_VALUE / 2
                    ? Long.MAX_VALUE
                    : System.nanoTime() + Math.max(1L, budgetNanos);
            int start = cursor;
            do {
                ListingTask task = tasks.get(cursor++);
                TradeRecipe recipe = buildListingVariants(task.merchant, task.listing,
                        task.entityType, task.profession, task.workstations, task.level, task.seed);
                if (recipe != null) {
                    entries.merge(recipe.fingerprint(), recipe, TradeRecipe::merge);
                }
            } while (cursor < tasks.size() && (cursor == start + 1 || System.nanoTime() < deadline));

            complete = cursor >= tasks.size();
            return complete;
        }

        public boolean isComplete() {
            return complete;
        }

        public int processedTasks() {
            return cursor;
        }

        public int totalTasks() {
            return tasks.size();
        }

        public List<TradeRecipe> snapshot() {
            return List.copyOf(entries.values());
        }
    }

    private static final class ListingTask {
        private final Entity merchant;
        private final VillagerTrades.ItemListing listing;
        private final ResourceLocation entityType;
        private final ResourceLocation profession;
        private final List<ResourceLocation> workstations;
        private final int level;
        private final long seed;

        private ListingTask(Entity merchant, VillagerTrades.ItemListing listing,
                            ResourceLocation entityType, ResourceLocation profession,
                            List<ResourceLocation> workstations, int level, long seed) {
            this.merchant = merchant;
            this.listing = listing;
            this.entityType = entityType;
            this.profession = profession;
            this.workstations = workstations;
            this.level = level;
            this.seed = seed;
        }
    }

    private static TradeRecipe buildListingVariants(Entity merchant,
                                                    VillagerTrades.ItemListing listing,
                                                    ResourceLocation entityType,
                                                    ResourceLocation profession,
                                                    List<ResourceLocation> workstations,
                                                    int level, long seed) {
        TradeRecipe exact = TradeListingResolver.resolve(listing, entityType, profession, workstations, level);
        if (exact != null) return exact;

        List<TradeRecipe> variants = new ArrayList<>();
        for (int sample = 0; sample < RANDOM_VARIANT_SAMPLES; sample++) {
            try {
                MerchantOffer offer = listing.getOffer(merchant, RandomSource.create(seed + sample * 131L));
                if (offer == null) continue;
                TradeRecipe recipe = TradeRecipe.fromOffer(entityType, profession, workstations, level, offer);
                if (offer.getResult().is(Items.ENCHANTED_BOOK)) {
                    // The enchantment itself is expanded exhaustively; further random samples are redundant.
                    return mergeVariants(expandEnchantedBookVariants(recipe));
                }
                variants.add(recipe);
            } catch (RuntimeException ex) {
                Jei_trade.LOGGER.debug("Could not create example trade {} level {}", profession, level, ex);
            }
        }
        return mergeVariants(variants);
    }

    private static TradeRecipe mergeVariants(List<TradeRecipe> variants) {
        if (variants.isEmpty()) return null;
        TradeRecipe merged = variants.get(0);
        for (int i = 1; i < variants.size(); i++) merged = TradeRecipe.merge(merged, variants.get(i));
        return merged;
    }

    private static List<TradeRecipe> expandEnchantedBookVariants(TradeRecipe base) {
        List<TradeRecipe> result = new ArrayList<>();
        for (Enchantment enchantment : net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT) {
            if (!enchantment.isTradeable()) continue;
            for (int level = enchantment.getMinLevel(); level <= enchantment.getMaxLevel(); level++) {
                ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantment, level));
                int minCost = 2 + level * 3;
                int maxCost = Math.min(64, 6 + level * 13);
                if (enchantment.isTreasureOnly()) {
                    minCost = Math.min(64, minCost * 2);
                    maxCost = Math.min(64, maxCost * 2);
                }
                ItemStack lowCost = base.buyA();
                ItemStack highCost = base.buyA();
                if (lowCost.is(Items.EMERALD)) {
                    lowCost.setCount(minCost);
                    highCost.setCount(maxCost);
                }
                result.add(TradeRecipe.withVariantStacks(base, lowCost, base.buyB(), book));
                result.add(TradeRecipe.withVariantStacks(base, highCost, base.buyB(), book));
            }
        }
        return result.isEmpty() ? List.of(base) : result;
    }

    public static List<ResourceLocation> findWorkstations(Level level, VillagerProfession profession) {
        Registry<PoiType> poiRegistry = level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE);
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (Map.Entry<ResourceKey<PoiType>, PoiType> entry : poiRegistry.entrySet()) {
            ResourceKey<PoiType> key = entry.getKey();
            var holderOptional = poiRegistry.getHolder(key);
            if (holderOptional.isEmpty()) continue;
            Holder<PoiType> holder = holderOptional.get();
            if (!profession.heldJobSite().test(holder)) continue;
            for (BlockState state : entry.getValue().matchingStates()) {
                Block block = state.getBlock();
                if (!block.asItem().equals(Items.AIR)) {
                    ResourceLocation blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
                    if (blockId != null) ids.add(blockId);
                }
            }
        }
        return List.copyOf(ids);
    }

    public static List<TradeRecipe> deduplicate(List<TradeRecipe> recipes) {
        Map<String, TradeRecipe> unique = new LinkedHashMap<>();
        for (TradeRecipe recipe : recipes) unique.merge(recipe.fingerprint(), recipe, TradeRecipe::merge);
        return new ArrayList<>(unique.values());
    }

    private static long seedFor(ResourceLocation profession, int level, int index) {
        long seed = profession.hashCode() * 31L + level * 997L + index * 131L;
        return seed ^ 0x5DEECE66DL;
    }
}
