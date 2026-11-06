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

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class CoyWidgets {
    static final int BODY = 0x8C120F18;
    static final int CELL = 0xB42A2533;
    static final int CELL_HOVER = 0xC8352F40;
    static final int EDGE = 0xFF0C0A10;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFB4B0BE;
    static final int LINE = 0x40FFFFFF;

    private CoyWidgets() {
    }

    static Color color(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    static int accent() {
        return ClickGui.accentColor();
    }

    static int enabled() {
        return withAlpha(shade(accent(), 0.72f), 0xDC);
    }

    static int enabledHover() {
        return withAlpha(shade(accent(), 0.82f), 0xE6);
    }

    static int shade(int color, float factor) {
        int r = Math.round(((color >> 16) & 0xFF) * factor);
        int g = Math.round(((color >> 8) & 0xFF) * factor);
        int b = Math.round((color & 0xFF) * factor);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int tint(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    static void outline(GuiRenderer r, double x, double y, double w, double h, double t, int argb) {
        Color c = color(argb);
        r.quad(x, y, w, t, c);
        r.quad(x, y + h - t, w, t, c);
        r.quad(x, y + t, t, h - t * 2, c);
        r.quad(x + w - t, y + t, t, h - t * 2, c);
    }

    static void header(GuiRenderer r, double x, double y, double w, double h, float lift) {
        Color top = color(tint(accent(), lift));
        Color bottom = color(lift > 0.12f ? tint(accent(), lift - 0.12f) : accent());
        r.quad(x, y, w, h, top, top, bottom, bottom);
    }

    public static class TopBar extends WTopBar {
        private static final double TOP = 6;
        private static final double HEIGHT = 12;
        private static final double MARGIN = 6;
        private static final double PAD = 4;

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
            x = mc.getWindow().getFramebufferWidth() - width - MARGIN * unit();
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
                width = theme.textWidth(tab.name) + PAD * 2 * unit() + 4 * unit();
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
                double px = unit();
                double bw = width - 4 * px;
                boolean current = current();
                header(r, x, y, bw, height, current ? 0.3f : mouseOver ? 0.22f : 0.12f);
                outline(r, x, y, bw, height, px, accent());
                r.text(tab.name, x + PAD * px, y + (height - theme.textHeight()) / 2, color(current || mouseOver ? TEXT : MUTED), true);
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
            double px = theme.scale(1);
            double top = y + header.height;
            double h = height - header.height;
            if (expanded && h > 0) r.quad(x, top, width, h, color(BODY));
            outline(r, x, y, width, expanded ? height : header.height, px, accent());
        }

        private class Header extends WHeader {
            Header(WWidget icon) {
                super(icon);
            }

            @Override
            protected void onCalculateWidgetPositions() {
                super.onCalculateWidgetPositions();
                for (Cell<?> cell : cells) {
                    if (cell.widget() instanceof WLabel) cell.widget().x = x + theme.scale(4);
                }
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                CoyWidgets.header(r, x, y, width, height, 0.12f);
            }
        }
    }

    public static class Checkbox extends WCheckbox {
        public Checkbox(boolean checked) {
            super(checked);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double px = theme.scale(1);
            double size = Math.min(width, height) * 0.62;
            double bx = x + (width - size) / 2;
            double by = y + (height - size) / 2;
            r.quad(bx, by, size, size, color(EDGE));
            r.quad(bx + px, by + px, size - px * 2, size - px * 2, color(checked ? accent() : mouseOver ? CELL_HOVER : CELL));
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
            double track = px * 4;
            double ty = y + (height - track) / 2;
            boolean hot = dragging || handleMouseOver;
            r.quad(left, ty, span, track, color(hot ? CELL_HOVER : CELL));
            r.quad(left, ty, span * t, track, color(hot ? enabledHover() : enabled()));
            double knob = px * 2;
            r.quad(left + span * t - knob / 2, ty - px, knob, track + px * 2, color(TEXT));
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
                width = Math.max(width, theme.textWidth(title) + theme.scale(16));
                height = Math.max(height, theme.textHeight() + theme.scale(4));
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                double ty = y + (height - theme.textHeight()) / 2;
                r.text(title, x, ty, color(mouseOver ? TEXT : MUTED), true);
                String sign = isExpanded() ? "-" : "+";
                r.text(sign, x + width - theme.textWidth(sign), ty, color(MUTED), true);
            }
        }
    }

    public static class Button extends WButton {
        public Button(String text, GuiTexture texture) {
            super(text, texture);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            int fill = pressed ? enabled() : mouseOver ? CELL_HOVER : CELL;
            r.quad(x, y, width, height, color(fill));
            double pad = pad();
            if (text != null) {
                r.text(text, x + width / 2 - textWidth / 2, y + pad, color(TEXT), true);
            } else if (texture != null) {
                double ts = theme.textHeight();
                r.quad(x + width / 2 - ts / 2, y + pad, ts, ts, texture, color(TEXT));
            }
        }
    }

    public static class ModuleRow extends WPressable {
        private final Module module;
        private final String title;

        public ModuleRow(Module module, String title) {
            this.module = module;
            this.title = title;
            this.tooltip = module.description;
        }

        @Override
        public double pad() {
            return theme.scale(3);
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();
            width = pad + theme.textWidth(title) + pad + theme.textWidth("+") + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onPressed(int button) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) module.toggle();
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(module));
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            boolean active = module.isActive();
            int fill = active ? (mouseOver ? enabledHover() : enabled()) : (mouseOver ? CELL_HOVER : CELL);
            r.quad(x, y, width, height - theme.scale(1), color(fill));
            r.text(title, x + pad(), y + pad(), color(TEXT), true);
            r.text("+", x + width - pad() - theme.textWidth("+"), y + pad(), color(TEXT), true);
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
                r.quad(x, cy, width, px, color(LINE));
                return;
            }
            double tw = theme.textWidth(text);
            double gap = theme.scale(4);
            double tx = x + width / 2 - tw / 2;
            r.quad(x, cy, Math.max(0, tx - gap - x), px, color(LINE));
            r.text(text, tx, y + (height - theme.textHeight()) / 2, color(MUTED), true);
            r.quad(tx + tw + gap, cy, Math.max(0, x + width - (tx + tw + gap)), px, color(LINE));
        }
    }
}
