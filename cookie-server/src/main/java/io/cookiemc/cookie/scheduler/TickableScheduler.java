package io.cookiemc.cookie.scheduler;

import io.cookiemc.cookie.region.ServerRegions;

public interface TickableScheduler {
    void tick(ServerRegions.WorldTickData tickData);
}
