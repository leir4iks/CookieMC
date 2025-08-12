package io.cookiemc.cookie.config;

@FunctionalInterface
public interface TriConsumer<A, B, C> {
    void accept(A first, B second, C third);
}
