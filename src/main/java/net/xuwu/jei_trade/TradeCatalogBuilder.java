package net.xuwu.jei_trade;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a deterministic catalog from the actual registered villager trade factories. */
public final class TradeCatalogBuilder {
    private TradeCatalogBuilder() {
    }

    public static List<TradeRecipe> build(Level level) {
        List<TradeRecipe> result = new ArrayList<>();
        Registry<VillagerProfession> professions = level.registryAccess().registryOrThrow(Registries.VILLAGER_PROFESSION);
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> professionEntry : VillagerTrades.TRADES.entrySet()) {
            VillagerProfession profession = professionEntry.getKey();
            ResourceLocation professionId = professions.getKey(profession);
            if (professionId == null) continue;
            List<ResourceLocation> workstations = findWorkstations(level, profession);
            for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry : professionEntry.getValue().int2ObjectEntrySet()) {
                int levelNumber = levelEntry.getIntKey();
                VillagerTrades.ItemListing[] listings = levelEntry.getValue();
                for (int index = 0; index < listings.length; index++) {
                    Villager villager = EntityType.VILLAGER.create(level);
                    if (villager == null) continue;
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, levelNumber));
                    try {
                        MerchantOffer offer = listings[index].getOffer(villager,
                                RandomSource.create(seedFor(professionId, levelNumber, index)));
                        if (offer != null) {
                            result.add(TradeRecipe.fromOffer(new ResourceLocation("minecraft", "villager"),
                                    professionId, workstations, levelNumber, offer));
                        }
                    } catch (RuntimeException ex) {
                        Jei_trade.LOGGER.debug("Could not create example trade {} level {}", professionId, levelNumber, ex);
                    }
                }
            }
        }
        return deduplicate(result);
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
                if (!block.asItem().equals(net.minecraft.world.item.Items.AIR)) {
                    ResourceLocation blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
                    if (blockId != null) ids.add(blockId);
                }
            }
        }
        return List.copyOf(ids);
    }

    public static List<TradeRecipe> deduplicate(List<TradeRecipe> recipes) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<TradeRecipe> result = new ArrayList<>();
        for (TradeRecipe recipe : recipes) {
            if (seen.add(recipe.fingerprint())) result.add(recipe);
        }
        return result;
    }

    private static long seedFor(ResourceLocation profession, int level, int index) {
        long seed = profession.hashCode() * 31L + level * 997L + index * 131L;
        return seed ^ 0x5DEECE66DL;
    }
}
