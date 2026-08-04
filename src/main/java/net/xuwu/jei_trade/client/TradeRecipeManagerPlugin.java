package net.xuwu.jei_trade.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import net.minecraft.world.item.ItemStack;
import net.xuwu.jei_trade.ClientTradeCatalog;
import net.xuwu.jei_trade.TradeRecipe;

import java.util.List;

final class TradeRecipeManagerPlugin implements ISimpleRecipeManagerPlugin<TradeRecipe> {
    @Override
    public boolean isHandledInput(ITypedIngredient<?> input) {
        return input.getIngredient(VanillaTypes.ITEM_STACK).isPresent();
    }

    @Override
    public boolean isHandledOutput(ITypedIngredient<?> output) {
        return output.getIngredient(VanillaTypes.ITEM_STACK).isPresent();
    }

    @Override
    public List<TradeRecipe> getRecipesForInput(ITypedIngredient<?> input) {
        return input.getItemStack().map(stack -> ClientTradeCatalog.snapshot().stream()
                .filter(recipe -> recipe.matches(stack)).toList()).orElseGet(List::of);
    }

    @Override
    public List<TradeRecipe> getRecipesForOutput(ITypedIngredient<?> output) {
        return output.getItemStack().map(stack -> ClientTradeCatalog.snapshot().stream()
                .filter(recipe -> recipe.result().is(stack.getItem())).toList()).orElseGet(List::of);
    }

    @Override
    public List<TradeRecipe> getAllRecipes() {
        return ClientTradeCatalog.snapshot();
    }
}
