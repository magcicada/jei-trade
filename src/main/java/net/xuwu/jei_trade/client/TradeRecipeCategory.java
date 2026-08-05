package net.xuwu.jei_trade.client;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.xuwu.jei_trade.TradeRecipe;
import net.xuwu.jei_trade.TradeRecipeGroup;

import java.util.List;

final class TradeRecipeCategory extends AbstractRecipeCategory<TradeRecipeGroup> {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 28;
    private static final int CATEGORY_WIDTH = 180;
    private static final int RANGE_MAX_WIDTH = 16;
    private static final float RANGE_MAX_SCALE = 0.65F;
    private static final float RANGE_Z = 300.0F;
    private final IDrawableStatic arrow;

    TradeRecipeCategory(IGuiHelper guiHelper, RecipeType<TradeRecipeGroup> recipeType) {
        super(recipeType,
                Component.translatable("jei_trade.category.villager_trade"),
                guiHelper.createDrawableItemStack(net.minecraft.world.item.Items.EMERALD.getDefaultInstance()),
                CATEGORY_WIDTH, HEADER_HEIGHT + TradeRecipeGroup.ROWS_PER_PAGE * ROW_HEIGHT);
        arrow = guiHelper.getRecipeArrow();
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
        ItemStack spawnEgg = group.sourceSpawnEggStack();
        if (!spawnEgg.isEmpty()) {
            builder.addInputSlot(workstationX, 4)
                    .setStandardSlotBackground()
                    .addItemStack(spawnEgg);
        }

        for (int index = 0; index < group.trades().size(); index++) {
            TradeRecipe trade = group.trades().get(index);
            int y = HEADER_HEIGHT + index * ROW_HEIGHT;
            IRecipeSlotBuilder buyA = builder.addInputSlot(4, y).setStandardSlotBackground();
            buyA.addItemStacks(trade.buyAVariantsForDisplay());
            addSlotTooltip(buyA, trade, trade.buyAVariants(), trade.buyACountRange(), false);
            if (!trade.buyB().isEmpty()) {
                IRecipeSlotBuilder buyB = builder.addInputSlot(27, y).setStandardSlotBackground();
                buyB.addItemStacks(trade.buyBVariantsForDisplay());
                addSlotTooltip(buyB, trade, trade.buyBVariants(), trade.buyBCountRange(), false);
            }
            IRecipeSlotBuilder result = builder.addOutputSlot(91, y).setStandardSlotBackground();
            result.addItemStacks(trade.resultVariantsForDisplay());
            addSlotTooltip(result, trade, trade.resultVariants(), trade.resultCountRange(), true);
        }
    }

    private static void addSlotTooltip(IRecipeSlotBuilder slot, TradeRecipe trade,
                                       java.util.List<ItemStack> variants, String countRange,
                                       boolean includeDetails) {
        slot.addRichTooltipCallback((view, tooltip) -> {
            int nbtVariants = trade.nbtVariantCount(variants);
            if (nbtVariants > 1) {
                boolean sameItem = variants.stream().allMatch(stack -> stack.is(variants.get(0).getItem()));
                tooltip.add(Component.translatable(sameItem ? "jei_trade.nbt_variants" : "jei_trade.variants", nbtVariants)
                        .withStyle(ChatFormatting.GRAY));
            }
            if (!countRange.isEmpty()) {
                tooltip.add(Component.translatable("jei_trade.count_range", countRange)
                        .withStyle(ChatFormatting.GRAY));
            }
            if (includeDetails) trade.details().forEach(tooltip::add);
        });
    }

    @Override
    public void draw(TradeRecipeGroup group, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        int workstationCount = Math.min(group.workstationStacks().size(), 3);
        int headerSlotCount = workstationCount + (group.sourceSpawnEggStack().isEmpty() ? 0 : 1);
        int titleX = headerSlotCount == 0 ? 4 : 4 + headerSlotCount * 20 + 3;
        boolean wanderingTrader = group.entityType() != null
                && "minecraft".equals(group.entityType().getNamespace())
                && "wandering_trader".equals(group.entityType().getPath());
        String source = wanderingTrader
                ? Component.translatable("jei_trade.source.wandering_trader").getString()
                : group.profession() == null
                ? merchantName(group.entityType()).getString()
                : Component.translatable("jei_trade.source.profession", professionName(group.profession())).getString();
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
            arrow.draw(graphics, 57, y + 2);
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
            int width = font.width(range);
            float scale = Math.min(RANGE_MAX_SCALE, RANGE_MAX_WIDTH / (float) Math.max(1, width));
            graphics.pose().pushPose();
            graphics.pose().translate(x + 18.0F, y + 18.0F, RANGE_Z);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, range, -width, -font.lineHeight, 0xFFFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    private static Component professionName(ResourceLocation profession) {
        var professionValue = BuiltInRegistries.VILLAGER_PROFESSION.get(profession);
        if (professionValue != null) {
            String vanillaKey = "entity.minecraft.villager." + professionValue;
            if (ClientLanguage.getInstance().has(vanillaKey)) return Component.translatable(vanillaKey);
        }
        for (String key : professionTranslationKeys(profession)) {
            if (ClientLanguage.getInstance().has(key)) return Component.translatable(key);
        }

        return Component.literal(profession.toString());
    }

    private static List<String> professionTranslationKeys(ResourceLocation profession) {
        String namespace = profession.getNamespace();
        String path = profession.getPath();
        return List.of(
                "entity." + namespace + ".villager." + path,
                "entity." + namespace + "." + path,
                "villager.profession." + namespace + "." + path,
                "villager.profession." + path,
                "profession." + namespace + "." + path,
                "profession." + path,
                "entity.minecraft.villager." + path
        );
    }

    private static Component merchantName(ResourceLocation entityType) {
        if (entityType != null) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(entityType);
            if (type != null && ClientLanguage.getInstance().has(type.getDescriptionId())) {
                return Component.translatable(type.getDescriptionId());
            }
        }
        return Component.translatable("jei_trade.source.merchant");
    }

}
