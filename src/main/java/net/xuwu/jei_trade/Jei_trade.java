package net.xuwu.jei_trade;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Server-aware data provider for the JEI villager trade viewer. */
@Mod(Jei_trade.MODID)
public final class Jei_trade {
    public static final String MODID = "jei_trade";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jei_trade() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        TradeNetworking.register();
        Config.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
    }
}
