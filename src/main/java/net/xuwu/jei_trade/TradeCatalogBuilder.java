package net.xuwu.jei_trade;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a deterministic catalog from the actual registered villager trade factories. */
public final class TradeCatalogBuilder {
    private static final int NORMAL_VARIANT_SAMPLES = 1;
    private static final int EXTENDED_VARIANT_SAMPLES = 100;
    private static final int NORMAL_MERCHANT_ENTITY_SAMPLES = 1;
    private static final int EXTENDED_MERCHANT_ENTITY_SAMPLES = EXTENDED_VARIANT_SAMPLES;
    private static final int MERCHANT_MIN_SAMPLES = 20;
    private static final int MERCHANT_UNCHANGED_LIMIT = 16;
    private static final int MAX_VARIANTS = 256;

    private TradeCatalogBuilder() {
    }

    /** Builds synchronously for explicit tooling. Runtime code uses {@link Session#advance(long)}. */
    public static List<TradeRecipe> build(Level level) {
        Session session = new Session(level, false);
        while (!session.advance(Long.MAX_VALUE / 2)) {
            // An unlimited budget normally completes in one call; keep this safe if that changes.
        }
        return session.snapshot();
    }

    public static Session session(Level level) {
        return new Session(level, false);
    }

    /** Creates the command-only rebuild mode with the full 100-sample random discovery pass. */
    public static Session extendedSession(Level level) {
        return new Session(level, true);
    }

    /**
     * Cooperative catalog builder. Minecraft entity/world calls remain on the server thread, but a
     * call advances by only one preemptible sampling unit before checking the time budget again.
     */
    public static final class Session {
        private final Level level;
        private final boolean extendedSampling;
        private final List<ListingTask> listingTasks = new ArrayList<>();
        private final List<EntityType<?>> entityTypes = new ArrayList<>();
        private final Map<String, TradeRecipe> exactEntries = new LinkedHashMap<>();
        private final Map<String, TradeAccumulator> sampledEntries = new LinkedHashMap<>();
        private int listingCursor;
        private int entityCursor;
        private MerchantTask merchantTask;
        private boolean complete;

        private Session(Level level, boolean extendedSampling) {
            this.level = level;
            this.extendedSampling = extendedSampling;
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
                        listingTasks.add(new ListingTask(villager, listings[index], villagerId, professionId,
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
                        listingTasks.add(new ListingTask(trader, listings[index], wanderingTraderId, null,
                                List.of(), levelNumber, seedFor(wanderingTraderId, levelNumber, index)));
                    }
                }
            }

            for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                if (entityType != EntityType.VILLAGER && entityType != EntityType.WANDERING_TRADER) {
                    entityTypes.add(entityType);
                }
            }
        }

        public boolean advance(long budgetNanos) {
            if (complete) return true;
            long deadline = budgetNanos >= Long.MAX_VALUE / 2
                    ? Long.MAX_VALUE
                    : System.nanoTime() + Math.max(1L, budgetNanos);
            boolean advanced = false;

            while (hasWork() && (!advanced || System.nanoTime() < deadline)) {
                if (listingCursor < listingTasks.size()) {
                    if (listingTasks.get(listingCursor).advance(this)) listingCursor++;
                } else {
                    if (merchantTask == null) merchantTask = new MerchantTask(entityTypes.get(entityCursor));
                    if (merchantTask.advance(this)) {
                        merchantTask = null;
                        entityCursor++;
                    }
                }
                advanced = true;
            }

            complete = !hasWork();
            return complete;
        }

        private boolean hasWork() {
            return listingCursor < listingTasks.size() || entityCursor < entityTypes.size();
        }

        private int variantSampleLimit() {
            return extendedSampling ? EXTENDED_VARIANT_SAMPLES : NORMAL_VARIANT_SAMPLES;
        }

        private int merchantSampleLimit() {
            return extendedSampling
                    ? EXTENDED_MERCHANT_ENTITY_SAMPLES
                    : NORMAL_MERCHANT_ENTITY_SAMPLES;
        }

        private void mergeExact(TradeRecipe recipe) {
            exactEntries.merge(recipe.fingerprint(), recipe, TradeRecipe::merge);
        }

        private boolean acceptOffer(ResourceLocation entityType, ResourceLocation profession,
                                    List<ResourceLocation> workstations, int merchantLevel,
                                    MerchantOffer offer) {
            String key = TradeRecipe.logicalFingerprint(entityType, profession, workstations, merchantLevel,
                    offer.getCostA(), offer.getCostB(), offer.getResult());
            TradeAccumulator accumulator = sampledEntries.get(key);
            if (accumulator == null) {
                sampledEntries.put(key, new TradeAccumulator(entityType, profession, workstations,
                        merchantLevel, offer));
                return true;
            }
            return accumulator.accept(offer);
        }

        public boolean isComplete() {
            return complete;
        }

        public int processedTasks() {
            return listingCursor + entityCursor;
        }

        public int totalTasks() {
            return listingTasks.size() + entityTypes.size();
        }

        public String progressDescription() {
            if (listingCursor < listingTasks.size()) {
                ListingTask task = listingTasks.get(listingCursor);
                return "trades " + listingCursor + "/" + listingTasks.size()
                        + ", sample " + task.sampleNumber() + "/" + variantSampleLimit();
            }
            int sample = merchantTask == null ? 0 : merchantTask.sampleNumber();
            return "entities " + entityCursor + "/" + entityTypes.size()
                    + (sample > 0 ? ", sample " + sample + "/" + merchantSampleLimit() : "");
        }

        public List<TradeRecipe> snapshot() {
            Map<String, TradeRecipe> result = new LinkedHashMap<>(exactEntries);
            for (TradeAccumulator accumulator : sampledEntries.values()) {
                TradeRecipe recipe = accumulator.build();
                result.merge(recipe.fingerprint(), recipe, TradeRecipe::merge);
            }
            return List.copyOf(result.values());
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
        private boolean resolverChecked;
        private int sample;

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

        private boolean advance(Session session) {
            if (!resolverChecked) {
                resolverChecked = true;
                TradeRecipe exact = TradeListingResolver.resolve(listing, entityType, profession, workstations, level);
                if (exact != null) {
                    session.mergeExact(exact);
                    return true;
                }
            }

            try {
                MerchantOffer offer = listing.getOffer(merchant, RandomSource.create(seed + sample * 131L));
                if (offer != null) {
                    if (offer.getResult().is(Items.ENCHANTED_BOOK)) {
                        session.mergeExact(expandEnchantedBookVariants(
                                TradeRecipe.fromOffer(entityType, profession, workstations, level, offer)));
                        return true;
                    }
                    session.acceptOffer(entityType, profession, workstations, level, offer);
                }
            } catch (RuntimeException ex) {
                Jei_trade.LOGGER.debug("Could not create example trade {} level {}", profession, level, ex);
            } finally {
                sample++;
            }
            return sample >= session.variantSampleLimit();
        }

        private int sampleNumber() {
            return sample;
        }
    }

    private static final class MerchantTask {
        private final EntityType<?> type;
        private int samples;
        private int unchangedSamples;

        private MerchantTask(EntityType<?> type) {
            this.type = type;
        }

        private boolean advance(Session session) {
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            Entity entity = null;
            try {
                entity = type.create(session.level);
                if (!(entity instanceof Merchant merchant)) return true;

                MerchantOffers offers = merchant.getOffers();
                List<MerchantOffer> declaredOffers = findDeclaredMerchantOffers(entity, type, samples);
                ResourceLocation professionId = null;
                List<ResourceLocation> workstations = List.of();
                int merchantLevel = 0;
                if (merchant instanceof VillagerDataHolder holder) {
                    VillagerProfession profession = holder.getVillagerData().getProfession();
                    professionId = session.level.registryAccess()
                            .registryOrThrow(Registries.VILLAGER_PROFESSION)
                            .getKey(profession);
                    workstations = findWorkstations(session.level, profession);
                    merchantLevel = holder.getVillagerData().getLevel();
                }

                boolean changed = false;
                if (offers != null) {
                    for (MerchantOffer offer : offers) {
                        changed |= session.acceptOffer(entityId, professionId, workstations, merchantLevel, offer);
                    }
                }
                for (MerchantOffer offer : declaredOffers) {
                    changed |= session.acceptOffer(entityId, professionId, workstations, merchantLevel, offer);
                }

                samples++;
                unchangedSamples = changed ? 0 : unchangedSamples + 1;
                return samples >= session.merchantSampleLimit()
                        || (samples >= MERCHANT_MIN_SAMPLES && unchangedSamples >= MERCHANT_UNCHANGED_LIMIT);
            } catch (RuntimeException | LinkageError ex) {
                Jei_trade.LOGGER.debug("Could not inspect merchant entity type {}", entityId, ex);
                return true;
            } finally {
                if (entity != null) entity.discard();
            }
        }

        private int sampleNumber() {
            return samples;
        }
    }

    private static final class TradeAccumulator {
        private final ResourceLocation entityType;
        private final ResourceLocation profession;
        private final List<ResourceLocation> workstations;
        private final int level;
        private final Map<String, ItemStack> buyA = new LinkedHashMap<>();
        private final Map<String, ItemStack> buyB = new LinkedHashMap<>();
        private final Map<String, ItemStack> result = new LinkedHashMap<>();
        private int buyAMin = Integer.MAX_VALUE;
        private int buyAMax;
        private int buyBMin = Integer.MAX_VALUE;
        private int buyBMax;
        private int resultMin = Integer.MAX_VALUE;
        private int resultMax;
        private int uses;
        private int maxUses;
        private int xp;
        private float priceMultiplier;
        private int demand;
        private int specialPrice;
        private boolean rewardExp;

        private TradeAccumulator(ResourceLocation entityType, ResourceLocation profession,
                                 List<ResourceLocation> workstations, int level, MerchantOffer offer) {
            this.entityType = entityType;
            this.profession = profession;
            this.workstations = List.copyOf(workstations);
            this.level = level;
            this.uses = offer.getUses();
            this.xp = offer.getXp();
            this.priceMultiplier = offer.getPriceMultiplier();
            this.demand = offer.getDemand();
            this.specialPrice = offer.getSpecialPriceDiff();
            this.rewardExp = offer.shouldRewardExp();
            accept(offer);
        }

        private boolean accept(MerchantOffer offer) {
            boolean changed = addVariant(buyA, offer.getCostA());
            changed |= addVariant(buyB, offer.getCostB());
            changed |= addVariant(result, offer.getResult());
            changed |= updateRanges(offer.getCostA(), offer.getCostB(), offer.getResult());
            if (offer.getMaxUses() > maxUses) {
                maxUses = offer.getMaxUses();
                changed = true;
            }
            return changed;
        }

        private boolean updateRanges(ItemStack costA, ItemStack costB, ItemStack output) {
            int oldBuyAMin = buyAMin;
            int oldBuyAMax = buyAMax;
            int oldBuyBMin = buyBMin;
            int oldBuyBMax = buyBMax;
            int oldResultMin = resultMin;
            int oldResultMax = resultMax;
            buyAMin = Math.min(buyAMin, count(costA));
            buyAMax = Math.max(buyAMax, count(costA));
            buyBMin = Math.min(buyBMin, count(costB));
            buyBMax = Math.max(buyBMax, count(costB));
            resultMin = Math.min(resultMin, count(output));
            resultMax = Math.max(resultMax, count(output));
            return oldBuyAMin != buyAMin || oldBuyAMax != buyAMax
                    || oldBuyBMin != buyBMin || oldBuyBMax != buyBMax
                    || oldResultMin != resultMin || oldResultMax != resultMax;
        }

        private TradeRecipe build() {
            return TradeRecipe.fromSamples(entityType, profession, workstations, level,
                    List.copyOf(buyA.values()), normalizedMin(buyAMin), buyAMax,
                    List.copyOf(buyB.values()), normalizedMin(buyBMin), buyBMax,
                    List.copyOf(result.values()), normalizedMin(resultMin), resultMax,
                    uses, maxUses, xp, priceMultiplier, demand, specialPrice, rewardExp, List.of());
        }

        private static boolean addVariant(Map<String, ItemStack> variants, ItemStack stack) {
            String key = TradeRecipe.stackFingerprintIgnoringCount(stack);
            if (variants.containsKey(key) || variants.size() >= MAX_VARIANTS) return false;
            variants.put(key, stack.copy());
            return true;
        }

        private static int normalizedMin(int value) {
            return value == Integer.MAX_VALUE ? 0 : value;
        }

        private static int count(ItemStack stack) {
            return stack == null || stack.isEmpty() ? 0 : stack.getCount();
        }
    }

    /** Reads Goblin Traders' public, data-driven trade table without making it a required dependency. */
    private static List<MerchantOffer> findDeclaredMerchantOffers(Entity entity, EntityType<?> type,
                                                                   int sample) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (entityId == null || !"goblintraders".equals(entityId.getNamespace())) return List.of();

        try {
            Class<?> managerClass = Class.forName("com.mrcrayfish.goblintraders.trades.TradeManager");
            Object manager = managerClass.getMethod("instance").invoke(null);
            Object entityTrades = managerClass.getMethod("getTrades", EntityType.class).invoke(manager, type);
            if (entityTrades == null) return List.of();

            java.lang.reflect.Method mapAccessor;
            try {
                mapAccessor = entityTrades.getClass().getMethod("getTradeMap");
            } catch (NoSuchMethodException ignored) {
                mapAccessor = entityTrades.getClass().getMethod("map");
            }
            Object rawMap = mapAccessor.invoke(entityTrades);
            if (!(rawMap instanceof Map<?, ?> tradeMap)) return List.of();

            List<MerchantOffer> found = new ArrayList<>();
            int index = 0;
            for (Object rawDefinitions : tradeMap.values()) {
                if (!(rawDefinitions instanceof Iterable<?> definitions)) continue;
                for (Object definition : definitions) {
                    RandomSource random = RandomSource.create(seedFor(entityId, sample, index++));
                    try {
                        MerchantOffer offer = declaredOffer(definition, entity, random);
                        if (offer != null) found.add(offer);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                        Jei_trade.LOGGER.debug("Could not inspect declared trade for {}", entityId, ex);
                    }
                }
            }
            return List.copyOf(found);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            Jei_trade.LOGGER.debug("Could not read declared trades for {}", entityId, ex);
            return List.of();
        }
    }

    private static MerchantOffer declaredOffer(Object definition, Entity entity, RandomSource random)
            throws ReflectiveOperationException {
        if (definition instanceof VillagerTrades.ItemListing listing) {
            return listing.getOffer(entity, random);
        }
        for (java.lang.reflect.Method method : definition.getClass().getMethods()) {
            if (method.getName().equals("createVanillaOffer") && method.getParameterCount() == 2) {
                Object offer = method.invoke(definition, entity, random);
                return offer instanceof MerchantOffer merchantOffer ? merchantOffer : null;
            }
        }
        return null;
    }

    private static TradeRecipe expandEnchantedBookVariants(TradeRecipe base) {
        List<ItemStack> books = new ArrayList<>();
        int minEmeralds = Integer.MAX_VALUE;
        int maxEmeralds = 0;
        for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) {
            if (!enchantment.isTradeable()) continue;
            for (int enchantmentLevel = enchantment.getMinLevel();
                 enchantmentLevel <= enchantment.getMaxLevel(); enchantmentLevel++) {
                books.add(EnchantedBookItem.createForEnchantment(
                        new EnchantmentInstance(enchantment, enchantmentLevel)));
                int minCost = 2 + enchantmentLevel * 3;
                int maxCost = Math.min(64, 6 + enchantmentLevel * 13);
                if (enchantment.isTreasureOnly()) {
                    minCost = Math.min(64, minCost * 2);
                    maxCost = Math.min(64, maxCost * 2);
                }
                minEmeralds = Math.min(minEmeralds, minCost);
                maxEmeralds = Math.max(maxEmeralds, maxCost);
            }
        }
        if (books.isEmpty()) return base;

        int buyAMin = base.buyAMin();
        int buyAMax = base.buyAMax();
        if (base.buyA().is(Items.EMERALD)) {
            buyAMin = minEmeralds;
            buyAMax = maxEmeralds;
        }
        return TradeRecipe.fromSamples(base.entityType(), base.profession(), base.workstations(), base.level(),
                base.buyAVariants(), buyAMin, buyAMax,
                base.buyBVariants(), base.buyBMin(), base.buyBMax(),
                books, 1, 1, base.uses(), base.maxUses(), base.xp(), base.priceMultiplier(),
                base.demand(), base.specialPrice(), base.rewardExp(), base.details());
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
