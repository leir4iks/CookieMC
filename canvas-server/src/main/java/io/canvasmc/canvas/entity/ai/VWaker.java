package io.canvasmc.canvas.entity.ai;

import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface VWaker {
    @Nullable Object wake();
}
