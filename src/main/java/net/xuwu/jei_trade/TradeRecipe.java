package net.xuwu.jei_trade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable JEI-facing representation of one merchant offer. */
public final class TradeRecipe {
    private final ResourceLocation entityType;
    private final ResourceLocation profession;
    private final List<ResourceLocation> workstations;
    private final int level;
    private final ItemStack buyA;
    private final ItemStack buyB;
    private final ItemStack result;
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
        this.entityType = entityType;
        this.profession = profession;
        this.workstations = List.copyOf(workstations);
        this.level = level;
        this.buyA = copy(buyA);
        this.buyB = copy(buyB);
        this.result = copy(result);
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

    public static TradeRecipe read(FriendlyByteBuf buf) {
        ResourceLocation entityType = readOptionalLocation(buf);
        ResourceLocation profession = readOptionalLocation(buf);
        int workstationCount = buf.readVarInt();
        List<ResourceLocation> workstations = new ArrayList<>(workstationCount);
        for (int i = 0; i < workstationCount; i++) {
            workstations.add(buf.readResourceLocation());
        }
        int level = buf.readVarInt();
        ItemStack buyA = buf.readItem();
        ItemStack buyB = buf.readBoolean() ? buf.readItem() : ItemStack.EMPTY;
        ItemStack result = buf.readItem();
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
        buf.writeItem(buyA);
        buf.writeBoolean(!buyB.isEmpty());
        if (!buyB.isEmpty()) {
            buf.writeItem(buyB);
        }
        buf.writeItem(result);
        buf.writeVarInt(uses);
        buf.writeVarInt(maxUses);
        buf.writeVarInt(xp);
        buf.writeFloat(priceMultiplier);
        buf.writeVarInt(demand);
        buf.writeVarInt(specialPrice);
        buf.writeBoolean(rewardExp);
    }

    private static void writeOptionalLocation(FriendlyByteBuf buf, ResourceLocation location) {
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    private static ResourceLocation readOptionalLocation(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public ResourceLocation entityType() { return entityType; }
    public ResourceLocation profession() { return profession; }
    public List<ResourceLocation> workstations() { return workstations; }
    public int level() { return level; }
    public ItemStack buyA() { return buyA.copy(); }
    public ItemStack buyB() { return buyB.copy(); }
    public ItemStack result() { return result.copy(); }
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
        return !stack.isEmpty() && (stack.is(buyA.getItem()) || stack.is(buyB.getItem())
                || stack.is(result.getItem()) || workstationMatches(stack));
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
                + workstations + '|' + level + '|' + stackFingerprint(buyA) + '|' + stackFingerprint(buyB)
                + '|' + stackFingerprint(result) + '|' + xp + '|' + priceMultiplier;
    }

    private static String stackFingerprint(ItemStack stack) {
        CompoundTag tag = stack.save(new CompoundTag());
        return tag.toString();
    }
}
