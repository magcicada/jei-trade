package net.xuwu.jei_trade.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.xuwu.jei_trade.ClientTradeCatalog;
import net.xuwu.jei_trade.Jei_trade;
import net.xuwu.jei_trade.TradeRecipeGroup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@JeiPlugin
public final class TradeJeiPlugin implements IModPlugin {
    public static final RecipeType<TradeRecipeGroup> TRADE_RECIPE_TYPE = RecipeType.create(
            Jei_trade.MODID, "villager_trade", TradeRecipeGroup.class);
    private static final Set<String> REGISTERED_RECIPE_KEYS = new HashSet<>();
    private static IRecipeManager recipeManager;

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
    public void registerRecipes(IRecipeRegistration registration) {
        ClientTradeCatalog.ensureFallback(Minecraft.getInstance().level);
        List<TradeRecipeGroup> groups = ClientTradeCatalog.snapshotGroups();
        registration.addRecipes(TRADE_RECIPE_TYPE, groups);
        groups.forEach(group -> REGISTERED_RECIPE_KEYS.add(group.fingerprint()));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(TRADE_RECIPE_TYPE, new TradeRecipeManagerPlugin());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        recipeManager = runtime.getRecipeManager();
        refreshRecipes();
    }

    @Override
    public void onRuntimeUnavailable() {
        recipeManager = null;
        REGISTERED_RECIPE_KEYS.clear();
    }

    public static void refreshRecipes() {
        if (recipeManager == null) return;
        List<TradeRecipeGroup> pending = ClientTradeCatalog.snapshotGroups().stream()
                .filter(group -> REGISTERED_RECIPE_KEYS.add(group.fingerprint()))
                .toList();
        if (!pending.isEmpty()) recipeManager.addRecipes(TRADE_RECIPE_TYPE, pending);
    }
}
