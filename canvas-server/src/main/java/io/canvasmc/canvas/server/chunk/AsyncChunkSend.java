package io.canvasmc.canvas.server.chunk;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AsyncChunkSend {

    public static final ExecutorService POOL = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        new ThreadFactoryBuilder()
            .setPriority(Thread.NORM_PRIORITY)
            .setNameFormat("Canvas Async Chunk Send Thread")
            .setUncaughtExceptionHandler(Util::onThreadException)
            .setThreadFactory(AsyncChunkSendThread::new)
            .build(),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    public static final Logger LOGGER = LogManager.getLogger("Canvas Async Chunk Send");

    public static class AsyncChunkSendThread extends Thread {

        protected AsyncChunkSendThread(Runnable task) {
            super(task);
        }
    }
}
