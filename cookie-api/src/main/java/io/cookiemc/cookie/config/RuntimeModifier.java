package io.cookiemc.cookie.config;

import java.util.function.Function;

public record RuntimeModifier<T>(Class<T> classType, Function<T, T> modifier) {
}
