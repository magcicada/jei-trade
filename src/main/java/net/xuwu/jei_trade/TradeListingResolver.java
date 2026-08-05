package net.xuwu.jei_trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.SuspiciousStewItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the fields of the vanilla 1.20.1 trade factories directly, following
 * Advanced Loot Info's typed-listing approach. Unknown factories remain
 * compatible through the sampler in {@link TradeCatalogBuilder}.
 */
final class TradeListingResolver {
    private TradeListingResolver() {
    }

    static TradeRecipe resolve(VillagerTrades.ItemListing listing,
                               ResourceLocation entityType, ResourceLocation profession,
                               List<ResourceLocation> workstations,
                               int level) {
        if (listing instanceof VillagerTrades.DyedArmorForEmeralds trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), trade.value,
                    List.of(ItemStack.EMPTY), 0,
                    List.of(trade.item.getDefaultInstance()), 1,
                    trade.maxUses, trade.villagerXp, 0.2F,
                    List.of(Component.translatable("jei_trade.detail.random_dye")));
        }
        if (listing instanceof VillagerTrades.EmeraldForItems trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(trade.item.getDefaultInstance()), trade.cost,
                    List.of(ItemStack.EMPTY), 0,
                    List.of(new ItemStack(Items.EMERALD)), 1,
                    trade.maxUses, trade.villagerXp, trade.priceMultiplier, List.of());
        }
        if (listing instanceof VillagerTrades.EmeraldsForVillagerTypeItem trade) {
            List<ItemStack> outputs = trade.trades.values().stream()
                    .map(item -> item.getDefaultInstance())
                    .distinct()
                    .toList();
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), trade.cost,
                    List.of(ItemStack.EMPTY), 0,
                    outputs, 1,
                    trade.maxUses, trade.villagerXp, 0.2F,
                    List.of(Component.translatable("jei_trade.detail.villager_type_output")));
        }
        if (listing instanceof VillagerTrades.EnchantBookForEmeralds trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), 5, 64,
                    List.of(ItemStack.EMPTY), 0,
                    enchantedBookVariants(), 1,
                    12, trade.villagerXp, 0.2F,
                    List.of(Component.translatable("jei_trade.detail.random_book_enchant")));
        }
        if (listing instanceof VillagerTrades.EnchantedItemForEmeralds trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), trade.baseEmeraldCost + 5,
                    trade.baseEmeraldCost + 19,
                    List.of(ItemStack.EMPTY), 0,
                    List.of(trade.itemStack.copy()), trade.itemStack.getCount(),
                    trade.maxUses, trade.villagerXp,
                    trade.priceMultiplier,
                    List.of(Component.translatable("jei_trade.detail.random_enchant", 5, 19),
                            treasureDetail(trade.itemStack)));
        }
        if (listing instanceof VillagerTrades.ItemsAndEmeraldsToItems trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(trade.fromItem.copy()), trade.fromCount,
                    List.of(new ItemStack(Items.EMERALD)), trade.emeraldCost,
                    List.of(trade.toItem.copy()), trade.toCount,
                    trade.maxUses, trade.villagerXp, trade.priceMultiplier, List.of());
        }
        if (listing instanceof VillagerTrades.ItemsForEmeralds trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), trade.emeraldCost,
                    List.of(ItemStack.EMPTY), 0,
                    List.of(trade.itemStack.copy()), trade.numberOfItems,
                    trade.maxUses, trade.villagerXp, trade.priceMultiplier, List.of());
        }
        if (listing instanceof VillagerTrades.SuspiciousStewForEmerald trade) {
            ItemStack stew = Items.SUSPICIOUS_STEW.getDefaultInstance();
            SuspiciousStewItem.saveMobEffect(stew, trade.effect, trade.duration);
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), 1,
                    List.of(ItemStack.EMPTY), 0,
                    List.of(stew), 1,
                    12, trade.xp, trade.priceMultiplier,
                    List.of(Component.translatable("jei_trade.detail.stew_effect",
                            Component.translatable(trade.effect.getDescriptionId()), trade.duration)));
        }
        if (listing instanceof VillagerTrades.TippedArrowForItemsAndEmeralds trade) {
            return definition(entityType, profession, workstations, level,
                    List.of(trade.fromItem.getDefaultInstance()), trade.fromCount,
                    List.of(new ItemStack(Items.EMERALD)), trade.emeraldCost,
                    List.of(trade.toItem.copy()), trade.toCount,
                    trade.maxUses, trade.villagerXp, trade.priceMultiplier, List.of());
        }
        if (listing instanceof VillagerTrades.TreasureMapForEmeralds trade) {
            ItemStack map = Items.MAP.getDefaultInstance();
            map.setHoverName(Component.translatable(trade.displayName));
            return definition(entityType, profession, workstations, level,
                    List.of(new ItemStack(Items.EMERALD)), trade.emeraldCost,
                    List.of(new ItemStack(Items.COMPASS)), 1,
                    List.of(map), 1,
                    trade.maxUses, trade.villagerXp, 0.2F,
                    List.of(Component.translatable("jei_trade.detail.treasure_map",
                            Component.translatable(trade.displayName))));
        }
        // Unknown map listings are handled structurally so optional integrations do not need to
        // depend on a particular mod or call a worldgen-backed getOffer() just to render JEI.
        return MapTradeListingResolver.resolve(listing, entityType, profession, workstations, level);
    }

    private static TradeRecipe definition(ResourceLocation entityType, ResourceLocation profession,
                                          List<ResourceLocation> workstations, int level,
                                          List<ItemStack> buyA, int buyACount,
                                          List<ItemStack> buyB, int buyBCount,
                                          List<ItemStack> result, int resultCount,
                                          int maxUses, int xp, float priceMultiplier,
                                          List<Component> details) {
        return definition(entityType, profession, workstations, level,
                buyA, buyACount, buyACount, buyB, buyBCount, buyBCount,
                result, resultCount, resultCount, maxUses, xp, priceMultiplier, details);
    }

    private static TradeRecipe definition(ResourceLocation entityType, ResourceLocation profession,
                                          List<ResourceLocation> workstations, int level,
                                          List<ItemStack> buyA, int buyAMin, int buyAMax,
                                          List<ItemStack> buyB, int buyBCount,
                                          List<ItemStack> result, int resultCount,
                                          int maxUses, int xp, float priceMultiplier,
                                          List<Component> details) {
        return definition(entityType, profession, workstations, level,
                buyA, buyAMin, buyAMax, buyB, buyBCount, buyBCount,
                result, resultCount, resultCount, maxUses, xp, priceMultiplier, details);
    }

    private static TradeRecipe definition(ResourceLocation entityType, ResourceLocation profession,
                                          List<ResourceLocation> workstations, int level,
                                          List<ItemStack> buyA, int buyAMin, int buyAMax,
                                          List<ItemStack> buyB, int buyBMin, int buyBMax,
                                          List<ItemStack> result, int resultMin, int resultMax,
                                          int maxUses, int xp, float priceMultiplier,
                                          List<Component> details) {
        return TradeRecipe.fromDefinition(entityType, profession, workstations, level,
                buyA, buyAMin, buyAMax, buyB, buyBMin, buyBMax,
                result, resultMin, resultMax, maxUses, xp, priceMultiplier, details);
    }

    private static List<ItemStack> enchantedBookVariants() {
        List<ItemStack> variants = new ArrayList<>();
        for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) {
            if (!enchantment.isTradeable()) continue;
            for (int level = enchantment.getMinLevel(); level <= enchantment.getMaxLevel(); level++) {
                variants.add(EnchantedBookItem.createForEnchantment(
                        new EnchantmentInstance(enchantment, level)));
                if (variants.size() >= 256) return variants;
            }
        }
        return variants.isEmpty() ? List.of(new ItemStack(Items.ENCHANTED_BOOK)) : variants;
    }

    private static Component treasureDetail(ItemStack item) {
        boolean hasCandidate = false;
        boolean hasTreasure = false;
        boolean hasNonTreasure = false;
        for (EnchantmentInstance candidate : EnchantmentHelper.getAvailableEnchantmentResults(19, item, false)) {
            hasCandidate = true;
            if (candidate.enchantment.isTreasureOnly()) {
                hasTreasure = true;
            } else {
                hasNonTreasure = true;
            }
        }
        return Component.translatable("jei_trade.detail.treasure_enchant",
                Component.translatable(treasureStatusKey(hasCandidate, hasTreasure, hasNonTreasure)));
    }

    private static String treasureStatusKey(boolean hasCandidate, boolean hasTreasure,
                                             boolean hasNonTreasure) {
        if (!hasCandidate) return "jei_trade.unknown";
        if (hasTreasure && !hasNonTreasure) return "jei_trade.yes";
        if (!hasTreasure && hasNonTreasure) return "jei_trade.no";
        return "jei_trade.possible";
    }
}
