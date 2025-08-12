package io.canvasmc.canvas.scheduler;

import io.canvasmc.canvas.region.ServerRegions;

public interface TickableScheduler {
    void tick(ServerRegions.WorldTickData tickData);
}
