package net.xuwu.jei_trade.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import net.xuwu.jei_trade.ClientTradeCatalog;
import net.xuwu.jei_trade.TradeRecipeGroup;

import java.util.List;

final class TradeRecipeManagerPlugin implements ISimpleRecipeManagerPlugin<TradeRecipeGroup> {
    @Override
    public boolean isHandledInput(ITypedIngredient<?> input) {
        return input.getIngredient(VanillaTypes.ITEM_STACK).isPresent();
    }

    @Override
    public boolean isHandledOutput(ITypedIngredient<?> output) {
        return output.getIngredient(VanillaTypes.ITEM_STACK).isPresent();
    }

    @Override
    public List<TradeRecipeGroup> getRecipesForInput(ITypedIngredient<?> input) {
        return input.getItemStack().map(stack -> ClientTradeCatalog.snapshotGroups().stream()
                .filter(recipe -> recipe.matches(stack)).toList()).orElseGet(List::of);
    }

    @Override
    public List<TradeRecipeGroup> getRecipesForOutput(ITypedIngredient<?> output) {
        return output.getItemStack().map(stack -> ClientTradeCatalog.snapshotGroups().stream()
                .filter(recipe -> recipe.outputMatches(stack)).toList()).orElseGet(List::of);
    }

    @Override
    public List<TradeRecipeGroup> getAllRecipes() {
        return ClientTradeCatalog.snapshotGroups();
    }
}
