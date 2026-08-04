package net.xuwu.jei_trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
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
    private final int uses;
    private final int maxUses;
    private final int xp;
    private final float priceMultiplier;
    private final int demand;
    private final int specialPrice;
    private final boolean rewardExp;

    public TradeRecipe(ResourceLocation entityType, ResourceLocation profession,
                       List<ResourceLocation> workstations, int level,
                       ItemStack buyA, ItemStack buyB, ItemStack result,
                       int uses, int maxUses, int xp, float priceMultiplier,
                       int demand, int specialPrice, boolean rewardExp) {
        this(entityType, profession, workstations, level,
                List.of(copy(buyA)), List.of(copy(buyB)), List.of(copy(result)),
                uses, maxUses, xp, priceMultiplier, demand, specialPrice, rewardExp);
    }

    private TradeRecipe(ResourceLocation entityType, ResourceLocation profession,
                        List<ResourceLocation> workstations, int level,
                        List<ItemStack> buyAVariants, List<ItemStack> buyBVariants,
                        List<ItemStack> resultVariants, int uses, int maxUses,
                        int xp, float priceMultiplier, int demand, int specialPrice,
                        boolean rewardExp) {
        this.entityType = entityType;
        this.profession = profession;
        this.workstations = List.copyOf(workstations);
        this.level = level;
        this.buyAVariants = copyList(buyAVariants);
        this.buyBVariants = copyList(buyBVariants);
        this.resultVariants = copyList(resultVariants);
        this.uses = uses;
        this.maxUses = maxUses;
        this.xp = xp;
        this.priceMultiplier = priceMultiplier;
        this.demand = demand;
        this.specialPrice = specialPrice;
        this.rewardExp = rewardExp;
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

    /** Creates a one-variant recipe using the metadata from an existing recipe. */
    public static TradeRecipe withVariantStacks(TradeRecipe base,
                                                ItemStack buyA, ItemStack buyB, ItemStack result) {
        return new TradeRecipe(base.entityType, base.profession, base.workstations, base.level,
                List.of(copy(buyA)), List.of(copy(buyB)), List.of(copy(result)),
                base.uses, base.maxUses, base.xp, base.priceMultiplier,
                base.demand, base.specialPrice, base.rewardExp);
    }

    /** Combines random/NBT variants of the same logical trade without losing any stack. */
    public static TradeRecipe merge(TradeRecipe left, TradeRecipe right) {
        if (!left.fingerprint().equals(right.fingerprint())) return left;
        return new TradeRecipe(left.entityType, left.profession, left.workstations, left.level,
                union(left.buyAVariants, right.buyAVariants),
                union(left.buyBVariants, right.buyBVariants),
                union(left.resultVariants, right.resultVariants),
                left.uses, Math.max(left.maxUses, right.maxUses), left.xp,
                left.priceMultiplier, left.demand, left.specialPrice, left.rewardExp);
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
        return new TradeRecipe(entityType, profession, workstations, level, buyA, buyB, result,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
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
        for (ItemStack stack : left) result.put(stackFingerprint(stack), copy(stack));
        for (ItemStack stack : right) {
            if (result.size() >= MAX_VARIANTS) break;
            result.putIfAbsent(stackFingerprint(stack), copy(stack));
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
    public List<ItemStack> buyAVariantsForDisplay() { return displayVariants(buyAVariants); }
    public List<ItemStack> buyBVariantsForDisplay() { return displayVariants(buyBVariants); }
    public List<ItemStack> resultVariantsForDisplay() { return displayVariants(resultVariants); }
    public String buyACountRange() { return countRange(buyAVariants); }
    public String buyBCountRange() { return countRange(buyBVariants); }
    public String resultCountRange() { return countRange(resultVariants); }
    public int uses() { return uses; }
    public int maxUses() { return maxUses; }
    public int xp() { return xp; }
    public float priceMultiplier() { return priceMultiplier; }
    public int demand() { return demand; }
    public int specialPrice() { return specialPrice; }
    public boolean rewardExp() { return rewardExp; }

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
                + stackFingerprintIgnoringCount(buyA()) + '|'
                + stackFingerprintIgnoringCount(buyB()) + '|'
                + itemFingerprint(result());
    }

    public String variantsFingerprint() {
        return fingerprint() + '|' + buyAVariants.stream().map(TradeRecipe::stackFingerprint).toList()
                + '|' + buyBVariants.stream().map(TradeRecipe::stackFingerprint).toList()
                + '|' + resultVariants.stream().map(TradeRecipe::stackFingerprint).toList();
    }

    private static String countRange(List<ItemStack> variants) {
        int min = variants.stream().mapToInt(ItemStack::getCount).min().orElse(1);
        int max = variants.stream().mapToInt(ItemStack::getCount).max().orElse(min);
        return min == max ? "" : min + "-" + max;
    }

    private static List<ItemStack> displayVariants(List<ItemStack> variants) {
        if (countRange(variants).isEmpty()) return copyList(variants);
        return variants.stream().map(stack -> {
            ItemStack copy = copy(stack);
            copy.setCount(1);
            return copy;
        }).toList();
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
