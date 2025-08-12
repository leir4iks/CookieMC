package io.canvasmc.canvas.server.level.distance;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.NotNull;

public class WorldSpecificViewDistancePersistentState extends SavedData {
    public static final String ID = "worldspecificviewdistance";
    public static final SavedDataType<WorldSpecificViewDistancePersistentState> TYPE = new SavedDataType<>(
        ID, context -> new WorldSpecificViewDistancePersistentState(),
        context -> Packed.CODEC.xmap(WorldSpecificViewDistancePersistentState::unpackState, WorldSpecificViewDistancePersistentState::pack), DataFixTypes.LEVEL
    );

    private int localViewDistance;
    private int localSimulationDistance;

    public static @NotNull WorldSpecificViewDistancePersistentState getFrom(@NotNull ServerLevel w) {
        return getFrom(w.getDataStorage());
    }

    public static @NotNull WorldSpecificViewDistancePersistentState getFrom(@NotNull DimensionDataStorage mgr) {
        return mgr.computeIfAbsent(TYPE);
    }

    private static @NotNull WorldSpecificViewDistancePersistentState unpackState(WorldSpecificViewDistancePersistentState.@NotNull Packed packedState) {
        var state = new WorldSpecificViewDistancePersistentState();
        state.localViewDistance = packedState.localViewDistance;
        state.localSimulationDistance = packedState.localSimulationDistance;
        return state;
    }

    private static WorldSpecificViewDistancePersistentState.@NotNull Packed pack(@NotNull WorldSpecificViewDistancePersistentState state) {
        return new Packed(state.localViewDistance, state.localSimulationDistance);
    }

    public int getLocalViewDistance() {
        return localViewDistance;
    }

    public void setLocalViewDistance(int viewDistance) {
        if (viewDistance != localViewDistance) {
            localViewDistance = viewDistance;
        }
    }

    public int getLocalSimulationDistance() {
        return localSimulationDistance;
    }

    public void setLocalSimulationDistance(int localSimulationDistance) {
        this.localSimulationDistance = localSimulationDistance;
    }

    @Override
    public boolean isDirty() {
        return true;
    }

    public record Packed(
        int localViewDistance, int localSimulationDistance
    ) {
        public static final Codec<Packed> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.INT.fieldOf("local_view_distance").forGetter(Packed::localViewDistance),
                    Codec.INT.fieldOf("local_simulation_distance").forGetter(Packed::localSimulationDistance)
                )
                .apply(instance, Packed::new)
        );
    }
}
