package io.cookiemc.cookie.locate;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.datafixers.util.Pair;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;

public class AsyncLocator {

    private static final ExecutorService LOCATING_EXECUTOR_SERVICE;

    static {
        int threads = io.cookiemc.cookie.Config.INSTANCE.asyncLocator.asyncLocatorThreads;
        LOCATING_EXECUTOR_SERVICE = new ThreadPoolExecutor(
            1,
            threads,
            io.cookiemc.cookie.Config.INSTANCE.asyncLocator.asyncLocatorKeepalive,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder()
                .setThreadFactory(
                    r -> new AsyncLocatorThread(r, "cookie Async Locator Thread") {
                        @Override
                        public void run() {
                            r.run();
                        }
                    }
                )
                .setNameFormat("cookie Async Locator Thread - %d")
                .setPriority(Thread.NORM_PRIORITY - 2)
                .build()
        );
    }

    private AsyncLocator() {
    }

    public static void shutdownExecutorService() {
        if (LOCATING_EXECUTOR_SERVICE != null) {
            LOCATING_EXECUTOR_SERVICE.shutdown();
        }
    }

    /**
     * Queues a task to locate a feature using {@link ServerLevel#findNearestMapStructure(TagKey, BlockPos, int, boolean)}
     * and returns a {@link LocateTask} with the futures for it.
     */
    public static LocateTask<BlockPos> locate(
        ServerLevel level,
        TagKey<Structure> structureTag,
        BlockPos pos,
        int searchRadius,
        boolean skipKnownStructures
    ) {
        CompletableFuture<BlockPosInstance<BlockPos>> completableFuture = new CompletableFuture<>();
        Future<?> future = LOCATING_EXECUTOR_SERVICE.submit(
            () -> doLocateLevel(completableFuture, level, structureTag, pos, searchRadius, skipKnownStructures)
        );
        return new LocateTask<>(level, completableFuture, future);
    }

    /**
     * Queues a task to locate a feature using
     * {@link ChunkGenerator#findNearestMapStructure(ServerLevel, HolderSet, BlockPos, int, boolean)} and returns a
     * {@link LocateTask} with the futures for it.
     */
    public static LocateTask<Pair<BlockPos, Holder<Structure>>> locate(
        ServerLevel level,
        HolderSet<Structure> structureSet,
        BlockPos pos,
        int searchRadius,
        boolean skipKnownStructures
    ) {
        CompletableFuture<BlockPosInstance<Pair<BlockPos, Holder<Structure>>>> completableFuture = new CompletableFuture<>();
        Future<?> future = LOCATING_EXECUTOR_SERVICE.submit(
            () -> doLocateChunkGenerator(completableFuture, level, structureSet, pos, searchRadius, skipKnownStructures)
        );
        return new LocateTask<>(level, completableFuture, future);
    }

    private static void doLocateLevel(
        CompletableFuture<BlockPosInstance<BlockPos>> completableFuture,
        ServerLevel level,
        TagKey<Structure> structureTag,
        BlockPos pos,
        int searchRadius,
        boolean skipExistingChunks
    ) {
        BlockPos foundPos = level.findNearestMapStructure(structureTag, pos, searchRadius, skipExistingChunks);
        completableFuture.complete(new BlockPosInstance<BlockPos>() {
            @Override
            public BlockPos getBlockPos() {
                return get();
            }

            @Override
            public BlockPos get() {
                return foundPos;
            }
        });
    }

    private static void doLocateChunkGenerator(
        CompletableFuture<BlockPosInstance<Pair<BlockPos, Holder<Structure>>>> completableFuture,
        ServerLevel level,
        HolderSet<Structure> structureSet,
        BlockPos pos,
        int searchRadius,
        boolean skipExistingChunks
    ) {
        Pair<BlockPos, Holder<Structure>> foundPair = level.getChunkSource().getGenerator()
            .findNearestMapStructure(level, structureSet, pos, searchRadius, skipExistingChunks);
        completableFuture.complete(new BlockPosInstance<>() {
            @Override
            public BlockPos getBlockPos() {
                return get().getFirst();
            }

            @Override
            public Pair<BlockPos, Holder<Structure>> get() {
                return foundPair;
            }
        });
    }

    public static class AsyncLocatorThread extends TickThread {
        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

        public AsyncLocatorThread(Runnable run, String name) {
            super(null, run, name, THREAD_COUNTER.incrementAndGet());
        }

        @Override
        public void run() {
            super.run();
        }
    }

    /**
     * Holder of the futures for an async locate task as well as providing some helper functions.
     * The completableFuture will be completed once the call to
     * {@link ServerLevel#findNearestMapStructure(TagKey, BlockPos, int, boolean)} has completed, and will hold the
     * result of it.
     * The taskFuture is the future for the {@link Runnable} itself in the executor service.
     */
    public record LocateTask<T>(ServerLevel world, CompletableFuture<BlockPosInstance<T>> completableFuture, Future<?> taskFuture) {
        /**
         * Helper function that calls {@link CompletableFuture#thenAccept(Consumer)} with the given action.
         * Bear in mind that the action will be executed from the task's thread. If you intend to change any game data,
         * it's strongly advised you use {@link #thenOnServerThread(Consumer)} instead so that it's queued and executed
         * on the main server thread instead.
         */
        public LocateTask<T> then(Consumer<BlockPosInstance<T>> action) {
            completableFuture.thenAccept(action);
            return this;
        }

        /**
         * Helper function that calls {@link CompletableFuture#thenAccept(Consumer)} with the given action on the server
         * thread.
         */
        public LocateTask<T> thenOnServerThread(Consumer<BlockPosInstance<T>> action) {
            completableFuture.thenAccept(posInstance -> {
                BlockPos pos = posInstance.getBlockPos();
                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                MinecraftServer.getThreadedServer().taskQueue.queueTickTaskQueue(
                    this.world, chunkX, chunkZ, () -> action.accept(posInstance)
                );
            });
            return this;
        }

        /**
         * Helper function that cancels both completableFuture and taskFuture.
         */
        public void cancel() {
            taskFuture.cancel(true);
            completableFuture.cancel(false);
        }
    }

    public interface BlockPosInstance<T> {
        BlockPos getBlockPos();
        T get();
    }
}
