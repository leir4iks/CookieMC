package io.cookiemc.cookie.entity.ai;

import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface VWaker {
    @Nullable Object wake();
}
