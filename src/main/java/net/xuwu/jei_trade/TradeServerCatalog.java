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
            changed |= entries.putIfAbsent(recipe.fingerprint(), recipe) == null;
        }
        return changed;
    }

    synchronized List<TradeRecipe> snapshot() {
        return new ArrayList<>(entries.values());
    }
}
