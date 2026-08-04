package net.xuwu.jei_trade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TradeServerCatalog {
    private final Map<String, TradeRecipe> entries = new LinkedHashMap<>();

    synchronized void replace(List<TradeRecipe> recipes) {
        entries.clear();
        addAll(recipes);
    }

    synchronized boolean addAll(List<TradeRecipe> recipes) {
        boolean changed = false;
        for (TradeRecipe recipe : recipes) {
            if (entries.size() >= Config.MAX_CATALOG_ENTRIES.get()) break;
            TradeRecipe previous = entries.get(recipe.fingerprint());
            TradeRecipe merged = previous == null ? recipe : TradeRecipe.merge(previous, recipe);
            changed |= previous == null || !previous.variantsFingerprint().equals(merged.variantsFingerprint());
            entries.put(recipe.fingerprint(), merged);
        }
        return changed;
    }

    synchronized List<TradeRecipe> snapshot() {
        return new ArrayList<>(entries.values());
    }
}
