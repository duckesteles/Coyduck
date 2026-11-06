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
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
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
    public enum View { Modules, Config, Hud, Friends, Gui }

    private static boolean passThrough;

    public static boolean passingThrough() {
        return passThrough;
    }

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
    private static final List<String> OWN_TABS = List.of("Modules", "Config", "GUI", "HUD", "Friends");
    private static final String[] CHANNELS = {"Red", "Green", "Blue", "Alpha"};

    private static final int WIDTH = 96;
    private static final int HEADER = 12;
    private static final int ROW = 12;
    private static final int GAP = 4;
    private static final int MARGIN = 6;
    private static final int INDENT = 3;
    private static final int PAD = 4;
    private static final int SCROLL = 16;
    private static final int SEARCH_WIDTH = 110;
    private static final int TIP_WIDTH = 160;
    private static final long TIP_DELAY = 400;
    private static final long SWITCH_TIME = 220;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB4B0BE;
    private static final int FAINT = 0x55FFFFFF;
    private static final int BODY = 0x8C120F18;
    private static final int CELL = 0xB42A2533;
    private static final int CELL_HOVER = 0xC8352F40;
    private static final int SWATCH_EDGE = 0xFF0C0A10;

    private enum Kind { HEADER, MODULE, ELEMENT, FRIEND, FRIEND_ADD, BOOL, SLIDER, CHANNEL, ENUM, COLOR, STRING, BIND, MODULE_BIND, MORE, ACTION, TAB, VIEW, PAGE, SEARCH }

    private enum Type { MODULES, SETTINGS, ELEMENTS, FRIENDS }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Action(String label, Runnable run, java.util.function.BooleanSupplier on) {}

    private static final class Panel {
        final String key;
        final String title;
        final Type type;
        final List<Module> modules;
        final Settings settings;
        final Object owner;
        final List<Action> actions = new ArrayList<>();
        int x;
        int y;
        boolean collapsed;
        boolean placed;

        Panel(String key, String title, Type type, List<Module> modules, Settings settings, Object owner) {
            this.key = key;
            this.title = title;
            this.type = type;
            this.modules = modules;
            this.settings = settings;
            this.owner = owner;
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
    private final List<Page> extra = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Object> expanded = new HashSet<>();
    private final Set<ColorSetting> openColors = new HashSet<>();

    private View view;
    private List<Panel> panels = new ArrayList<>();
    private int page;
    private int previousPage = -1;
    private int direction;
    private long switchedAt;
    private Style font = Style.EMPTY;

    private boolean custom;
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Hit slidingHit;
    private int slidingChannel = -1;
    private Object binding;

    private Object editing;
    private String editText = "";
    private String search = "";
    private boolean searchFocused;

    private Object hoverKey;
    private long hoverSince;
    private String hint;

    private boolean paint;
    private int accent;
    private int enabledCell;
    private int enabledHover;

    public CoyGuiScreen(ClickGui owner) {
        this(owner, View.Modules);
    }

    public CoyGuiScreen(ClickGui owner, View view) {
        super(Text.literal("Coyduck"));
        this.owner = owner;
        this.view = view;
    }

    @Override
    protected void init() {
        if (pages.isEmpty()) {
            build();
            buildExtra();
            loadLayout();
            showView(view);
        }
        arrange();
        for (Page each : allPages()) {
            for (Panel panel : each.panels) panel.x = MathHelper.clamp(panel.x, 0, Math.max(0, width - WIDTH));
        }
    }

    private List<Page> allPages() {
        List<Page> all = new ArrayList<>(pages);
        all.addAll(extra);
        return all;
    }

    private void build() {
        List<Module> all = new ArrayList<>(Modules.get().getAll());
        if (!owner.usesPages()) {
            Page single = new Page("");
            addModulePanels(single, all, false);
            pages.add(single);
            return;
        }

        Map<String, List<Module>> byAddon = new LinkedHashMap<>();
        for (String name : owner.pageOrder()) byAddon.put(name, new ArrayList<>());
        for (Module module : all) byAddon.computeIfAbsent(addonName(module), name -> new ArrayList<>()).add(module);
        for (Map.Entry<String, List<Module>> entry : byAddon.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Page addon = new Page(entry.getKey());
            addModulePanels(addon, entry.getValue(), true);
            pages.add(addon);
        }
        if (pages.isEmpty()) pages.add(new Page(""));
    }

    private void addModulePanels(Page target, List<Module> modules, boolean paged) {
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
                    target.panels.add(new Panel(prefix + title, title, Type.MODULES, grouped, null, null));
                }
                continue;
            }
            target.panels.add(new Panel(prefix + category.name, category.name, Type.MODULES, inCategory, null, null));
        }
    }

    private void buildExtra() {
        Page config = new Page("Config");
        for (SettingGroup group : Config.get().settings.groups) config.panels.add(groupPanel("Config/" + group.name, group.name, group, Config.get()));
        extra.add(config);

        Page hud = new Page("Hud");
        Hud system = Hud.get();
        Panel hudPanel = new Panel("Hud/Hud", "Hud", Type.SETTINGS, null, system.settings, system);
        hudPanel.actions.add(new Action("Active", () -> system.active = !system.active, () -> system.active));
        hudPanel.actions.add(new Action("Edit Layout", () -> client.setScreen(new HudEditorScreen(GuiThemes.get())), null));
        hud.panels.add(hudPanel);
        hud.panels.add(new Panel("Hud/Elements", "Elements", Type.ELEMENTS, null, null, system));
        extra.add(hud);

        Page friends = new Page("Friends");
        friends.panels.add(new Panel("Friends/Friends", "Friends", Type.FRIENDS, null, null, Friends.get()));
        extra.add(friends);

        Page gui = new Page("Gui");
        gui.panels.add(new Panel("Gui/" + owner.title, owner.title, Type.SETTINGS, null, owner.settings, owner));
        extra.add(gui);
    }

    private static Panel groupPanel(String key, String title, SettingGroup group, Object owner) {
        Settings single = new Settings();
        single.groups.add(group);
        return new Panel(key, title, Type.SETTINGS, null, single, owner);
    }

    private static String addonName(Module module) {
        return module.addon == null || module.addon.name == null ? "Meteor Client" : module.addon.name;
    }

    private void showView(View next) {
        view = next;
        previousPage = -1;
        dragging = null;
        sliding = null;
        binding = null;
        finishEdit(true);
        panels = switch (view) {
            case Modules -> pages.get(MathHelper.clamp(page, 0, pages.size() - 1)).panels;
            case Config -> extra.get(0).panels;
            case Hud -> extra.get(1).panels;
            case Friends -> extra.get(2).panels;
            case Gui -> extra.get(3).panels;
        };
    }

    private void arrange() {
        for (Page each : allPages()) {
            boolean all = !custom;
            for (Panel panel : each.panels) if (!panel.placed) all = true;
            if (!all) continue;
            int x = MARGIN;
            int y = MARGIN;
            for (Panel panel : each.panels) {
                if (x + WIDTH > width - MARGIN && x > MARGIN) {
                    x = MARGIN;
                    y += height / 2;
                }
                panel.x = x;
                panel.y = y;
                panel.placed = true;
                x += WIDTH + GAP;
            }
        }
    }

    private void switchPage(int step) {
        if (view != View.Modules) return;
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
            for (Panel panel : pages.get(previousPage).panels) drawPanel(context, panel, out, -1, -1);
            for (Panel panel : panels) drawPanel(context, panel, in, -1, -1);
            hits.clear();
        } else {
            for (Panel panel : panels) {
                Object over = drawPanel(context, panel, 0, mouseX, mouseY);
                if (over != null) hovered = over;
            }
        }
        drawSearch(context, mouseX, mouseY);
        drawTabs(context, mouseX, mouseY);
        drawPages(context, mouseX, mouseY);

        if (hovered != hoverKey) {
            hoverKey = hovered;
            hoverSince = now;
        }
        if (hint != null && !hint.isEmpty() && dragging == null && sliding == null && now - hoverSince >= TIP_DELAY) drawTip(context, mouseX, mouseY);
    }

    private boolean matches(Module module) {
        if (search.isEmpty() || view != View.Modules) return true;
        String query = search.toLowerCase(Locale.ROOT);
        return module.title.toLowerCase(Locale.ROOT).contains(query) || module.name.contains(query);
    }

    private boolean shown(Panel panel) {
        if (panel.type != Type.MODULES || search.isEmpty()) return true;
        for (Module module : panel.modules) if (matches(module)) return true;
        return false;
    }

    private Object drawPanel(DrawContext context, Panel panel, int offset, int mouseX, int mouseY) {
        if (!shown(panel)) return null;
        int x = panel.x + offset;
        int y = panel.y;

        paint = false;
        int[] measure = {y + HEADER + 1};
        if (!panel.collapsed) rows(context, panel, x, measure, -1, -1);
        int bottom = panel.collapsed ? y + HEADER : measure[0];

        if (!panel.collapsed) context.fill(x, y + HEADER, x + WIDTH, bottom, BODY);
        context.fillGradient(x, y, x + WIDTH, y + HEADER, tint(accent, 0.12f), accent);
        text(context, trim(panel.title, WIDTH - PAD * 2), x + PAD, y + 2, TEXT);
        hits.add(new Hit(x, y, WIDTH, HEADER, Kind.HEADER, panel, null));

        Object hovered = null;
        if (!panel.collapsed) {
            paint = true;
            int[] rowY = {y + HEADER + 1};
            hovered = rows(context, panel, x, rowY, mouseX, mouseY);
        }
        outline(context, x, y, x + WIDTH, bottom, accent);
        return hovered;
    }

    private Object rows(DrawContext context, Panel panel, int x, int[] y, int mouseX, int mouseY) {
        Object hovered = null;
        switch (panel.type) {
            case MODULES -> {
                for (Module module : panel.modules) {
                    if (!matches(module)) continue;
                    boolean open = expanded.contains(module);
                    if (headRow(context, x, y[0], module.title, module.isActive(), open, mouseX, mouseY, Kind.MODULE, module)) {
                        hovered = module;
                        hint = module.description;
                    }
                    y[0] += ROW;
                    if (open) {
                        Object inner = settingsRows(context, module.settings, module, x + 1 + INDENT, x + WIDTH - 1, y, mouseX, mouseY);
                        if (inner != null) hovered = inner;
                        Object bind = bindRow(context, module, x + 1 + INDENT, x + WIDTH - 1, y, mouseX, mouseY);
                        if (bind != null) hovered = bind;
                    }
                }
            }
            case SETTINGS -> {
                for (Action action : panel.actions) {
                    boolean on = action.on() != null && action.on().getAsBoolean();
                    boolean over = inside(mouseX, mouseY, x + 1, y[0], WIDTH - 2, ROW - 1);
                    if (paint) {
                        cell(context, x + 1, x + WIDTH - 1, y[0], on, over);
                        text(context, action.label(), x + PAD, y[0] + 2, TEXT);
                        hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.ACTION, action, null));
                    }
                    if (over) hovered = action;
                    y[0] += ROW;
                }
                Object inner = settingsRows(context, panel.settings, panel.owner, x + 1, x + WIDTH - 1, y, mouseX, mouseY);
                if (inner != null) hovered = inner;
            }
            case ELEMENTS -> {
                for (HudElement element : Hud.get()) {
                    boolean open = expanded.contains(element);
                    if (headRow(context, x, y[0], element.info.title, element.isActive(), open, mouseX, mouseY, Kind.ELEMENT, element)) {
                        hovered = element;
                        hint = element.info.description;
                    }
                    y[0] += ROW;
                    if (open) {
                        Object inner = settingsRows(context, element.settings, element, x + 1 + INDENT, x + WIDTH - 1, y, mouseX, mouseY);
                        if (inner != null) hovered = inner;
                    }
                }
            }
            case FRIENDS -> {
                for (Friend friend : Friends.get()) {
                    boolean over = inside(mouseX, mouseY, x + 1, y[0], WIDTH - 2, ROW - 1);
                    if (paint) {
                        cell(context, x + 1, x + WIDTH - 1, y[0], false, over);
                        text(context, trim(friend.getName(), WIDTH - PAD * 2 - 8), x + PAD, y[0] + 2, TEXT);
                        text(context, "-", x + WIDTH - PAD - width("-"), y[0] + 2, TEXT);
                        hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.FRIEND, friend, null));
                    }
                    if (over) {
                        hovered = friend;
                        hint = "Click to remove.";
                    }
                    y[0] += ROW;
                }
                boolean over = inside(mouseX, mouseY, x + 1, y[0], WIDTH - 2, ROW - 1);
                if (paint) {
                    boolean typing = editing == Kind.FRIEND_ADD;
                    cell(context, x + 1, x + WIDTH - 1, y[0], typing, over);
                    String shown = typing ? editText + cursor() : "Add friend";
                    text(context, trim(shown, WIDTH - PAD * 2 - 8), x + PAD, y[0] + 2, typing ? TEXT : MUTED);
                    text(context, "+", x + WIDTH - PAD - width("+"), y[0] + 2, TEXT);
                    hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.FRIEND_ADD, null, null));
                }
                if (over) {
                    hovered = Kind.FRIEND_ADD;
                    hint = "Click, type a name and press Enter.";
                }
                y[0] += ROW;
            }
        }
        return hovered;
    }

    private boolean headRow(DrawContext context, int x, int y, String title, boolean active, boolean open, int mouseX, int mouseY, Kind kind, Object target) {
        boolean over = inside(mouseX, mouseY, x + 1, y, WIDTH - 2, ROW - 1);
        if (paint) {
            int fill = active ? (over ? enabledHover : enabledCell) : (over ? CELL_HOVER : CELL);
            context.fill(x + 1, y, x + WIDTH - 1, y + ROW - 1, fill);
            text(context, trim(title, WIDTH - PAD * 2 - 8), x + PAD, y + 2, TEXT);
            String sign = open ? "-" : "+";
            text(context, sign, x + WIDTH - PAD - width(sign), y + 2, TEXT);
            hits.add(new Hit(x + 1, y, WIDTH - 2, ROW - 1, kind, target, null));
        }
        return over;
    }

    private Object settingsRows(DrawContext context, Settings settings, Object owner, int left, int right, int[] y, int mouseX, int mouseY) {
        Object hovered = null;
        boolean groups = settings.groups.size() > 1;

        for (SettingGroup group : settings.groups) {
            if (visibleCount(group) == 0) continue;
            if (groups) {
                if (paint) text(context, trim(group.name, right - left - PAD * 2), left + PAD, y[0] + 2, MUTED);
                y[0] += ROW;
            }
            for (Setting<?> setting : group) {
                if (!setting.isVisible()) continue;
                boolean over = inside(mouseX, mouseY, left, y[0], right - left, ROW - 1);
                if (paint) drawSetting(context, owner, setting, left, right, y[0], over);
                if (over) {
                    hovered = setting;
                    hint = setting.description;
                }
                y[0] += ROW;
                if (setting instanceof ColorSetting color && openColors.contains(color)) {
                    for (int channel = 0; channel < 4; channel++) {
                        boolean on = inside(mouseX, mouseY, left + INDENT, y[0], right - left - INDENT, ROW - 1);
                        if (paint) drawChannel(context, color, channel, left + INDENT, right, y[0], on);
                        y[0] += ROW;
                    }
                }
            }
        }
        return hovered;
    }

    private Object bindRow(DrawContext context, Module module, int left, int right, int[] y, int mouseX, int mouseY) {
        boolean over = inside(mouseX, mouseY, left, y[0], right - left, ROW - 1);
        if (paint) {
            cell(context, left, right, y[0], false, over);
            text(context, "Bind", left + PAD, y[0] + 2, TEXT);
            value(context, bindText(module.keybind, module), right, y[0]);
            hits.add(new Hit(left, y[0], right - left, ROW - 1, Kind.MODULE_BIND, module, module));
        }
        y[0] += ROW;
        if (over) {
            hint = "Click, then press a key or mouse button. Escape clears it.";
            return module.keybind;
        }
        return null;
    }

    private void drawSetting(DrawContext context, Object owner, Setting<?> setting, int left, int right, int y, boolean over) {
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
            cell(context, left, right, y, openColors.contains(color), over);
            text(context, trim(name, nameMax - 12), left + PAD, y + 2, TEXT);
            int sx = right - PAD - 7;
            int sy = y + 2;
            context.fill(sx - 1, sy - 1, sx + 8, sy + 8, SWATCH_EDGE);
            context.fill(sx, sy, sx + 7, sy + 7, color.get().getPacked());
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.COLOR, color, null));
            return;
        }

        if (setting instanceof StringSetting string) {
            boolean typing = editing == string;
            cell(context, left, right, y, typing, over);
            String shown = typing ? editText + cursor() : string.get();
            int nameWidth = Math.min(width(name), nameMax / 2);
            text(context, trim(name, nameMax / 2), left + PAD, y + 2, TEXT);
            String trimmed = trimStart(shown, nameMax - nameWidth - 6);
            text(context, trimmed, right - PAD - width(trimmed), y + 2, typing ? TEXT : MUTED);
            hits.add(new Hit(left, y, right - left, ROW - 1, Kind.STRING, string, null));
            return;
        }

        cell(context, left, right, y, false, over);
        text(context, trim(name, nameMax - width("...") - 4), left + PAD, y + 2, TEXT);
        value(context, "...", right, y);
        hits.add(new Hit(left, y, right - left, ROW - 1, Kind.MORE, setting, owner));
    }

    private void drawChannel(DrawContext context, ColorSetting color, int channel, int left, int right, int y, boolean over) {
        SettingColor current = color.get();
        int amount = channel(current, channel);
        cell(context, left, right, y, false, over);
        int filled = left + Math.round((right - left) * amount / 255f);
        int tintColor = switch (channel) {
            case 0 -> 0xDCB4464E;
            case 1 -> 0xDC4EA45C;
            case 2 -> 0xDC4E64B4;
            default -> over ? enabledHover : enabledCell;
        };
        if (filled > left) context.fill(left, y, filled, y + ROW - 1, tintColor);
        text(context, CHANNELS[channel], left + PAD, y + 2, TEXT);
        value(context, String.valueOf(amount), right, y);
        hits.add(new Hit(left, y, right - left, ROW - 1, Kind.CHANNEL, color, new int[] {left, right, channel}));
    }

    private static int channel(SettingColor color, int channel) {
        return switch (channel) {
            case 0 -> color.r;
            case 1 -> color.g;
            case 2 -> color.b;
            default -> color.a;
        };
    }

    private void cell(DrawContext context, int left, int right, int y, boolean enabled, boolean over) {
        int fill = enabled ? (over ? enabledHover : enabledCell) : (over ? CELL_HOVER : CELL);
        context.fill(left, y, right, y + ROW - 1, fill);
    }

    private void value(DrawContext context, String shown, int right, int y) {
        text(context, shown, right - PAD - width(shown), y + 2, MUTED);
    }

    private String cursor() {
        return (Util.getMeasuringTimeMs() / 500) % 2 == 0 ? "_" : " ";
    }

    private void drawSearch(DrawContext context, int mouseX, int mouseY) {
        if (view != View.Modules) return;
        int x = MARGIN;
        int y = height - MARGIN - HEADER;
        boolean over = inside(mouseX, mouseY, x, y, SEARCH_WIDTH, HEADER);
        context.fill(x, y, x + SEARCH_WIDTH, y + HEADER, searchFocused ? enabledCell : over ? CELL_HOVER : CELL);
        outline(context, x, y, x + SEARCH_WIDTH, y + HEADER, accent);
        String shown = searchFocused ? search + cursor() : search.isEmpty() ? "Search" : search;
        text(context, trimStart(shown, SEARCH_WIDTH - PAD * 2), x + PAD, y + 2, searchFocused || !search.isEmpty() ? TEXT : MUTED);
        hits.add(new Hit(x, y, SEARCH_WIDTH, HEADER, Kind.SEARCH, null, null));
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY) {
        int x = width - MARGIN;
        int y = height - MARGIN - HEADER;
        List<Tab> tabs = Tabs.get();
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            View target = viewOf(tab.name);
            boolean current = target == view;
            int w = width(tab.name) + PAD * 2;
            x -= w;
            boolean over = inside(mouseX, mouseY, x, y, w, HEADER);
            context.fillGradient(x, y, x + w, y + HEADER, tint(accent, current ? 0.3f : over ? 0.22f : 0.12f), current ? tint(accent, 0.18f) : accent);
            outline(context, x, y, x + w, y + HEADER, accent);
            text(context, tab.name, x + PAD, y + 2, current || over ? TEXT : MUTED);
            if (target != null) hits.add(new Hit(x, y, w, HEADER, Kind.VIEW, target, null));
            else hits.add(new Hit(x, y, w, HEADER, Kind.TAB, tab, null));
            x -= GAP;
        }
    }

    public static View viewOf(String tab) {
        return switch (tab) {
            case "Modules" -> View.Modules;
            case "Config" -> View.Config;
            case "HUD" -> View.Hud;
            case "Friends" -> View.Friends;
            case "GUI" -> View.Gui;
            default -> null;
        };
    }

    private void drawPages(DrawContext context, int mouseX, int mouseY) {
        if (view != View.Modules || pages.size() < 2) return;
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
        text(context, "<", x + PAD, y + 2, left ? (inside(mouseX, mouseY, x, y, arrow, HEADER) ? TEXT : MUTED) : FAINT);
        text(context, label, x + arrow + PAD, y + 2, TEXT);
        text(context, ">", x + w - arrow + PAD, y + 2, right ? (inside(mouseX, mouseY, x + w - arrow, y, arrow, HEADER) ? TEXT : MUTED) : FAINT);
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
        if (hit == null || hit.kind != Kind.SEARCH) searchFocused = false;
        if (hit == null || (hit.kind != Kind.STRING && hit.kind != Kind.FRIEND_ADD)) finishEdit(true);
        if (hit == null) return super.mouseClicked(click, doubled);

        switch (hit.kind) {
            case HEADER -> {
                Panel panel = (Panel) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    panel.collapsed = !panel.collapsed;
                    saveLayout();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && doubled) {
                    custom = false;
                    for (Page each : allPages()) for (Panel p : each.panels) p.placed = false;
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
            case ELEMENT -> {
                HudElement element = (HudElement) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) element.toggle();
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !expanded.remove(element)) expanded.add(element);
            }
            case FRIEND -> Friends.get().remove((Friend) hit.target);
            case FRIEND_ADD -> startEdit(Kind.FRIEND_ADD, "");
            case ACTION -> ((Action) hit.target).run().run();
            case BOOL -> {
                BoolSetting setting = (BoolSetting) hit.target;
                setting.set(!setting.get());
            }
            case SLIDER, CHANNEL -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    sliding = (Setting<?>) hit.target;
                    slidingHit = hit;
                    slidingChannel = hit.kind == Kind.CHANNEL ? ((int[]) hit.extra)[2] : -1;
                    slide(mx);
                }
            }
            case ENUM -> cycle((EnumSetting<?>) hit.target, button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1);
            case COLOR -> {
                ColorSetting color = (ColorSetting) hit.target;
                if (!openColors.remove(color)) openColors.add(color);
            }
            case STRING -> {
                StringSetting setting = (StringSetting) hit.target;
                if (editing != setting) startEdit(setting, setting.get());
            }
            case BIND -> binding = hit.target;
            case MODULE_BIND -> binding = hit.extra;
            case MORE -> {
                if (hit.extra instanceof Module module) client.setScreen(GuiThemes.get().moduleScreen(module));
                else if (hit.extra instanceof HudElement) client.setScreen(new HudEditorScreen(GuiThemes.get()));
                else openMeteorTab();
            }
            case VIEW -> {
                showView((View) hit.target);
                arrange();
            }
            case TAB -> ((Tab) hit.target).openScreen(GuiThemes.get());
            case PAGE -> switchPage((Integer) hit.target);
            case SEARCH -> searchFocused = true;
        }
        return true;
    }

    private void openMeteorTab() {
        String name = switch (view) {
            case Config -> "Config";
            case Hud -> "HUD";
            case Friends -> "Friends";
            case Gui -> "GUI";
            default -> "";
        };
        for (Tab tab : Tabs.get()) {
            if (tab.name.equals(name)) {
                passThrough = true;
                client.setScreen(tab.createScreen(GuiThemes.get()));
                passThrough = false;
                return;
            }
        }
    }

    private void startEdit(Object target, String text) {
        finishEdit(true);
        editing = target;
        editText = text;
    }

    private void finishEdit(boolean commit) {
        if (editing == null) return;
        if (commit) {
            if (editing instanceof StringSetting setting) setting.set(editText);
            else if (editing == Kind.FRIEND_ADD && !editText.isBlank()) Friends.get().add(new Friend(editText.trim()));
        }
        editing = null;
        editText = "";
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
        slidingChannel = -1;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (mouseX < panel.x || mouseX >= panel.x + WIDTH || !shown(panel)) continue;
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
        if (editing != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) finishEdit(true);
            else if (key == GLFW.GLFW_KEY_ESCAPE) finishEdit(false);
            else if (key == GLFW.GLFW_KEY_BACKSPACE && !editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
            return true;
        }
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) searchFocused = false;
            else if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 ? "" : search.substring(0, search.length() - 1);
                jumpToMatch();
            }
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
    public boolean charTyped(CharInput input) {
        if (!input.isValidChar()) return false;
        if (editing != null) {
            if (editText.length() < 64) editText += input.asString();
            return true;
        }
        if (searchFocused) {
            if (search.length() < 24) search += input.asString();
            jumpToMatch();
            return true;
        }
        return false;
    }

    private void jumpToMatch() {
        if (view != View.Modules || search.isEmpty()) return;
        for (Panel panel : panels) if (shown(panel)) return;
        for (int i = 0; i < pages.size(); i++) {
            for (Panel panel : pages.get(i).panels) {
                if (!shown(panel)) continue;
                if (i != page) switchPage(i - page);
                return;
            }
        }
    }

    @Override
    public void close() {
        finishEdit(true);
        saveLayout();
        super.close();
    }

    @Override
    public void removed() {
        finishEdit(true);
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
        if (sliding instanceof ColorSetting color && slidingChannel >= 0) {
            SettingColor next = new SettingColor(color.get());
            int amount = (int) Math.round(t * 255);
            switch (slidingChannel) {
                case 0 -> next.r = amount;
                case 1 -> next.g = amount;
                case 2 -> next.b = amount;
                default -> next.a = amount;
            }
            color.set(next);
        } else if (sliding instanceof DoubleSetting setting) {
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

    private String trimStart(String text, int max) {
        if (width(text) <= max) return text;
        int start = 0;
        while (start < text.length() && width(text.substring(start)) > max) start++;
        return text.substring(start);
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
        for (Page each : allPages()) {
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
            for (Page each : allPages()) for (Panel panel : each.panels) {
                if (!panel.key.equals(parts[0])) continue;
                try {
                    panel.x = Integer.parseInt(parts[1]);
                    panel.y = Integer.parseInt(parts[2]);
                    panel.collapsed = parts[3].equals("1");
                    panel.placed = custom;
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
