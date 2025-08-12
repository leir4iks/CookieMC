package io.canvasmc.canvas.entity;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.canvasmc.canvas.Config;
import io.canvasmc.canvas.region.ServerRegions;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MultithreadedTracker {

    private static final String THREAD_PREFIX = "Canvas Async Tracker";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static long lastWarnMillis = System.currentTimeMillis();
    public static ThreadPoolExecutor TRACKER_EXECUTOR = null;

    private MultithreadedTracker() {
    }

    public static void init() {
        if (TRACKER_EXECUTOR == null) {
            TRACKER_EXECUTOR = new ThreadPoolExecutor(
                getCorePoolSize(),
                getMaxPoolSize(),
                getKeepAliveTime(), TimeUnit.SECONDS,
                getQueueImpl(),
                getThreadFactory(),
                getRejectedPolicy()
            );
        } else {
            // Temp no-op
            //throw new IllegalStateException();
        }
    }

    public static void tick(ServerLevel level) {
        try {
            if (!Config.INSTANCE.entities.entityTracking.compatModeEnabled) {
                tickAsync(level);
            } else {
                tickAsyncWithCompatMode(level);
            }
        } catch (Exception e) {
            LOGGER.error("Error occurred while executing async task.", e);
        }
    }

    private static void tickAsync(ServerLevel world) {
        final NearbyPlayers nearbyPlayers = world.moonrise$getNearbyPlayers();
        final Entity[] trackerEntitiesRaw = ServerRegions.getTickData(world).trackerEntities.getRawDataUnchecked();

        // Move tracking to off-main
        TRACKER_EXECUTOR.execute(() -> {
            for (final Entity entity : trackerEntitiesRaw) {
                if (entity == null) continue;

                final ChunkMap.TrackedEntity trackedInstance = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();
                if (trackedInstance == null) {
                    continue;
                }

                synchronized (trackedInstance) {
                    trackedInstance.moonrise$tick(nearbyPlayers.getChunk(entity.chunkPosition()));
                    @Nullable FullChunkStatus chunkStatus = ((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity).moonrise$getChunkStatus();
                    if ((trackedInstance).moonrise$hasPlayers()
                        || (chunkStatus == null || chunkStatus.isOrAfter(FullChunkStatus.ENTITY_TICKING))) {
                        trackedInstance.serverEntity.sendChanges();
                    }
                }
            }
        });
    }

    private static void tickAsyncWithCompatMode(ServerLevel level) {
        ServerRegions.WorldTickData tickData = ServerRegions.getTickData(level);
        final NearbyPlayers nearbyPlayers = tickData.getNearbyPlayers();

        final ReferenceList<Entity> trackerEntities = tickData.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final Runnable[] sendChangesTasks = new Runnable[trackerEntitiesRaw.length];
        final Runnable[] tickTask = new Runnable[trackerEntitiesRaw.length];
        int index = 0;

        for (final Entity entity : trackerEntitiesRaw) {
            if (entity == null) continue;

            final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();

            if (tracker == null) continue;

            synchronized (tracker) {
                tickTask[index] = tracker.leafTickCompact(nearbyPlayers.getChunk(entity.chunkPosition()));
                sendChangesTasks[index] = () -> tracker.serverEntity.sendChanges(); // Collect send changes to task array
            }
            index++;
        }

        // batch submit tasks
        TRACKER_EXECUTOR.execute(() -> {
            for (final Runnable tick : tickTask) {
                if (tick == null) continue;

                tick.run();
            }
            for (final Runnable sendChanges : sendChangesTasks) {
                if (sendChanges == null) continue;

                sendChanges.run();
            }
        });
    }

    private static int getCorePoolSize() {
        return 1;
    }

    private static int getMaxPoolSize() {
        return Config.INSTANCE.entities.entityTracking.asyncEntityTrackerMaxThreads;
    }

    private static long getKeepAliveTime() {
        return Config.INSTANCE.entities.entityTracking.asyncEntityTrackerKeepalive;
    }

    private static BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = Config.INSTANCE.entities.entityTracking.asyncEntityTrackerQueueSize;

        return new LinkedBlockingQueue<>(queueCapacity);
    }

    private static @NotNull ThreadFactory getThreadFactory() {
        return new ThreadFactoryBuilder()
            .setThreadFactory(MultithreadedTrackerThread::new)
            .setNameFormat(THREAD_PREFIX + " Thread - %d")
            .setPriority(Thread.NORM_PRIORITY - 2)
            .setUncaughtExceptionHandler(Util::onThreadException)
            .build();
    }

    private static @NotNull RejectedExecutionHandler getRejectedPolicy() {
        return (rejectedTask, executor) -> {
            BlockingQueue<Runnable> workQueue = executor.getQueue();

            if (!executor.isShutdown()) {
                if (!workQueue.isEmpty()) {
                    List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                    workQueue.drainTo(pendingTasks);

                    for (Runnable pendingTask : pendingTasks) {
                        pendingTask.run();
                    }
                }

                rejectedTask.run();
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async entity tracker is busy! Tracking tasks will be done in the server thread. Increasing max-threads in Leaf config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        };
    }

    public static class MultithreadedTrackerThread extends TickThread {

        public MultithreadedTrackerThread(Runnable runnable) {
            super(runnable);
        }
    }
}
