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
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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
    private static final StyleSpriteSource REGULAR = new StyleSpriteSource.Font(Identifier.of("coyduck", "ui"));
    private static final StyleSpriteSource BOLD = new StyleSpriteSource.Font(Identifier.of("coyduck", "ui_bold"));

    private static final int MAX_WIDTH = 150;
    private static final int MIN_WIDTH = 112;
    private static final int HEADER = 28;
    private static final int ROW = 21;
    private static final int SETTING = 19;
    private static final int SLIDER = 24;
    private static final int GAP = 12;
    private static final int TOP = 46;
    private static final int PAD = 10;
    private static final int MARGIN = 20;
    private static final int RADIUS = 7;

    private static final int GLASS = 0xB60E0E13;
    private static final int SHADOW = 0x18000000;
    private static final int LINE = 0x14FFFFFF;
    private static final int HOVER = 0x12FFFFFF;
    private static final int WELL = 0x30000000;
    private static final int PRIMARY = 0xFFF3F3F6;
    private static final int SECONDARY = 0xFF9C9CAA;
    private static final int MUTED = 0xFF6C6C7A;
    private static final int TRACK = 0x33FFFFFF;
    private static final int CHIP = 0x1CFFFFFF;

    private enum Kind { HEADER, MODULE, BOOL, SLIDER, ENUM, BIND, MODULE_BIND, MORE, TAB, PANEL }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final class Panel {
        final Category category;
        final List<Module> modules;
        final int index;
        int x;
        int y;
        boolean collapsed;
        float scroll;
        float scrollTarget;
        int contentHeight;
        int viewHeight;

        Panel(Category category, List<Module> modules, int index) {
            this.category = category;
            this.modules = modules;
            this.index = index;
        }
    }

    private final ClickGui owner;
    private final List<Panel> panels = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Module> expanded = new HashSet<>();
    private final Map<Object, Float> hover = new HashMap<>();
    private final Map<Object, Float> on = new HashMap<>();
    private final Map<Module, Float> open = new HashMap<>();
    private final long openedAt = Util.getMeasuringTimeMs();

    private int width2;
    private String search = "";
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Hit slidingHit;
    private Object binding;
    private String hint;
    private String shownHint = "";
    private float hintAlpha;
    private long lastFrame;
    private float fade = 1;

    public CoyGuiScreen(ClickGui owner) {
        super(Text.literal("Coyduck"));
        this.owner = owner;
    }

    @Override
    protected void init() {
        List<Category> categories = new ArrayList<>();
        for (Category category : Modules.loopCategories()) {
            if (category == Coyduck.CATEGORY) categories.addFirst(category);
            else categories.add(category);
        }
        categories.removeIf(category -> Modules.get().getGroup(category).isEmpty());

        int count = Math.max(1, categories.size());
        width2 = MathHelper.clamp((width - MARGIN * 2 - GAP * (count - 1)) / count, MIN_WIDTH, MAX_WIDTH);
        int total = count * width2 + (count - 1) * GAP;
        int start = Math.max(MARGIN, (width - total) / 2);

        if (!panels.isEmpty()) return;
        int x = start;
        int y = TOP;
        int index = 0;
        for (Category category : categories) {
            List<Module> modules = new ArrayList<>(Modules.get().getGroup(category));
            modules.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            Panel panel = new Panel(category, modules, index++);
            if (x + width2 > width - MARGIN + 1 && x > start) {
                x = start;
                y += 240;
            }
            panel.x = x;
            panel.y = y;
            x += width2 + GAP;
            panels.add(panel);
        }
        loadLayout();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (owner.dim.get()) context.fill(0, 0, width, height, alpha(0x70050508, appear(0)));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;

        hits.clear();
        hint = null;

        fade = appear(0);
        drawSearch(context, now);
        drawTabs(context, mouseX, mouseY);

        for (Panel panel : panels) {
            context.createNewRootLayer();
            fade = appear(panel.index * 28);
            drawPanel(context, panel, mouseX, mouseY, dt, Math.round((1 - fade) * 10));
        }

        context.createNewRootLayer();
        fade = 1;
        drawHint(context, dt);
    }

    private void drawSearch(DrawContext context, long now) {
        int w = 210;
        int h = 22;
        int x = (width - w) / 2;
        int y = 12;
        shadow(context, x, y, w, h, h / 2);
        round(context, x, y, w, h, h / 2, c(GLASS));
        boolean empty = search.isEmpty();
        String shown = empty ? "Search modules" : search;
        int tx = x + 12;
        int ty = y + (h - 8) / 2;
        text(context, shown, tx, ty, c(empty ? MUTED : PRIMARY), false);
        if (!empty && (now / 530) % 2 == 0) {
            int cx = tx + width(search, false) + 1;
            context.fill(cx, y + 6, cx + 1, y + h - 6, c(PRIMARY));
        }
        if (!empty) {
            String esc = "esc";
            text(context, esc, x + w - 12 - width(esc, false), ty, c(MUTED), false);
        }
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY) {
        int x = width - MARGIN;
        List<Tab> tabs = new ArrayList<>(Tabs.get());
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            if (tab.name.equalsIgnoreCase("Modules")) continue;
            int w = width(tab.name, false);
            x -= w;
            boolean over = mouseX >= x - 4 && mouseX < x + w + 4 && mouseY >= 12 && mouseY < 34;
            float h = animate(hover, tab, over ? 1 : 0, 0.016f, 14);
            text(context, tab.name, x, 19, c(blend(MUTED, PRIMARY, h)), false);
            hits.add(new Hit(x - 4, 12, w + 8, 22, Kind.TAB, tab, null));
            x -= 14;
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
        int w = width2;

        int body = 0;
        if (!panel.collapsed) {
            for (Module module : shown) body += ROW + Math.round(settingsHeight(module) * openness(module, dt));
            body += 6;
        }
        panel.contentHeight = body;
        panel.viewHeight = panel.collapsed ? 0 : Math.max(ROW * 3, Math.min(body, height - y - HEADER - 34));
        panel.scrollTarget = MathHelper.clamp(panel.scrollTarget, 0, Math.max(0, body - panel.viewHeight));
        panel.scroll += (panel.scrollTarget - panel.scroll) * Math.min(1, dt * 16);

        int total = HEADER + panel.viewHeight;
        shadow(context, x, y, w, total, RADIUS);
        round(context, x, y, w, total, RADIUS, c(GLASS));

        int active = 0;
        for (Module module : panel.modules) if (module.isActive()) active++;
        text(context, panel.category.name, x + PAD + 2, y + (HEADER - 8) / 2, c(PRIMARY), true);
        String count = String.valueOf(active);
        int chipW = Math.max(14, width(count, true) + 8);
        int chipX = x + w - PAD - chipW;
        int chipY = y + (HEADER - 12) / 2;
        round(context, chipX, chipY, chipW, 12, 6, c(active > 0 ? alpha(accent(), 0.22f) : CHIP));
        text(context, count, chipX + (chipW - width(count, true)) / 2, chipY + 2, c(active > 0 ? lighten(accent()) : SECONDARY), true);
        hits.add(new Hit(x, y, w, HEADER, Kind.HEADER, panel, null));
        if (panel.collapsed) return;

        context.fill(x + PAD, y + HEADER - 1, x + w - PAD, y + HEADER, c(LINE));

        int top = y + HEADER;
        int bottom = top + panel.viewHeight;
        hits.add(new Hit(x, top, w, panel.viewHeight, Kind.PANEL, panel, null));

        context.enableScissor(x, top, x + w, bottom - 2);
        int row = top + 3 - Math.round(panel.scroll);
        for (Module module : shown) row = drawModule(context, module, x, w, row, top, bottom - 2, mouseX, mouseY, dt);
        context.disableScissor();

        if (body > panel.viewHeight) {
            int track = panel.viewHeight - 10;
            int bar = Math.max(14, Math.round(track * panel.viewHeight / (float) body));
            int barY = top + 5 + Math.round((track - bar) * (panel.scroll / Math.max(1, body - panel.viewHeight)));
            round(context, x + w - 4, barY, 2, bar, 1, c(0x40FFFFFF));
        }
    }

    private int drawModule(DrawContext context, Module module, int x, int w, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        boolean visible = y + ROW > top && y < bottom;
        boolean over = visible && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW, bottom);
        float h = animate(hover, module, over ? 1 : 0, dt, 16);
        float e = animate(on, module, module.isActive() ? 1 : 0, dt, 12);

        if (visible) {
            if (h > 0.01f) round(context, x + 5, y + 1, w - 10, ROW - 2, 5, c(alpha(HOVER, h)));
            int color = blend(SECONDARY, PRIMARY, Math.max(e, h * 0.7f));
            int switchX = x + w - PAD - 16;
            text(context, trim(module.title, switchX - (x + PAD + 2) - 6, false), x + PAD + 2, y + (ROW - 8) / 2, c(color), false);
            toggle(context, switchX, y + (ROW - 9) / 2, e);
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + ROW, bottom) - Math.max(y, top), Kind.MODULE, module, null));
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
            context.enableScissor(x, clipTop, x + w, clipBottom);
            round(context, x + 6, y, w - 12, full - 4, 6, c(WELL));
            drawSettings(context, module, x, w, y + 3, clipTop, clipBottom, mouseX, mouseY, dt);
            context.disableScissor();
        }
        return y + shown;
    }

    private void drawSettings(DrawContext context, Module module, int x, int w, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        int left = x + PAD + 6;
        int right = x + w - PAD - 6;

        y = drawRow(context, module, Kind.MODULE_BIND, x, w, y, top, bottom, mouseX, mouseY, dt, "Bind", bindText(module.keybind, module), null, true);

        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            List<Setting<?>> list = visibleSettings(group);
            if (list.isEmpty()) continue;
            if (groups) {
                if (y + SETTING > top && y < bottom) text(context, group.name.toUpperCase(Locale.ROOT), left, y + (SETTING - 8) / 2 + 2, c(MUTED), true);
                y += SETTING;
            }
            for (Setting<?> setting : list) y = drawSetting(context, module, setting, x, w, y, top, bottom, mouseX, mouseY, dt, left, right);
        }

        drawRow(context, module, Kind.MORE, x, w, y, top, bottom, mouseX, mouseY, dt, "All settings", "›", null, false);
    }

    private int drawSetting(DrawContext context, Module module, Setting<?> setting, int x, int w, int y, int top, int bottom, int mouseX, int mouseY, float dt, int left, int right) {
        if (setting instanceof BoolSetting bool) {
            boolean visible = y + SETTING > top && y < bottom;
            boolean over = visible && within(mouseX, mouseY, x, w, y, SETTING, top, bottom);
            float h = animate(hover, setting, over ? 1 : 0, dt, 16);
            float e = animate(on, setting, bool.get() ? 1 : 0, dt, 12);
            if (visible) {
                if (h > 0.01f) round(context, x + 8, y + 1, w - 16, SETTING - 2, 4, c(alpha(HOVER, h)));
                text(context, trim(setting.title, right - left - 22, false), left, y + (SETTING - 8) / 2, c(blend(SECONDARY, PRIMARY, Math.max(h, e * 0.5f))), false);
                toggle(context, right - 16, y + (SETTING - 9) / 2, e);
                hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + SETTING, bottom) - Math.max(y, top), Kind.BOOL, setting, module));
                if (over) hint = setting.description;
            }
            return y + SETTING;
        }

        if (setting instanceof DoubleSetting || setting instanceof IntSetting) {
            boolean visible = y + SLIDER > top && y < bottom;
            boolean over = visible && within(mouseX, mouseY, x, w, y, SLIDER, top, bottom);
            float h = animate(hover, setting, over || sliding == setting ? 1 : 0, dt, 16);
            if (visible) {
                if (h > 0.01f) round(context, x + 8, y + 1, w - 16, SLIDER - 2, 4, c(alpha(HOVER, h)));
                String value = valueText(setting);
                int valueWidth = width(value, false);
                text(context, trim(setting.title, right - left - valueWidth - 6, false), left, y + 4, c(blend(SECONDARY, PRIMARY, h)), false);
                text(context, value, right - valueWidth, y + 4, c(PRIMARY), false);
                float t = sliderFraction(setting);
                int trackY = y + 16;
                round(context, left, trackY, right - left, 3, 1, c(TRACK));
                int fill = Math.round((right - left) * t);
                if (fill > 0) round(context, left, trackY, fill, 3, 1, c(accent()));
                int knob = left + fill;
                round(context, knob - 3, trackY - 2, 7, 7, 3, c(blend(0xFFE6E6EE, 0xFFFFFFFF, h)));
                hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + SLIDER, bottom) - Math.max(y, top), Kind.SLIDER, setting, new int[]{left, right}));
                if (over) hint = setting.description;
            }
            return y + SLIDER;
        }

        if (setting instanceof EnumSetting<?>) {
            return drawRow(context, module, Kind.ENUM, x, w, y, top, bottom, mouseX, mouseY, dt, setting.title, pretty(String.valueOf(setting.get())), setting, false);
        }

        if (setting instanceof KeybindSetting keybind) {
            return drawRow(context, module, Kind.BIND, x, w, y, top, bottom, mouseX, mouseY, dt, setting.title, bindText(keybind.get(), keybind), setting, true);
        }

        if (setting instanceof ColorSetting color) {
            int end = drawRow(context, module, Kind.MORE, x, w, y, top, bottom, mouseX, mouseY, dt, setting.title, "", setting, false);
            if (y + SETTING > top && y < bottom) {
                round(context, right - 14, y + (SETTING - 9) / 2, 14, 9, 4, c(color.get().getPacked() | 0xFF000000));
            }
            return end;
        }

        return drawRow(context, module, Kind.MORE, x, w, y, top, bottom, mouseX, mouseY, dt, setting.title, "›", setting, false);
    }

    private int drawRow(DrawContext context, Module module, Kind kind, int x, int w, int y, int top, int bottom, int mouseX, int mouseY, float dt,
                        String label, String value, Setting<?> setting, boolean chip) {
        boolean visible = y + SETTING > top && y < bottom;
        Object key = setting != null ? setting : kind.name() + module.name;
        boolean over = visible && within(mouseX, mouseY, x, w, y, SETTING, top, bottom);
        float h = animate(hover, key, over ? 1 : 0, dt, 16);
        if (visible) {
            if (h > 0.01f) round(context, x + 8, y + 1, w - 16, SETTING - 2, 4, c(alpha(HOVER, h)));
            int left = x + PAD + 6;
            int right = x + w - PAD - 6;
            boolean listening = binding != null && binding == (kind == Kind.MODULE_BIND ? module : setting);
            int valueWidth = width(value, false);
            int textY = y + (SETTING - 8) / 2;
            if (chip && !value.isEmpty()) {
                int cw = valueWidth + 10;
                round(context, right - cw, y + (SETTING - 13) / 2, cw, 13, 4, c(listening ? alpha(accent(), 0.3f) : CHIP));
                text(context, value, right - cw + 5, textY, c(listening ? lighten(accent()) : PRIMARY), false);
                valueWidth = cw;
            } else {
                int color = kind == Kind.ENUM ? lighten(accent()) : (kind == Kind.MORE ? blend(MUTED, PRIMARY, h) : PRIMARY);
                text(context, value, right - valueWidth, textY, c(color), false);
            }
            text(context, trim(label, right - left - valueWidth - 6, false), left, textY, c(blend(SECONDARY, PRIMARY, h)), false);
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + SETTING, bottom) - Math.max(y, top), kind, setting != null ? setting : module, module));
            if (over && setting != null) hint = setting.description;
        }
        return y + SETTING;
    }

    private void drawHint(DrawContext context, float dt) {
        boolean has = hint != null && !hint.isEmpty();
        if (has) shownHint = hint;
        hintAlpha += ((has ? 1 : 0) - hintAlpha) * Math.min(1, dt * 12);
        if (hintAlpha < 0.02f || shownHint.isEmpty()) return;

        String text = trim(shownHint, Math.min(width - 80, 420), false);
        int w = width(text, false) + 24;
        int h = 22;
        int x = (width - w) / 2;
        int y = height - h - 14 + Math.round((1 - hintAlpha) * 4);
        float saved = fade;
        fade = hintAlpha;
        shadow(context, x, y, w, h, h / 2);
        round(context, x, y, w, h, h / 2, c(GLASS));
        text(context, text, x + 12, y + (h - 8) / 2, c(PRIMARY), false);
        fade = saved;
    }

    private void toggle(DrawContext context, int x, int y, float e) {
        round(context, x, y, 16, 9, 4, c(blend(0x2EFFFFFF, accent(), e)));
        int knob = x + 1 + Math.round(7 * e);
        round(context, knob, y + 1, 7, 7, 3, c(blend(0xFFB4B4C0, 0xFFFFFFFF, e)));
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
            dragging.x = MathHelper.clamp((int) click.x() - dragX, 0, Math.max(0, width - width2));
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
        setting.set((Enum) values[Math.floorMod(current.ordinal() + step, values.length)]);
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

    private static String pretty(String value) {
        return value.replace('_', ' ');
    }

    private String bindText(Keybind keybind, Object owner) {
        if (binding == owner) return "press a key";
        return keybind.isSet() ? keybind.toString() : "None";
    }

    private static List<Setting<?>> visibleSettings(SettingGroup group) {
        List<Setting<?>> list = new ArrayList<>();
        for (Setting<?> setting : group) if (setting.isVisible()) list.add(setting);
        return list;
    }

    private int settingsHeight(Module module) {
        int height = SETTING * 2 + 10;
        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            List<Setting<?>> list = visibleSettings(group);
            if (list.isEmpty()) continue;
            if (groups) height += SETTING;
            for (Setting<?> setting : list) height += setting instanceof DoubleSetting || setting instanceof IntSetting ? SLIDER : SETTING;
        }
        return height;
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

    private static boolean within(int mouseX, int mouseY, int x, int w, int y, int h, int top, int bottom) {
        return mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
    }

    private Text styled(String value, boolean bold) {
        return Text.literal(value).setStyle(Style.EMPTY.withFont(bold ? BOLD : REGULAR));
    }

    private int width(String value, boolean bold) {
        return textRenderer.getWidth(styled(value, bold));
    }

    private void text(DrawContext context, String value, int x, int y, int color, boolean bold) {
        context.drawText(textRenderer, styled(value, bold), x, y, color, false);
    }

    private String trim(String value, int max, boolean bold) {
        if (width(value, bold) <= max) return value;
        String dots = "…";
        int end = value.length();
        while (end > 0 && width(value.substring(0, end), bold) + width(dots, bold) > max) end--;
        return value.substring(0, end) + dots;
    }

    private static void round(DrawContext context, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        context.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            context.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            context.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    private void shadow(DrawContext context, int x, int y, int w, int h, int r) {
        for (int i = 3; i >= 1; i--) round(context, x - i, y - i + 2, w + i * 2, h + i * 2, r + i, c(alpha(SHADOW, 1f / i)));
    }

    private float appear(long delay) {
        float t = MathHelper.clamp((Util.getMeasuringTimeMs() - openedAt - delay) / 220f, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private int c(int color) {
        return alpha(color, fade);
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
        StringBuilder out = new StringBuilder();
        for (Panel panel : panels) {
            if (!out.isEmpty()) out.append('|');
            out.append(panel.category.name).append(':').append(panel.x).append(':').append(panel.y).append(':').append(panel.collapsed ? 1 : 0);
        }
        owner.layout.set(out.toString());
    }

    private void loadLayout() {
        String saved = owner.layout.get();
        if (saved == null || saved.isEmpty()) return;
        for (String entry : saved.split("\\|")) {
            String[] parts = entry.split(":");
            if (parts.length != 4) continue;
            for (Panel panel : panels) {
                if (!panel.category.name.equals(parts[0])) continue;
                try {
                    panel.x = MathHelper.clamp(Integer.parseInt(parts[1]), 0, Math.max(0, width - width2));
                    panel.y = MathHelper.clamp(Integer.parseInt(parts[2]), 0, Math.max(0, height - HEADER));
                    panel.collapsed = parts[3].equals("1");
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
