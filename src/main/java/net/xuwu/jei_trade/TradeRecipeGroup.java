package net.xuwu.jei_trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A JEI-facing profession/merchant view made from the synchronized offer list. */
public final class TradeRecipeGroup {
    public static final int ROWS_PER_PAGE = 6;

    private final String groupKey;
    private final ResourceLocation entityType;
    private final ResourceLocation profession;
    private final List<ResourceLocation> workstations;
    private final List<TradeRecipe> trades;
    private final int page;
    private final int pageCount;
    private final int totalTrades;

    private TradeRecipeGroup(String groupKey, ResourceLocation entityType, ResourceLocation profession,
                             List<ResourceLocation> workstations, List<TradeRecipe> trades,
                             int page, int pageCount, int totalTrades) {
        this.groupKey = groupKey;
        this.entityType = entityType;
        this.profession = profession;
        this.workstations = List.copyOf(workstations);
        this.trades = List.copyOf(trades);
        this.page = page;
        this.pageCount = pageCount;
        this.totalTrades = totalTrades;
    }

    public static List<TradeRecipeGroup> buildPages(List<TradeRecipe> recipes) {
        Map<String, List<TradeRecipe>> grouped = new LinkedHashMap<>();
        for (TradeRecipe recipe : recipes) {
            grouped.computeIfAbsent(groupKey(recipe), ignored -> new ArrayList<>()).add(recipe);
        }

        List<TradeRecipeGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<TradeRecipe>> entry : grouped.entrySet()) {
            List<TradeRecipe> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(TradeRecipe::level)
                            .thenComparing(TradeRecipe::fingerprint))
                    .toList();
            TradeRecipe first = sorted.get(0);
            int pageCount = Math.max(1, (sorted.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
            for (int page = 0; page < pageCount; page++) {
                int from = page * ROWS_PER_PAGE;
                int to = Math.min(sorted.size(), from + ROWS_PER_PAGE);
                result.add(new TradeRecipeGroup(entry.getKey(), first.entityType(), first.profession(),
                        first.workstations(), sorted.subList(from, to), page + 1, pageCount, sorted.size()));
            }
        }
        return result;
    }

    private static String groupKey(TradeRecipe recipe) {
        return Objects.toString(recipe.entityType(), "") + '|'
                + Objects.toString(recipe.profession(), "") + '|'
                + recipe.workstations();
    }

    public ResourceLocation entityType() {
        return entityType;
    }

    public ResourceLocation profession() {
        return profession;
    }

    public List<ResourceLocation> workstations() {
        return workstations;
    }

    public List<ItemStack> workstationStacks() {
        List<ItemStack> result = new ArrayList<>();
        for (ResourceLocation id : workstations) {
            var block = BuiltInRegistries.BLOCK.get(id);
            if (block != null && !block.asItem().equals(net.minecraft.world.item.Items.AIR)) {
                result.add(new ItemStack(block));
            }
        }
        return result;
    }

    public List<TradeRecipe> trades() {
        return trades;
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return pageCount;
    }

    public int totalTrades() {
        return totalTrades;
    }

    public boolean matches(ItemStack stack) {
        return trades.stream().anyMatch(recipe -> recipe.matches(stack));
    }

    public boolean outputMatches(ItemStack stack) {
        return trades.stream().anyMatch(recipe -> recipe.outputMatches(stack));
    }

    public String fingerprint() {
        return groupKey + "|page=" + page + '|' + trades.stream()
                .map(TradeRecipe::fingerprint)
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }
}
