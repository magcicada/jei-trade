package net.xuwu.jei_trade.client;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.xuwu.jei_trade.Jei_trade;
import net.xuwu.jei_trade.TradeRecipe;

import java.util.List;

final class TradeRecipeCategory extends AbstractRecipeCategory<TradeRecipe> {
    TradeRecipeCategory(IGuiHelper guiHelper, RecipeType<TradeRecipe> recipeType) {
        super(recipeType,
                Component.translatable("jei_trade.category.villager_trade"),
                guiHelper.createDrawableItemStack(net.minecraft.world.item.Items.EMERALD.getDefaultInstance()),
                136, 64);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, TradeRecipe recipe, IFocusGroup focuses) {
        if (!recipe.workstationStack().isEmpty()) {
            builder.addInputSlot(4, 4).setStandardSlotBackground().addItemStack(recipe.workstationStack());
        }
        builder.addInputSlot(34, 4).setStandardSlotBackground().addItemStack(recipe.buyA());
        if (!recipe.buyB().isEmpty()) {
            builder.addInputSlot(55, 4).setStandardSlotBackground().addItemStack(recipe.buyB());
        }
        builder.addOutputSlot(96, 4).setOutputSlotBackground().addItemStack(recipe.result());
    }

    @Override
    public void draw(TradeRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        String source = recipe.profession() == null
                ? Component.translatable("jei_trade.source.merchant").getString()
                : Component.translatable("jei_trade.source.profession", recipe.profession()).getString();
        graphics.drawString(Minecraft.getInstance().font, source, 4, 27, 0x404040, false);
        graphics.drawString(Minecraft.getInstance().font,
                Component.translatable("jei_trade.level", recipe.level()), 4, 39, 0x404040, false);
        graphics.drawString(Minecraft.getInstance().font,
                Component.translatable("jei_trade.uses", recipe.uses(), recipe.maxUses()), 4, 51, 0x606060, false);
    }

    @Override
    public net.minecraft.resources.ResourceLocation getRegistryName(TradeRecipe recipe) {
        return new net.minecraft.resources.ResourceLocation(Jei_trade.MODID,
                "trade/" + Integer.toHexString(recipe.fingerprint().hashCode()));
    }
}
