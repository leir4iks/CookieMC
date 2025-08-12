package io.canvasmc.canvas.command;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.canvasmc.canvas.CanvasBootstrap;
import io.canvasmc.canvas.command.debug.EntityDumpCommand;
import io.canvasmc.canvas.command.debug.FlySpeedCommand;
import io.canvasmc.canvas.command.debug.PriorityCommand;
import io.canvasmc.canvas.command.debug.RandomTeleportCommand;
import io.canvasmc.canvas.command.debug.ResendChunksCommand;
import io.canvasmc.canvas.command.debug.SenderInfoCommand;
import io.canvasmc.canvas.command.debug.SyncloadCommand;
import io.canvasmc.canvas.command.debug.TasksCommand;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.DebugMobSpawningCommand;
import net.minecraft.server.commands.DebugPathCommand;
import net.minecraft.server.commands.RaidCommand;
import net.minecraft.server.commands.ServerPackCommand;
import net.minecraft.server.commands.SpawnArmorTrimsCommand;
import net.minecraft.server.commands.WardenSpawnTrackerCommand;
import org.jetbrains.annotations.NotNull;
import org.purpurmc.purpur.PurpurConfig;

public final class CanvasCommands {
    private static final Set<LiteralCommandNode<CommandSourceStack>> ALL = Sets.newHashSet();
    private static CommandDispatcher<CommandSourceStack> DISPATCHER;

    public static void register(@NotNull CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        CanvasCommands.DISPATCHER = dispatcher;
        // public-server commands
        register(SimulationDistanceCommand::new);
        register(ViewDistanceCommand::new);
        register(SetMaxPlayersCommand::new);
        // debug commands
        if (CanvasBootstrap.RUNNING_IN_IDE) {
            CanvasBootstrap.LOGGER.info("Registering Canvas debug commands");
        }
        register(ResendChunksCommand::new);
        register(SenderInfoCommand::new);
        register(SyncloadCommand::new);
        register(PriorityCommand::new);
        register(FlySpeedCommand::new);
        register(TasksCommand::new);
        register(RandomTeleportCommand::new);
        register(EntityDumpCommand::new);
        if (PurpurConfig.registerMinecraftDebugCommands || CanvasBootstrap.RUNNING_IN_IDE) {
            registerMinecraftDebugCommands(dispatcher, context);
        }
    }

    private static void registerMinecraftDebugCommands(@NotNull CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        CanvasBootstrap.LOGGER.info("Registering Minecraft debug commands");
        RaidCommand.register(dispatcher, context);
        DebugPathCommand.register(dispatcher);
        DebugMobSpawningCommand.register(dispatcher);
        WardenSpawnTrackerCommand.register(dispatcher);
        SpawnArmorTrimsCommand.register(dispatcher);
        ServerPackCommand.register(dispatcher);
    }

    private static void register(@NotNull Supplier<? extends CommandInstance> instance) {
        CommandInstance command = instance.get();
        if (command.isDebug() && !CanvasBootstrap.RUNNING_IN_IDE) {
            return;
        }
        ALL.add(command.register(DISPATCHER));
    }
}
