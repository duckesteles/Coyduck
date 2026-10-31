package dev.coyduck.gui;

import dev.coyduck.Coyduck;
import dev.coyduck.modules.ClickGui;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CoyGuiScreen extends WidgetScreen {
    private final ClickGui owner;

    public CoyGuiScreen(ClickGui owner) {
        super(GuiThemes.get(), "Coyduck");
        this.owner = owner;
    }

    @Override
    public void initWidgets() {
        add(new Board(owner, theme));
        add(theme.topBar()).top().centerX();
    }

    private static final class Board extends WWidget {
        private static final int PANEL = 0xDC111118;
        private static final int HEAD = 0xF018181F;
        private static final int HOVER = 0x10FFFFFF;
        private static final int ON = 0xFFF5F5FA;
        private static final int OFF = 0xFFA5A5B2;
        private static final int MUTED = 0xFF74747F;
        private static final int SHADOW = 0x24000000;

        private record Hit(double x, double y, double w, double h, Object target, boolean header) {
            boolean contains(double mx, double my) {
                return mx >= x && mx < x + w && my >= y && my < y + h;
            }
        }

        private static final class Panel {
            final Category category;
            final List<Module> modules;
            final int index;
            double x;
            double y;
            boolean collapsed;
            double scroll;
            double scrollTarget;
            double content;
            double view;
            boolean placed;

            Panel(Category category, List<Module> modules, int index) {
                this.category = category;
                this.modules = modules;
                this.index = index;
            }
        }

        private final ClickGui owner;
        private final List<Panel> panels = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private final Map<Object, Float> hover = new HashMap<>();
        private final Map<Object, Float> lit = new HashMap<>();
        private final long openedAt = Util.getMeasuringTimeMs();

        private String search = "";
        private Panel dragging;
        private double dragX;
        private double dragY;
        private double mouseX;
        private double mouseY;
        private String hint;
        private String shownHint = "";
        private float hintAlpha;
        private long lastFrame;
        private float fade = 1;

        Board(ClickGui owner, GuiTheme theme) {
            this.owner = owner;
            this.theme = theme;
            int index = 0;
            List<Category> categories = new ArrayList<>();
            for (Category category : Modules.loopCategories()) {
                if (category == Coyduck.CATEGORY) categories.addFirst(category);
                else categories.add(category);
            }
            for (Category category : categories) {
                List<Module> modules = new ArrayList<>(Modules.get().getGroup(category));
                if (modules.isEmpty()) continue;
                modules.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                panels.add(new Panel(category, modules, index++));
            }
        }

        private double u(double value) {
            return theme.scale(value);
        }

        private double panelWidth() {
            double widest = 0;
            for (Panel panel : panels) {
                widest = Math.max(widest, theme.textWidth(panel.category.name, panel.category.name.length(), true) + u(30));
                for (Module module : panel.modules) widest = Math.max(widest, theme.textWidth(module.title));
            }
            return Math.max(u(96), widest + u(26));
        }

        @Override
        protected void onCalculateSize() {
            width = Utils.getWindowWidth();
            height = Utils.getWindowHeight();
        }

        private void place() {
            double w = panelWidth();
            double gap = u(8);
            double x = u(12);
            double y = u(42);
            for (Panel panel : panels) {
                if (x + w > width - u(12) && x > u(12)) {
                    x = u(12);
                    y += u(230);
                }
                panel.x = x;
                panel.y = y;
                panel.placed = true;
                x += w + gap;
            }
            loadLayout();
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mx, double my, double delta) {
            if (panels.isEmpty()) return;
            if (!panels.getFirst().placed) place();

            mouseX = mx;
            mouseY = my;
            long now = Util.getMeasuringTimeMs();
            float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1000f, 0.1f);
            lastFrame = now;
            hits.clear();
            hint = null;

            double w = panelWidth();
            for (Panel panel : panels) {
                fade = appear(panel.index * 22L);
                drawPanel(renderer, panel, w, dt);
            }
            fade = appear(0);
            drawSearch(renderer, now);
            fade = 1;
            drawHint(renderer, dt);
        }

        private void drawPanel(GuiRenderer r, Panel panel, double w, float dt) {
            List<Module> shown = new ArrayList<>();
            String query = search.toLowerCase(Locale.ROOT);
            for (Module module : panel.modules) {
                if (query.isEmpty() || module.title.toLowerCase(Locale.ROOT).contains(query)) shown.add(module);
            }
            if (!query.isEmpty() && shown.isEmpty()) return;

            double header = u(21);
            double row = u(15);
            double pad = u(8);
            double x = panel.x;
            double y = panel.y + (1 - fade) * u(6);

            panel.content = panel.collapsed ? 0 : shown.size() * row + u(4);
            panel.view = panel.collapsed ? 0 : Math.min(panel.content, height - y - header - u(30));
            panel.scrollTarget = MathHelper.clamp(panel.scrollTarget, 0, Math.max(0, panel.content - panel.view));
            panel.scroll += (panel.scrollTarget - panel.scroll) * Math.min(1, dt * 16);

            double total = header + panel.view;
            round(r, x - 1, y + 1, w + 2, total + 2, u(4), SHADOW);
            round(r, x, y, w, total, u(4), PANEL);
            round(r, x, y, w, header, u(4), HEAD);
            if (!panel.collapsed) r.quad(x, y + header - u(4), w, u(4), color(HEAD));
            r.quad(x + pad, y + header - 1, w - pad * 2, 1, color(alpha(accent(), panel.collapsed ? 0.3f : 0.75f)));

            int active = 0;
            for (Module module : panel.modules) if (module.isActive()) active++;
            double titleY = y + (header - theme.textHeight(true)) / 2;
            r.text(panel.category.name, x + pad, titleY, color(ON), true);
            String count = String.valueOf(active);
            r.text(count, x + w - pad - theme.textWidth(count), y + (header - theme.textHeight()) / 2, color(active > 0 ? lighten(accent()) : MUTED), false);
            hits.add(new Hit(x, y, w, header, panel, true));
            if (panel.collapsed) return;

            double top = y + header;
            double bottom = top + panel.view;
            r.scissorStart(x, top, w, panel.view);
            double rowY = top + u(2) - panel.scroll;
            for (Module module : shown) {
                if (rowY + row > top && rowY < bottom) drawModule(r, module, x, rowY, w, row, pad, top, bottom, dt);
                rowY += row;
            }
            r.scissorEnd();

            if (panel.content > panel.view + 1) {
                double track = panel.view - u(6);
                double bar = Math.max(u(10), track * panel.view / panel.content);
                double barY = top + u(3) + (track - bar) * (panel.scroll / Math.max(1, panel.content - panel.view));
                r.quad(x + w - u(3), barY, u(1.5), bar, color(0x50FFFFFF));
            }
        }

        private void drawModule(GuiRenderer r, Module module, double x, double y, double w, double h, double pad, double top, double bottom, float dt) {
            double clipTop = Math.max(y, top);
            double clipBottom = Math.min(y + h, bottom);
            boolean over = mouseX >= x && mouseX < x + w && mouseY >= clipTop && mouseY < clipBottom && dragging == null;
            float hv = animate(hover, module, over ? 1 : 0, dt, 18);
            float on = animate(lit, module, module.isActive() ? 1 : 0, dt, 12);

            if (hv > 0.01f) r.quad(x, y, w, h, color(alpha(HOVER, hv)));
            if (on > 0.01f) {
                r.quad(x, y, w, h, color(alpha(accent(), 0.13f * on)));
                r.quad(x, y + u(3), u(2), h - u(6), color(alpha(accent(), on)));
            }
            double textY = y + (h - theme.textHeight()) / 2;
            r.text(module.title, x + pad + u(1.5) * on, textY, color(blend(OFF, ON, Math.max(on, hv * 0.6f))), false);

            hits.add(new Hit(x, clipTop, w, clipBottom - clipTop, module, false));
            if (over) hint = module.description;
        }

        private void drawSearch(GuiRenderer r, long now) {
            double h = u(17);
            double w = u(150);
            double x = u(12);
            double y = u(12);
            round(r, x, y, w, h, h / 2, search.isEmpty() ? 0xA0111118 : PANEL);
            boolean empty = search.isEmpty();
            String text = empty ? "Type to search" : search;
            double ty = y + (h - theme.textHeight()) / 2;
            r.text(text, x + u(9), ty, color(empty ? MUTED : ON), false);
            if (!empty && (now / 530) % 2 == 0) {
                double cx = x + u(9) + theme.textWidth(search) + u(1);
                r.quad(cx, y + u(4), u(1), h - u(8), color(ON));
            }
        }

        private void drawHint(GuiRenderer r, float dt) {
            boolean has = hint != null && !hint.isEmpty();
            if (has) shownHint = hint;
            hintAlpha += ((has ? 1 : 0) - hintAlpha) * Math.min(1, dt * 12);
            if (hintAlpha < 0.02f || shownHint.isEmpty()) return;

            String text = shownHint;
            double max = Math.min(width - u(60), u(420));
            while (text.length() > 4 && theme.textWidth(text) > max) text = text.substring(0, text.length() - 2);
            if (!text.equals(shownHint)) text = text.trim() + "…";
            double w = theme.textWidth(text) + u(20);
            double h = u(17);
            double x = (width - w) / 2;
            double y = height - h - u(12) + (1 - hintAlpha) * u(3);
            fade = hintAlpha;
            round(r, x, y, w, h, h / 2, PANEL);
            r.text(text, x + u(10), y + (h - theme.textHeight()) / 2, color(ON), false);
            fade = 1;
        }

        @Override
        public boolean onMouseClicked(Click click, boolean used) {
            if (used) return false;
            Hit hit = hitAt(click.x(), click.y());
            if (hit == null) return false;
            int button = click.button();

            if (hit.header) {
                Panel panel = (Panel) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    panel.collapsed = !panel.collapsed;
                    saveLayout();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    dragging = panel;
                    dragX = click.x() - panel.x;
                    dragY = click.y() - panel.y;
                    panels.remove(panel);
                    panels.add(panel);
                }
                return true;
            }

            Module module = (Module) hit.target;
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) module.toggle();
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(module));
            return true;
        }

        @Override
        public boolean onMouseReleased(Click click) {
            if (dragging != null) {
                dragging = null;
                saveLayout();
                return true;
            }
            return false;
        }

        @Override
        public void onMouseMoved(double mx, double my, double lastX, double lastY) {
            mouseX = mx;
            mouseY = my;
            if (dragging == null) return;
            dragging.x = MathHelper.clamp(mx - dragX, 0, Math.max(0, width - panelWidth()));
            dragging.y = MathHelper.clamp(my - dragY, 0, Math.max(0, height - u(21)));
        }

        @Override
        public boolean onMouseScrolled(double amount) {
            for (Panel panel : panels) {
                double y = panel.y + u(21);
                if (mouseX >= panel.x && mouseX < panel.x + panelWidth() && mouseY >= y && mouseY < y + panel.view) {
                    panel.scrollTarget = MathHelper.clamp(panel.scrollTarget - amount * u(15) * 2, 0, Math.max(0, panel.content - panel.view));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onKeyPressed(KeyInput input) {
            if (input.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                return true;
            }
            return false;
        }

        @Override
        public boolean onCharTyped(CharInput input) {
            if (!input.isValidChar() || search.length() >= 24) return false;
            search += input.asString();
            return true;
        }

        private Hit hitAt(double mx, double my) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(mx, my)) return hits.get(i);
            }
            return null;
        }

        private float appear(long delay) {
            float t = MathHelper.clamp((Util.getMeasuringTimeMs() - openedAt - delay) / 200f, 0, 1);
            return 1 - (1 - t) * (1 - t) * (1 - t);
        }

        private static float animate(Map<Object, Float> map, Object key, float target, float dt, float speed) {
            float value = map.getOrDefault(key, target);
            value += (target - value) * Math.min(1, dt * speed);
            map.put(key, value);
            return value;
        }

        private void round(GuiRenderer r, double x, double y, double w, double h, double radius, int argb) {
            Color c = color(argb);
            int rad = (int) Math.min(Math.floor(radius), Math.floor(Math.min(w, h) / 2));
            if (rad <= 0) {
                r.quad(x, y, w, h, c);
                return;
            }
            r.quad(x, y + rad, w, h - rad * 2, c);
            for (int i = 0; i < rad; i++) {
                double dy = rad - i - 0.5;
                double inset = rad - Math.sqrt(rad * rad - dy * dy);
                r.quad(x + inset, y + i, w - inset * 2, 1, c);
                r.quad(x + inset, y + h - 1 - i, w - inset * 2, 1, c);
            }
        }

        private Color color(int argb) {
            int a = Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(fade, 0, 1));
            return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, a);
        }

        private int accent() {
            return owner.accent.get().getPacked() | 0xFF000000;
        }

        private static int lighten(int color) {
            return blend(color, 0xFFFFFFFF, 0.35f);
        }

        private static int alpha(int color, float factor) {
            int a = Math.round(((color >>> 24) & 0xFF) * MathHelper.clamp(factor, 0, 1));
            return (a << 24) | (color & 0xFFFFFF);
        }

        private static int blend(int from, int to, float t) {
            t = MathHelper.clamp(t, 0, 1);
            int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
            int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
            int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
            int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private void saveLayout() {
            StringBuilder out = new StringBuilder("v2");
            for (Panel panel : panels) {
                out.append('|');
                out.append(panel.category.name).append(':')
                    .append(Math.round(panel.x / Math.max(1, width) * 10000)).append(':')
                    .append(Math.round(panel.y / Math.max(1, height) * 10000)).append(':')
                    .append(panel.collapsed ? 1 : 0);
            }
            owner.layout.set(out.toString());
        }

        private void loadLayout() {
            String saved = owner.layout.get();
            if (saved == null || !saved.startsWith("v2|")) return;
            for (String entry : saved.substring(3).split("\\|")) {
                String[] parts = entry.split(":");
                if (parts.length != 4) continue;
                for (Panel panel : panels) {
                    if (!panel.category.name.equals(parts[0])) continue;
                    try {
                        panel.x = MathHelper.clamp(Integer.parseInt(parts[1]) / 10000.0 * width, 0, Math.max(0, width - panelWidth()));
                        panel.y = MathHelper.clamp(Integer.parseInt(parts[2]) / 10000.0 * height, 0, Math.max(0, height - u(21)));
                        panel.collapsed = parts[3].equals("1");
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }
}
