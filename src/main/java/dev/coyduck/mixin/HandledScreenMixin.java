package dev.coyduck.mixin;

import dev.coyduck.modules.ChestSearch;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow
    protected int x;
    @Shadow
    protected int y;
    @Shadow
    protected int backgroundWidth;

    @Unique
    private TextFieldWidget coyduck$search;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void coyduck$addSearch(CallbackInfo info) {
        coyduck$search = null;
        Object self = this;
        if (ChestSearch.active() == null || !(self instanceof GenericContainerScreen) && !(self instanceof ShulkerBoxScreen)) return;
        coyduck$search = new TextFieldWidget(textRenderer, x + backgroundWidth - 90, y - 15, 90, 13, Text.literal("Search"));
        coyduck$search.setPlaceholder(Text.literal("Search"));
        coyduck$search.setMaxLength(48);
        addDrawableChild(coyduck$search);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void coyduck$typeSearch(KeyInput input, CallbackInfoReturnable<Boolean> info) {
        if (coyduck$search == null || !coyduck$search.isFocused() || input.key() == GLFW.GLFW_KEY_ESCAPE) return;
        coyduck$search.keyPressed(input);
        info.setReturnValue(true);
    }

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void coyduck$highlight(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        if (coyduck$search == null) return;
        ChestSearch search = ChestSearch.active();
        if (search == null || !search.matches(slot.getStack(), coyduck$search.getText())) return;
        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, search.highlight());
    }
}
