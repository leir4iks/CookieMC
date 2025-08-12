package io.canvasmc.canvas.entity.ai;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class AsyncGoalThread extends Thread {

    public static volatile boolean RUNNING = true;
    public AsyncGoalThread(final MinecraftServer server) {
        super(() -> run(server), "Leaf Async Goal Thread");
        this.setDaemon(false);
        this.setUncaughtExceptionHandler(Util::onThreadException);
        this.setPriority(Thread.NORM_PRIORITY - 1);
        this.start();
    }

    private static void run(MinecraftServer server) {
        while (RUNNING) {
            boolean retry = false;
            for (ServerLevel level : server.getAllLevels()) {
                retry |= level.levelTickData.asyncGoalExecutor.wakeAll();
                if (server.isRegionized()) {
                    level.regioniser.computeForAllRegionsUnsynchronised((region) -> region.getData().tickData.asyncGoalExecutor.wakeAll());
                }
            }

            if (!retry) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
    }
}
