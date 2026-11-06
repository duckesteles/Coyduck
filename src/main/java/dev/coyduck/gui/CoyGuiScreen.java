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
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CoyGuiScreen extends Screen {
    private static final List<String> GROUPS = List.of("Combat", "Player", "Movement", "Render", "World", "Misc");
    private static final Map<String, String> SPLIT = Map.ofEntries(
        Map.entry("anti-piston", "Combat"), Map.entry("auto-anchor", "Combat"), Map.entry("auto-city+", "Combat"),
        Map.entry("auto-crystal", "Combat"), Map.entry("auto-totem+", "Combat"), Map.entry("auto-trap+", "Combat"),
        Map.entry("auto-web+", "Combat"), Map.entry("burrow+", "Combat"), Map.entry("hole-fill", "Combat"),
        Map.entry("kill-aura+", "Combat"), Map.entry("ladder-trap", "Combat"), Map.entry("pearl-phase", "Combat"),
        Map.entry("self-trap+", "Combat"), Map.entry("surround+", "Combat"),
        Map.entry("air-mine", "Player"), Map.entry("auto-xp", "Player"), Map.entry("no-rotate+", "Player"),
        Map.entry("elytra-fly+", "Movement"), Map.entry("elytra-pilot", "Movement"), Map.entry("hole-snap", "Movement"),
        Map.entry("speed+", "Movement"),
        Map.entry("break-esp+", "Render"), Map.entry("crystal-chams", "Render"), Map.entry("custom-fov", "Render"),
        Map.entry("esp+", "Render"), Map.entry("hide-rich", "Render"), Map.entry("hole-esp+", "Render"),
        Map.entry("kill-effects", "Render"), Map.entry("no-explosion", "Render"), Map.entry("shaders+", "Render"),
        Map.entry("schem-builder", "World")
    );
    private static final long SWITCH_TIME = 220;

    private static final int WIDTH = 96;
    private static final int HEADER = 12;
    private static final int ROW = 12;
    private static final int GAP = 4;
    private static final int MARGIN = 6;
    private static final int INDENT = 3;
    private static final int PAD = 4;
    private static final int SCROLL = 16;
    private static final int TIP_WIDTH = 160;
    private static final long TIP_DELAY = 400;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB4B0BE;
    private static final int BODY = 0x8C120F18;
    private static final int CELL = 0xB42A2533;
    private static final int CELL_HOVER = 0xC8352F40;
    private static final int SWATCH_EDGE = 0xFF0C0A10;

    private enum Kind { HEADER, MODULE, BOOL, SLIDER, ENUM, COLOR, BIND, MODULE_BIND, MORE, TAB, PAGE }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final class Panel {
        final String key;
        final String title;
        final List<Module> modules;
        int x;
        int y;
        boolean collapsed;

        Panel(String key, String title, List<Module> modules) {
            this.key = key;
            this.title = title;
            this.modules = modules;
        }
    }

    private static final class Page {
        final String name;
        final List<Panel> panels = new ArrayList<>();

        Page(String name) {
            this.name = name;
        }
    }

    private final ClickGui owner;
    private final List<Page> pages = new ArrayList<>();
    private List<Panel> panels = new ArrayList<>();
    private int page;
    private int previousPage = -1;
    private int direction;
    private long switchedAt;
    private Style font = Style.EMPTY;
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Module> expanded = new HashSet<>();

    private boolean custom;
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Hit slidingHit;
    private Object binding;

    private Object hoverKey;
    private long hoverSince;
    private String hint;

    private int accent;
    private int enabledCell;
    private int enabledHover;

    public CoyGuiScreen(ClickGui owner) {
        super(Text.literal("Coyduck"));
        this.owner = owner;
    }

    @Override
    protected void init() {
        if (pages.isEmpty()) {
            build();
            loadLayout();
            page = 0;
            panels = pages.get(0).panels;
        }
        if (!custom) arrange();
        for (Page each : pages) {
            for (Panel panel : each.panels) panel.x = MathHelper.clamp(panel.x, 0, Math.max(0, width - WIDTH));
        }
    }

    private void build() {
        List<Module> all = new ArrayList<>(Modules.get().getAll());
        if (!owner.usesPages()) {
            Page single = new Page("");
            addPanels(single, all, false);
            pages.add(single);
            return;
        }

        Map<String, List<Module>> byAddon = new LinkedHashMap<>();
        for (String name : owner.pageOrder()) byAddon.put(name, new ArrayList<>());
        for (Module module : all) byAddon.computeIfAbsent(addonName(module), name -> new ArrayList<>()).add(module);
        for (Map.Entry<String, List<Module>> entry : byAddon.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Page addon = new Page(entry.getKey());
            addPanels(addon, entry.getValue(), true);
            pages.add(addon);
        }
        if (pages.isEmpty()) pages.add(new Page(""));
    }

    private void addPanels(Page target, List<Module> modules, boolean paged) {
        List<Category> categories = new ArrayList<>();
        for (Category category : Modules.loopCategories()) {
            if (category == Coyduck.CATEGORY) categories.addFirst(category);
            else categories.add(category);
        }
        String prefix = paged ? target.name + "/" : "";
        for (Category category : categories) {
            List<Module> inCategory = new ArrayList<>();
            for (Module module : modules) if (module.category == category) inCategory.add(module);
            if (inCategory.isEmpty()) continue;
            inCategory.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

            if (category == Coyduck.CATEGORY && owner.splitsCoyduck()) {
                for (String group : GROUPS) {
                    List<Module> grouped = new ArrayList<>();
                    for (Module module : inCategory) if (SPLIT.getOrDefault(module.name, "Misc").equals(group)) grouped.add(module);
                    if (grouped.isEmpty()) continue;
                    String title = paged ? group : "Coyduck " + group;
                    target.panels.add(new Panel(prefix + title, title, grouped));
                }
                continue;
            }
            target.panels.add(new Panel(prefix + category.name, category.name, inCategory));
        }
    }

    private static String addonName(Module module) {
        return module.addon == null || module.addon.name == null ? "Meteor Client" : module.addon.name;
    }

    private void arrange() {
        for (Page each : pages) {
            int x = MARGIN;
            int y = MARGIN;
            for (Panel panel : each.panels) {
                if (x + WIDTH > width - MARGIN && x > MARGIN) {
                    x = MARGIN;
                    y += height / 2;
                }
                panel.x = x;
                panel.y = y;
                x += WIDTH + GAP;
            }
        }
    }

    private void switchPage(int step) {
        int next = MathHelper.clamp(page + step, 0, pages.size() - 1);
        if (next == page) return;
        previousPage = page;
        direction = Integer.signum(step);
        page = next;
        panels = pages.get(page).panels;
        switchedAt = Util.getMeasuringTimeMs();
        dragging = null;
        sliding = null;
        binding = null;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (owner.dim.get()) context.fill(0, 0, width, height, 0x64000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        accent = owner.accent.get().getPacked() | 0xFF000000;
        enabledCell = withAlpha(shade(accent, 0.72f), 0xDC);
        enabledHover = withAlpha(shade(accent, 0.82f), 0xE6);
        Identifier id = owner.fontId();
        font = id == null ? Style.EMPTY : Style.EMPTY.withFont(new StyleSpriteSource.Font(id));
        hits.clear();
        hint = null;
        Object hovered = null;

        long now = Util.getMeasuringTimeMs();
        float t = previousPage < 0 ? 1 : MathHelper.clamp((now - switchedAt) / (float) SWITCH_TIME, 0, 1);
        if (t >= 1) previousPage = -1;

        if (previousPage >= 0) {
            float eased = 1 - (1 - t) * (1 - t) * (1 - t);
            int out = Math.round(-direction * eased * width);
            int in = Math.round(direction * (1 - eased) * width);
            List<Hit> kept = new ArrayList<>(hits);
            for (Panel panel : pages.get(previousPage).panels) drawPanel(context, panel, out, -1, -1);
            for (Panel panel : panels) drawPanel(context, panel, in, -1, -1);
            hits.clear();
            hits.addAll(kept);
        } else {
            for (Panel panel : panels) {
                Object over = drawPanel(context, panel, 0, mouseX, mouseY);
                if (over != null) hovered = over;
            }
        }
        drawTabs(context, mouseX, mouseY);
        drawPages(context, mouseX, mouseY);

        if (hovered != hoverKey) {
            hoverKey = hovered;
            hoverSince = now;
        }
        if (hint != null && !hint.isEmpty() && dragging == null && sliding == null && now - hoverSince >= TIP_DELAY) drawTip(context, mouseX, mouseY);
    }

    private Object drawPanel(DrawContext context, Panel panel, int offset, int mouseX, int mouseY) {
        int x = panel.x + offset;
        int y = panel.y;
        int bodyHeight = panel.collapsed ? 0 : bodyHeight(panel);
        int bottom = y + HEADER + bodyHeight;
        Object hovered = null;

        if (!panel.collapsed) context.fill(x, y + HEADER, x + WIDTH, bottom, BODY);
        context.fillGradient(x, y, x + WIDTH, y + HEADER, tint(accent, 0.12f), accent);
        text(context, trim(panel.title, WIDTH - PAD * 2), x + PAD, y + 2, TEXT);
        hits.add(new Hit(x, y, WIDTH, HEADER, Kind.HEADER, panel, null));

        if (!panel.collapsed) {
            int rowY = y + HEADER + 1;
            for (Module module : panel.modules) {
                boolean open = expanded.contains(module);
                boolean over = inside(mouseX, mouseY, x + 1, rowY, WIDTH - 2, ROW - 1);
                int fill = module.isActive() ? (over ? enabledHover : enabledCell) : (over ? CELL_HOVER : CELL);
                context.fill(x + 1, rowY, x + WIDTH - 1, rowY + ROW - 1, fill);
                text(context, trim(module.title, WIDTH - PAD * 2 - 8), x + PAD, rowY + 2, TEXT);
                String sign = open ? "-" : "+";
                text(context, sign, x + WIDTH - PAD - width(sign), rowY + 2, TEXT);
                hits.add(new Hit(x + 1, rowY, WIDTH - 2, ROW - 1, Kind.MODULE, module, null));
                if (over) {
                    hovered = module;
                    hint = module.description;
                }
                rowY += ROW;

                if (open) {
                    int[] next = {rowY};
                    Object inner = drawSettings(context, module, x, next, mouseX, mouseY);
                    if (inner != null) hovered = inner;
                    rowY = next[0];
                }
            }
        }

        outline(context, x, y, x + WIDTH, Math.max(bottom, y + HEADER), accent);
        return hovered;
    }

    private Object drawSettings(DrawContext context, Module module, int x, int[] y, int mouseX, int mouseY) {
        Object hovered = null;
        int left = x + 1 + INDENT;
        int right = x + WIDTH - 1;
        boolean groups = module.settings.groups.size() > 1;

        for (SettingGroup group : module.settings.groups) {
            if (visibleCount(group) == 0) continue;
            if (groups) {
                text(context, trim(group.name, right - left - PAD * 2), left + PAD, y[0] + 2, MUTED);
                y[0] += ROW;
            }
            for (Setting<?> setting : group) {
                if (!setting.isVisible()) continue;
                boolean over = inside(mouseX, mouseY, left, y[0], right - left, ROW - 1);
                drawSetting(context, module, setting, left, right, y[0], over);
                if (over) {
                    hovered = setting;
                    hint = setting.description;
                }
                y[0] += ROW;
            }
        }

        boolean over = inside(mouseX, mouseY, left, y[0], right - left, ROW - 1);
        cell(context, left, right, y[0], false, over);
        text(context, "Bind", left + PAD, y[0] + 2, TEXT);
        value(context, bindText(module.keybind, module), right, y[0]);
        hits.add(new Hit(left, y[0], right - left, ROW - 1, Kind.MODULE_BIND, module, module));
        if (over) {
            hovered = module.keybind;
            hint = "Click, then press a key or mouse button. Escape clears it.";
        }
        y[0] += ROW;
        return hovered;
    }

    private void drawSetting(DrawContext context, Module module, Setting<?> setting, int left, int right, int y, boolean over) {
        String name = setting.title;
        int nameMax = right - left - PAD * 2;

        if (setting instanceof BoolSetting bool) {
            cell(context, left, right, y, bool.get(), over);
            text(context, trim(name, nameMax), left + PAD, y + 2, TEXT);
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.BOOL, bool, null));
            return;
        }

        if (setting instanceof DoubleSetting || setting instanceof IntSetting) {
            cell(context, left, right, y, false, over);
            int filled = left + Math.round((right - left) * sliderFraction(setting));
            if (filled > left) context.fill(left, y, filled, y + ROW - 1, over ? enabledHover : enabledCell);
            String shown = valueText(setting);
            text(context, trim(name, nameMax - width(shown) - 4), left + PAD, y + 2, TEXT);
            value(context, shown, right, y);
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.SLIDER, setting, new int[] {left, right}));
            return;
        }

        if (setting instanceof EnumSetting<?> choice) {
            cell(context, left, right, y, false, over);
            String shown = choice.get().toString();
            text(context, trim(name, nameMax - width(shown) - 4), left + PAD, y + 2, TEXT);
            value(context, shown, right, y);
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.ENUM, choice, null));
            return;
        }

        if (setting instanceof KeybindSetting keybind) {
            cell(context, left, right, y, false, over);
            String shown = bindText(keybind.get(), keybind);
            text(context, trim(name, nameMax - width(shown) - 4), left + PAD, y + 2, TEXT);
            value(context, shown, right, y);
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.BIND, keybind, null));
            return;
        }

        if (setting instanceof ColorSetting color) {
            cell(context, left, right, y, false, over);
            text(context, trim(name, nameMax - 12), left + PAD, y + 2, TEXT);
            int sx = right - PAD - 7;
            int sy = y + 2;
            context.fill(sx - 1, sy - 1, sx + 8, sy + 8, SWATCH_EDGE);
            context.fill(sx, sy, sx + 7, sy + 7, color.get().getPacked());
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.COLOR, color, module));
            return;
        }

        cell(context, left, right, y, false, over);
        text(context, trim(name, nameMax - width("...") - 4), left + PAD, y + 2, TEXT);
        value(context, "...", right, y);
        hits.add(new Hit(left, y, right - left, ROW - 1, Kind.MORE, setting, module));
    }

    private void cell(DrawContext context, int left, int right, int y, boolean enabled, boolean over) {
        int fill = enabled ? (over ? enabledHover : enabledCell) : (over ? CELL_HOVER : CELL);
        context.fill(left, y, right, y + ROW - 1, fill);
    }

    private void value(DrawContext context, String shown, int right, int y) {
        text(context, shown, right - PAD - width(shown), y + 2, MUTED);
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY) {
        int x = width - MARGIN;
        int y = height - MARGIN - HEADER;
        List<Tab> tabs = Tabs.get();
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            if (tab.name.equalsIgnoreCase("Modules")) continue;
            int w = width(tab.name) + PAD * 2;
            x -= w;
            boolean over = inside(mouseX, mouseY, x, y, w, HEADER);
            context.fillGradient(x, y, x + w, y + HEADER, tint(accent, over ? 0.25f : 0.12f), over ? tint(accent, 0.1f) : accent);
            outline(context, x, y, x + w, y + HEADER, accent);
            text(context, tab.name, x + PAD, y + 2, TEXT);
            hits.add(new Hit(x, y, w, HEADER, Kind.TAB, tab, null));
            x -= GAP;
        }
    }

    private void drawPages(DrawContext context, int mouseX, int mouseY) {
        if (pages.size() < 2) return;
        String name = pages.get(page).name;
        String label = name + "  " + (page + 1) + "/" + pages.size();
        int arrow = width("<") + PAD * 2;
        int w = width(label) + PAD * 2 + arrow * 2;
        int x = (width - w) / 2;
        int y = height - MARGIN - HEADER;
        context.fillGradient(x, y, x + w, y + HEADER, tint(accent, 0.12f), accent);
        outline(context, x, y, x + w, y + HEADER, accent);
        boolean left = page > 0;
        boolean right = page < pages.size() - 1;
        text(context, "<", x + PAD, y + 2, left ? (inside(mouseX, mouseY, x, y, arrow, HEADER) ? TEXT : MUTED) : 0x55FFFFFF);
        text(context, label, x + arrow + PAD, y + 2, TEXT);
        text(context, ">", x + w - arrow + PAD, y + 2, right ? (inside(mouseX, mouseY, x + w - arrow, y, arrow, HEADER) ? TEXT : MUTED) : 0x55FFFFFF);
        if (left) hits.add(new Hit(x, y, arrow, HEADER, Kind.PAGE, -1, null));
        if (right) hits.add(new Hit(x + w - arrow, y, arrow, HEADER, Kind.PAGE, 1, null));
    }

    private void drawTip(DrawContext context, int mouseX, int mouseY) {
        List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.styled(hint, font), TIP_WIDTH);
        int w = 0;
        for (OrderedText line : lines) w = Math.max(w, textRenderer.getWidth(line));
        w += PAD * 2;
        int h = lines.size() * 10 + 4;
        int x = mouseX + 10;
        int y = mouseY + 4;
        if (x + w > width - 2) x = mouseX - 10 - w;
        if (y + h > height - 2) y = height - 2 - h;
        context.fill(x, y, x + w, y + h, 0xE6120F18);
        outline(context, x, y, x + w, y + h, accent);
        int line = y + 3;
        for (OrderedText text : lines) {
            context.drawText(textRenderer, text, x + PAD, line, TEXT, true);
            line += 10;
        }
    }

    private int bodyHeight(Panel panel) {
        int rows = panel.modules.size();
        for (Module module : panel.modules) {
            if (!expanded.contains(module)) continue;
            boolean groups = module.settings.groups.size() > 1;
            rows++;
            for (SettingGroup group : module.settings.groups) {
                int visible = visibleCount(group);
                if (visible == 0) continue;
                rows += visible + (groups ? 1 : 0);
            }
        }
        return rows * ROW + 1;
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
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && doubled) {
                    custom = false;
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
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    if (owner.opensSettingsWindow()) client.setScreen(GuiThemes.get().moduleScreen(module));
                    else if (!expanded.remove(module)) expanded.add(module);
                }
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
            case COLOR, MORE -> client.setScreen(GuiThemes.get().moduleScreen((Module) hit.extra));
            case TAB -> ((Tab) hit.target).openScreen(GuiThemes.get());
            case PAGE -> switchPage((Integer) hit.target);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != null) {
            custom = true;
            dragging.x = MathHelper.clamp((int) click.x() - dragX, 0, Math.max(0, width - WIDTH));
            dragging.y = (int) click.y() - dragY;
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
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (mouseX < panel.x || mouseX >= panel.x + WIDTH) continue;
            panel.y += (int) Math.signum(vertical) * SCROLL;
            custom = true;
            saveLayout();
            return true;
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
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
            switchPage(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) {
            switchPage(1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        saveLayout();
        super.close();
    }

    @Override
    public void removed() {
        saveLayout();
        super.removed();
    }

    private Hit hitAt(double mx, double my) {
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

    private String bindText(Keybind keybind, Object target) {
        if (binding == target) return "...";
        return keybind.isSet() ? keybind.toString() : "None";
    }

    private static int visibleCount(SettingGroup group) {
        int count = 0;
        for (Setting<?> setting : group) if (setting.isVisible()) count++;
        return count;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int width(String text) {
        return textRenderer.getWidth(Text.literal(text).setStyle(font));
    }

    private void text(DrawContext context, String text, int x, int y, int color) {
        context.drawText(textRenderer, Text.literal(text).setStyle(font), x, y, color, true);
    }

    private String trim(String text, int max) {
        if (width(text) <= max) return text;
        String dots = "..";
        int end = text.length();
        while (end > 0 && width(text.substring(0, end)) + width(dots) > max) end--;
        return text.substring(0, end) + dots;
    }

    private static void outline(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    private static int shade(int color, float factor) {
        int r = Math.round(((color >> 16) & 0xFF) * factor);
        int g = Math.round(((color >> 8) & 0xFF) * factor);
        int b = Math.round((color & 0xFF) * factor);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int tint(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private void saveLayout() {
        StringBuilder out = new StringBuilder(custom ? "custom" : "auto");
        for (Page each : pages) {
            for (Panel panel : each.panels) {
                out.append('|').append(panel.key).append(':').append(panel.x).append(':').append(panel.y).append(':').append(panel.collapsed ? 1 : 0);
            }
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
            for (Page each : pages) for (Panel panel : each.panels) {
                if (!panel.key.equals(parts[0])) continue;
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
