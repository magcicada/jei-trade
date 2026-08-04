package net.xuwu.jei_trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable JEI-facing representation of one logical merchant offer. */
public final class TradeRecipe {
    private static final int MAX_VARIANTS = 256;

    private final ResourceLocation entityType;
    private final ResourceLocation profession;
    private final List<ResourceLocation> workstations;
    private final int level;
    private final List<ItemStack> buyAVariants;
    private final List<ItemStack> buyBVariants;
    private final List<ItemStack> resultVariants;
    private final int buyAMin;
    private final int buyAMax;
    private final int buyBMin;
    private final int buyBMax;
    private final int resultMin;
    private final int resultMax;
    private final int uses;
    private final int maxUses;
    private final int xp;
    private final float priceMultiplier;
    private final int demand;
    private final int specialPrice;
    private final boolean rewardExp;
    private final List<Component> details;

    public TradeRecipe(ResourceLocation entityType, ResourceLocation profession,
                       List<ResourceLocation> workstations, int level,
                       ItemStack buyA, ItemStack buyB, ItemStack result,
                       int uses, int maxUses, int xp, float priceMultiplier,
                       int demand, int specialPrice, boolean rewardExp) {
        this(entityType, profession, workstations, level,
                List.of(copy(buyA)), List.of(copy(buyB)), List.of(copy(result)),
                count(buyA), count(buyA), count(buyB), count(buyB), count(result), count(result),
                uses, maxUses, xp, priceMultiplier, demand, specialPrice, rewardExp);
    }

    private TradeRecipe(ResourceLocation entityType, ResourceLocation profession,
                        List<ResourceLocation> workstations, int level,
                        List<ItemStack> buyAVariants, List<ItemStack> buyBVariants,
                        List<ItemStack> resultVariants,
                        int buyAMin, int buyAMax, int buyBMin, int buyBMax,
                        int resultMin, int resultMax, int uses, int maxUses,
                        int xp, float priceMultiplier, int demand, int specialPrice,
                        boolean rewardExp) {
        this(entityType, profession, workstations, level, buyAVariants, buyBVariants, resultVariants,
                buyAMin, buyAMax, buyBMin, buyBMax, resultMin, resultMax,
                uses, maxUses, xp, priceMultiplier, demand, specialPrice, rewardExp, List.of());
    }

    private TradeRecipe(ResourceLocation entityType, ResourceLocation profession,
                        List<ResourceLocation> workstations, int level,
                        List<ItemStack> buyAVariants, List<ItemStack> buyBVariants,
                        List<ItemStack> resultVariants,
                        int buyAMin, int buyAMax, int buyBMin, int buyBMax,
                        int resultMin, int resultMax, int uses, int maxUses,
                        int xp, float priceMultiplier, int demand, int specialPrice,
                        boolean rewardExp, List<Component> details) {
        this.entityType = entityType;
        this.profession = profession;
        this.workstations = List.copyOf(workstations);
        this.level = level;
        this.buyAVariants = copyList(buyAVariants);
        this.buyBVariants = copyList(buyBVariants);
        this.resultVariants = copyList(resultVariants);
        this.buyAMin = Math.max(0, buyAMin);
        this.buyAMax = Math.max(this.buyAMin, buyAMax);
        this.buyBMin = Math.max(0, buyBMin);
        this.buyBMax = Math.max(this.buyBMin, buyBMax);
        this.resultMin = Math.max(0, resultMin);
        this.resultMax = Math.max(this.resultMin, resultMax);
        this.uses = uses;
        this.maxUses = maxUses;
        this.xp = xp;
        this.priceMultiplier = priceMultiplier;
        this.demand = demand;
        this.specialPrice = specialPrice;
        this.rewardExp = rewardExp;
        this.details = List.copyOf(details);
    }

    public static TradeRecipe fromOffer(ResourceLocation entityType, ResourceLocation profession,
                                        List<ResourceLocation> workstations, int level,
                                        MerchantOffer offer) {
        return new TradeRecipe(entityType, profession, workstations, level,
                offer.getCostA(), offer.getCostB(), offer.getResult(),
                offer.getUses(), offer.getMaxUses(), offer.getXp(),
                offer.getPriceMultiplier(), offer.getDemand(), offer.getSpecialPriceDiff(),
                offer.shouldRewardExp());
    }

    public static TradeRecipe fromDefinition(ResourceLocation entityType, ResourceLocation profession,
                                              List<ResourceLocation> workstations, int level,
                                              List<ItemStack> buyAVariants, int buyAMin, int buyAMax,
                                              List<ItemStack> buyBVariants, int buyBMin, int buyBMax,
                                              List<ItemStack> resultVariants, int resultMin, int resultMax,
                                              int maxUses, int xp, float priceMultiplier,
                                              List<Component> details) {
        return new TradeRecipe(entityType, profession, workstations, level,
                buyAVariants, buyBVariants, resultVariants,
                buyAMin, buyAMax, buyBMin, buyBMax, resultMin, resultMax,
                0, maxUses, xp, priceMultiplier, 0, 0, true, details);
    }

    /** Creates a one-variant recipe using the metadata from an existing recipe. */
    public static TradeRecipe withVariantStacks(TradeRecipe base,
                                                ItemStack buyA, ItemStack buyB, ItemStack result) {
        return new TradeRecipe(base.entityType, base.profession, base.workstations, base.level,
                List.of(copy(buyA)), List.of(copy(buyB)), List.of(copy(result)),
                base.buyAMin, base.buyAMax, base.buyBMin, base.buyBMax,
                base.resultMin, base.resultMax,
                base.uses, base.maxUses, base.xp, base.priceMultiplier,
                base.demand, base.specialPrice, base.rewardExp, base.details);
    }

    /** Combines random/NBT variants of the same logical trade without losing any stack. */
    public static TradeRecipe merge(TradeRecipe left, TradeRecipe right) {
        if (!left.fingerprint().equals(right.fingerprint())) return left;
        return new TradeRecipe(left.entityType, left.profession, left.workstations, left.level,
                union(left.buyAVariants, right.buyAVariants),
                union(left.buyBVariants, right.buyBVariants),
                union(left.resultVariants, right.resultVariants),
                Math.min(left.buyAMin, right.buyAMin), Math.max(left.buyAMax, right.buyAMax),
                Math.min(left.buyBMin, right.buyBMin), Math.max(left.buyBMax, right.buyBMax),
                Math.min(left.resultMin, right.resultMin), Math.max(left.resultMax, right.resultMax),
                left.uses, Math.max(left.maxUses, right.maxUses), left.xp,
                left.priceMultiplier, left.demand, left.specialPrice, left.rewardExp,
                unionDetails(left.details, right.details));
    }

    public static TradeRecipe read(FriendlyByteBuf buf) {
        ResourceLocation entityType = readOptionalLocation(buf);
        ResourceLocation profession = readOptionalLocation(buf);
        int workstationCount = buf.readVarInt();
        if (workstationCount < 0 || workstationCount > MAX_VARIANTS) {
            throw new IllegalArgumentException("Invalid workstation count: " + workstationCount);
        }
        List<ResourceLocation> workstations = new ArrayList<>(workstationCount);
        for (int i = 0; i < workstationCount; i++) {
            workstations.add(buf.readResourceLocation());
        }
        int level = buf.readVarInt();
        List<ItemStack> buyA = readVariantList(buf);
        List<ItemStack> buyB = readVariantList(buf);
        List<ItemStack> result = readVariantList(buf);
        int buyAMin = buf.readVarInt();
        int buyAMax = buf.readVarInt();
        int buyBMin = buf.readVarInt();
        int buyBMax = buf.readVarInt();
        int resultMin = buf.readVarInt();
        int resultMax = buf.readVarInt();
        int detailCount = buf.readVarInt();
        if (detailCount < 0 || detailCount > 32) {
            throw new IllegalArgumentException("Invalid trade detail count: " + detailCount);
        }
        List<Component> details = new ArrayList<>(detailCount);
        for (int i = 0; i < detailCount; i++) details.add(buf.readComponent());
        return new TradeRecipe(entityType, profession, workstations, level, buyA, buyB, result,
                buyAMin, buyAMax, buyBMin, buyBMax, resultMin, resultMax,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), details);
    }

    public void write(FriendlyByteBuf buf) {
        writeOptionalLocation(buf, entityType);
        writeOptionalLocation(buf, profession);
        buf.writeVarInt(workstations.size());
        workstations.forEach(buf::writeResourceLocation);
        buf.writeVarInt(level);
        writeVariantList(buf, buyAVariants);
        writeVariantList(buf, buyBVariants);
        writeVariantList(buf, resultVariants);
        buf.writeVarInt(buyAMin);
        buf.writeVarInt(buyAMax);
        buf.writeVarInt(buyBMin);
        buf.writeVarInt(buyBMax);
        buf.writeVarInt(resultMin);
        buf.writeVarInt(resultMax);
        buf.writeVarInt(details.size());
        details.forEach(buf::writeComponent);
        buf.writeVarInt(uses);
        buf.writeVarInt(maxUses);
        buf.writeVarInt(xp);
        buf.writeFloat(priceMultiplier);
        buf.writeVarInt(demand);
        buf.writeVarInt(specialPrice);
        buf.writeBoolean(rewardExp);
    }

    private static void writeVariantList(FriendlyByteBuf buf, List<ItemStack> variants) {
        buf.writeVarInt(Math.min(variants.size(), MAX_VARIANTS));
        for (int i = 0; i < variants.size() && i < MAX_VARIANTS; i++) {
            buf.writeItem(variants.get(i));
        }
    }

    private static List<ItemStack> readVariantList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count <= 0 || count > MAX_VARIANTS) {
            throw new IllegalArgumentException("Invalid trade variant count: " + count);
        }
        List<ItemStack> variants = new ArrayList<>(count);
        for (int i = 0; i < count; i++) variants.add(buf.readItem());
        return variants;
    }

    private static void writeOptionalLocation(FriendlyByteBuf buf, ResourceLocation location) {
        buf.writeBoolean(location != null);
        if (location != null) buf.writeResourceLocation(location);
    }

    private static ResourceLocation readOptionalLocation(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    private static List<ItemStack> union(List<ItemStack> left, List<ItemStack> right) {
        Map<String, ItemStack> result = new LinkedHashMap<>();
        for (ItemStack stack : left) result.put(stackFingerprintIgnoringCount(stack), copy(stack));
        for (ItemStack stack : right) {
            if (result.size() >= MAX_VARIANTS) break;
            result.putIfAbsent(stackFingerprintIgnoringCount(stack), copy(stack));
        }
        return List.copyOf(result.values());
    }

    private static List<ItemStack> copyList(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return List.of(ItemStack.EMPTY);
        return stacks.stream().map(TradeRecipe::copy).toList();
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public ResourceLocation entityType() { return entityType; }
    public ResourceLocation profession() { return profession; }
    public List<ResourceLocation> workstations() { return workstations; }
    public int level() { return level; }
    public ItemStack buyA() { return copy(buyAVariants.get(0)); }
    public ItemStack buyB() { return copy(buyBVariants.get(0)); }
    public ItemStack result() { return copy(resultVariants.get(0)); }
    public List<ItemStack> buyAVariants() { return copyList(buyAVariants); }
    public List<ItemStack> buyBVariants() { return copyList(buyBVariants); }
    public List<ItemStack> resultVariants() { return copyList(resultVariants); }
    public List<ItemStack> buyAVariantsForDisplay() { return displayVariants(buyAVariants, buyAMin, buyAMax); }
    public List<ItemStack> buyBVariantsForDisplay() { return displayVariants(buyBVariants, buyBMin, buyBMax); }
    public List<ItemStack> resultVariantsForDisplay() { return displayVariants(resultVariants, resultMin, resultMax); }
    public String buyACountRange() { return countRange(buyAMin, buyAMax); }
    public String buyBCountRange() { return countRange(buyBMin, buyBMax); }
    public String resultCountRange() { return countRange(resultMin, resultMax); }
    public int uses() { return uses; }
    public int maxUses() { return maxUses; }
    public int xp() { return xp; }
    public float priceMultiplier() { return priceMultiplier; }
    public int demand() { return demand; }
    public int specialPrice() { return specialPrice; }
    public boolean rewardExp() { return rewardExp; }
    public List<Component> details() { return details; }

    public int nbtVariantCount(List<ItemStack> variants) {
        return (int) variants.stream().map(TradeRecipe::stackFingerprintIgnoringCount).distinct().count();
    }

    public ItemStack workstationStack() {
        if (workstations.isEmpty()) return ItemStack.EMPTY;
        net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(workstations.get(0));
        return block == null ? ItemStack.EMPTY : new ItemStack(block);
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return containsItem(buyAVariants, stack) || containsItem(buyBVariants, stack)
                || containsItem(resultVariants, stack) || workstationMatches(stack);
    }

    public boolean outputMatches(ItemStack stack) {
        return containsItem(resultVariants, stack);
    }

    private static boolean containsItem(List<ItemStack> variants, ItemStack stack) {
        return variants.stream().anyMatch(candidate -> candidate.is(stack.getItem()));
    }

    private boolean workstationMatches(ItemStack stack) {
        for (ResourceLocation id : workstations) {
            net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
            if (block != null && stack.is(block.asItem())) return true;
        }
        return false;
    }

    public String fingerprint() {
        return Objects.toString(entityType, "") + '|' + Objects.toString(profession, "") + '|'
                + workstations + '|' + level + '|'
                + itemFingerprint(buyA()) + '|'
                + itemFingerprint(buyB()) + '|'
                + itemFingerprint(result());
    }

    public String variantsFingerprint() {
        return fingerprint() + '|' + buyAVariants.stream().map(TradeRecipe::stackFingerprint).toList()
                + '|' + buyBVariants.stream().map(TradeRecipe::stackFingerprint).toList()
                + '|' + resultVariants.stream().map(TradeRecipe::stackFingerprint).toList();
    }

    private static String countRange(int min, int max) {
        return min == max ? "" : min + "-" + max;
    }

    private static List<ItemStack> displayVariants(List<ItemStack> variants, int min, int max) {
        Map<String, ItemStack> result = new LinkedHashMap<>();
        for (ItemStack stack : variants) {
            ItemStack copy = copy(stack);
            copy.setCount(min != max ? 1 : min);
            result.putIfAbsent(stackFingerprint(copy), copy);
        }
        return List.copyOf(result.values());
    }

    private static int count(ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }

    private static List<Component> unionDetails(List<Component> left, List<Component> right) {
        List<Component> result = new ArrayList<>(left);
        for (Component detail : right) {
            if (!result.contains(detail)) result.add(detail);
            if (result.size() >= 32) break;
        }
        return List.copyOf(result);
    }

    private static String stackFingerprintIgnoringCount(ItemStack stack) {
        CompoundTag tag = stack.save(new CompoundTag());
        tag.remove("Count");
        return tag.toString();
    }

    private static String itemFingerprint(ItemStack stack) {
        return Objects.toString(BuiltInRegistries.ITEM.getKey(stack.getItem()), "");
    }

    private static String stackFingerprint(ItemStack stack) {
        return stack.save(new CompoundTag()).toString();
    }
}
