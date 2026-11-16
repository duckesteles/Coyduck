package dev.coyduck.feature;

public interface Shell {
    Shell DETACHED = new Shell() {};

    default boolean isActive() {
        return false;
    }

    default void toggle() {}

    default void info(String message, Object... args) {}

    default void warning(String message, Object... args) {}

    default void error(String message, Object... args) {}
}
