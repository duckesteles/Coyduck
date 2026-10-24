package dev.coyduck.mixin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GuiTheme.class, remap = false)
public interface GuiThemeAccessor {
    @Mutable
    @Accessor(value = "name", remap = false)
    void coyduck$setName(String name);
}
