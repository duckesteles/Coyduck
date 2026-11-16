package dev.coyduck;

import dev.coyduck.render.Argb;
import dev.coyduck.render.Panel;
import dev.coyduck.widget.Widget;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

public final class MeteorWidget extends HudElement implements Widget.Frame, Panel {
    public static final HudGroup GROUP = new HudGroup("Coyduck");

    private final Widget widget;
    private final Color color = new Color();
    private HudRenderer renderer;

    private MeteorWidget(HudElementInfo<MeteorWidget> info, Widget widget) {
        super(info);
        this.widget = widget;
        MeteorSettings.mirror(widget.settings, settings);
        widget.bind(this);
    }

    public static void register(Widget.Info info) {
        var self = new Object() {
            HudElementInfo<MeteorWidget> value;
        };
        self.value = new HudElementInfo<>(GROUP, info.name(), info.description(), () -> new MeteorWidget(self.value, info.factory().get()));
        Hud.get().register(self.value);
    }

    @Override
    public void render(HudRenderer target) {
        renderer = target;
        widget.render(this);
    }

    @Override
    public void size(double width, double height) {
        setSize(width, height);
    }

    @Override
    public int x() {
        return box.getRenderX();
    }

    @Override
    public int y() {
        return box.getRenderY();
    }

    @Override
    public int width() {
        return getWidth();
    }

    @Override
    public boolean rightSide() {
        return getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
    }

    @Override
    public boolean inEditor() {
        return isInEditor();
    }

    @Override
    public DrawContext context() {
        return renderer.drawContext;
    }

    @Override
    public double text(String text, double x, double y, int argb, boolean shadow, double scale) {
        return renderer.text(text, x, y, color(argb), shadow, scale);
    }

    @Override
    public double textWidth(String text, boolean shadow, double scale) {
        return renderer.textWidth(text, shadow, scale);
    }

    @Override
    public double textHeight(boolean shadow, double scale) {
        return renderer.textHeight(shadow, scale);
    }

    @Override
    public void quad(double x, double y, double width, double height, int argb) {
        renderer.quad(x, y, width, height, color(argb));
    }

    @Override
    public void texture(Identifier id, double x, double y, double width, double height, int argb) {
        renderer.texture(id, x, y, width, height, color(argb));
    }

    @Override
    public void item(ItemStack stack, int x, int y, float scale, boolean overlay, String count) {
        renderer.item(stack, x, y, scale, overlay, count);
    }

    @Override
    public void post(Runnable task) {
        renderer.post(task);
    }

    private Color color(int argb) {
        color.r = Argb.r(argb);
        color.g = Argb.g(argb);
        color.b = Argb.b(argb);
        color.a = Argb.a(argb);
        return color;
    }
}
