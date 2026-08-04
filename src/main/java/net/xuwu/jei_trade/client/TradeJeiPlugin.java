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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JeiPlugin
public final class TradeJeiPlugin implements IModPlugin {
    public static final RecipeType<TradeRecipeGroup> TRADE_RECIPE_TYPE = RecipeType.create(
            Jei_trade.MODID, "villager_trade", TradeRecipeGroup.class);
    private static final Map<String, TradeRecipeGroup> REGISTERED_RECIPE_GROUPS = new HashMap<>();
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
        groups.forEach(group -> REGISTERED_RECIPE_GROUPS.put(group.fingerprint(), group));
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
        REGISTERED_RECIPE_GROUPS.clear();
    }

    public static void refreshRecipes() {
        if (recipeManager == null) return;
        List<TradeRecipeGroup> current = ClientTradeCatalog.snapshotGroups();
        Map<String, TradeRecipeGroup> currentByKey = current.stream()
                .collect(Collectors.toMap(TradeRecipeGroup::fingerprint,
                        group -> group, (left, right) -> right));

        List<TradeRecipeGroup> stale = REGISTERED_RECIPE_GROUPS.entrySet().stream()
                .filter(entry -> !currentByKey.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!stale.isEmpty()) {
            recipeManager.hideRecipes(TRADE_RECIPE_TYPE, stale);
            stale.forEach(group -> REGISTERED_RECIPE_GROUPS.remove(group.fingerprint()));
        }

        List<TradeRecipeGroup> pending = current.stream()
                .filter(group -> !REGISTERED_RECIPE_GROUPS.containsKey(group.fingerprint()))
                .toList();
        if (!pending.isEmpty()) recipeManager.addRecipes(TRADE_RECIPE_TYPE, pending);
        pending.forEach(group -> REGISTERED_RECIPE_GROUPS.put(group.fingerprint(), group));
    }
}
