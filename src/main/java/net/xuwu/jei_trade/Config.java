package net.xuwu.jei_trade;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Small server configuration for the synchronized trade catalog. */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_SERVER_CATALOG = BUILDER
            .comment("Send the server's villager and merchant trade catalog to clients.")
            .define("enableServerCatalog", true);

    public static final ForgeConfigSpec.BooleanValue OBSERVE_MERCHANTS = BUILDER
            .comment("Add offers from custom Merchant entities when players interact with them.")
            .define("observeMerchants", true);

    public static final ForgeConfigSpec.IntValue MAX_CATALOG_ENTRIES = BUILDER
            .comment("Maximum number of trade entries sent to one client.")
            .defineInRange("maxCatalogEntries", 10000, 100, 100000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}
