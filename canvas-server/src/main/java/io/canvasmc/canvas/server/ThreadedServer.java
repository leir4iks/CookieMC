package io.canvasmc.canvas.server;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.canvasmc.canvas.CanvasBootstrap;
import io.canvasmc.canvas.Config;
import io.canvasmc.canvas.LevelAccess;
import io.canvasmc.canvas.ThreadedBukkitServer;
import io.canvasmc.canvas.region.Region;
import io.canvasmc.canvas.region.RegionizedTaskQueue;
import io.canvasmc.canvas.region.ServerRegions;
import io.canvasmc.canvas.scheduler.MultithreadedTickScheduler;
import io.canvasmc.canvas.scheduler.TickScheduler;
import io.canvasmc.canvas.spark.MultiLoopThreadDumper;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.util.MCUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThreadedServer implements ThreadedBukkitServer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ThreadedServer");
    public static BooleanSupplier SHOULD_KEEP_TICKING;
    public final TickScheduler scheduler;
    public final RegionizedTaskQueue taskQueue = new RegionizedTaskQueue(); // Threaded Regions
    private final DedicatedServer server;
    protected long tickSection;
    private boolean started = false;

    public ThreadedServer(MinecraftServer server) {
        ThreadedBukkitServer.setInstance(this);
        this.scheduler = new TickScheduler(Config.INSTANCE.ticking.allocatedSchedulerThreadCount, (DedicatedServer) server);
        this.server = (DedicatedServer) server;
    }

    @Override
    public @NotNull LevelAccess getLevelAccess(final @NotNull World world) {
        return ((CraftWorld) world).getHandle();
    }

    @Override
    public @NotNull MultithreadedTickScheduler getScheduler() {
        return TickScheduler.getScheduler();
    }

    @Override
    public void scheduleOnMain(final @NotNull Runnable runnable) {
        this.server.scheduleOnMain(runnable);
    }

    @Override
    public @Nullable Region getRegionAtChunk(final @NotNull World world, final int chunkX, final int chunkZ) {
        ThreadedRegionizer.ThreadedRegion<ServerRegions.TickRegionData, ServerRegions.TickRegionSectionData> region = ((CraftWorld) world).getHandle().regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
        return region == null ? null : region.getData();
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final @NotNull BoundingBox aabb) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level,
            new AABB(CraftVector.toVec3(aabb.getMax()), CraftVector.toVec3(aabb.getMin()))
        );
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final double blockX, final double blockZ) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, blockX, blockZ);
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final Location position, final @NotNull Vector deltaMovement, final int buffer) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, CraftLocation.toVec3(position), CraftVector.toVec3(deltaMovement), buffer);
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final @NotNull Location pos) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, CraftLocation.toBlockPosition(pos));
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final @NotNull Location pos, final int blockRadius) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, CraftLocation.toBlockPosition(pos), blockRadius);
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final @NotNull Chunk chunk) {
        return isTickThreadFor(world, chunk.getX(), chunk.getZ());
    }

    @Override
    public boolean isTickThreadFor(final @NotNull Entity entity) {
        return ServerRegions.isTickThreadFor(((CraftEntity) entity).getHandle());
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final int chunkX, final int chunkZ) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, chunkX, chunkZ);
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final int chunkX, final int chunkZ, final int radius) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, chunkX, chunkZ, radius);
    }

    @Override
    public boolean isTickThreadFor(final @NotNull World world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return ServerRegions.isTickThreadFor(level, fromChunkX, fromChunkZ, toChunkX, toChunkZ);
    }

    @Override
    public boolean isSameRegion(@NotNull final Location location1, @NotNull final Location location2) {
        return ServerRegions.isSameRegion(location1, location2);
    }

    public boolean hasStarted() {
        return started;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public void spin() {
        try {
            MultiLoopThreadDumper.REGISTRY.add(Thread.currentThread().getName());
            MultiLoopThreadDumper.REGISTRY.add("ls_wg "); // add linear-scaling world-gen workers
            MultiLoopThreadDumper.REGISTRY.add("Tick Runner ");
            MultiLoopThreadDumper.REGISTRY.add("EntityTracking");
            MultiLoopThreadDumper.REGISTRY.add("MobSpawning");
            MultiLoopThreadDumper.REGISTRY.add("Canvas Async "/*this includes things like pathfinding, chunk sending, etc*/);

            if (!server.initServer()) {
                throw new IllegalStateException("Failed to initialize server");
            }

            this.started = true;
            this.server.nextTickTimeNanos = Util.getNanos();
            this.server.statusIcon = this.server.loadStatusIcon().orElse(null);
            this.server.status = this.server.buildServerStatus();

            LOGGER.info("Running delayed init tasks");
            this.server.server.getScheduler().mainThreadHeartbeat();

            this.server.server.spark.enableBeforePlugins();

            MultiWatchdogThread.register(new MultiWatchdogThread.ThreadEntry(Thread.currentThread(), "main thread", "Main Thread", this.server::isTicking, this.server::isEmptyTickSkipping));
            MultiWatchdogThread.hasStarted = true;
            //noinspection removal
            Arrays.fill(this.server.recentTps, 20);
            tickSection = Util.getNanos();
            if (io.papermc.paper.configuration.GlobalConfiguration.isFirstStart) {
                LOGGER.info("*************************************************************************************");
                LOGGER.info("This is the first time you're starting this server.");
                LOGGER.info("It's recommended you read our 'Getting Started' documentation for guidance.");
                LOGGER.info("View this and more helpful information here: https://docs.papermc.io/paper/next-steps");
                LOGGER.info("*************************************************************************************");
            }

            if (!Boolean.getBoolean("Purpur.IReallyDontWantStartupCommands") && !org.purpurmc.purpur.PurpurConfig.startupCommands.isEmpty()) {
                LOGGER.info("Purpur: Running startup commands specified in purpur.yml.");
                for (final String startupCommand : org.purpurmc.purpur.PurpurConfig.startupCommands) {
                    LOGGER.info("Purpur: Running the following command: \"{}\"", startupCommand);
                    this.server.handleConsoleInput(startupCommand, this.server.createCommandSourceStack());
                }
            }

            final long actualDoneTimeMs = System.currentTimeMillis() - CanvasBootstrap.BOOT_TIME.toEpochMilli();
            List<World> worlds = Bukkit.getServer().getWorlds();
            LOGGER.info("Booted server with {} worlds {}", worlds.size(), worlds.stream().map(World::getName).collect(Collectors.toSet()));
            LOGGER.info("Done ({})! For help, type \"help\"", String.format(java.util.Locale.ROOT, "%.3fs", actualDoneTimeMs / 1_000.00D));
            performVersionCheck();
            while (this.server.isRunning()) {
                tickSection = this.getServer().tick(tickSection);
            }
        } catch (Throwable throwable2) {
            //noinspection removal
            if (throwable2 instanceof ThreadDeath) {
                MinecraftServer.LOGGER.error("Main thread terminated by WatchDog due to hard crash", throwable2);
                return;
            }
            MinecraftServer.LOGGER.error("Encountered an unexpected exception", throwable2);
            CrashReport crashreport = MinecraftServer.constructOrExtractCrashReport(throwable2);

            this.server.fillSystemReport(crashreport.getSystemReport());
            Path path = this.server.getServerDirectory().resolve("crash-reports").resolve("crash-" + Util.getFilenameFormattedDateTime() + "-server.txt");

            if (crashreport.saveToFile(path, ReportType.CRASH)) {
                MinecraftServer.LOGGER.error("This crash report has been saved to: {}", path.toAbsolutePath());
            } else {
                MinecraftServer.LOGGER.error("We were unable to save this crash report to disk.");
            }

            this.server.onServerCrash(crashreport);
        } finally {
            try {
                this.server.stopped = true;
                this.server.stopServer();
            } catch (Throwable throwable3) {
                MinecraftServer.LOGGER.error("Exception stopping the server", throwable3);
            } finally {
                if (this.server.services.profileCache() != null) {
                    this.server.services.profileCache().clearExecutor();
                }
            }

        }
    }

    public String getName() {
        return Thread.currentThread().getName();
    }

    public Collection<ServerLevel> getAllLevels() {
        return MinecraftServer.getServer().levels.values();
    }

    public void markPrepareHalt() {
        // mark all threads to stop ticking.
        for (final TickScheduler.FullTick<?> fullTick : TickScheduler.FullTick.ALL_REGISTERED) {
            fullTick.retire();
        }
    }

    private void performVersionCheck() {
        MCUtil.scheduleAsyncTask(() -> {
            ServerBuildInfo buildInfo = ServerBuildInfo.buildInfo();
            final OptionalInt buildNumber = buildInfo.buildNumber();
            int distance;
            if (buildNumber.isPresent()) {
                distance = ((Supplier<Integer>) () -> {
                    try {
                        final String jenkinsApiUrl = "https://jenkins.canvasmc.io/job/Canvas/lastSuccessfulBuild/api/json";
                        try (final BufferedReader reader = Resources.asCharSource(
                            URI.create(jenkinsApiUrl).toURL(),
                            Charsets.UTF_8
                        ).openBufferedStream()) {
                            final JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                            final int latestBuild = json.getAsJsonPrimitive("number").getAsInt();
                            return latestBuild - buildNumber.getAsInt();
                        } catch (final JsonSyntaxException ex) {
                            LOGGER.error("Error parsing JSON from CanvasMC's Jenkins API", ex);
                            return -1;
                        }
                    } catch (final IOException e) {
                        LOGGER.error("Error while parsing version from Jenkins API", e);
                        return -1;
                    }
                }).get();
            } else {
                distance = -10;
            }
            if (distance != -10 && distance != -1 && distance != 0) {
                LOGGER.warn("You are {} version(s) behind. Download the new version at 'https://canvasmc.io/downloads'", distance);
            }
        });
    }
}
