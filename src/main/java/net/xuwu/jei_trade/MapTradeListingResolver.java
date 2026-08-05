package net.xuwu.jei_trade;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.level.saveddata.maps.MapDecoration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves map-based trade factories without invoking their world-dependent getOffer method.
 *
 * <p>Many mods copy the vanilla map-trade shape but keep the implementation in their own class:
 * a structure tag, a translated map name, a map decoration type and one or two price stacks.
 * Looking for that shape instead of a mod class name keeps the integration optional and usable
 * for other custom merchants as well.</p>
 */
final class MapTradeListingResolver {
    private static final int DEFAULT_MAX_USES = 12;
    private static final int DEFAULT_XP = 0;
    private static final float DEFAULT_PRICE_MULTIPLIER = 0.2F;

    private static final ClassValue<Optional<Shape>> SHAPES = new ClassValue<>() {
        @Override
        protected Optional<Shape> computeValue(Class<?> type) {
            try {
                return Optional.ofNullable(findShape(type));
            } catch (RuntimeException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };

    private MapTradeListingResolver() {
    }

    static TradeRecipe resolve(VillagerTrades.ItemListing listing,
                               ResourceLocation entityType, ResourceLocation profession,
                               List<ResourceLocation> workstations, int level) {
        Optional<Shape> shape = SHAPES.get(listing.getClass());
        if (shape.isEmpty()) return null;

        try {
            Shape mapShape = shape.get();
            if (!hasStructureTarget(mapShape.structureTags, listing)) return null;

            String displayName = (String) mapShape.displayName.get(listing);
            if (displayName == null || displayName.isBlank()) return null;

            List<ItemStack> prices = new ArrayList<>(2);
            for (Field field : mapShape.priceFields) {
                Object value = field.get(listing);
                if (value instanceof ItemStack stack) prices.add(stack.copy());
                if (prices.size() == 2) break;
            }
            if (prices.isEmpty()) return null;
            while (prices.size() < 2) prices.add(ItemStack.EMPTY);

            ItemStack priceA = prices.get(0);
            ItemStack priceB = prices.get(1);
            ItemStack map = Items.MAP.getDefaultInstance();
            map.setHoverName(Component.translatable(displayName));

            int maxUses = readInt(mapShape.maxUses, listing, DEFAULT_MAX_USES);
            int xp = readInt(mapShape.xp, listing, DEFAULT_XP);
            float priceMultiplier = readFloat(mapShape.priceMultiplier, listing,
                    DEFAULT_PRICE_MULTIPLIER);

            return TradeRecipe.fromDefinition(entityType, profession, workstations, level,
                    List.of(priceA), count(priceA), count(priceA),
                    List.of(priceB), count(priceB), count(priceB),
                    List.of(map), 1, 1, maxUses, xp, priceMultiplier,
                    List.of(Component.translatable("jei_trade.detail.treasure_map",
                            Component.translatable(displayName))));
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static Shape findShape(Class<?> type) {
        List<Field> fields = instanceFields(type);
        List<Field> structureTags = fields.stream()
                .filter(field -> TagKey.class.isAssignableFrom(field.getType()))
                .toList();
        Field displayName = findDisplayName(fields);
        List<Field> itemStacks = fields.stream()
                .filter(field -> ItemStack.class.isAssignableFrom(field.getType()))
                .toList();
        if (structureTags.isEmpty() || displayName == null || itemStacks.isEmpty()) return null;

        List<Field> priceFields = itemStacks.stream()
                .filter(field -> hasNameToken(field, "price", "cost", "buy", "input", "from"))
                .toList();
        if (priceFields.isEmpty()) {
            // A two-stack map listing normally stores only its two prices. Do not guess when a
            // class has several unrelated stacks, because that could create a false map trade.
            if (itemStacks.size() > 2) return null;
            priceFields = itemStacks;
        }

        Field decorationType = fields.stream()
                .filter(field -> MapDecoration.Type.class.isAssignableFrom(field.getType()))
                .findFirst()
                .orElse(null);
        if (decorationType == null && !hasNameToken(displayName, "map", "display")) return null;

        return new Shape(structureTags, displayName, priceFields,
                findIntField(fields, "maxuses", "maxtrades", "maxtrade"),
                findIntField(fields, "xp", "experience"),
                findFloatField(fields, "pricemultiplier", "pricemult", "multiplier"));
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;
                if (!field.trySetAccessible()) continue;
                fields.add(field);
            }
        }
        return fields;
    }

    private static Field findDisplayName(List<Field> fields) {
        List<Field> strings = fields.stream()
                .filter(field -> field.getType() == String.class)
                .toList();
        return strings.stream()
                .filter(field -> hasNameToken(field, "display", "translation", "mapname"))
                .findFirst()
                .orElse(strings.size() == 1 ? strings.get(0) : null);
    }

    private static Field findIntField(List<Field> fields, String... names) {
        return fields.stream()
                .filter(field -> field.getType() == int.class || field.getType() == Integer.class)
                .filter(field -> hasNameToken(field, names))
                .findFirst()
                .orElse(null);
    }

    private static Field findFloatField(List<Field> fields, String... names) {
        return fields.stream()
                .filter(field -> field.getType() == float.class || field.getType() == Float.class
                        || field.getType() == double.class || field.getType() == Double.class)
                .filter(field -> hasNameToken(field, names))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasStructureTarget(List<Field> fields, Object listing) throws IllegalAccessException {
        for (Field field : fields) {
            Object value = field.get(listing);
            if (value instanceof TagKey<?> tag && tag.isFor(Registries.STRUCTURE)) return true;
        }
        return false;
    }

    private static boolean hasNameToken(Field field, String... tokens) {
        String name = field.getName().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (name.contains(token)) return true;
        }
        return false;
    }

    private static int readInt(Field field, Object target, int fallback) throws IllegalAccessException {
        if (field == null) return fallback;
        Object value = field.get(target);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static float readFloat(Field field, Object target, float fallback) throws IllegalAccessException {
        if (field == null) return fallback;
        Object value = field.get(target);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int count(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    private record Shape(List<Field> structureTags, Field displayName, List<Field> priceFields,
                         Field maxUses, Field xp, Field priceMultiplier) {
    }
}
