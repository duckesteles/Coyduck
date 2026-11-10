package dev.coyduck.gui;

import dev.coyduck.Coyduck;
import dev.coyduck.modules.ClickGui;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.FontFaceSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StatusEffectListSetting;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.systems.hud.screens.HudElementScreen;
import meteordevelopment.meteorclient.systems.macros.Macro;
import meteordevelopment.meteorclient.systems.macros.Macros;
import meteordevelopment.meteorclient.systems.profiles.Profile;
import meteordevelopment.meteorclient.systems.profiles.Profiles;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CoyGuiScreen extends Screen {
    public enum View { Modules, Config, Hud, Friends, Gui, Baritone, Macros, Profiles }

    private static boolean passThrough;
    private static int lastPage;

    public static boolean passingThrough() {
        return passThrough;
    }

    private static final List<String> GROUPS = List.of("Combat", "Player", "Movement", "Render", "World", "Misc");
    private static final Map<String, String> SPLIT = Map.ofEntries(
        Map.entry("anti-piston", "Combat"), Map.entry("auto-anchor", "Combat"), Map.entry("auto-city+", "Combat"),
        Map.entry("auto-crystal", "Combat"), Map.entry("auto-totem+", "Combat"), Map.entry("auto-trap+", "Combat"),
        Map.entry("auto-web+", "Combat"), Map.entry("burrow+", "Combat"), Map.entry("hole-fill", "Combat"),
        Map.entry("kill-aura+", "Combat"), Map.entry("ladder-trap", "Combat"), Map.entry("pearl-phase", "Combat"),
        Map.entry("self-trap+", "Combat"), Map.entry("surround+", "Combat"), Map.entry("mace-strike", "Combat"),
        Map.entry("air-mine", "Player"), Map.entry("auto-xp", "Player"), Map.entry("no-rotate+", "Player"),
        Map.entry("elytra-fly+", "Movement"), Map.entry("elytra-pilot", "Movement"), Map.entry("hole-snap", "Movement"),
        Map.entry("speed+", "Movement"), Map.entry("no-climb", "Movement"), Map.entry("elytra-evade", "Movement"),
        Map.entry("boat-fly+", "Movement"),
        Map.entry("break-esp+", "Render"), Map.entry("crystal-chams", "Render"), Map.entry("custom-fov", "Render"),
        Map.entry("esp+", "Render"), Map.entry("hole-esp+", "Render"),
        Map.entry("kill-effects", "Render"), Map.entry("no-explosion", "Render"), Map.entry("shaders+", "Render"),
        Map.entry("schem-builder", "World")
    );
    private static final String[] CHANNELS = {"Red", "Green", "Blue", "Alpha"};
    private static final Identifier RESET_ICON = Identifier.of("coyduck", "textures/gui/reset.png");
    private static final Object LIST_SEARCH = new Object();
    private static final Object LIST_TEXT = new Object();
    private static final Object FRIEND_ADD = new Object();
    private static final Object SEARCH_EDIT = new Object();

    private static final int WIDTH = 96;
    private static final int HEADER = 12;
    private static final int ROW = 12;
    private static final int GAP = 4;
    private static final int MARGIN = 6;
    private static final int PAD = 4;
    private static final int SCROLL = 16;
    private static final int SEARCH_WIDTH = 110;
    private static final int TIP_WIDTH = 160;
    private static final long TIP_DELAY = 400;
    private static final long SWITCH_TIME = 220;

    private static final int WIN_W = 230;
    private static final int WIN_ROW = 13;
    private static final int WIN_HEAD = 13;
    private static final int CTRL_W = 96;
    private static final int ICON = 8;
    private static final int MAX_CANDIDATES = 400;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB4B0BE;
    private static final int FAINT = 0x55FFFFFF;
    private static final int BODY = 0x8C120F18;
    private static final int WIN_BODY = 0xE6151219;
    private static final int CELL = 0xB42A2533;
    private static final int CELL_HOVER = 0xC8352F40;
    private static final int ROW_HOVER = 0x24FFFFFF;
    private static final int EDGE = 0xFF0C0A10;
    private static final int LINE = 0x30FFFFFF;

    private enum Kind {
        HEADER, MODULE, ELEMENT, FRIEND, FRIEND_ADD, ACTION, ADD_INFO, ADD_PRESET, TAB, VIEW, PAGE, SEARCH, MACRO, PROFILE, CREATE,
        W_HEADER, W_CLOSE, W_RESET_ALL, W_BACK, W_RESET, W_BOOL, W_SLIDER, W_CHANNEL, W_ENUM, W_COLOR, W_STRING, W_BIND,
        W_MODULE_BIND, W_LIST, W_MORE, W_ACTION, W_LIST_SEARCH, W_LIST_ADD, W_LIST_REMOVE, W_LIST_TEXT, W_LIST_PICK, W_AREA
    }

    private enum Type { MODULES, ELEMENTS, FRIENDS, ADD, ACTIONS, MACROS, PROFILES }

    private enum RowKind { GROUP, SETTING, CHANNEL, BIND, ACTION, SEARCH, ENTRY_REMOVE, ENTRY_ADD, ENTRY_PICK, TEXT_ADD, NOTE }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Action(String label, Runnable run, java.util.function.BooleanSupplier on) {}

    private record Row(RowKind kind, String label, Object target, Object extra) {}

    private static final class Panel {
        final String key;
        final String title;
        final Type type;
        final List<Module> modules;
        final List<Action> actions = new ArrayList<>();
        int x;
        int y;
        int bottom;
        boolean collapsed;
        boolean placed;

        Panel(String key, String title, Type type, List<Module> modules) {
            this.key = key;
            this.title = title;
            this.type = type;
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

    private static final class Window {
        final String title;
        final Settings settings;
        final Object owner;
        final boolean closable;
        final Runnable resetAll;
        final Runnable onClose;
        int x;
        int y;
        int scroll;
        int height;
        Setting<?> list;
        String listSearch = "";
        List<Object> cache;
        String cacheKey;
        List<Row> extras = new ArrayList<>();
        boolean more;

        Window(String title, Settings settings, Object owner, boolean closable, Runnable resetAll, Runnable onClose) {
            this.title = title;
            this.settings = settings;
            this.owner = owner;
            this.closable = closable;
            this.resetAll = resetAll;
            this.onClose = onClose;
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

    private Window window;
    private boolean draggingWindow;
    private List<Panel> results = new ArrayList<>();
    private String resultsFor;

    private boolean custom;
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Setting<?> slideBeforeSetting;
    private Object slideBefore;
    private Hit slidingHit;
    private int slidingChannel = -1;
    private Object binding;

    private Object editing;
    private Setting<?> editingList;
    private String editText = "";
    private int editCursor;
    private String search = "";

    private Object hoverKey;
    private long hoverSince;
    private String hint;

    private boolean paint;
    private boolean swallowChar;
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
            page = MathHelper.clamp(lastPage, 0, pages.size() - 1);
            showView(view);
        } else if (window == null && (view == View.Config || view == View.Gui || view == View.Baritone)) {
            showView(view);
        }
        arrange();
        for (Page each : allPages()) {
            for (Panel panel : each.panels) {
                panel.x = MathHelper.clamp(panel.x, 0, Math.max(0, width - WIDTH));
                clampY(panel);
            }
        }
        if (window != null) clampWindow();
    }

    private void moveY(Panel panel, int y) {
        int length = panel.collapsed ? HEADER : Math.max(HEADER, panel.bottom - panel.y);
        int clamped = MathHelper.clamp(y, HEADER - length, Math.max(0, height - HEADER));
        panel.bottom += clamped - panel.y;
        panel.y = clamped;
    }

    private void clampY(Panel panel) {
        moveY(panel, panel.y);
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
                    target.panels.add(new Panel(prefix + title, title, Type.MODULES, grouped));
                }
                continue;
            }
            target.panels.add(new Panel(prefix + category.name, category.name, Type.MODULES, inCategory));
        }
    }

    private void buildExtra() {
        extra.add(new Page("Config"));

        Page hud = new Page("Hud");
        Hud system = Hud.get();
        Panel hudPanel = new Panel("Hud/Hud", "Hud", Type.ACTIONS, null);
        hudPanel.actions.add(new Action("Active", () -> system.active = !system.active, () -> system.active));
        hudPanel.actions.add(new Action("Settings", () -> openWindow(new Window("Hud", system.settings, system, true, system.settings::reset, null)), null));
        hudPanel.actions.add(new Action("Edit Layout", () -> client.setScreen(new HudEditorScreen(GuiThemes.get())), null));
        hudPanel.actions.add(new Action("Reset Elements", system::resetToDefaultElements, null));
        hudPanel.actions.add(new Action("Clear Elements", system::clear, null));
        hud.panels.add(hudPanel);
        hud.panels.add(new Panel("Hud/Elements", "Elements", Type.ELEMENTS, null));
        hud.panels.add(new Panel("Hud/Add", "Add Element", Type.ADD, null));
        extra.add(hud);

        Page friends = new Page("Friends");
        friends.panels.add(new Panel("Friends/Friends", "Friends", Type.FRIENDS, null));
        extra.add(friends);

        extra.add(new Page("Gui"));
        extra.add(new Page("Baritone"));

        Page macros = new Page("Macros");
        macros.panels.add(new Panel("Macros/Macros", "Macros", Type.MACROS, null));
        extra.add(macros);

        Page profiles = new Page("Profiles");
        profiles.panels.add(new Panel("Profiles/Profiles", "Profiles", Type.PROFILES, null));
        extra.add(profiles);
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
        closeWindow();
        panels = switch (view) {
            case Modules -> pages.get(MathHelper.clamp(page, 0, pages.size() - 1)).panels;
            case Config -> extra.get(0).panels;
            case Hud -> extra.get(1).panels;
            case Friends -> extra.get(2).panels;
            case Gui -> extra.get(3).panels;
            case Baritone -> extra.get(4).panels;
            case Macros -> extra.get(5).panels;
            case Profiles -> extra.get(6).panels;
        };
        switch (view) {
            case Config -> openWindow(new Window("Config", Config.get().settings, Config.get(), false, Config.get().settings::reset, null));
            case Gui -> openWindow(new Window(owner.title, owner.settings, owner, false, owner::resetKeepingState, null));
            case Baritone -> {
                Settings baritone = PathManagers.get().getSettings().get();
                openWindow(new Window("Baritone", baritone, null, false, baritone::reset, () -> PathManagers.get().getSettings().save()));
            }
            default -> {
            }
        }
    }

    private void collectExtras(Window w) {
        w.extras = new ArrayList<>();
        w.more = false;
        if (!(w.owner instanceof Module module)) return;
        WWidget root;
        try {
            root = module.getWidget(GuiThemes.get());
        } catch (RuntimeException e) {
            w.more = true;
            return;
        }
        if (root != null) collect(w, root);
    }

    private void collect(Window w, WWidget widget) {
        if (widget instanceof WContainer container) {
            for (Cell<?> cell : container.cells) collect(w, cell.widget());
        } else if (widget instanceof WButton button) {
            Runnable action = button.action;
            String text = button.getText();
            String label = text == null || text.isBlank() ? "Remove" : text;
            if (action != null) w.extras.add(new Row(RowKind.ACTION, label, (Runnable) () -> {
                action.run();
                collectExtras(w);
            }, null));
        } else if (widget instanceof WLabel label) {
            String text = label.get();
            if (text != null && !text.isBlank()) w.extras.add(new Row(RowKind.NOTE, text, null, null));
        } else if (widget != null) {
            w.more = true;
        }
    }

    private void openWindow(Window next) {
        closeWindow();
        collectExtras(next);
        window = next;
        window.x = (width - WIN_W) / 2;
        window.y = MARGIN + 8;
        clampWindow();
    }

    private void closeWindow() {
        if (window == null) return;
        finishEdit(true);
        if (window.onClose != null) window.onClose.run();
        window = null;
    }

    private void clampWindow() {
        window.x = MathHelper.clamp(window.x, 0, Math.max(0, width - WIN_W));
        window.y = MathHelper.clamp(window.y, 0, Math.max(0, height - WIN_HEAD - HEADER - MARGIN * 2));
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

    private void resetLayout() {
        custom = false;
        for (Page each : allPages()) {
            for (Panel panel : each.panels) {
                panel.placed = false;
                panel.collapsed = false;
            }
        }
        arrange();
        saveLayout();
    }

    private void switchPage(int step) {
        if (view != View.Modules) return;
        int next = MathHelper.clamp(page + step, 0, pages.size() - 1);
        if (next == page) return;
        previousPage = page;
        direction = Integer.signum(step);
        page = next;
        lastPage = page;
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

        boolean overWindow = window != null && inside(mouseX, mouseY, window.x, window.y, WIN_W, WIN_HEAD + window.height);
        int panelMouseX = overWindow ? -1 : mouseX;
        int panelMouseY = overWindow ? -1 : mouseY;

        if (previousPage >= 0 && !searching()) {
            float eased = 1 - (1 - t) * (1 - t) * (1 - t);
            int out = Math.round(-direction * eased * width);
            int in = Math.round(direction * (1 - eased) * width);
            for (Panel panel : pages.get(previousPage).panels) drawPanel(context, panel, out, -1, -1);
            for (Panel panel : panels) drawPanel(context, panel, in, -1, -1);
            hits.clear();
        } else {
            List<Panel> list = visiblePanels();
            for (Panel panel : list) {
                Object over = drawPanel(context, panel, 0, panelMouseX, panelMouseY);
                if (over != null) hovered = over;
            }
            if (searching() && list.isEmpty()) text(context, "No module matches \"" + search + "\"", MARGIN, MARGIN, MUTED);
        }
        drawSearch(context, mouseX, mouseY);
        drawTabs(context, mouseX, mouseY);
        drawPages(context, mouseX, mouseY);
        if (window != null) {
            Object over = drawWindow(context, window, mouseX, mouseY);
            if (over != null) hovered = over;
        }

        if (hovered != hoverKey) {
            hoverKey = hovered;
            hoverSince = now;
        }
        if (hint != null && !hint.isEmpty() && dragging == null && sliding == null && !draggingWindow && now - hoverSince >= TIP_DELAY) drawTip(context, mouseX, mouseY);
    }

    private boolean searching() {
        return view == View.Modules && !search.isEmpty();
    }

    private List<Panel> visiblePanels() {
        if (!searching()) return panels;
        if (search.equals(resultsFor)) return results;
        Map<String, List<Module>> byAddon = new LinkedHashMap<>();
        for (String name : owner.pageOrder()) byAddon.put(name, new ArrayList<>());
        for (Module module : Modules.get().getAll()) {
            if (matches(module)) byAddon.computeIfAbsent(addonName(module), name -> new ArrayList<>()).add(module);
        }
        List<Panel> found = new ArrayList<>();
        int x = MARGIN;
        int y = MARGIN;
        for (Map.Entry<String, List<Module>> entry : byAddon.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            entry.getValue().sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            Panel panel = new Panel("search/" + entry.getKey(), entry.getKey(), Type.MODULES, entry.getValue());
            if (x + WIDTH > width - MARGIN && x > MARGIN) {
                x = MARGIN;
                y += height / 2;
            }
            panel.x = x;
            panel.y = y;
            panel.placed = true;
            x += WIDTH + GAP;
            found.add(panel);
        }
        results = found;
        resultsFor = search;
        return results;
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
        clampY(panel);
        int x = panel.x + offset;
        int y = panel.y;

        paint = false;
        int[] measure = {y + HEADER + 1};
        if (!panel.collapsed) panelRows(context, panel, x, measure, -1, -1);
        int bottom = panel.collapsed ? y + HEADER : measure[0];
        panel.bottom = bottom;

        if (!panel.collapsed) context.fill(x, y + HEADER, x + WIDTH, bottom, BODY);
        context.fillGradient(x, y, x + WIDTH, y + HEADER, tint(accent, 0.12f), accent);
        text(context, trim(panel.title, WIDTH - PAD * 2), x + PAD, y + 2, TEXT);
        hits.add(new Hit(x, y, WIDTH, HEADER, Kind.HEADER, panel, null));

        Object hovered = null;
        if (!panel.collapsed) {
            paint = true;
            int[] rowY = {y + HEADER + 1};
            hovered = panelRows(context, panel, x, rowY, mouseX, mouseY);
        }
        outline(context, x, y, x + WIDTH, bottom, accent);
        return hovered;
    }

    private Object panelRows(DrawContext context, Panel panel, int x, int[] y, int mouseX, int mouseY) {
        Object hovered = null;
        switch (panel.type) {
            case MODULES -> {
                for (Module module : panel.modules) {
                    if (!matches(module)) continue;
                    boolean open = window != null && window.owner == module;
                    if (headRow(context, x, y[0], module.title, module.isActive(), open, mouseX, mouseY, Kind.MODULE, module)) {
                        hovered = module;
                        hint = module.description;
                    }
                    y[0] += ROW;
                }
            }
            case ACTIONS -> {
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
            }
            case ELEMENTS -> {
                for (HudElement element : Hud.get()) {
                    boolean open = window != null && window.owner == element;
                    if (headRow(context, x, y[0], element.info.title, element.isActive(), open, mouseX, mouseY, Kind.ELEMENT, element)) {
                        hovered = element;
                        hint = element.info.description + " Right click for its settings.";
                    }
                    y[0] += ROW;
                }
            }
            case ADD -> {
                String last = null;
                for (HudElementInfo<?> info : Hud.get().infos.values()) {
                    String group = info.group.title();
                    if (!group.equals(last)) {
                        if (paint) text(context, trim(group, WIDTH - PAD * 2), x + PAD, y[0] + 2, MUTED);
                        y[0] += ROW;
                        last = group;
                    }
                    boolean open = expanded.contains(info);
                    boolean over = inside(mouseX, mouseY, x + 1, y[0], WIDTH - 2, ROW - 1);
                    if (paint) {
                        cell(context, x + 1, x + WIDTH - 1, y[0], false, over);
                        text(context, trim(info.title, WIDTH - PAD * 2 - 8), x + PAD, y[0] + 2, TEXT);
                        String sign = info.hasPresets() ? (open ? "-" : ">") : "+";
                        text(context, sign, x + WIDTH - PAD - width(sign), y[0] + 2, TEXT);
                        hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.ADD_INFO, info, null));
                    }
                    if (over) {
                        hovered = info;
                        hint = info.hasPresets() ? info.description + " Click to add, right click for presets." : info.description + " Click to add it to the middle of the screen.";
                    }
                    y[0] += ROW;
                    if (open && info.hasPresets()) {
                        for (HudElementInfo<?>.Preset preset : info.presets) {
                            boolean on = inside(mouseX, mouseY, x + 4, y[0], WIDTH - 5, ROW - 1);
                            if (paint) {
                                cell(context, x + 4, x + WIDTH - 1, y[0], false, on);
                                text(context, trim(preset.title, WIDTH - PAD * 2 - 11), x + 4 + PAD, y[0] + 2, TEXT);
                                text(context, "+", x + WIDTH - PAD - width("+"), y[0] + 2, TEXT);
                                hits.add(new Hit(x + 4, y[0], WIDTH - 5, ROW - 1, Kind.ADD_PRESET, preset, null));
                            }
                            if (on) hovered = preset;
                            y[0] += ROW;
                        }
                    }
                }
            }
            case MACROS -> {
                for (Macro macro : Macros.get()) {
                    boolean open = window != null && window.owner == macro;
                    if (headRow(context, x, y[0], macro.name.get().isEmpty() ? "Unnamed" : macro.name.get(), false, open, mouseX, mouseY, Kind.MACRO, macro)) {
                        hovered = macro;
                        hint = "Click to edit.";
                    }
                    y[0] += ROW;
                }
                if (createRow(context, x, y, mouseX, mouseY, "New macro")) hovered = Kind.CREATE;
            }
            case PROFILES -> {
                for (Profile profile : Profiles.get()) {
                    boolean open = window != null && window.owner == profile;
                    if (headRow(context, x, y[0], profile.name.get().isEmpty() ? "Unnamed" : profile.name.get(), false, open, mouseX, mouseY, Kind.PROFILE, profile)) {
                        hovered = profile;
                        hint = "Click to edit, save or load.";
                    }
                    y[0] += ROW;
                }
                if (createRow(context, x, y, mouseX, mouseY, "New profile")) hovered = Kind.CREATE;
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
                    boolean typing = editing == FRIEND_ADD;
                    cell(context, x + 1, x + WIDTH - 1, y[0], typing, over);
                    if (typing) drawEdit(context, x + PAD, y[0] + 2, WIDTH - PAD * 2 - 8);
                    else text(context, "Add friend", x + PAD, y[0] + 2, MUTED);
                    text(context, "+", x + WIDTH - PAD - width("+"), y[0] + 2, TEXT);
                    hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.FRIEND_ADD, null, null));
                }
                if (over) {
                    hovered = FRIEND_ADD;
                    hint = "Click, type a name and press Enter.";
                }
                y[0] += ROW;
            }
        }
        return hovered;
    }

    private boolean createRow(DrawContext context, int x, int[] y, int mouseX, int mouseY, String label) {
        boolean over = inside(mouseX, mouseY, x + 1, y[0], WIDTH - 2, ROW - 1);
        if (paint) {
            cell(context, x + 1, x + WIDTH - 1, y[0], false, over);
            text(context, label, x + PAD, y[0] + 2, MUTED);
            text(context, "+", x + WIDTH - PAD - width("+"), y[0] + 2, TEXT);
            hits.add(new Hit(x + 1, y[0], WIDTH - 2, ROW - 1, Kind.CREATE, label, null));
        }
        y[0] += ROW;
        return over;
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

    private List<Row> windowRows(Window w) {
        List<Row> rows = new ArrayList<>();
        if (w.list != null) {
            listRows(w, rows);
            return rows;
        }
        boolean groups = w.settings.groups.size() > 1;
        for (SettingGroup group : w.settings.groups) {
            if (visibleCount(group) == 0) continue;
            if (groups) rows.add(new Row(RowKind.GROUP, group.name, null, null));
            for (Setting<?> setting : group) {
                if (!setting.isVisible()) continue;
                rows.add(new Row(RowKind.SETTING, setting.title, setting, null));
                if (setting instanceof ColorSetting color && openColors.contains(color)) {
                    for (int channel = 0; channel < 4; channel++) rows.add(new Row(RowKind.CHANNEL, CHANNELS[channel], color, channel));
                }
            }
        }
        if (!w.extras.isEmpty()) {
            rows.add(new Row(RowKind.GROUP, "Actions", null, null));
            rows.addAll(w.extras);
        }
        if (w.more && w.owner instanceof Module module) {
            rows.add(new Row(RowKind.ACTION, "More options", (Runnable) () -> client.setScreen(GuiThemes.get().moduleScreen(module)), null));
        }
        if (w.owner instanceof Module module) {
            rows.add(new Row(RowKind.GROUP, "Module", null, null));
            rows.add(new Row(RowKind.BIND, "Bind", module, null));
            rows.add(new Row(RowKind.ACTION, module.isActive() ? "Turn off" : "Turn on", (Runnable) module::toggle, null));
        }
        if (w.owner instanceof Macro macro) {
            rows.add(new Row(RowKind.GROUP, "Macro", null, null));
            rows.add(new Row(RowKind.ACTION, "Delete", (Runnable) () -> {
                Macros.get().remove(macro);
                closeWindow();
            }, null));
        }
        if (w.owner instanceof Profile profile) {
            rows.add(new Row(RowKind.GROUP, "Profile", null, null));
            rows.add(new Row(RowKind.ACTION, "Save current setup into it", (Runnable) profile::save, null));
            rows.add(new Row(RowKind.ACTION, "Load it", (Runnable) profile::load, null));
            rows.add(new Row(RowKind.ACTION, "Delete", (Runnable) () -> {
                profile.delete();
                Profiles.get().remove(profile);
                closeWindow();
            }, null));
        }
        if (w.owner == owner) {
            rows.add(new Row(RowKind.GROUP, "Layout", null, null));
            rows.add(new Row(RowKind.ACTION, "Reset panel layout", (Runnable) this::resetLayout, null));
        }
        if (w.owner instanceof HudElement element) {
            rows.add(new Row(RowKind.GROUP, "Element", null, null));
            rows.add(new Row(RowKind.ACTION, element.isActive() ? "Hide" : "Show", (Runnable) element::toggle, null));
            rows.add(new Row(RowKind.ACTION, "Remove", (Runnable) () -> {
                element.remove();
                closeWindow();
            }, null));
        }
        return rows;
    }

    private void listRows(Window w, List<Row> rows) {
        Setting<?> setting = w.list;
        rows.add(new Row(RowKind.SEARCH, "Search", setting, null));
        if (isPicker(setting)) {
            pickerRows(w, rows);
            return;
        }
        Collection<?> selected = (Collection<?>) setting.get();
        rows.add(new Row(RowKind.GROUP, "Selected (" + selected.size() + ")", null, null));
        String query = w.listSearch.toLowerCase(Locale.ROOT);
        for (Object entry : selected) {
            if (!query.isEmpty() && !entryName(entry).toLowerCase(Locale.ROOT).contains(query)) continue;
            rows.add(new Row(RowKind.ENTRY_REMOVE, entryName(entry), setting, entry));
        }
        if (setting instanceof StringListSetting) {
            rows.add(new Row(RowKind.TEXT_ADD, "Add", setting, null));
            return;
        }
        rows.add(new Row(RowKind.GROUP, "Add", null, null));
        int shown = 0;
        for (Object entry : candidates(w)) {
            if (selected.contains(entry)) continue;
            if (shown++ >= MAX_CANDIDATES) {
                rows.add(new Row(RowKind.NOTE, "Type to narrow the list", null, null));
                break;
            }
            rows.add(new Row(RowKind.ENTRY_ADD, entryName(entry), setting, entry));
        }
    }

    private void pickerRows(Window w, List<Row> rows) {
        Setting<?> setting = w.list;
        if (setting instanceof FontFaceSetting) {
            List<Object> builtin = new ArrayList<>();
            List<Object> system = new ArrayList<>();
            for (Object entry : candidates(w)) {
                if (isBuiltinFont((FontFamily) entry)) builtin.add(entry);
                else system.add(entry);
            }
            if (!builtin.isEmpty()) {
                rows.add(new Row(RowKind.GROUP, "Built in", null, null));
                for (Object entry : builtin) rows.add(new Row(RowKind.ENTRY_PICK, entryName(entry), setting, entry));
            }
            if (!system.isEmpty()) {
                rows.add(new Row(RowKind.GROUP, "System", null, null));
                rows.add(new Row(RowKind.NOTE, "Some system fonts can crash Meteor's font loader", null, null));
                int shown = 0;
                for (Object entry : system) {
                    if (shown++ >= MAX_CANDIDATES) {
                        rows.add(new Row(RowKind.NOTE, "Type to narrow the list", null, null));
                        break;
                    }
                    rows.add(new Row(RowKind.ENTRY_PICK, entryName(entry), setting, entry));
                }
            }
            return;
        }
        int shown = 0;
        for (Object entry : candidates(w)) {
            if (shown++ >= MAX_CANDIDATES) {
                rows.add(new Row(RowKind.NOTE, "Type to narrow the list", null, null));
                break;
            }
            rows.add(new Row(RowKind.ENTRY_PICK, entryName(entry), setting, entry));
        }
    }

    private static boolean isBuiltinFont(FontFamily family) {
        for (String name : Fonts.BUILTIN_FONTS) if (name.equalsIgnoreCase(family.getName())) return true;
        return false;
    }

    private static boolean isPicker(Setting<?> setting) {
        return setting instanceof FontFaceSetting || setting instanceof BlockSetting || setting instanceof ItemSetting;
    }

    private static boolean picked(Setting<?> setting, Object entry) {
        Object value = setting.get();
        if (entry instanceof FontFamily family) return value instanceof FontFace face && face.info.family().equals(family.getName());
        return Objects.equals(value, entry);
    }

    private static String pickerValue(Setting<?> setting) {
        Object value = setting.get();
        if (value instanceof FontFace face) return face.info.family();
        if (value == null) return "None";
        return entryName(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pick(Setting<?> setting, Object entry) {
        if (entry instanceof FontFamily family) {
            FontFace face = family.get(FontInfo.Type.Regular);
            if (face == null) {
                for (FontInfo.Type type : FontInfo.Type.values()) {
                    face = family.get(type);
                    if (face != null) break;
                }
            }
            if (face != null) ((Setting) setting).set(face);
            return;
        }
        ((Setting) setting).set(entry);
    }

    private List<Object> candidates(Window w) {
        String key = w.list.name + "|" + w.listSearch;
        if (key.equals(w.cacheKey) && w.cache != null) return w.cache;
        List<Object> all = new ArrayList<>();
        Setting<?> setting = w.list;
        if (setting instanceof BlockListSetting blocks) {
            for (Block block : Registries.BLOCK) if (block != Blocks.AIR && (blocks.filter == null || blocks.filter.test(block))) all.add(block);
        } else if (setting instanceof ItemListSetting items) {
            for (Item item : Registries.ITEM) if (item != Items.AIR && (items.filter == null || items.filter.test(item))) all.add(item);
        } else if (setting instanceof ModuleListSetting) {
            all.addAll(Modules.get().getAll());
        } else if (setting instanceof EntityTypeListSetting types) {
            for (EntityType<?> type : Registries.ENTITY_TYPE) if (types.filter == null || types.filter.test(type)) all.add(type);
        } else if (setting instanceof StatusEffectListSetting) {
            for (StatusEffect effect : Registries.STATUS_EFFECT) all.add(effect);
        } else if (setting instanceof FontFaceSetting) {
            all.addAll(Fonts.FONT_FAMILIES);
        } else if (setting instanceof BlockSetting block) {
            for (Block entry : Registries.BLOCK) if (block.filter == null || block.filter.test(entry)) all.add(entry);
        } else if (setting instanceof ItemSetting item) {
            for (Item entry : Registries.ITEM) if (item.filter == null || item.filter.test(entry)) all.add(entry);
        }
        String query = w.listSearch.toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) all.removeIf(entry -> !entryName(entry).toLowerCase(Locale.ROOT).contains(query));
        all.sort((a, b) -> entryName(a).compareToIgnoreCase(entryName(b)));
        w.cache = all;
        w.cacheKey = key;
        return all;
    }

    private static String entryName(Object entry) {
        if (entry instanceof Block block) return block.getName().getString();
        if (entry instanceof Item item) return item.getName().getString();
        if (entry instanceof Module module) return module.title;
        if (entry instanceof EntityType<?> type) return type.getName().getString();
        if (entry instanceof StatusEffect effect) return effect.getName().getString();
        if (entry instanceof FontFamily family) return family.getName();
        return String.valueOf(entry);
    }

    private static boolean isList(Setting<?> setting) {
        return setting instanceof BlockListSetting || setting instanceof ItemListSetting || setting instanceof ModuleListSetting
            || setting instanceof EntityTypeListSetting || setting instanceof StatusEffectListSetting || setting instanceof StringListSetting;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void changeList(Setting<?> setting, Object entry, boolean add) {
        Object value = setting.get();
        if (value instanceof List<?> list) {
            List copy = new ArrayList<>(list);
            if (add) {
                if (!copy.contains(entry)) copy.add(entry);
            } else copy.remove(entry);
            ((Setting) setting).set(copy);
        } else if (value instanceof Set<?> set) {
            Set copy = new LinkedHashSet<>(set);
            if (add) copy.add(entry);
            else copy.remove(entry);
            ((Setting) setting).set(copy);
        }
    }

    private Object drawWindow(DrawContext context, Window w, int mouseX, int mouseY) {
        List<Row> rows = windowRows(w);
        int content = rows.size() * WIN_ROW + 2;
        int limit = height - w.y - WIN_HEAD - MARGIN * 2 - HEADER;
        w.height = Math.max(WIN_ROW, Math.min(content, limit));
        w.scroll = MathHelper.clamp(w.scroll, 0, Math.max(0, content - w.height));

        int x = w.x;
        int top = w.y + WIN_HEAD;
        int bottom = top + w.height;
        Object hovered = null;

        context.fillGradient(x, w.y, x + WIN_W, top, tint(accent, 0.12f), accent);
        hits.add(new Hit(x, w.y, WIN_W, WIN_HEAD, Kind.W_HEADER, w, null));
        int titleX = x + PAD;
        if (w.list != null) {
            boolean over = inside(mouseX, mouseY, x, w.y, 14, WIN_HEAD);
            text(context, "<", x + PAD, w.y + 2, over ? TEXT : MUTED);
            hits.add(new Hit(x, w.y, 14, WIN_HEAD, Kind.W_BACK, w, null));
            titleX += 10;
        }
        String title = w.list != null ? w.list.title : w.title;
        int right = x + WIN_W - PAD;
        if (w.closable) {
            boolean over = inside(mouseX, mouseY, right - 8, w.y, 12, WIN_HEAD);
            text(context, "x", right - width("x"), w.y + 2, over ? TEXT : MUTED);
            hits.add(new Hit(right - 8, w.y, 12, WIN_HEAD, Kind.W_CLOSE, w, null));
            right -= 14;
        }
        if (w.list == null && w.resetAll != null) {
            String label = "Reset all";
            int lw = width(label);
            boolean over = inside(mouseX, mouseY, right - lw - 2, w.y, lw + 4, WIN_HEAD);
            text(context, label, right - lw, w.y + 2, over ? TEXT : MUTED);
            hits.add(new Hit(right - lw - 2, w.y, lw + 4, WIN_HEAD, Kind.W_RESET_ALL, w, null));
            if (over) hint = "Puts every setting here back to its default.";
            right -= lw + 8;
        }
        text(context, trim(title, right - titleX - 2), titleX, w.y + 2, TEXT);

        context.fill(x, top, x + WIN_W, bottom, WIN_BODY);
        hits.add(new Hit(x, top, WIN_W, w.height, Kind.W_AREA, w, null));
        context.enableScissor(x, top, x + WIN_W, bottom);
        int y = top + 1 - w.scroll;
        for (Row row : rows) {
            if (y + WIN_ROW > top && y < bottom) {
                boolean over = mouseY >= top && mouseY < bottom && inside(mouseX, mouseY, x, y, WIN_W, WIN_ROW);
                Object result = drawRow(context, w, row, x, y, over, mouseX, mouseY, top, bottom);
                if (result != null) hovered = result;
            }
            y += WIN_ROW;
        }
        context.disableScissor();
        if (content > w.height) {
            int barH = Math.max(8, w.height * w.height / content);
            int barY = top + (w.height - barH) * w.scroll / Math.max(1, content - w.height);
            context.fill(x + WIN_W - 2, barY, x + WIN_W - 1, barY + barH, withAlpha(accent, 0xC0));
        }
        outline(context, x, w.y, x + WIN_W, bottom, accent);
        return hovered;
    }

    private Object drawRow(DrawContext context, Window w, Row row, int x, int y, boolean over, int mouseX, int mouseY, int top, int bottom) {
        int left = x + PAD;
        int resetX = x + WIN_W - PAD - ICON;
        int ctrlRight = resetX - 4;
        int ctrlLeft = ctrlRight - CTRL_W;
        int ty = y + 3;

        switch (row.kind()) {
            case GROUP -> {
                text(context, row.label(), left, ty, MUTED);
                int lineX = left + width(row.label()) + 4;
                context.fill(lineX, y + WIN_ROW / 2, x + WIN_W - PAD, y + WIN_ROW / 2 + 1, LINE);
                return null;
            }
            case NOTE -> {
                text(context, row.label(), left, ty, MUTED);
                return null;
            }
            case SEARCH -> {
                boolean typing = editing == LIST_SEARCH;
                box(context, x + PAD, y + 1, x + WIN_W - PAD, y + WIN_ROW - 1, typing, over);
                if (typing) drawEdit(context, x + PAD * 2, ty, WIN_W - PAD * 4);
                else text(context, trimStart(w.listSearch.isEmpty() ? "Search" : w.listSearch, WIN_W - PAD * 4), x + PAD * 2, ty, w.listSearch.isEmpty() ? MUTED : TEXT);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, Kind.W_LIST_SEARCH, w, null);
                return null;
            }
            case ENTRY_PICK -> {
                boolean current = picked(row.target() instanceof Setting<?> set ? set : w.list, row.extra());
                if (current) context.fill(x + 1, y, x + WIN_W - 1, y + WIN_ROW, enabledCell);
                else if (over) context.fill(x + 1, y, x + WIN_W - 1, y + WIN_ROW, ROW_HOVER);
                text(context, trim(row.label(), WIN_W - PAD * 3 - 8), left, ty, TEXT);
                if (current) text(context, "*", x + WIN_W - PAD - width("*"), ty, TEXT);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, Kind.W_LIST_PICK, row.target(), row.extra());
                return null;
            }
            case ENTRY_REMOVE, ENTRY_ADD -> {
                if (over) context.fill(x + 1, y, x + WIN_W - 1, y + WIN_ROW, ROW_HOVER);
                text(context, trim(row.label(), WIN_W - PAD * 3 - 8), left, ty, TEXT);
                String sign = row.kind() == RowKind.ENTRY_ADD ? "+" : "-";
                text(context, sign, x + WIN_W - PAD - width(sign), ty, over ? TEXT : MUTED);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, row.kind() == RowKind.ENTRY_ADD ? Kind.W_LIST_ADD : Kind.W_LIST_REMOVE, row.target(), row.extra());
                return null;
            }
            case TEXT_ADD -> {
                boolean typing = editing == LIST_TEXT;
                box(context, x + PAD, y + 1, x + WIN_W - PAD, y + WIN_ROW - 1, typing, over);
                if (typing) drawEdit(context, x + PAD * 2, ty, WIN_W - PAD * 4);
                else text(context, "Add a line", x + PAD * 2, ty, MUTED);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, Kind.W_LIST_TEXT, row.target(), null);
                if (over) hint = "Click, type and press Enter to add it.";
                return null;
            }
            case ACTION -> {
                box(context, x + PAD, y + 1, x + WIN_W - PAD, y + WIN_ROW - 1, false, over);
                text(context, row.label(), x + (WIN_W - width(row.label())) / 2, ty, TEXT);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, Kind.W_ACTION, row.target(), null);
                return null;
            }
            case BIND -> {
                if (over) context.fill(x + 1, y, x + WIN_W - 1, y + WIN_ROW, ROW_HOVER);
                Module module = (Module) row.target();
                text(context, "Bind", left, ty, TEXT);
                box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, binding == module, over);
                String key = bindText(module.keybind, module);
                text(context, key, ctrlLeft + (CTRL_W - width(key)) / 2, ty, TEXT);
                addHit(x, y, WIN_W, WIN_ROW, top, bottom, Kind.W_MODULE_BIND, module, null);
                if (over) hint = "Click, then press a key or mouse button. Escape clears it.";
                return null;
            }
            case CHANNEL -> {
                ColorSetting color = (ColorSetting) row.target();
                int channel = (Integer) row.extra();
                text(context, row.label(), left + 6, ty, MUTED);
                int amount = channel(color.get(), channel);
                box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
                int filled = ctrlLeft + 1 + Math.round((CTRL_W - 2) * amount / 255f);
                int tintColor = switch (channel) {
                    case 0 -> 0xDCB4464E;
                    case 1 -> 0xDC4EA45C;
                    case 2 -> 0xDC4E64B4;
                    default -> enabledCell;
                };
                if (filled > ctrlLeft + 1) context.fill(ctrlLeft + 1, y + 2, filled, y + WIN_ROW - 2, tintColor);
                String shown = String.valueOf(amount);
                text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
                addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_CHANNEL, color, new int[] {ctrlLeft + 1, ctrlRight - 1, channel});
                return null;
            }
            default -> {
            }
        }

        Setting<?> setting = (Setting<?>) row.target();
        if (over) {
            context.fill(x + 1, y, x + WIN_W - 1, y + WIN_ROW, ROW_HOVER);
            hint = setting.description;
        }
        int nameMax = ctrlLeft - left - 4;
        text(context, trim(setting.title, nameMax), left, ty, TEXT);

        boolean changed = !Objects.equals(setting.get(), setting.getDefaultValue());
        boolean resetOver = mouseY >= top && mouseY < bottom && inside(mouseX, mouseY, resetX - 1, y, ICON + 2, WIN_ROW);
        int iconColor = resetOver ? TEXT : changed ? 0xE6FFFFFF : 0x40FFFFFF;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, RESET_ICON, resetX, y + (WIN_ROW - ICON) / 2, 0, 0, ICON, ICON, 32, 32, 32, 32, iconColor);
        addHit(resetX - 1, y, ICON + 2, WIN_ROW, top, bottom, Kind.W_RESET, setting, null);

        if (setting instanceof BoolSetting bool) {
            int size = 9;
            int bx = ctrlRight - size;
            int by = y + (WIN_ROW - size) / 2;
            context.fill(bx, by, bx + size, by + size, EDGE);
            context.fill(bx + 1, by + 1, bx + size - 1, by + size - 1, bool.get() ? accent : over ? CELL_HOVER : CELL);
            addHit(x, y, resetX - x - 2, WIN_ROW, top, bottom, Kind.W_BOOL, bool, null);
        } else if ((setting instanceof DoubleSetting || setting instanceof IntSetting) && editing == setting) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, true, over);
            drawEdit(context, ctrlLeft + PAD, ty, CTRL_W - PAD * 2);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_SLIDER, setting, new int[] {ctrlLeft + 1, ctrlRight - 1});
        } else if (setting instanceof DoubleSetting || setting instanceof IntSetting) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
            int filled = ctrlLeft + 1 + Math.round((CTRL_W - 2) * sliderFraction(setting));
            if (filled > ctrlLeft + 1) context.fill(ctrlLeft + 1, y + 2, filled, y + WIN_ROW - 2, over ? enabledHover : enabledCell);
            String shown = valueText(setting);
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_SLIDER, setting, new int[] {ctrlLeft + 1, ctrlRight - 1});
        } else if (setting instanceof EnumSetting<?> choice) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
            String shown = trim(choice.get().toString(), CTRL_W - 16);
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
            text(context, "<", ctrlLeft + 3, ty, FAINT);
            text(context, ">", ctrlRight - 3 - width(">"), ty, FAINT);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_ENUM, choice, null);
        } else if (setting instanceof ColorSetting color) {
            int sw = 18;
            int sx = ctrlRight - sw;
            context.fill(sx, y + 2, ctrlRight, y + WIN_ROW - 2, EDGE);
            context.fill(sx + 1, y + 3, ctrlRight - 1, y + WIN_ROW - 3, color.get().getPacked() | 0xFF000000);
            SettingColor c = color.get();
            String hex = String.format(Locale.ROOT, "#%02X%02X%02X", c.r, c.g, c.b);
            text(context, hex, sx - 4 - width(hex), ty, openColors.contains(color) ? TEXT : MUTED);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_COLOR, color, null);
        } else if (setting instanceof StringSetting string) {
            boolean typing = editing == string;
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, typing, over);
            if (typing) drawEdit(context, ctrlLeft + PAD, ty, CTRL_W - PAD * 2);
            else text(context, trimStart(string.get(), CTRL_W - PAD * 2), ctrlLeft + PAD, ty, MUTED);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_STRING, string, null);
        } else if (setting instanceof KeybindSetting keybind) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, binding == keybind, over);
            String shown = bindText(keybind.get(), keybind);
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_BIND, keybind, null);
        } else if (isPicker(setting)) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
            String shown = trim(pickerValue(setting), CTRL_W - 16) + "  >";
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_LIST, setting, null);
        } else if (isList(setting)) {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
            String shown = ((Collection<?>) setting.get()).size() + " selected  >";
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, TEXT);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_LIST, setting, null);
        } else {
            box(context, ctrlLeft, y + 1, ctrlRight, y + WIN_ROW - 1, false, over);
            String shown = "Edit  >";
            text(context, shown, ctrlLeft + (CTRL_W - width(shown)) / 2, ty, MUTED);
            addHit(ctrlLeft, y, CTRL_W, WIN_ROW, top, bottom, Kind.W_MORE, setting, w.owner);
        }
        return setting;
    }

    private void addHit(int x, int y, int w, int h, int top, int bottom, Kind kind, Object target, Object extra) {
        int y1 = Math.max(y, top);
        int y2 = Math.min(y + h, bottom);
        if (y2 > y1) hits.add(new Hit(x, y1, w, y2 - y1, kind, target, extra));
    }

    private void box(DrawContext context, int x1, int y1, int x2, int y2, boolean active, boolean over) {
        context.fill(x1, y1, x2, y2, active ? accent : EDGE);
        context.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, over ? CELL_HOVER : CELL);
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

    private void drawEdit(DrawContext context, int x, int y, int max) {
        int start = 0;
        while (start < editCursor && width(editText.substring(start, editCursor)) > max - 2) start++;
        String visible = editText.substring(start);
        int end = visible.length();
        while (end > 0 && width(visible.substring(0, end)) > max) end--;
        visible = visible.substring(0, end);
        text(context, visible, x, y, TEXT);
        if ((Util.getMeasuringTimeMs() / 500) % 2 == 0) {
            int cx = x + width(editText.substring(start, Math.min(editCursor, start + end)));
            context.fill(cx, y - 1, cx + 1, y + 9, TEXT);
        }
    }

    private void setEditText(String text, int cursor) {
        editText = text;
        editCursor = MathHelper.clamp(cursor, 0, text.length());
        if (editing == SEARCH_EDIT) search = editText; else if (editing == LIST_SEARCH && window != null) {
            window.listSearch = editText;
            window.scroll = 0;
        }
    }

    private void drawSearch(DrawContext context, int mouseX, int mouseY) {
        if (view != View.Modules) return;
        int x = MARGIN;
        int y = height - MARGIN - HEADER;
        boolean over = inside(mouseX, mouseY, x, y, SEARCH_WIDTH, HEADER);
        boolean typing = editing == SEARCH_EDIT;
        context.fill(x, y, x + SEARCH_WIDTH, y + HEADER, typing ? enabledCell : over ? CELL_HOVER : CELL);
        outline(context, x, y, x + SEARCH_WIDTH, y + HEADER, accent);
        if (typing) drawEdit(context, x + PAD, y + 2, SEARCH_WIDTH - PAD * 2);
        else text(context, trimStart(search.isEmpty() ? "Search" : search, SEARCH_WIDTH - PAD * 2), x + PAD, y + 2, search.isEmpty() ? MUTED : TEXT);
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
            case "Baritone" -> View.Baritone;
            case "Macros" -> View.Macros;
            case "Profiles" -> View.Profiles;
            default -> null;
        };
    }

    private void drawPages(DrawContext context, int mouseX, int mouseY) {
        if (view != View.Modules || pages.size() < 2 || searching()) return;
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
        context.fill(x, y, x + w, y + h, 0xF0120F18);
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
        if (hit == null || (hit.kind != Kind.W_STRING && hit.kind != Kind.FRIEND_ADD && hit.kind != Kind.W_LIST_SEARCH && hit.kind != Kind.W_LIST_TEXT && hit.kind != Kind.SEARCH
            && !(hit.kind == Kind.W_SLIDER && hit.target == editing))) finishEdit(true);
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
                    List<Panel> list = visiblePanels();
                    list.remove(panel);
                    list.add(panel);
                }
            }
            case MODULE -> {
                Module module = (Module) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) module.toggle();
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    if (owner.opensSettingsWindow()) client.setScreen(GuiThemes.get().moduleScreen(module));
                    else if (window != null && window.owner == module) closeWindow();
                    else openWindow(new Window(module.title, module.settings, module, true, module.settings::reset, null));
                }
            }
            case ELEMENT -> {
                HudElement element = (HudElement) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) element.toggle();
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    if (window != null && window.owner == element) closeWindow();
                    else openWindow(new Window(element.info.title, element.settings, element, true, element.settings::reset, null));
                }
            }
            case FRIEND -> Friends.get().remove((Friend) hit.target);
            case FRIEND_ADD -> startEdit(FRIEND_ADD, "", null);
            case ACTION -> ((Action) hit.target).run().run();
            case ADD_INFO -> {
                HudElementInfo<?> info = (HudElementInfo<?>) hit.target;
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && info.hasPresets()) {
                    if (!expanded.remove(info)) expanded.add(info);
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    Hud.get().add(info, client.getWindow().getFramebufferWidth() / 2, client.getWindow().getFramebufferHeight() / 2);
                }
            }
            case ADD_PRESET -> Hud.get().add((HudElementInfo<?>.Preset) hit.target, client.getWindow().getFramebufferWidth() / 2, client.getWindow().getFramebufferHeight() / 2);
            case VIEW -> {
                showView((View) hit.target);
                arrange();
            }
            case TAB -> ((Tab) hit.target).openScreen(GuiThemes.get());
            case PAGE -> switchPage((Integer) hit.target);
            case SEARCH -> {
                if (editing != SEARCH_EDIT) startEdit(SEARCH_EDIT, search, null);
            }
            case MACRO -> {
                Macro macro = (Macro) hit.target;
                if (window != null && window.owner == macro) closeWindow();
                else openWindow(new Window(macro.name.get().isEmpty() ? "Macro" : macro.name.get(), macro.settings, macro, true, null, () -> Macros.get().save()));
            }
            case PROFILE -> {
                Profile profile = (Profile) hit.target;
                if (window != null && window.owner == profile) closeWindow();
                else openWindow(new Window(profile.name.get().isEmpty() ? "Profile" : profile.name.get(), profile.settings, profile, true, null, () -> Profiles.get().save()));
            }
            case CREATE -> {
                if (view == View.Macros) {
                    Macro macro = new Macro();
                    macro.name.set("Macro " + (Macros.get().getAll().size() + 1));
                    Macros.get().add(macro);
                    openWindow(new Window(macro.name.get(), macro.settings, macro, true, null, () -> Macros.get().save()));
                } else if (view == View.Profiles) {
                    Profile profile = new Profile();
                    profile.name.set("Profile " + (Profiles.get().getAll().size() + 1));
                    Profiles.get().add(profile);
                    openWindow(new Window(profile.name.get(), profile.settings, profile, true, null, () -> Profiles.get().save()));
                }
            }
            case W_HEADER -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    draggingWindow = true;
                    dragX = (int) mx - window.x;
                    dragY = (int) my - window.y;
                }
            }
            case W_CLOSE -> closeWindow();
            case W_RESET_ALL -> {
                if (window.resetAll != null) window.resetAll.run();
            }
            case W_BACK -> {
                finishEdit(true);
                window.list = null;
                window.listSearch = "";
                window.scroll = 0;
            }
            case W_RESET -> ((Setting<?>) hit.target).reset();
            case W_BOOL -> {
                BoolSetting setting = (BoolSetting) hit.target;
                setting.set(!setting.get());
            }
            case W_SLIDER, W_CHANNEL -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    Setting<?> target = (Setting<?>) hit.target;
                    if (hit.kind == Kind.W_SLIDER && (target instanceof DoubleSetting || target instanceof IntSetting)) {
                        if (editing == target) return true;
                        if (doubled) {
                            if (slideBeforeSetting == target) restoreSlide();
                            sliding = null;
                            startEdit(target, valueText(target), null);
                            return true;
                        }
                        slideBeforeSetting = target;
                        slideBefore = target.get();
                    }
                    sliding = target;
                    slidingHit = hit;
                    slidingChannel = hit.kind == Kind.W_CHANNEL ? ((int[]) hit.extra)[2] : -1;
                    slide(mx);
                }
            }
            case W_ENUM -> cycle((EnumSetting<?>) hit.target, button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1);
            case W_COLOR -> {
                ColorSetting color = (ColorSetting) hit.target;
                if (!openColors.remove(color)) openColors.add(color);
            }
            case W_STRING -> {
                StringSetting setting = (StringSetting) hit.target;
                if (editing != setting) startEdit(setting, setting.get(), null);
            }
            case W_BIND, W_MODULE_BIND -> binding = hit.target;
            case W_ACTION -> ((Runnable) hit.target).run();
            case W_LIST -> {
                window.list = (Setting<?>) hit.target;
                window.listSearch = "";
                window.scroll = 0;
                window.cache = null;
            }
            case W_LIST_SEARCH -> {
                if (editing != LIST_SEARCH) startEdit(LIST_SEARCH, window.listSearch, null);
            }
            case W_LIST_ADD -> changeList((Setting<?>) hit.target, hit.extra, true);
            case W_LIST_REMOVE -> changeList((Setting<?>) hit.target, hit.extra, false);
            case W_LIST_TEXT -> startEdit(LIST_TEXT, "", (Setting<?>) hit.target);
            case W_LIST_PICK -> {
                pick((Setting<?>) hit.target, hit.extra);
                finishEdit(true);
                window.list = null;
                window.listSearch = "";
                window.scroll = 0;
            }
            case W_MORE -> {
                if (hit.extra instanceof Module module) client.setScreen(GuiThemes.get().moduleScreen(module));
                else if (hit.extra instanceof HudElement element) client.setScreen(new HudElementScreen(GuiThemes.get(), element));
                else openMeteorTab();
            }
            case W_AREA -> {
            }
        }
        return true;
    }

    private void openMeteorTab() {
        String name = switch (view) {
            case Config -> "Config";
            case Hud -> "HUD";
            case Friends -> "Friends";
            case Gui -> "GUI";
            case Baritone -> "Baritone";
            case Macros -> "Macros";
            case Profiles -> "Profiles";
            default -> "";
        };
        for (Tab tab : Tabs.get()) {
            if (!tab.name.equals(name)) continue;
            passThrough = true;
            try {
                client.setScreen(tab.createScreen(GuiThemes.get()));
            } finally {
                passThrough = false;
            }
            return;
        }
    }

    private void startEdit(Object target, String text, Setting<?> list) {
        finishEdit(true);
        editing = target;
        editingList = list;
        editText = text;
        editCursor = text.length();
    }

    private void finishEdit(boolean commit) {
        if (editing == null) return;
        if (commit) {
            if (editing instanceof StringSetting setting) setting.set(editText);
            else if (editing instanceof DoubleSetting setting) {
                try {
                    setting.set(MathHelper.clamp(Double.parseDouble(editText.trim().replace(',', '.')), setting.min, setting.max));
                } catch (NumberFormatException ignored) {
                }
            } else if (editing instanceof IntSetting setting) {
                try {
                    setting.set(MathHelper.clamp((int) Math.round(Double.parseDouble(editText.trim().replace(',', '.'))), setting.min, setting.max));
                } catch (NumberFormatException ignored) {
                }
            }
            else if (editing == FRIEND_ADD && !editText.isBlank()) Friends.get().add(new Friend(editText.trim()));
            else if (editing == LIST_TEXT && editingList != null && !editText.isBlank()) changeList(editingList, editText, true);
        }
        editing = null;
        editingList = null;
        editText = "";
        editCursor = 0;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingWindow && window != null) {
            window.x = (int) click.x() - dragX;
            window.y = (int) click.y() - dragY;
            clampWindow();
            return true;
        }
        if (dragging != null) {
            custom = true;
            dragging.x = MathHelper.clamp((int) click.x() - dragX, 0, Math.max(0, width - WIDTH));
            moveY(dragging, (int) click.y() - dragY);
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
        draggingWindow = false;
        sliding = null;
        slidingHit = null;
        slidingChannel = -1;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (window != null && inside((int) mouseX, (int) mouseY, window.x, window.y, WIN_W, WIN_HEAD + window.height)) {
            window.scroll -= (int) Math.signum(vertical) * WIN_ROW * 3;
            return true;
        }
        List<Panel> list = visiblePanels();
        for (int i = list.size() - 1; i >= 0; i--) {
            Panel panel = list.get(i);
            if (mouseX < panel.x || mouseX >= panel.x + WIDTH || mouseY < panel.y || mouseY >= Math.max(panel.bottom, panel.y + HEADER) || !shown(panel)) continue;
            moveY(panel, panel.y + (int) Math.signum(vertical) * SCROLL);
            if (!searching()) {
                custom = true;
                saveLayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        swallowChar = false;
        if (binding != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) applyBind(Keybind.none());
            else {
                applyBind(Keybind.fromKeys(key, input.modifiers()));
                swallowChar = true;
            }
            return true;
        }
        if (editing != null) {
            boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
            boolean control = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
            boolean live = editing == SEARCH_EDIT || editing == LIST_SEARCH;
            if (enter) {
                Object was = editing;
                Setting<?> list = editingList;
                if (live) {
                    editing = null;
                    editText = "";
                } else finishEdit(true);
                if (was == LIST_TEXT) startEdit(LIST_TEXT, "", list);
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (editing == SEARCH_EDIT) setEditText("", 0);
                if (live) {
                    editing = null;
                    editText = "";
                } else finishEdit(false);
            } else if (key == GLFW.GLFW_KEY_LEFT) {
                editCursor = control ? wordLeft() : Math.max(0, editCursor - 1);
            } else if (key == GLFW.GLFW_KEY_RIGHT) {
                editCursor = control ? wordRight() : Math.min(editText.length(), editCursor + 1);
            } else if (key == GLFW.GLFW_KEY_HOME) {
                editCursor = 0;
            } else if (key == GLFW.GLFW_KEY_END) {
                editCursor = editText.length();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && editCursor > 0) {
                int from = control ? wordLeft() : editCursor - 1;
                setEditText(editText.substring(0, from) + editText.substring(editCursor), from);
            } else if (key == GLFW.GLFW_KEY_DELETE && editCursor < editText.length()) {
                int to = control ? wordRight() : editCursor + 1;
                setEditText(editText.substring(0, editCursor) + editText.substring(to), editCursor);
            } else if (control && key == GLFW.GLFW_KEY_V) {
                insert(client.keyboard.getClipboard().replace("\n", " "));
            } else if (control && key == GLFW.GLFW_KEY_C) {
                client.keyboard.setClipboard(editText);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && window != null) {
            if (window.list != null) {
                window.list = null;
                window.listSearch = "";
                window.scroll = 0;
                return true;
            }
            if (window.closable) {
                closeWindow();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && searching()) {
            search = "";
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            switchPage(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            switchPage(1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        if (!input.isValidChar()) return false;
        if (editing == null) {
            if (view != View.Modules || binding != null || input.asString().isBlank()) return false;
            startEdit(SEARCH_EDIT, search, null);
        }
        if ((editing instanceof DoubleSetting || editing instanceof IntSetting) && !input.asString().matches("[0-9.,-]+")) return true;
        insert(input.asString());
        return true;
    }

    private void restoreSlide() {
        if (slideBeforeSetting instanceof DoubleSetting setting && slideBefore instanceof Double value) setting.set(value);
        else if (slideBeforeSetting instanceof IntSetting setting && slideBefore instanceof Integer value) setting.set(value);
        slideBeforeSetting = null;
        slideBefore = null;
    }

    private void insert(String typed) {
        if (typed.isEmpty() || editText.length() + typed.length() > 256) return;
        setEditText(editText.substring(0, editCursor) + typed + editText.substring(editCursor), editCursor + typed.length());
    }

    private int wordLeft() {
        int i = editCursor;
        while (i > 0 && editText.charAt(i - 1) == ' ') i--;
        while (i > 0 && editText.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int wordRight() {
        int i = editCursor;
        while (i < editText.length() && editText.charAt(i) == ' ') i++;
        while (i < editText.length() && editText.charAt(i) != ' ') i++;
        return i;
    }

    @Override
    public void close() {
        closeWindow();
        saveLayout();
        super.close();
    }

    @Override
    public void removed() {
        closeWindow();
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
        double t = MathHelper.clamp((mx - range[0]) / (double) Math.max(1, range[1] - range[0]), 0, 1);
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
        if (max <= 0) return "";
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
