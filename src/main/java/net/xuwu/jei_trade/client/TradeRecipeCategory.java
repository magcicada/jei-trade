package net.xuwu.jei_trade.client;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.xuwu.jei_trade.Jei_trade;
import net.xuwu.jei_trade.TradeRecipe;
import net.xuwu.jei_trade.TradeRecipeGroup;

final class TradeRecipeCategory extends AbstractRecipeCategory<TradeRecipeGroup> {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 28;
    private static final int CATEGORY_WIDTH = 180;

    TradeRecipeCategory(IGuiHelper guiHelper, RecipeType<TradeRecipeGroup> recipeType) {
        super(recipeType,
                Component.translatable("jei_trade.category.villager_trade"),
                guiHelper.createDrawableItemStack(net.minecraft.world.item.Items.EMERALD.getDefaultInstance()),
                CATEGORY_WIDTH, HEADER_HEIGHT + TradeRecipeGroup.ROWS_PER_PAGE * ROW_HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, TradeRecipeGroup group, IFocusGroup focuses) {
        int workstationX = 4;
        for (var workstation : group.workstationStacks()) {
            if (workstationX > 44) break;
            builder.addInputSlot(workstationX, 4)
                    .setStandardSlotBackground()
                    .addItemStack(workstation);
            workstationX += 20;
        }

        for (int index = 0; index < group.trades().size(); index++) {
            TradeRecipe trade = group.trades().get(index);
            int y = HEADER_HEIGHT + index * ROW_HEIGHT;
            builder.addInputSlot(4, y).setStandardSlotBackground().addItemStacks(trade.buyAVariantsForDisplay());
            if (!trade.buyB().isEmpty()) {
                builder.addInputSlot(27, y).setStandardSlotBackground().addItemStacks(trade.buyBVariantsForDisplay());
            }
            builder.addInputSlot(91, y).setStandardSlotBackground().addItemStacks(trade.resultVariantsForDisplay());
        }
    }

    @Override
    public void draw(TradeRecipeGroup group, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        int workstationCount = Math.min(group.workstationStacks().size(), 3);
        int titleX = workstationCount == 0 ? 4 : 4 + workstationCount * 20 + 3;
        boolean wanderingTrader = group.entityType() != null
                && "minecraft".equals(group.entityType().getNamespace())
                && "wandering_trader".equals(group.entityType().getPath());
        String source = wanderingTrader
                ? Component.translatable("jei_trade.source.wandering_trader").getString()
                : group.profession() == null
                ? Component.translatable("jei_trade.source.merchant").getString()
                : Component.translatable("jei_trade.source.profession", group.profession().toString()).getString();
        source = minecraft.font.plainSubstrByWidth(source, CATEGORY_WIDTH - titleX - 4);
        graphics.drawString(minecraft.font, source, titleX, 7, 0x404040, false);
        if (group.pageCount() > 1) {
            String page = Component.translatable("jei_trade.page", group.page(), group.pageCount()).getString();
            graphics.drawString(minecraft.font, page, CATEGORY_WIDTH - minecraft.font.width(page) - 4,
                    18, 0x606060, false);
        }

        for (int index = 0; index < group.trades().size(); index++) {
            TradeRecipe trade = group.trades().get(index);
            int y = HEADER_HEIGHT + index * ROW_HEIGHT;
            graphics.drawString(minecraft.font, "->", 55, y + 5, 0x404040, false);
            String level = Component.translatable("jei_trade.level", trade.level()).getString();
            graphics.drawString(minecraft.font, level, 120, y + 2, 0x404040, false);
            graphics.drawString(minecraft.font,
                    Component.translatable("jei_trade.max_uses", trade.maxUses()), 120, y + 12,
                    0x606060, false);
            drawRange(graphics, minecraft.font, trade.buyACountRange(), 4, y);
            drawRange(graphics, minecraft.font, trade.buyBCountRange(), 27, y);
            drawRange(graphics, minecraft.font, trade.resultCountRange(), 91, y);
        }
    }

    private static void drawRange(GuiGraphics graphics, Font font, String range, int x, int y) {
        if (!range.isEmpty()) {
            graphics.drawString(font, range, x + 17 - font.width(range), y + 9, 0xFFFFFFFF, true);
        }
    }

    @Override
    public ResourceLocation getRegistryName(TradeRecipeGroup group) {
        return new ResourceLocation(Jei_trade.MODID,
                "trade/" + Integer.toHexString(group.fingerprint().hashCode()));
    }
}
