package dev.coyduck.gui;

import dev.coyduck.Coyduck;
import dev.coyduck.modules.ClickGui;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CoyGuiScreen extends Screen {
    private static final int WIDTH = 116;
    private static final int HEADER = 17;
    private static final int ROW = 14;
    private static final int SETTING = 13;
    private static final int GAP = 7;
    private static final int TOP = 34;
    private static final int PAD = 6;
    private static final int MARGIN = 12;

    private static final int PANEL = 0xA60F0F14;
    private static final int HEAD = 0xCC16161D;
    private static final int OUTLINE = 0x22FFFFFF;
    private static final int HOVER = 0x16FFFFFF;
    private static final int INSET = 0x46000000;
    private static final int TITLE = 0xFFEDEDF3;
    private static final int TEXT = 0xFFA4A4B0;
    private static final int BRIGHT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6E6E7A;
    private static final int LABEL = 0xFF9696A2;
    private static final int VALUE = 0xFFDADAE2;
    private static final int TRACK = 0x30FFFFFF;

    private enum Kind { HEADER, MODULE, BOOL, SLIDER, ENUM, BIND, MODULE_BIND, MORE, TAB, PANEL }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final class Panel {
        final Category category;
        final List<Module> modules;
        int x;
        int y;
        boolean collapsed;
        float scroll;
        float scrollTarget;
        int contentHeight;
        int viewHeight;
        int maxView;

        Panel(Category category, List<Module> modules) {
            this.category = category;
            this.modules = modules;
        }
    }

    private final ClickGui owner;
    private final List<Panel> panels = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Module> expanded = new HashSet<>();
    private final Map<Object, Float> hover = new HashMap<>();
    private final Map<Object, Float> on = new HashMap<>();
    private final Map<Module, Float> open = new HashMap<>();

    private String search = "";
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Hit slidingHit;
    private Object binding;
    private String hint;
    private float hintAlpha;
    private long lastFrame;
    private final long openedAt;
    private boolean custom;

    public CoyGuiScreen(ClickGui owner) {
        super(Text.literal("Coyduck"));
        this.owner = owner;
        this.openedAt = Util.getMeasuringTimeMs();
    }

    @Override
    protected void init() {
        if (panels.isEmpty()) {
            List<Category> categories = new ArrayList<>();
            for (Category category : Modules.loopCategories()) {
                if (category == Coyduck.CATEGORY) categories.addFirst(category);
                else categories.add(category);
            }
            for (Category category : categories) {
                List<Module> modules = new ArrayList<>(Modules.get().getGroup(category));
                if (modules.isEmpty()) continue;
                modules.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                panels.add(new Panel(category, modules));
            }
            loadLayout();
        }
        if (!custom) arrange();
        for (Panel panel : panels) {
            panel.x = MathHelper.clamp(panel.x, 0, Math.max(0, width - WIDTH));
            panel.y = MathHelper.clamp(panel.y, 0, Math.max(0, height - HEADER));
        }
    }

    private void arrange() {
        int count = panels.size();
        if (count == 0) return;
        int columns = MathHelper.clamp((width - MARGIN * 2 + GAP) / (WIDTH + GAP), 1, count);
        int rows = (count + columns - 1) / columns;
        int available = height - TOP - MARGIN;
        int cap = rows > 1 ? Math.max(ROW * 4, available / rows - HEADER - GAP) : available - HEADER - 26;
        int total = columns * WIDTH + (columns - 1) * GAP;
        int start = Math.max(MARGIN, (width - total) / 2);
        int[] stack = new int[columns];
        java.util.Arrays.fill(stack, TOP);
        for (Panel panel : panels) {
            int column = 0;
            for (int i = 1; i < columns; i++) if (stack[i] < stack[column]) column = i;
            panel.x = start + column * (WIDTH + GAP);
            panel.y = stack[column];
            panel.maxView = cap;
            stack[column] += HEADER + (panel.collapsed ? 0 : Math.min(panel.modules.size() * ROW, cap) + 2) + GAP;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!owner.dim.get()) return;
        float in = appear();
        context.fill(0, 0, width, height, alpha(0x9A06060A, in));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;

        hits.clear();
        hint = null;
        int lift = Math.round((1 - ease(appear())) * 8);

        drawSearch(context, now);
        drawTabs(context, mouseX, mouseY);

        for (Panel panel : panels) {
            context.createNewRootLayer();
            drawPanel(context, panel, mouseX, mouseY, dt, lift);
        }

        context.createNewRootLayer();
        drawHint(context, dt);
    }

    private void drawSearch(DrawContext context, long now) {
        int w = 170;
        int x = (width - w) / 2;
        int y = 8;
        context.fill(x, y, x + w, y + 15, PANEL);
        context.drawStrokedRectangle(x, y, w, 15, search.isEmpty() ? OUTLINE : alpha(accent(), 0.8f));
        String shown = search.isEmpty() ? "Search" : search;
        context.drawText(textRenderer, shown, x + 6, y + 4, search.isEmpty() ? MUTED : VALUE, false);
        if ((now / 500) % 2 == 0) {
            int cx = x + 6 + (search.isEmpty() ? 0 : textRenderer.getWidth(search) + 1);
            context.fill(cx, y + 3, cx + 1, y + 12, search.isEmpty() ? MUTED : VALUE);
        }
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY) {
        int x = width - MARGIN;
        List<Tab> tabs = new ArrayList<>(Tabs.get());
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            boolean current = tab.name.equalsIgnoreCase("Modules");
            int w = textRenderer.getWidth(tab.name);
            x -= w;
            boolean over = mouseX >= x - 6 && mouseX < x + w + 6 && mouseY >= 8 && mouseY < 23;
            context.drawText(textRenderer, tab.name, x, 12, current || over ? BRIGHT : MUTED, false);
            if (current) context.fill(x, 22, x + w, 23, accent());
            else hits.add(new Hit(x - 6, 8, w + 12, 15, Kind.TAB, tab, null));
            x -= 12;
        }
    }

    private void drawPanel(DrawContext context, Panel panel, int mouseX, int mouseY, float dt, int lift) {
        List<Module> shown = new ArrayList<>();
        String query = search.toLowerCase(Locale.ROOT);
        for (Module module : panel.modules) {
            if (query.isEmpty() || module.title.toLowerCase(Locale.ROOT).contains(query)) shown.add(module);
        }
        if (!query.isEmpty() && shown.isEmpty()) return;

        int x = panel.x;
        int y = panel.y + lift;

        int active = 0;
        for (Module module : panel.modules) if (module.isActive()) active++;

        context.fill(x + 1, y, x + WIDTH - 1, y + 1, HEAD);
        context.fill(x, y + 1, x + WIDTH, y + HEADER, HEAD);
        context.drawText(textRenderer, panel.category.name, x + PAD, y + 5, TITLE, false);
        String count = active + "/" + panel.modules.size();
        context.drawText(textRenderer, count, x + WIDTH - PAD - textRenderer.getWidth(count), y + 5, MUTED, false);
        context.fill(x, y + HEADER - 1, x + WIDTH, y + HEADER, alpha(accent(), panel.collapsed ? 0.25f : 0.6f));
        hits.add(new Hit(x, y, WIDTH, HEADER, Kind.HEADER, panel, null));
        if (panel.collapsed) return;

        int body = 0;
        for (Module module : shown) body += ROW + Math.round(settingsHeight(module) * openness(module, dt));
        panel.contentHeight = body;
        int limit = height - y - HEADER - 26;
        if (panel.maxView > 0) limit = Math.min(limit, panel.maxView);
        panel.viewHeight = Math.max(ROW * 3, Math.min(body, limit));
        panel.scrollTarget = MathHelper.clamp(panel.scrollTarget, 0, Math.max(0, body - panel.viewHeight));
        panel.scroll += (panel.scrollTarget - panel.scroll) * Math.min(1, dt * 18);

        int top = y + HEADER;
        int bottom = top + panel.viewHeight;
        context.fill(x, top, x + WIDTH, bottom, PANEL);
        context.fill(x + 1, bottom, x + WIDTH - 1, bottom + 1, PANEL);
        context.drawStrokedRectangle(x, y + HEADER - 1, WIDTH, panel.viewHeight + 2, OUTLINE);
        hits.add(new Hit(x, top, WIDTH, panel.viewHeight, Kind.PANEL, panel, null));

        context.enableScissor(x, top, x + WIDTH, bottom);
        int row = top - Math.round(panel.scroll);
        for (Module module : shown) {
            row = drawModule(context, module, x, row, top, bottom, mouseX, mouseY, dt);
        }
        context.disableScissor();

        if (body > panel.viewHeight) {
            float ratio = panel.viewHeight / (float) body;
            int barHeight = Math.max(10, Math.round(panel.viewHeight * ratio));
            int barY = top + Math.round((panel.viewHeight - barHeight) * (panel.scroll / Math.max(1, body - panel.viewHeight)));
            context.fill(x + WIDTH - 2, barY, x + WIDTH - 1, barY + barHeight, alpha(accent(), 0.5f));
        }
    }

    private int drawModule(DrawContext context, Module module, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        boolean visible = y + ROW > top && y < bottom;
        boolean over = visible && mouseX >= x && mouseX < x + WIDTH && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW, bottom);
        float h = animate(hover, module, over ? 1 : 0, dt, 14);
        float e = animate(on, module, module.isActive() ? 1 : 0, dt, 12);

        if (visible) {
            if (h > 0.01f) context.fill(x, y, x + WIDTH, y + ROW, alpha(HOVER, h));
            if (e > 0.01f) {
                context.fill(x, y, x + WIDTH, y + ROW, alpha(accent(), 0.16f * e));
                context.fill(x, y + 2, x + 2, y + ROW - 2, alpha(accent(), e));
            }
            int color = blend(TEXT, BRIGHT, Math.max(e, h * 0.6f));
            context.drawText(textRenderer, trim(module.title, WIDTH - PAD * 2 - 10), x + PAD + Math.round(e * 2), y + 3, color, false);
            boolean expandedNow = expanded.contains(module);
            context.drawText(textRenderer, expandedNow ? "-" : "+", x + WIDTH - PAD - 5, y + 3, blend(MUTED, TEXT, h), false);
            hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + ROW, bottom) - Math.max(y, top), Kind.MODULE, module, null));
            if (over) hint = module.description;
        }
        y += ROW;

        float openness = open.getOrDefault(module, 0f);
        if (openness <= 0.001f) return y;

        int full = settingsHeight(module);
        int shown = Math.round(full * openness);
        int clipTop = Math.max(y, top);
        int clipBottom = Math.min(y + shown, bottom);
        if (clipBottom > clipTop) {
            context.enableScissor(x, clipTop, x + WIDTH, clipBottom);
            context.fill(x, y, x + WIDTH, y + shown, INSET);
            context.fill(x, y, x + 1, y + shown, alpha(accent(), 0.35f));
            drawSettings(context, module, x, y, clipTop, clipBottom, mouseX, mouseY, dt);
            context.disableScissor();
        }
        return y + shown;
    }

    private void drawSettings(DrawContext context, Module module, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        int left = x + PAD + 4;
        int right = x + WIDTH - PAD;

        y = drawRow(context, module, Kind.MODULE_BIND, x, y, top, bottom, mouseX, mouseY, dt, "Bind", bindText(module.keybind, module), null);

        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            List<Setting<?>> list = visibleSettings(group);
            if (list.isEmpty()) continue;
            if (groups) {
                if (y + SETTING > top && y < bottom) context.drawText(textRenderer, group.name.toUpperCase(Locale.ROOT), left, y + 3, MUTED, false);
                y += SETTING;
            }
            for (Setting<?> setting : list) {
                y = drawSetting(context, module, setting, x, y, top, bottom, mouseX, mouseY, dt, left, right);
            }
        }

        drawRow(context, module, Kind.MORE, x, y, top, bottom, mouseX, mouseY, dt, "All settings", "›", null);
    }

    private int drawSetting(DrawContext context, Module module, Setting<?> setting, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt, int left, int right) {
        if (setting instanceof BoolSetting bool) {
            boolean visible = y + SETTING > top && y < bottom;
            boolean over = visible && within(mouseX, mouseY, x, y, top, bottom);
            float h = animate(hover, setting, over ? 1 : 0, dt, 14);
            float e = animate(on, setting, bool.get() ? 1 : 0, dt, 14);
            if (visible) {
                if (h > 0.01f) context.fill(x + 1, y, x + WIDTH, y + SETTING, alpha(HOVER, h));
                context.drawText(textRenderer, trim(setting.title, WIDTH - PAD * 2 - 16), left, y + 3, blend(LABEL, VALUE, h), false);
                int bx = right - 7;
                int by = y + 3;
                context.drawStrokedRectangle(bx, by, 7, 7, blend(0xFF3A3A45, accent(), e));
                if (e > 0.01f) context.fill(bx + 2, by + 2, bx + 5, by + 5, alpha(accent(), e));
                hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), Kind.BOOL, setting, module));
                if (over) hint = setting.description;
            }
            return y + SETTING;
        }

        if (setting instanceof DoubleSetting || setting instanceof IntSetting) {
            boolean visible = y + SETTING > top && y < bottom;
            boolean over = visible && within(mouseX, mouseY, x, y, top, bottom);
            float h = animate(hover, setting, over || sliding == setting ? 1 : 0, dt, 14);
            if (visible) {
                if (h > 0.01f) context.fill(x + 1, y, x + WIDTH, y + SETTING, alpha(HOVER, h));
                String value = valueText(setting);
                int valueWidth = textRenderer.getWidth(value);
                context.drawText(textRenderer, trim(setting.title, right - left - valueWidth - 4), left, y + 2, blend(LABEL, VALUE, h), false);
                context.drawText(textRenderer, value, right - valueWidth, y + 2, VALUE, false);
                float t = sliderFraction(setting);
                context.fill(left, y + SETTING - 2, right, y + SETTING - 1, TRACK);
                context.fill(left, y + SETTING - 2, left + Math.round((right - left) * t), y + SETTING - 1, accent());
                hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), Kind.SLIDER, setting, new int[]{left, right}));
                if (over) hint = setting.description;
            }
            return y + SETTING;
        }

        if (setting instanceof EnumSetting<?>) {
            return drawRow(context, module, Kind.ENUM, x, y, top, bottom, mouseX, mouseY, dt, setting.title, String.valueOf(setting.get()), setting);
        }

        if (setting instanceof KeybindSetting keybind) {
            return drawRow(context, module, Kind.BIND, x, y, top, bottom, mouseX, mouseY, dt, setting.title, bindText(keybind.get(), keybind), setting);
        }

        if (setting instanceof ColorSetting color) {
            int end = drawRow(context, module, Kind.MORE, x, y, top, bottom, mouseX, mouseY, dt, setting.title, "", setting);
            if (y + SETTING > top && y < bottom) {
                context.fill(right - 7, y + 3, right, y + 10, color.get().getPacked() | 0xFF000000);
                context.drawStrokedRectangle(right - 8, y + 2, 9, 9, 0xFF3A3A45);
            }
            return end;
        }

        return drawRow(context, module, Kind.MORE, x, y, top, bottom, mouseX, mouseY, dt, setting.title, "›", setting);
    }

    private int drawRow(DrawContext context, Module module, Kind kind, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt, String label, String value, Setting<?> setting) {
        boolean visible = y + SETTING > top && y < bottom;
        Object key = setting != null ? setting : kind.name() + module.name;
        boolean over = visible && within(mouseX, mouseY, x, y, top, bottom);
        float h = animate(hover, key, over ? 1 : 0, dt, 14);
        if (visible) {
            if (h > 0.01f) context.fill(x + 1, y, x + WIDTH, y + SETTING, alpha(HOVER, h));
            int right = x + WIDTH - PAD;
            int valueWidth = textRenderer.getWidth(value);
            boolean active = binding != null && binding == (kind == Kind.MODULE_BIND ? module : setting);
            context.drawText(textRenderer, trim(label, right - (x + PAD + 4) - valueWidth - 4), x + PAD + 4, y + 3, blend(LABEL, VALUE, h), false);
            context.drawText(textRenderer, value, right - valueWidth, y + 3, active ? accent() : (kind == Kind.ENUM ? blend(VALUE, accent(), 0.35f) : VALUE), false);
            hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), kind, setting != null ? setting : module, module));
            if (over && setting != null) hint = setting.description;
        }
        return y + SETTING;
    }

    private void drawHint(DrawContext context, float dt) {
        float target = hint == null || hint.isEmpty() ? 0 : 1;
        hintAlpha += (target - hintAlpha) * Math.min(1, dt * 12);
        if (hintAlpha < 0.02f || hint == null) return;
        String text = trim(hint, width - 80);
        int w = textRenderer.getWidth(text) + 16;
        int x = (width - w) / 2;
        int y = height - 22;
        context.fill(x, y, x + w, y + 15, alpha(PANEL, hintAlpha));
        context.drawStrokedRectangle(x, y, w, 15, alpha(OUTLINE, hintAlpha));
        context.drawText(textRenderer, text, x + 8, y + 4, alpha(VALUE, hintAlpha), false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        int button = click.button();

        if (binding != null) {
            if (button > GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                applyBind(Keybind.fromButton(button));
                return true;
            }
            binding = null;
        }

        Hit hit = hitAt(mx, my);
        if (hit == null) return super.mouseClicked(click, doubled);

        switch (hit.kind) {
            case HEADER -> {
                Panel panel = (Panel) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    panel.collapsed = !panel.collapsed;
                    if (!custom) arrange();
                    saveLayout();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && doubled) {
                    custom = false;
                    for (Panel each : panels) each.maxView = 0;
                    arrange();
                    saveLayout();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    dragging = panel;
                    dragX = (int) mx - panel.x;
                    dragY = (int) my - panel.y;
                    panels.remove(panel);
                    panels.add(panel);
                }
            }
            case MODULE -> {
                Module module = (Module) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) module.toggle();
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !expanded.remove(module)) expanded.add(module);
            }
            case BOOL -> {
                BoolSetting setting = (BoolSetting) hit.target;
                setting.set(!setting.get());
            }
            case SLIDER -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    sliding = (Setting<?>) hit.target;
                    slidingHit = hit;
                    slide(mx);
                }
            }
            case ENUM -> cycle((EnumSetting<?>) hit.target, button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1);
            case BIND -> binding = hit.target;
            case MODULE_BIND -> binding = hit.extra;
            case MORE -> client.setScreen(GuiThemes.get().moduleScreen((Module) hit.extra));
            case TAB -> ((Tab) hit.target).openScreen(GuiThemes.get());
            case PANEL -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != null) {
            custom = true;
            dragging.x = MathHelper.clamp((int) click.x() - dragX, 0, Math.max(0, width - WIDTH));
            dragging.y = MathHelper.clamp((int) click.y() - dragY, 0, Math.max(0, height - HEADER));
            return true;
        }
        if (sliding != null) {
            slide(click.x());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) saveLayout();
        dragging = null;
        sliding = null;
        slidingHit = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.kind == Kind.PANEL && hit.contains(mouseX, mouseY)) {
                Panel panel = (Panel) hit.target;
                panel.scrollTarget = MathHelper.clamp(panel.scrollTarget - (float) vertical * ROW * 2, 0, Math.max(0, panel.contentHeight - panel.viewHeight));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (binding != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) applyBind(Keybind.none());
            else applyBind(Keybind.fromKeys(key, input.modifiers()));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty()) {
                search = "";
                return true;
            }
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (binding != null || !input.isValidChar()) return false;
        if (search.length() < 24) search += input.asString();
        return true;
    }

    @Override
    public void close() {
        saveLayout();
        super.close();
    }

    private Hit hitAt(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.kind != Kind.PANEL && hit.contains(mx, my)) return hit;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) return hit;
        }
        return null;
    }

    private void applyBind(Keybind keybind) {
        if (binding instanceof Module module) module.keybind.set(keybind);
        else if (binding instanceof KeybindSetting setting) setting.set(keybind);
        binding = null;
    }

    private void slide(double mx) {
        if (sliding == null || slidingHit == null) return;
        int[] range = (int[]) slidingHit.extra;
        double t = MathHelper.clamp((mx - range[0]) / (double) (range[1] - range[0]), 0, 1);
        if (sliding instanceof DoubleSetting setting) {
            double value = setting.sliderMin + (setting.sliderMax - setting.sliderMin) * t;
            double scale = Math.pow(10, setting.decimalPlaces);
            value = Math.round(value * scale) / scale;
            setting.set(MathHelper.clamp(value, setting.min, setting.max));
        } else if (sliding instanceof IntSetting setting) {
            int value = (int) Math.round(setting.sliderMin + (setting.sliderMax - setting.sliderMin) * t);
            setting.set(MathHelper.clamp(value, setting.min, setting.max));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cycle(EnumSetting setting, int step) {
        Enum<?> current = (Enum<?>) setting.get();
        Object[] values = current.getDeclaringClass().getEnumConstants();
        int index = Math.floorMod(current.ordinal() + step, values.length);
        setting.set((Enum) values[index]);
    }

    private static float sliderFraction(Setting<?> setting) {
        if (setting instanceof DoubleSetting d) {
            double span = d.sliderMax - d.sliderMin;
            return span <= 0 ? 0 : (float) MathHelper.clamp((d.get() - d.sliderMin) / span, 0, 1);
        }
        if (setting instanceof IntSetting i) {
            int span = i.sliderMax - i.sliderMin;
            return span <= 0 ? 0 : (float) MathHelper.clamp((i.get() - i.sliderMin) / (double) span, 0, 1);
        }
        return 0;
    }

    private static String valueText(Setting<?> setting) {
        if (setting instanceof DoubleSetting d) return String.format(Locale.ROOT, "%." + Math.min(d.decimalPlaces, 2) + "f", d.get());
        return String.valueOf(setting.get());
    }

    private String bindText(Keybind keybind, Object owner) {
        if (binding == owner) return "...";
        return keybind.isSet() ? keybind.toString() : "None";
    }

    private static List<Setting<?>> visibleSettings(SettingGroup group) {
        List<Setting<?>> list = new ArrayList<>();
        for (Setting<?> setting : group) if (setting.isVisible()) list.add(setting);
        return list;
    }

    private int settingsHeight(Module module) {
        int rows = 2;
        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            int visible = visibleSettings(group).size();
            if (visible == 0) continue;
            rows += visible + (groups ? 1 : 0);
        }
        return rows * SETTING;
    }

    private float openness(Module module, float dt) {
        float target = expanded.contains(module) ? 1 : 0;
        float value = open.getOrDefault(module, 0f);
        value += (target - value) * Math.min(1, dt * 14);
        if (Math.abs(target - value) < 0.002f) value = target;
        if (value == 0) open.remove(module);
        else open.put(module, value);
        return value;
    }

    private static float animate(Map<Object, Float> map, Object key, float target, float dt, float speed) {
        float value = map.getOrDefault(key, target);
        value += (target - value) * Math.min(1, dt * speed);
        map.put(key, value);
        return value;
    }

    private boolean within(int mouseX, int mouseY, int x, int y, int top, int bottom) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= Math.max(y, top) && mouseY < Math.min(y + SETTING, bottom);
    }

    private String trim(String text, int max) {
        if (textRenderer.getWidth(text) <= max) return text;
        String dots = "…";
        int end = text.length();
        while (end > 0 && textRenderer.getWidth(text.substring(0, end)) + textRenderer.getWidth(dots) > max) end--;
        return text.substring(0, end) + dots;
    }

    private float appear() {
        return Math.min(1, (Util.getMeasuringTimeMs() - openedAt) / 160f);
    }

    private static float ease(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private int accent() {
        return owner.accent.get().getPacked() | 0xFF000000;
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
        StringBuilder out = new StringBuilder(custom ? "custom" : "auto");
        for (Panel panel : panels) {
            out.append('|').append(panel.category.name).append(':').append(panel.x).append(':').append(panel.y).append(':').append(panel.collapsed ? 1 : 0);
        }
        owner.layout.set(out.toString());
    }

    private void loadLayout() {
        String saved = owner.layout.get();
        if (saved == null || !(saved.startsWith("custom|") || saved.startsWith("auto|"))) return;
        custom = saved.startsWith("custom|");
        for (String entry : saved.substring(saved.indexOf('|') + 1).split("\\|")) {
            String[] parts = entry.split(":");
            if (parts.length != 4) continue;
            for (Panel panel : panels) {
                if (!panel.category.name.equals(parts[0])) continue;
                try {
                    panel.x = Integer.parseInt(parts[1]);
                    panel.y = Integer.parseInt(parts[2]);
                    panel.collapsed = parts[3].equals("1");
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
