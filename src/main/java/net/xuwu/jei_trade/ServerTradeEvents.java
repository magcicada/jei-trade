package net.xuwu.jei_trade;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.trading.Merchant;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
    private static final ExecutorService SAMPLE_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JEI Trade catalog sampling");
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
            setStatus(server, "cache load failed; building normal catalog");
            Jei_trade.LOGGER.warn("Could not load trade catalog cache {}; starting normal catalog build",
                    cachePath, unwrap(error));
            startNormalBuild(server, revision);
            return;
        }
        if (cached.isEmpty()) {
            setStatus(server, "no cache; building normal catalog");
            Jei_trade.LOGGER.info("No trade catalog cache found at {}; starting normal catalog build", cachePath);
            startNormalBuild(server, revision);
            return;
        }

        TradeServerCatalog catalog = catalog(server);
        catalog.replace(cached.get());
        syncAll(server, catalog.snapshot());
        setStatus(server, "loaded " + catalog.snapshot().size() + " entries from cache");
        Jei_trade.LOGGER.info("Loaded {} synchronized trade entries from {}",
                catalog.snapshot().size(), cachePath);
    }

    private static void finishBuild(MinecraftServer server, BuildJob job,
                                    List<TradeRecipe> recipes, Throwable error) {
        if (!isCurrent(server, job.revision) || builder(server) != job) return;
        if (error != null) {
            removeBuilder(server, job);
            setStatus(server, job.extended
                    ? "real-entity rebuild failed"
                    : "normal 100-sample catalog build failed");
            if (job.source != null) {
                job.source.sendFailure(Component.literal("JEI Trade catalog build failed: "
                        + unwrap(error).getMessage()));
            }
            Jei_trade.LOGGER.error("Could not build JEI Trade catalog", unwrap(error));
            return;
        }

        removeBuilder(server, job);
        TradeServerCatalog catalog = catalog(server);
        catalog.replace(recipes == null ? List.of() : recipes);
        List<TradeRecipe> synchronizedRecipes = catalog.snapshot();
        syncAll(server, synchronizedRecipes);
        String mode = job.extended ? "extended real-entity" : "normal null-listing/100-entity-sample";
        setStatus(server, "saving " + synchronizedRecipes.size() + " " + mode + " entries");
        if (job.source != null) {
            job.source.sendSuccess(() -> Component.literal("JEI Trade " + mode
                    + " catalog build completed with " + synchronizedRecipes.size()
                    + (job.extended ? " entries; saving cache in the background." : " entries.")), true);
        }
        if (!job.extended) {
            setStatus(server, "ready: " + synchronizedRecipes.size()
                    + " normal entries; run /jeitrade rebuild to cache 100 custom samples");
            Jei_trade.LOGGER.info("Normal null-listing/100-entity-sample catalog is ready with {} entries; it was not cached",
                    synchronizedRecipes.size());
            return;
        }

        Path cachePath = TradeCatalogCache.path(server);
        CompletableFuture.supplyAsync(() -> {
            if (!isCurrent(server, job.revision)) return false;
            try {
                TradeCatalogCache.save(cachePath, synchronizedRecipes);
                return true;
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        }, CACHE_WORKER).whenComplete((saved, saveError) -> {
            if (!isCurrent(server, job.revision)) return;
            server.execute(() -> finishCacheSave(server, job, cachePath,
                    synchronizedRecipes.size(), saved, saveError));
        });
    }

    private static void finishCacheSave(MinecraftServer server, BuildJob job, Path cachePath,
                                        int recipeCount, Boolean saved, Throwable error) {
        if (!isCurrent(server, job.revision)) return;
        if (error != null) {
            setStatus(server, "rebuilt " + recipeCount + " entries, but cache save failed");
            if (job.source != null) {
                job.source.sendFailure(Component.literal("JEI Trade rebuilt the catalog, but could not save the cache: "
                        + unwrap(error).getMessage()));
            }
            Jei_trade.LOGGER.error("Could not save trade catalog cache {}", cachePath, unwrap(error));
            return;
        }
        if (!Boolean.TRUE.equals(saved)) return;
        setStatus(server, "ready: " + recipeCount + " entries cached");
        if (job.source != null) {
            job.source.sendSuccess(() -> Component.literal("JEI Trade cache saved: " + cachePath), false);
        }
        Jei_trade.LOGGER.info("Saved {} synchronized trade entries to {}", recipeCount, cachePath);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        synchronized (ServerTradeEvents.class) {
            BuildJob job = BUILDERS.remove(server);
            if (job != null && job.future != null) job.future.cancel(true);
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
                        + status(server)));
                return 0;
            }
            long revision = nextRevisionLocked(server);
            BuildJob job = new BuildJob(source, revision, true);
            BUILDERS.put(server, job);
            STATUS.put(server, "rebuilding with real entities in background (100 samples)");
            launchBuild(server, job);
        }
        source.sendSuccess(() -> Component.literal("Started JEI Trade extended 100-sample rebuild. "
                + (ModList.get().isLoaded("c2me")
                ? "C2ME-safe sampling uses short server-thread slices; orchestration runs in the background."
                : "Real-entity sampling runs on a dedicated background thread.")), true);
        return 1;
    }

    private static void startNormalBuild(MinecraftServer server, long revision) {
        synchronized (ServerTradeEvents.class) {
            if (!isCurrent(server, revision) || BUILDERS.containsKey(server)) return;
            BuildJob job = new BuildJob(null, revision, false);
            BUILDERS.put(server, job);
            STATUS.put(server, ModList.get().isLoaded("c2me")
                    ? "building normal catalog through C2ME-safe background bridge"
                    : "building normal catalog in background");
            launchBuild(server, job);
        }
        Jei_trade.LOGGER.info("Started normal null-listing/100-entity-sample trade catalog discovery in background");
    }

    private static void launchBuild(MinecraftServer server, BuildJob job) {
        CompletableFuture<List<TradeRecipe>> future = CompletableFuture.supplyAsync(
                () -> buildCatalog(server, job.extended), SAMPLE_WORKER);
        job.future = future;
        future.whenComplete((recipes, error) -> {
            if (!isCurrent(server, job.revision)) return;
            server.execute(() -> finishBuild(server, job, recipes, error));
        });
    }

    private static List<TradeRecipe> buildCatalog(MinecraftServer server, boolean extended) {
        if (!ModList.get().isLoaded("c2me")) {
            return extended
                    ? TradeCatalogBuilder.buildReal(server.overworld())
                    : TradeCatalogBuilder.buildNormal(server.overworld());
        }

        return buildCatalogThroughServerThread(server, extended);
    }

    /**
     * C2ME protects the server world's random source and rejects entity/world calls from worker
     * threads. Keep the catalog worker and its cancellation semantics, but execute only short
     * Minecraft sampling slices on the server thread.
     */
    private static List<TradeRecipe> buildCatalogThroughServerThread(MinecraftServer server,
                                                                       boolean extended) {
        CompletableFuture<TradeCatalogBuilder.Session> created = new CompletableFuture<>();
        server.execute(() -> {
            try {
                created.complete(extended
                        ? TradeCatalogBuilder.extendedSession(server.overworld())
                        : TradeCatalogBuilder.session(server.overworld()));
            } catch (Throwable error) {
                created.completeExceptionally(error);
            }
        });
        TradeCatalogBuilder.Session session = await(created);
        long budgetNanos = Math.max(1L, Config.REBUILD_BUDGET_MICROS.get()) * 1_000L;
        while (true) {
            if (!server.isRunning() || Thread.currentThread().isInterrupted()) {
                throw new CompletionException(new java.util.concurrent.CancellationException(
                        "Minecraft server stopped during C2ME-safe trade sampling"));
            }
            CompletableFuture<Boolean> step = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    step.complete(session.advance(budgetNanos));
                } catch (Throwable error) {
                    step.completeExceptionally(error);
                }
            });
            if (await(step)) return session.snapshot();
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompletionException(error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw cause instanceof CompletionException completion ? completion
                    : new CompletionException(cause);
        }
    }

    private static int reportStatus(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("JEI Trade status: " + status(server)), false);
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
        private final CommandSourceStack source;
        private final long revision;
        private final boolean extended;
        private volatile CompletableFuture<List<TradeRecipe>> future;

        private BuildJob(CommandSourceStack source, long revision, boolean extended) {
            this.source = source;
            this.revision = revision;
            this.extended = extended;
        }
    }
}
