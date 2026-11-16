package dev.coyduck.feature;

public record Bind(boolean key, int value, int modifiers) {
    public static final Bind NONE = new Bind(true, -1, 0);

    public static Bind key(int code) {
        return new Bind(true, code, 0);
    }

    public static Bind button(int button) {
        return new Bind(false, button, 0);
    }

    public boolean isSet() {
        return value != -1;
    }
}
