package net.xuwu.jei_trade;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.trading.Merchant;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = Jei_trade.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerTradeEvents {
    private static final Map<MinecraftServer, TradeServerCatalog> CATALOGS = new WeakHashMap<>();
    private static final Map<MinecraftServer, BuildJob> BUILDERS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> REVISIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, String> STATUS = new WeakHashMap<>();
    private static final ExecutorService CACHE_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JEI Trade catalog cache");
        thread.setDaemon(true);
        return thread;
    });

    private ServerTradeEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("jeitrade")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rebuild").executes(context -> startRebuild(context.getSource())))
                .then(Commands.literal("status").executes(context -> reportStatus(context.getSource()))));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!Config.ENABLE_SERVER_CATALOG.get()) return;
        catalog(server).replace(List.of());
        long revision = nextRevision(server);
        setStatus(server, "loading cache");
        Path cachePath = TradeCatalogCache.path(server);

        CompletableFuture.supplyAsync(() -> {
            try {
                return TradeCatalogCache.load(cachePath);
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        }, CACHE_WORKER).whenComplete((cached, error) -> {
            if (!isCurrent(server, revision)) return;
            server.execute(() -> finishCacheLoad(server, revision, cachePath, cached, error));
        });
    }

    private static void finishCacheLoad(MinecraftServer server, long revision, Path cachePath,
                                        Optional<List<TradeRecipe>> cached, Throwable error) {
        if (!isCurrent(server, revision)) return;
        if (error != null) {
            setStatus(server, "cache load failed; run /jeitrade rebuild");
            Jei_trade.LOGGER.warn("Could not load trade catalog cache {}; run /jeitrade rebuild",
                    cachePath, unwrap(error));
            return;
        }
        if (cached.isEmpty()) {
            setStatus(server, "no cache; run /jeitrade rebuild");
            Jei_trade.LOGGER.info("No trade catalog cache found at {}; run /jeitrade rebuild once", cachePath);
            return;
        }

        TradeServerCatalog catalog = catalog(server);
        catalog.replace(cached.get());
        syncAll(server, catalog.snapshot());
        setStatus(server, "loaded " + catalog.snapshot().size() + " entries from cache");
        Jei_trade.LOGGER.info("Loaded {} synchronized trade entries from {}",
                catalog.snapshot().size(), cachePath);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        BuildJob job = builder(server);
        if (job == null) return;

        long budgetNanos = Math.max(1L, Config.REBUILD_BUDGET_MICROS.get()) * 1_000L;
        if (!job.session.advance(budgetNanos)) {
            setStatus(server, "rebuilding: " + job.session.progressDescription());
            return;
        }

        removeBuilder(server, job);
        List<TradeRecipe> recipes = job.session.snapshot();
        TradeServerCatalog catalog = catalog(server);
        catalog.replace(recipes);
        List<TradeRecipe> synchronizedRecipes = catalog.snapshot();
        syncAll(server, synchronizedRecipes);
        setStatus(server, "saving " + synchronizedRecipes.size() + " rebuilt entries");
        job.source.sendSuccess(() -> Component.literal("JEI Trade rebuild completed with "
                + synchronizedRecipes.size() + " entries; saving cache in the background."), true);

        Path cachePath = TradeCatalogCache.path(server);
        CompletableFuture.supplyAsync(() -> {
            if (!isCurrent(server, job.revision)) return false;
            try {
                TradeCatalogCache.save(cachePath, synchronizedRecipes);
                return true;
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        }, CACHE_WORKER).whenComplete((saved, error) -> {
            if (!isCurrent(server, job.revision)) return;
            server.execute(() -> finishCacheSave(server, job, cachePath, synchronizedRecipes.size(), saved, error));
        });
    }

    private static void finishCacheSave(MinecraftServer server, BuildJob job, Path cachePath,
                                        int recipeCount, Boolean saved, Throwable error) {
        if (!isCurrent(server, job.revision)) return;
        if (error != null) {
            setStatus(server, "rebuilt " + recipeCount + " entries, but cache save failed");
            job.source.sendFailure(Component.literal("JEI Trade rebuilt the catalog, but could not save the cache: "
                    + unwrap(error).getMessage()));
            Jei_trade.LOGGER.error("Could not save trade catalog cache {}", cachePath, unwrap(error));
            return;
        }
        if (!Boolean.TRUE.equals(saved)) return;
        setStatus(server, "ready: " + recipeCount + " entries cached");
        job.source.sendSuccess(() -> Component.literal("JEI Trade cache saved: " + cachePath), false);
        Jei_trade.LOGGER.info("Saved {} synchronized trade entries to {}", recipeCount, cachePath);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        synchronized (ServerTradeEvents.class) {
            BUILDERS.remove(server);
            CATALOGS.remove(server);
            REVISIONS.remove(server);
            STATUS.remove(server);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Config.ENABLE_SERVER_CATALOG.get()) return;
        TradeNetworking.send(player, catalog(player.server).snapshot());
    }

    @SubscribeEvent
    public static void onMerchantInteract(PlayerInteractEvent.EntityInteract event) {
        if (!Config.OBSERVE_MERCHANTS.get() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof Merchant merchant)) return;
        player.server.execute(() -> {
            if (merchant.getOffers() == null || merchant.getOffers().isEmpty()) return;
            TradeServerCatalog catalog = catalog(player.server);
            int level = merchant instanceof VillagerDataHolder holder
                    ? holder.getVillagerData().getLevel() : 0;
            java.util.List<TradeRecipe> observed = new java.util.ArrayList<>();
            for (var offer : merchant.getOffers()) {
                net.minecraft.resources.ResourceLocation entityId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
                net.minecraft.resources.ResourceLocation professionId = null;
                java.util.List<net.minecraft.resources.ResourceLocation> workstations = java.util.List.of();
                if (merchant instanceof VillagerDataHolder holder) {
                    var profession = holder.getVillagerData().getProfession();
                    professionId = net.minecraftforge.registries.ForgeRegistries.VILLAGER_PROFESSIONS.getKey(profession);
                    workstations = TradeCatalogBuilder.findWorkstations(target.level(), profession);
                }
                observed.add(TradeRecipe.fromOffer(entityId, professionId, workstations, level, offer));
            }
            if (catalog.addAll(observed)) syncAll(player.server, catalog.snapshot());
        });
    }

    private static int startRebuild(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (!Config.ENABLE_SERVER_CATALOG.get()) {
            source.sendFailure(Component.literal("JEI Trade server catalog is disabled in the server config."));
            return 0;
        }
        synchronized (ServerTradeEvents.class) {
            BuildJob existing = BUILDERS.get(server);
            if (existing != null) {
                source.sendFailure(Component.literal("A JEI Trade rebuild is already running: "
                        + existing.session.progressDescription()));
                return 0;
            }
            ServerLevel level = server.overworld();
            long revision = nextRevisionLocked(server);
            BuildJob job = new BuildJob(TradeCatalogBuilder.session(level), source, revision);
            BUILDERS.put(server, job);
            STATUS.put(server, "rebuilding: " + job.session.progressDescription());
        }
        source.sendSuccess(() -> Component.literal("Started JEI Trade 100-sample rebuild. "
                + "Sampling is spread across server ticks; cache work runs on a dedicated background thread."), true);
        return 1;
    }

    private static int reportStatus(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        BuildJob job = builder(server);
        String status = job == null ? status(server) : "rebuilding: " + job.session.progressDescription();
        source.sendSuccess(() -> Component.literal("JEI Trade status: " + status), false);
        return 1;
    }

    private static void syncAll(MinecraftServer server, List<TradeRecipe> recipes) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            TradeNetworking.send(online, recipes);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static synchronized TradeServerCatalog catalog(MinecraftServer server) {
        return CATALOGS.computeIfAbsent(server, ignored -> new TradeServerCatalog());
    }

    private static synchronized BuildJob builder(MinecraftServer server) {
        return BUILDERS.get(server);
    }

    private static synchronized void removeBuilder(MinecraftServer server, BuildJob expected) {
        if (BUILDERS.get(server) == expected) BUILDERS.remove(server);
    }

    private static synchronized long nextRevision(MinecraftServer server) {
        return nextRevisionLocked(server);
    }

    private static long nextRevisionLocked(MinecraftServer server) {
        long revision = REVISIONS.getOrDefault(server, 0L) + 1L;
        REVISIONS.put(server, revision);
        return revision;
    }

    private static synchronized boolean isCurrent(MinecraftServer server, long revision) {
        return REVISIONS.getOrDefault(server, -1L) == revision;
    }

    private static synchronized void setStatus(MinecraftServer server, String status) {
        STATUS.put(server, status);
    }

    private static synchronized String status(MinecraftServer server) {
        return STATUS.getOrDefault(server, "idle");
    }

    private static final class BuildJob {
        private final TradeCatalogBuilder.Session session;
        private final CommandSourceStack source;
        private final long revision;

        private BuildJob(TradeCatalogBuilder.Session session, CommandSourceStack source, long revision) {
            this.session = session;
            this.source = source;
            this.revision = revision;
        }
    }
}
