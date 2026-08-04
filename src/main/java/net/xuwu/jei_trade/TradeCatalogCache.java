package net.xuwu.jei_trade;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Versioned, per-world binary cache for the completed synchronized trade catalog. */
final class TradeCatalogCache {
    private static final int MAGIC = 0x4A544344; // JTCD
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_RECIPES = 100_000;
    private static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
    private static final String CACHE_FILE = "trade_catalog-1.20.1.bin";

    private TradeCatalogCache() {
    }

    static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve(Jei_trade.MODID).resolve(CACHE_FILE);
    }

    static Optional<List<TradeRecipe>> load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        long size = Files.size(path);
        if (size <= 0 || size > MAX_CACHE_BYTES) {
            throw new IOException("Trade cache has invalid size: " + size);
        }

        byte[] bytes = Files.readAllBytes(path);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            if (buffer.readInt() != MAGIC) throw new IOException("Trade cache magic does not match");
            int version = buffer.readVarInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported trade cache format: " + version);
            }
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_RECIPES) {
                throw new IOException("Trade cache recipe count is invalid: " + count);
            }
            List<TradeRecipe> recipes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) recipes.add(TradeRecipe.read(buffer));
            return Optional.of(List.copyOf(recipes));
        } catch (RuntimeException ex) {
            throw new IOException("Could not decode trade cache", ex);
        } finally {
            buffer.release();
        }
    }

    static void save(Path path, List<TradeRecipe> recipes) throws IOException {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        byte[] bytes;
        try {
            buffer.writeInt(MAGIC);
            buffer.writeVarInt(FORMAT_VERSION);
            int count = Math.min(recipes.size(), MAX_RECIPES);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) recipes.get(i).write(buffer);
            if (buffer.readableBytes() > MAX_CACHE_BYTES) {
                throw new IOException("Trade cache exceeds " + MAX_CACHE_BYTES + " bytes");
            }
            bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
        } finally {
            buffer.release();
        }

        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
