package dev.coyduck.render;

public enum Shape {
    Lines,
    Sides,
    Both;

    public boolean lines() {
        return this != Sides;
    }

    public boolean sides() {
        return this != Lines;
    }
}
