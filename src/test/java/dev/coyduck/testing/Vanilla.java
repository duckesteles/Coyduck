package dev.coyduck.testing;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class Vanilla implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }
}
