package io.canvasmc.canvas.server;

import io.canvasmc.canvas.CanvasBootstrap;
import io.canvasmc.canvas.Config;
import io.canvasmc.canvas.region.ServerRegions;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import org.jetbrains.annotations.NotNull;

public class NetworkRouter {
    private final ServerLevel world;
    private final List<Connection> pendingRoute = new CopyOnWriteArrayList<>() {
        @Override
        public boolean add(final Connection connection) {
            boolean added = super.add(connection);
            try {
                return added;
            } finally {
                if (added && Config.INSTANCE.debug.logConnectionDocking)
                    CanvasBootstrap.LOGGER.info("Docked connection for \"{}\" on network router for {}", connection.getPlayer().getName().getString(), NetworkRouter.this.world);
            }
        }

        @Override
        public boolean remove(final Object o) {
            if (!(o instanceof Connection connection)) throw new RuntimeException("must be connection");
            boolean removed = super.remove(o);
            try {
                return removed;
            } finally {
                if (removed && Config.INSTANCE.debug.logConnectionDocking)
                    CanvasBootstrap.LOGGER.info("Undocked connection for \"{}\" from network router for {}", connection.getPlayer().getName().getString(), NetworkRouter.this.world);
            }
        }
    };

    public NetworkRouter(@NotNull ServerLevel world) {
        this.world = world;
    }

    public void tickRouter() {
        // tick router, we need to ensure all players connections are properly being handled
        // if the connection is disconnected during routing, handle accordingly
        boolean isRegionized = this.world.server.isRegionized();
        for (Connection connection : this.pendingRoute) {
            if (isRegionized) {
                processConnectionRegionized(connection);
            } else {
                // route to world
                ServerPlayer player = connection.getPlayer();
                this.world.chunkSource.updateRegionTicket(player.chunkPosition(), true, TicketType.NETWORK_ROUTER);
                this.pendingRoute.remove(connection);
                this.world.levelTickData.connections.add(connection);
                connection.owner.set(this.world.levelTickData);
            }
        }
    }

    private void processConnectionRegionized(@NotNull final Connection connection) {
        ServerPlayer player = connection.getPlayer();
        final int chunkX = player.chunkPosition().x;
        final int chunkZ = player.chunkPosition().z;
        final ServerChunkCache chunkSource = player.serverLevel().chunkSource;
        this.world.regioniser.computeAtRegionIfPresentOrElseUnsynchronized(chunkX, chunkZ, (_) -> {
            // region is present, move to region
            chunkSource.updateRegionTicket(chunkX, chunkZ, true, TicketType.NETWORK_ROUTER);
            ThreadedRegionizer.ThreadedRegion<ServerRegions.TickRegionData, ServerRegions.TickRegionSectionData> region =
                this.world.regioniser.getRegionAtSynchronised(chunkX, chunkZ);
            if (region == null) {
                throw new IllegalStateException("literally shouldn't be possible?");
            }
            connection.owner.set(region.getData().tickData);
            this.pendingRoute.remove(connection);
            region.getData().tickData.connections.add(connection);
        }, () -> {
            // schedule, we need to load this chunk
            chunkSource.updateRegionTicket(chunkX, chunkZ, true, TicketType.NETWORK_ROUTER);
            chunkSource.getChunk(chunkX, chunkZ, true);
        });
    }

    public void clearFromRouter(@NotNull Connection connection) {
        this.pendingRoute.remove(connection);
        connection.computeIfOwningTickDataPresent((tickData) -> {
            tickData.connections.remove(connection);
            connection.owner.set(null);
        });
    }

    public void connectToWorld(@NotNull Connection connection) {
        if (this.pendingRoute.contains(connection)) {
            return; // no need to re-add to routing if we already are routing
        }
        connection.computeIfOwningTickDataPresent((tickData) -> {
            tickData.connections.remove(connection);
            connection.owner.set(null);
        });
        this.pendingRoute.add(connection);
    }
}
