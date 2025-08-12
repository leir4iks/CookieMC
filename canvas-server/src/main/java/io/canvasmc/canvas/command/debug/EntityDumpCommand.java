package io.canvasmc.canvas.command.debug;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.canvasmc.canvas.command.CommandInstance;
import io.canvasmc.canvas.util.JsonTextFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import static net.minecraft.commands.Commands.literal;

public class EntityDumpCommand implements CommandInstance {
    @Override
    public LiteralCommandNode<CommandSourceStack> register(@NotNull final CommandDispatcher<CommandSourceStack> dispatcher) {
        return dispatcher.register(literal("entitydump").executes(context -> {
            if (!context.getSource().isPlayer()) {
                context.getSource().sendFailure(Component.literal("This command can only be run by a player"));
                return 0;
            }
            ServerPlayer player = context.getSource().getPlayer();
            if (player == null) throw new IllegalStateException("Player cannot be null");
            RayTraceResult bukkitTraceResult = player.getBukkitEntity().rayTraceEntities(10, false);
            if (bukkitTraceResult == null || bukkitTraceResult.getHitEntity() == null) return 0;
            Entity entity = ((CraftEntity) bukkitTraceResult.getHitEntity()).getHandle();
            CompoundTag compoundTag = entity.saveWithoutId(new CompoundTag());
            JsonElement jsonElement = new Gson().fromJson(compoundTag.toString(), JsonObject.class);
            player.sendSystemMessage(new JsonTextFormatter(1).apply(jsonElement));
            return 1;
        }));
    }

    @Override
    public boolean isDebug() {
        return true;
    }
}
