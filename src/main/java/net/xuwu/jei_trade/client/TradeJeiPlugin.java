package net.xuwu.jei_trade.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import net.xuwu.jei_trade.Jei_trade;
import net.xuwu.jei_trade.TradeRecipe;

@JeiPlugin
public final class TradeJeiPlugin implements IModPlugin {
    public static final RecipeType<TradeRecipe> TRADE_RECIPE_TYPE = RecipeType.create(
            Jei_trade.MODID, "villager_trade", TradeRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(Jei_trade.MODID, "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new TradeRecipeCategory(
                registration.getJeiHelpers().getGuiHelper(), TRADE_RECIPE_TYPE));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(TRADE_RECIPE_TYPE, new TradeRecipeManagerPlugin());
    }
}
