package dev.coyduck.logic;

public final class Verdicts {
    boolean self;
    boolean friend;
    boolean balance;

    public void clear() {
        self = false;
        friend = false;
        balance = false;
    }

    public boolean self() {
        return self;
    }

    public boolean friend() {
        return friend;
    }

    public boolean balance() {
        return balance;
    }
}
