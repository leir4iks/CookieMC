package io.canvasmc.canvas.entity.pathfinding;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.canvasmc.canvas.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * used to handle the scheduling of async path processing
 */
public class AsyncPathProcessor {

    private static final String THREAD_PREFIX = "Canvas Async Pathfinding";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static final ThreadPoolExecutor pathProcessingExecutor;
    private static long lastWarnMillis = System.currentTimeMillis();

    static {
        pathProcessingExecutor = new ThreadPoolExecutor(
            Config.INSTANCE.entities.pathfinding.maxProcessors,
            Config.INSTANCE.entities.pathfinding.maxProcessors,
            Config.INSTANCE.entities.pathfinding.keepAlive, TimeUnit.SECONDS,
            getQueueImpl(),
            new ThreadFactoryBuilder()
                .setNameFormat(THREAD_PREFIX + " Thread - %d")
                .setPriority(Thread.NORM_PRIORITY - 2)
                .build(),
            new RejectedTaskHandler()
        );
        LOGGER.info("Using {} threads for Async Pathfinding", Config.INSTANCE.entities.pathfinding.maxProcessors);
    }

    protected static @NotNull CompletableFuture<Void> queue(@NotNull AsyncPath path) {
        return CompletableFuture.runAsync(path::process, pathProcessingExecutor)
            .orTimeout(60L, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                if (throwable instanceof TimeoutException e) {
                    LOGGER.warn("Async Pathfinding process timed out", e);
                } else LOGGER.warn("Error occurred while processing async path", throwable);
                return null;
            });
    }

    /**
     * takes a possibly unprocessed path, and waits until it is completed
     * the consumer will be immediately invoked if the path is already processed
     * the consumer will always be called on the main thread
     *
     * @param path            a path to wait on
     * @param afterProcessing a consumer to be called
     */
    public static void awaitProcessing(@NotNull ServerLevel world, @Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        if (path != null && !path.isProcessed() && path instanceof AsyncPath asyncPath) {
            asyncPath.postProcessing((blockPos) -> {
                Runnable task = () -> afterProcessing.accept(path);
                int chunkX = blockPos.getX() >> 4;
                int chunkZ = blockPos.getZ() >> 4;
                world.server.threadedServer().taskQueue.queueTickTaskQueue(
                    world, chunkX, chunkZ, task
                );
            });
        } else {
            afterProcessing.accept(path);
        }
    }

    @Contract(" -> new")
    private static @NotNull BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = Config.INSTANCE.entities.pathfinding.asyncPathfindingQueueSize;

        return new LinkedBlockingQueue<>(queueCapacity);
    }

    private static class RejectedTaskHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable rejectedTask, @NotNull ThreadPoolExecutor executor) {
            BlockingQueue<Runnable> workQueue = executor.getQueue();
            if (!executor.isShutdown()) {
                switch (Config.INSTANCE.entities.pathfinding.asyncPathfindingRejectPolicy) {
                    case FLUSH_ALL -> {
                        if (!workQueue.isEmpty()) {
                            List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                            workQueue.drainTo(pendingTasks);

                            for (Runnable pendingTask : pendingTasks) {
                                pendingTask.run();
                            }
                        }
                        rejectedTask.run();
                    }
                    case CALLER_RUNS -> rejectedTask.run();
                }
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async pathfinding processor is busy! Pathfinding tasks will be treated as policy defined in config. Increasing max-threads in Canvas config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        }
    }
}
