package dev.coyduck.gui;

import dev.coyduck.modules.ClickGui;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WHorizontalSeparator;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class CoyWidgets {
    static final int PANEL = 0xA60F0F14;
    static final int HEAD = 0xCC16161D;
    static final int OUTLINE = 0x22FFFFFF;
    static final int HOVER = 0x16FFFFFF;
    static final int PRESS = 0x24FFFFFF;
    static final int TITLE = 0xFFEDEDF3;
    static final int TEXT = 0xFFA4A4B0;
    static final int BRIGHT = 0xFFFFFFFF;
    static final int MUTED = 0xFF6E6E7A;
    static final int VALUE = 0xFFDADAE2;
    static final int TRACK = 0x30FFFFFF;
    static final int BOX = 0xFF3A3A45;

    private CoyWidgets() {
    }

    static Color color(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    static int accent() {
        return ClickGui.accentColor();
    }

    static int alpha(int argb, double factor) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(factor, 0, 1));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static int blend(int from, int to, double t) {
        t = MathHelper.clamp(t, 0, 1);
        int a = (int) Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static void outline(GuiRenderer r, double x, double y, double w, double h, double t, int argb) {
        Color c = color(argb);
        r.quad(x, y, w, t, c);
        r.quad(x, y + h - t, w, t, c);
        r.quad(x, y + t, t, h - t * 2, c);
        r.quad(x + w - t, y + t, t, h - t * 2, c);
    }

    static double approach(double value, double target, double delta, double speed) {
        return value + (target - value) * Math.min(1, delta * speed);
    }

    public static class TopBar extends WTopBar {
        private static final double TOP = 8;
        private static final double HEIGHT = 15;
        private static final double MARGIN = 12;
        private static final double GAP = 12;

        private static double unit() {
            return mc.getWindow().getScaleFactor();
        }

        @Override
        protected Color getButtonColor(boolean pressed, boolean hovered) {
            return color(0);
        }

        @Override
        protected Color getNameColor() {
            return color(TEXT);
        }

        @Override
        public void init() {
            for (Tab tab : Tabs.get()) add(new TabButton(tab));
        }

        @Override
        protected void onCalculateWidgetPositions() {
            x = mc.getWindow().getFramebufferWidth() - width - (MARGIN - GAP / 2) * unit();
            y = TOP * unit();
            super.onCalculateWidgetPositions();
        }

        private class TabButton extends WPressable {
            private final Tab tab;

            TabButton(Tab tab) {
                this.tab = tab;
            }

            private boolean current() {
                return mc.currentScreen instanceof TabScreen screen && screen.tab == tab;
            }

            @Override
            protected void onCalculateSize() {
                width = theme.textWidth(tab.name) + GAP * unit();
                height = HEIGHT * unit();
            }

            @Override
            protected void onPressed(int button) {
                if (current()) return;
                double mouseX = mc.mouse.getX();
                double mouseY = mc.mouse.getY();
                tab.openScreen(theme);
                GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), mouseX, mouseY);
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                boolean current = current();
                double textX = x + GAP * unit() / 2;
                r.text(tab.name, textX, y + (height - theme.textHeight()) / 2, color(current || mouseOver ? BRIGHT : MUTED), false);
                if (current) r.quad(textX, y + height - unit(), theme.textWidth(tab.name), unit(), color(accent()));
            }
        }
    }

    public static class Window extends WWindow {
        public Window(WWidget icon, String title) {
            super(icon, title);
        }

        @Override
        protected WHeader header(WWidget icon) {
            return new Header(icon);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            if (!expanded && animProgress <= 0) return;
            double px = theme.scale(1);
            double top = y + header.height;
            double h = height - header.height;
            if (h <= 0) return;
            r.quad(x, top, width, h, color(PANEL));
            outline(r, x, top - px, width, h + px, px, OUTLINE);
        }

        private class Header extends WHeader {
            Header(WWidget icon) {
                super(icon);
            }

            @Override
            protected void onCalculateWidgetPositions() {
                super.onCalculateWidgetPositions();
                for (Cell<?> cell : cells) {
                    if (cell.widget() instanceof WLabel) cell.widget().x = x + theme.scale(6);
                }
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                double px = theme.scale(1);
                r.quad(x + px, y, width - px * 2, px, color(HEAD));
                r.quad(x, y + px, width, height - px, color(HEAD));
                r.quad(x, y + height - px, width, px, color(alpha(accent(), expanded ? 0.6 : 0.25)));
            }
        }
    }

    public static class Checkbox extends WCheckbox {
        private double progress = -1;

        public Checkbox(boolean checked) {
            super(checked);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            if (progress < 0) progress = checked ? 1 : 0;
            progress = approach(progress, checked ? 1 : 0, delta, 14);
            double px = theme.scale(1);
            double size = Math.min(width, height) * 0.62;
            double bx = x + (width - size) / 2;
            double by = y + (height - size) / 2;
            int edge = blend(mouseOver ? 0xFF4C4C5A : BOX, accent(), progress);
            outline(r, bx, by, size, size, px, edge);
            if (progress > 0.01) {
                double inner = size - px * 4;
                r.quad(bx + px * 2, by + px * 2, inner, inner, color(alpha(accent(), progress)));
            }
        }
    }

    public static class Slider extends WSlider {
        public Slider(double value, double min, double max) {
            super(value, min, max);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double px = theme.scale(1);
            double handle = handleSize();
            double left = x + handle / 2;
            double span = width - handle;
            double t = max > min ? MathHelper.clamp((value - min) / (max - min), 0, 1) : 0;
            double cy = y + height / 2;
            r.quad(left, cy - px / 2, span, px, color(TRACK));
            r.quad(left, cy - px / 2, span * t, px, color(accent()));
            double knob = theme.scale(5);
            boolean hot = dragging || handleMouseOver;
            r.quad(left + span * t - knob / 2, cy - knob / 2, knob, knob, color(hot ? BRIGHT : VALUE));
        }
    }

    public static class Section extends WSection {
        public Section(String title, boolean expanded, WWidget headerWidget) {
            super(title, expanded, headerWidget);
        }

        @Override
        protected WHeader createHeader() {
            return new Header(title);
        }

        private class Header extends WHeader {
            Header(String title) {
                super(title);
            }

            @Override
            protected void onCalculateSize() {
                super.onCalculateSize();
                width = Math.max(width, theme.textWidth(title.toUpperCase(Locale.ROOT)) + theme.scale(16));
                height = Math.max(height, theme.textHeight() + theme.scale(6));
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                String label = title.toUpperCase(Locale.ROOT);
                double ty = y + (height - theme.textHeight()) / 2;
                r.text(label, x, ty, color(mouseOver ? TEXT : MUTED), false);
                double lineX = x + theme.textWidth(label) + theme.scale(6);
                double sign = theme.textWidth("-");
                r.quad(lineX, y + height / 2, Math.max(0, x + width - lineX - sign - theme.scale(6)), theme.scale(1), color(OUTLINE));
                r.text(isExpanded() ? "-" : "+", x + width - sign, ty, color(MUTED), false);
            }
        }
    }

    public static class Button extends WButton {
        public Button(String text, GuiTexture texture) {
            super(text, texture);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double px = theme.scale(1);
            int fill = pressed ? alpha(accent(), 0.3) : mouseOver ? PRESS : HOVER;
            r.quad(x, y, width, height, color(fill));
            outline(r, x, y, width, height, px, mouseOver ? alpha(accent(), 0.6) : OUTLINE);
            double pad = pad();
            if (text != null) {
                r.text(text, x + width / 2 - textWidth / 2, y + pad, color(mouseOver ? BRIGHT : VALUE), false);
            } else if (texture != null) {
                double ts = theme.textHeight();
                r.quad(x + width / 2 - ts / 2, y + pad, ts, ts, texture, color(mouseOver ? BRIGHT : VALUE));
            }
        }
    }

    public static class ModuleRow extends WPressable {
        private final Module module;
        private final String title;
        private double lit = -1;
        private double hover;

        public ModuleRow(Module module, String title) {
            this.module = module;
            this.title = title;
            this.tooltip = module.description;
        }

        @Override
        public double pad() {
            return theme.scale(4);
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();
            width = pad + theme.textWidth(title) + pad + theme.scale(4);
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onPressed(int button) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) module.toggle();
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(module));
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            if (lit < 0) lit = module.isActive() ? 1 : 0;
            lit = approach(lit, module.isActive() ? 1 : 0, delta, 12);
            hover = approach(hover, mouseOver ? 1 : 0, delta, 14);
            double px = theme.scale(1);
            if (hover > 0.01) r.quad(x, y, width, height, color(alpha(HOVER, hover)));
            if (lit > 0.01) {
                r.quad(x, y, width, height, color(alpha(accent(), 0.16 * lit)));
                r.quad(x, y + px * 2, px * 2, height - px * 4, color(alpha(accent(), lit)));
            }
            int text = blend(TEXT, BRIGHT, Math.max(lit, hover * 0.6));
            r.text(title, x + pad() + px * 2 * lit, y + pad(), color(text), false);
        }
    }

    public static class Separator extends WHorizontalSeparator {
        public Separator(String text) {
            super(text);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double px = theme.scale(1);
            double cy = y + height / 2;
            if (text == null) {
                r.quad(x, cy, width, px, color(OUTLINE));
                return;
            }
            String label = text.toUpperCase(Locale.ROOT);
            double tw = theme.textWidth(label);
            double gap = theme.scale(6);
            double tx = x + width / 2 - tw / 2;
            r.quad(x, cy, Math.max(0, tx - gap - x), px, color(OUTLINE));
            r.text(label, tx, y + (height - theme.textHeight()) / 2, color(MUTED), false);
            r.quad(tx + tw + gap, cy, Math.max(0, x + width - (tx + tw + gap)), px, color(OUTLINE));
        }
    }
}
