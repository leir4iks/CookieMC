package io.canvasmc.canvas.entity.tnt;

import io.canvasmc.canvas.region.ServerRegions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;

public class TNTMergeManager {
    public static void onEntityUnload(@NotNull Entity entity) {
        if (entity.getType() == EntityType.TNT)
            ServerRegions.getTickData(entity.level().level()).tntCount.decrementAndGet();
    }

    public static void onEntityLoad(@NotNull Entity entity) {
        if (entity.getType() == EntityType.TNT)
            ServerRegions.getTickData(entity.level().level()).tntCount.incrementAndGet();
    }
}
