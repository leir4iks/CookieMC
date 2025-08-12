package io.cookiemc.cookie.command;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.cookiemc.cookie.cookieBootstrap;
import io.cookiemc.cookie.command.debug.EntityDumpCommand;
import io.cookiemc.cookie.command.debug.FlySpeedCommand;
import io.cookiemc.cookie.command.debug.PriorityCommand;
import io.cookiemc.cookie.command.debug.RandomTeleportCommand;
import io.cookiemc.cookie.command.debug.ResendChunksCommand;
import io.cookiemc.cookie.command.debug.SenderInfoCommand;
import io.cookiemc.cookie.command.debug.SyncloadCommand;
import io.cookiemc.cookie.command.debug.TasksCommand;
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

public final class cookieCommands {
    private static final Set<LiteralCommandNode<CommandSourceStack>> ALL = Sets.newHashSet();
    private static CommandDispatcher<CommandSourceStack> DISPATCHER;

    public static void register(@NotNull CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        cookieCommands.DISPATCHER = dispatcher;
        // public-server commands
        register(SimulationDistanceCommand::new);
        register(ViewDistanceCommand::new);
        register(SetMaxPlayersCommand::new);
        // debug commands
        if (cookieBootstrap.RUNNING_IN_IDE) {
            cookieBootstrap.LOGGER.info("Registering cookie debug commands");
        }
        register(ResendChunksCommand::new);
        register(SenderInfoCommand::new);
        register(SyncloadCommand::new);
        register(PriorityCommand::new);
        register(FlySpeedCommand::new);
        register(TasksCommand::new);
        register(RandomTeleportCommand::new);
        register(EntityDumpCommand::new);
        if (PurpurConfig.registerMinecraftDebugCommands || cookieBootstrap.RUNNING_IN_IDE) {
            registerMinecraftDebugCommands(dispatcher, context);
        }
    }

    private static void registerMinecraftDebugCommands(@NotNull CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        cookieBootstrap.LOGGER.info("Registering Minecraft debug commands");
        RaidCommand.register(dispatcher, context);
        DebugPathCommand.register(dispatcher);
        DebugMobSpawningCommand.register(dispatcher);
        WardenSpawnTrackerCommand.register(dispatcher);
        SpawnArmorTrimsCommand.register(dispatcher);
        ServerPackCommand.register(dispatcher);
    }

    private static void register(@NotNull Supplier<? extends CommandInstance> instance) {
        CommandInstance command = instance.get();
        if (command.isDebug() && !cookieBootstrap.RUNNING_IN_IDE) {
            return;
        }
        ALL.add(command.register(DISPATCHER));
    }
}
