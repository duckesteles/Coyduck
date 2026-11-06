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
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
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
    private static final int WIDTH = 124;
    private static final int HEADER = 20;
    private static final int ROW = 16;
    private static final int SETTING = 14;
    private static final int GAP = 10;
    private static final int TOP = 38;
    private static final int PAD = 8;
    private static final int MARGIN = 16;
    private static final int INNER = 3;
    private static final int INDENT = 4;
    private static final int TIP_WIDTH = 170;

    private static final float OPEN_MS = 230;
    private static final float CLOSE_MS = 140;
    private static final float STAGGER_MS = 24;
    private static final float EXPAND_MS = 190;
    private static final long HINT_DELAY = 280;

    private static final int[] CORNER = {2, 1};
    private static final int[] SMALL = {1};

    private static final int PANEL = 0xB40E0E13;
    private static final int HEAD = 0xE6141419;
    private static final int FIELD = 0xC8111116;
    private static final int TIP = 0xF20C0C10;
    private static final int OUTLINE = 0x1EFFFFFF;
    private static final int HOVER = 0x14FFFFFF;
    private static final int TRAY = 0x38000000;
    private static final int PILL = 0x1CFFFFFF;
    private static final int OFF = 0x34FFFFFF;
    private static final int TITLE = 0xFFEDEDF3;
    private static final int TEXT = 0xFFA8A8B4;
    private static final int BRIGHT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6A6A76;
    private static final int LABEL = 0xFF9A9AA6;
    private static final int VALUE = 0xFFDADAE2;
    private static final int TRACK = 0x2CFFFFFF;
    private static final int KNOB = 0xFFB8B8C2;
    private static final int BOX = 0xFF3A3A45;

    private static final Object SEARCH = new Object();

    private enum Kind { HEADER, MODULE, BOOL, SLIDER, ENUM, BIND, MODULE_BIND, MORE, TAB, PANEL }

    private record Hit(int x, int y, int w, int h, Kind kind, Object target, Object extra) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final class Panel {
        final Category category;
        final List<Module> modules;
        final List<Module> shown = new ArrayList<>();
        int x;
        int y;
        float ax;
        float ay;
        boolean placed;
        boolean collapsed;
        float fold = 1;
        float glow;
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
    private final Map<Object, float[]> hover = new HashMap<>();
    private final Map<Object, float[]> on = new HashMap<>();
    private final Map<Module, float[]> open = new HashMap<>();

    private String search = "";
    private String query = "";
    private int matches;
    private Panel dragging;
    private int dragX;
    private int dragY;
    private Setting<?> sliding;
    private Hit slidingHit;
    private Object binding;

    private Object hoverKey;
    private Object lastHoverKey;
    private long hoverSince;
    private String hintTitle;
    private String hint;
    private String tipTitle;
    private String tipText;
    private List<OrderedText> tipLines;
    private int tipWidth;
    private float tipAlpha;

    private long lastFrame;
    private final long openedAt;
    private long closedAt;
    private boolean closing;
    private boolean custom;
    private float fade = 1;
    private int accent;
    private int accentText;

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
            for (Panel panel : panels) panel.fold = panel.collapsed ? 0 : 1;
        }
        if (!custom) arrange();
        for (Panel panel : panels) {
            panel.x = MathHelper.clamp(panel.x, 0, Math.max(0, width - WIDTH));
            panel.y = MathHelper.clamp(panel.y, 0, Math.max(0, height - HEADER));
            panel.placed = false;
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
            stack[column] += HEADER + (panel.collapsed ? 0 : Math.min(panel.modules.size() * ROW + INNER * 2, cap)) + GAP;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!owner.dim.get()) return;
        context.fill(0, 0, width, height, alpha(0x9A06060A, presence(Util.getMeasuringTimeMs())));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;

        float global = presence(now);
        if (closing && global <= 0) {
            super.close();
            return;
        }

        accent = owner.accent.get().getPacked() | 0xFF000000;
        accentText = blend(accent, BRIGHT, 0.18f);
        hits.clear();
        hoverKey = null;
        hint = null;
        hintTitle = null;
        filter();

        fade = global;
        drawBrand(context);
        drawSearch(context, now, dt);
        drawTabs(context, mouseX, mouseY, dt);

        for (int i = 0; i < panels.size(); i++) {
            context.createNewRootLayer();
            drawPanel(context, panels.get(i), i, mouseX, mouseY, dt, now, global);
        }

        context.createNewRootLayer();
        fade = global;
        drawDescription(context, mouseX, mouseY, now, dt);
    }

    private float presence(long now) {
        if (closing) {
            float t = MathHelper.clamp((now - closedAt) / CLOSE_MS, 0, 1);
            return 1 - t * t;
        }
        return ease(MathHelper.clamp((now - openedAt) / OPEN_MS, 0, 1));
    }

    private void filter() {
        query = search.toLowerCase(Locale.ROOT);
        matches = 0;
        for (Panel panel : panels) {
            panel.shown.clear();
            for (Module module : panel.modules) {
                if (query.isEmpty() || module.title.toLowerCase(Locale.ROOT).contains(query) || module.name.contains(query)) panel.shown.add(module);
            }
            matches += panel.shown.size();
        }
    }

    private void drawBrand(DrawContext context) {
        int x = MARGIN;
        int y = 12;
        round(context, x, y + 1, x + 5, y + 6, accent, SMALL, true, true);
        text(context, "Coyduck", x + 9, y, TITLE);
    }

    private void drawSearch(DrawContext context, long now, float dt) {
        int w = 180;
        int h = 16;
        int x = (width - w) / 2;
        int y = 8;
        float focus = animate(hover, SEARCH, search.isEmpty() ? 0 : 1, dt, 12);
        round(context, x, y, x + w, y + h, FIELD, CORNER, true, true);
        outline(context, x, y, x + w, y + h, blend(OUTLINE, alpha(accent, 0.75f), focus), CORNER);

        int icon = blend(MUTED, accentText, focus);
        int ix = x + 7;
        int iy = y + 4;
        outline(context, ix, iy, ix + 6, iy + 6, icon, SMALL);
        fill(context, ix + 5, iy + 5, ix + 6, iy + 6, icon);
        fill(context, ix + 6, iy + 6, ix + 7, iy + 7, icon);
        fill(context, ix + 7, iy + 7, ix + 8, iy + 8, icon);

        int tx = x + 19;
        if (search.isEmpty()) text(context, "Search modules", tx, y + 4, MUTED);
        else text(context, search, tx, y + 4, VALUE);
        if ((now / 500) % 2 == 0) {
            int cx = tx + (search.isEmpty() ? 0 : textRenderer.getWidth(search) + 1);
            fill(context, cx, y + 3, cx + 1, y + 13, search.isEmpty() ? MUTED : VALUE);
        }
        if (!search.isEmpty()) {
            String count = String.valueOf(matches);
            text(context, count, x + w - 8 - textRenderer.getWidth(count), y + 4, matches == 0 ? 0xFFD06A78 : MUTED);
        }
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY, float dt) {
        int x = width - MARGIN;
        List<Tab> tabs = Tabs.get();
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            boolean current = tab.name.equalsIgnoreCase("Modules");
            int w = textRenderer.getWidth(tab.name);
            x -= w;
            boolean over = !closing && mouseX >= x - 6 && mouseX < x + w + 6 && mouseY >= 8 && mouseY < 24;
            float h = animate(hover, tab, current || over ? 1 : 0, dt, 14);
            text(context, tab.name, x, 12, blend(MUTED, BRIGHT, h));
            if (current) fill(context, x, 22, x + w, 23, accent);
            else {
                if (h > 0.01f) fill(context, x + Math.round(w * (1 - h) / 2), 22, x + w - Math.round(w * (1 - h) / 2), 23, alpha(accent, 0.5f * h));
                hits.add(new Hit(x - 6, 8, w + 12, 16, Kind.TAB, tab, null));
            }
            x -= 14;
        }
    }

    private void drawPanel(DrawContext context, Panel panel, int index, int mouseX, int mouseY, float dt, long now, float global) {
        boolean searching = !query.isEmpty();
        if (searching && panel.shown.isEmpty()) return;

        float lift;
        if (closing) {
            fade = global;
            lift = (1 - global) * 6;
        } else {
            float t = ease(MathHelper.clamp((now - openedAt - index * STAGGER_MS) / OPEN_MS, 0, 1));
            fade = t;
            lift = (1 - t) * 10;
        }

        if (!panel.placed || dragging == panel) {
            panel.ax = panel.x;
            panel.ay = panel.y;
            panel.placed = true;
        } else {
            panel.ax += (panel.x - panel.ax) * Math.min(1, dt * 16);
            panel.ay += (panel.y - panel.ay) * Math.min(1, dt * 16);
            if (Math.abs(panel.x - panel.ax) < 0.3f) panel.ax = panel.x;
            if (Math.abs(panel.y - panel.ay) < 0.3f) panel.ay = panel.y;
        }

        int x = Math.round(panel.ax);
        int y = Math.round(panel.ay + lift);

        boolean folded = panel.collapsed && !searching;
        panel.fold = step(panel.fold, folded ? 0 : 1, dt, EXPAND_MS);
        float unfold = smooth(panel.fold);

        int bodyHeight = 0;
        if (panel.fold > 0) {
            int body = INNER * 2;
            for (Module module : panel.shown) body += ROW + Math.round(settingsHeight(module) * openness(module, dt));
            panel.contentHeight = body;
            int limit = height - y - HEADER - 26;
            if (panel.maxView > 0) limit = Math.min(limit, panel.maxView);
            panel.viewHeight = Math.min(body, Math.max(ROW * 3, limit));
            panel.scrollTarget = MathHelper.clamp(panel.scrollTarget, 0, Math.max(0, body - panel.viewHeight));
            panel.scroll += (panel.scrollTarget - panel.scroll) * Math.min(1, dt * 18);
            if (Math.abs(panel.scrollTarget - panel.scroll) < 0.1f) panel.scroll = panel.scrollTarget;
            bodyHeight = Math.round(panel.viewHeight * unfold);
        }

        boolean overPanel = !busy() && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEADER + bodyHeight;
        panel.glow += ((overPanel || dragging == panel ? 1 : 0) - panel.glow) * Math.min(1, dt * 10);

        int active = 0;
        for (Module module : panel.modules) if (module.isActive()) active++;

        boolean flat = bodyHeight <= 0;
        round(context, x, y, x + WIDTH, y + HEADER, HEAD, CORNER, true, flat);
        if (!flat) round(context, x, y + HEADER, x + WIDTH, y + HEADER + bodyHeight, PANEL, CORNER, false, true);
        int edge = blend(OUTLINE, alpha(accent, 0.55f), dragging == panel ? 1 : panel.glow * 0.3f);
        outline(context, x, y, x + WIDTH, y + HEADER + bodyHeight, edge, CORNER);

        text(context, panel.category.name, x + PAD, y + 6, TITLE);
        String total = "/" + panel.modules.size();
        String enabled = String.valueOf(active);
        int totalX = x + WIDTH - PAD - textRenderer.getWidth(total);
        text(context, total, totalX, y + 6, MUTED);
        text(context, enabled, totalX - textRenderer.getWidth(enabled), y + 6, active > 0 ? accentText : MUTED);
        if (!flat) fill(context, x + PAD, y + HEADER - 1, x + WIDTH - PAD, y + HEADER, alpha(accent, 0.55f * unfold));
        hits.add(new Hit(x, y, WIDTH, HEADER, Kind.HEADER, panel, null));
        if (flat) return;

        int top = y + HEADER;
        int bottom = top + bodyHeight;
        hits.add(new Hit(x, top, WIDTH, bodyHeight, Kind.PANEL, panel, null));

        context.enableScissor(x, top, x + WIDTH, bottom);
        int row = top + INNER - Math.round(panel.scroll);
        for (Module module : panel.shown) {
            if (row >= bottom) break;
            row = drawModule(context, module, x, row, top, bottom, mouseX, mouseY, dt);
        }
        context.disableScissor();

        int body = panel.contentHeight;
        if (body > panel.viewHeight) {
            float ratio = panel.viewHeight / (float) body;
            int barHeight = Math.max(12, Math.round((bodyHeight - 4) * ratio));
            int barY = top + 2 + Math.round((bodyHeight - 4 - barHeight) * (panel.scroll / Math.max(1, body - panel.viewHeight)));
            fill(context, x + WIDTH - 3, barY, x + WIDTH - 2, barY + barHeight, alpha(accent, 0.25f + 0.45f * panel.glow));
        }
    }

    private int drawModule(DrawContext context, Module module, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        boolean visible = y + ROW > top && y < bottom;
        boolean over = visible && !busy() && mouseX >= x && mouseX < x + WIDTH && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW, bottom);
        float h = animate(hover, module, over ? 1 : 0, dt, 14);
        float e = animate(on, module, module.isActive() ? 1 : 0, dt, 12);
        float[] state = open.get(module);
        float openness = state == null ? 0 : state[1];

        if (visible) {
            int left = x + INDENT;
            int right = x + WIDTH - INDENT;
            if (h > 0.01f) round(context, left, y + 1, right, y + ROW - 1, alpha(HOVER, h), SMALL, true, true);
            if (e > 0.01f) {
                round(context, left, y + 1, right, y + ROW - 1, alpha(accent, 0.13f * e), SMALL, true, true);
                fill(context, left, y + 4, left + 2, y + ROW - 4, alpha(accent, e));
            }
            int color = blend(blend(TEXT, accentText, e), BRIGHT, h * 0.35f);
            int tx = left + 5 + Math.round(e * 2);
            drawTitle(context, module.title, tx, y + 4, WIDTH - INDENT * 2 - 20, color);
            chevron(context, right - 8, y + ROW / 2, openness, alpha(blend(MUTED, TEXT, h), 0.45f + 0.55f * Math.max(h, openness)));
            hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + ROW, bottom) - Math.max(y, top), Kind.MODULE, module, null));
            if (over) setHint(module, module.title, module.description);
        }
        y += ROW;

        if (openness <= 0.001f) return y;

        int full = settingsHeight(module);
        int shown = Math.round(full * openness);
        int clipTop = Math.max(y, top);
        int clipBottom = Math.min(y + shown, bottom);
        if (clipBottom > clipTop) {
            context.enableScissor(x, clipTop, x + WIDTH, clipBottom);
            round(context, x + INDENT, y, x + WIDTH - INDENT, y + shown, TRAY, SMALL, true, true);
            fill(context, x + INDENT, y + 2, x + INDENT + 1, y + shown - 2, alpha(accent, 0.45f));
            drawSettings(context, module, x, y + 2, clipTop, clipBottom, mouseX, mouseY, dt);
            context.disableScissor();
        }
        return y + shown;
    }

    private void drawTitle(DrawContext context, String title, int x, int y, int max, int color) {
        String shown = trim(title, max);
        int at = query.isEmpty() || shown != title ? -1 : title.toLowerCase(Locale.ROOT).indexOf(query);
        if (at < 0) {
            text(context, shown, x, y, color);
            return;
        }
        String before = title.substring(0, at);
        String match = title.substring(at, at + query.length());
        text(context, before, x, y, color);
        int mx = x + textRenderer.getWidth(before);
        text(context, match, mx, y, accentText);
        fill(context, mx, y + 9, mx + textRenderer.getWidth(match), y + 10, alpha(accent, 0.6f));
        text(context, title.substring(at + query.length()), mx + textRenderer.getWidth(match), y, color);
    }

    private void drawSettings(DrawContext context, Module module, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt) {
        int left = x + INDENT + 7;
        int right = x + WIDTH - INDENT - 5;

        y = drawRow(context, module, Kind.MODULE_BIND, x, y, top, bottom, mouseX, mouseY, dt, "Bind", bindText(module.keybind, module), null);

        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            if (visibleCount(group) == 0) continue;
            if (groups) {
                if (y + SETTING > top && y < bottom) {
                    String name = group.name.toUpperCase(Locale.ROOT);
                    String label = trim(name, right - left - 8);
                    text(context, label, left, y + 4, MUTED);
                    int line = left + textRenderer.getWidth(label) + 5;
                    if (line < right) fill(context, line, y + 8, right, y + 9, OUTLINE);
                }
                y += SETTING;
            }
            for (Setting<?> setting : group) {
                if (!setting.isVisible()) continue;
                if (y >= bottom) return;
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
                rowHover(context, x, y, h);
                text(context, trim(setting.title, right - left - 18), left, y + 3, blend(LABEL, VALUE, Math.max(h, e * 0.5f)));
                int sx = right - 13;
                int sy = y + 3;
                round(context, sx, sy, right, sy + 7, blend(OFF, alpha(accent, 0.9f), e), CORNER, true, true);
                int kx = sx + 1 + Math.round(6 * e);
                round(context, kx, sy + 1, kx + 5, sy + 6, blend(KNOB, BRIGHT, e), SMALL, true, true);
                hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), Kind.BOOL, setting, module));
                if (over) setHint(setting, setting.title, setting.description);
            }
            return y + SETTING;
        }

        if (setting instanceof DoubleSetting || setting instanceof IntSetting) {
            boolean visible = y + SETTING > top && y < bottom;
            boolean over = visible && within(mouseX, mouseY, x, y, top, bottom);
            float h = animate(hover, setting, over || sliding == setting ? 1 : 0, dt, 14);
            float t = animate(on, setting, sliderFraction(setting), dt, 20);
            if (visible) {
                rowHover(context, x, y, h);
                String value = valueText(setting);
                int valueWidth = textRenderer.getWidth(value);
                text(context, trim(setting.title, right - left - valueWidth - 4), left, y + 2, blend(LABEL, VALUE, h));
                text(context, value, right - valueWidth, y + 2, blend(VALUE, BRIGHT, h));
                int ty = y + SETTING - 3;
                int fx = left + Math.round((right - left) * MathHelper.clamp(t, 0, 1));
                fill(context, left, ty, right, ty + 2, TRACK);
                fill(context, left, ty, fx, ty + 2, accent);
                if (h > 0.01f) fill(context, Math.max(left, fx - 1), ty - 1, Math.min(right, fx + 1), ty + 3, alpha(BRIGHT, h));
                hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), Kind.SLIDER, setting, new int[]{left, right}));
                if (over) setHint(setting, setting.title, setting.description);
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
                round(context, right - 10, y + 3, right, y + 10, color.get().getPacked() | 0xFF000000, SMALL, true, true);
                outline(context, right - 11, y + 2, right + 1, y + 11, BOX, SMALL);
            }
            return end;
        }

        return drawRow(context, module, Kind.MORE, x, y, top, bottom, mouseX, mouseY, dt, setting.title, "›", setting);
    }

    private int drawRow(DrawContext context, Module module, Kind kind, int x, int y, int top, int bottom, int mouseX, int mouseY, float dt, String label, String value, Setting<?> setting) {
        boolean visible = y + SETTING > top && y < bottom;
        Object key = setting != null ? setting : kind == Kind.MODULE_BIND ? module.keybind : module.settings;
        boolean over = visible && within(mouseX, mouseY, x, y, top, bottom);
        float h = animate(hover, key, over ? 1 : 0, dt, 14);
        if (visible) {
            rowHover(context, x, y, h);
            int left = x + INDENT + 7;
            int right = x + WIDTH - INDENT - 5;
            int valueWidth = textRenderer.getWidth(value);
            boolean bind = kind == Kind.BIND || kind == Kind.MODULE_BIND;
            boolean active = binding != null && binding == (kind == Kind.MODULE_BIND ? module : setting);
            int labelMax = right - left - valueWidth - (bind ? 8 : 4);
            text(context, trim(label, labelMax), left, y + 3, blend(LABEL, VALUE, h));
            if (bind) {
                round(context, right - valueWidth - 3, y + 1, right + 3, y + SETTING - 1, active ? alpha(accent, 0.35f) : blend(PILL, 0x2CFFFFFF, h), SMALL, true, true);
            }
            int valueColor = active ? accentText : kind == Kind.ENUM ? blend(VALUE, accentText, 0.45f) : kind == Kind.MORE ? blend(MUTED, VALUE, h) : VALUE;
            text(context, value, right - valueWidth, y + 3, valueColor);
            hits.add(new Hit(x, Math.max(y, top), WIDTH, Math.min(y + SETTING, bottom) - Math.max(y, top), kind, setting != null ? setting : module, module));
            if (over) {
                if (setting != null) setHint(setting, setting.title, setting.description);
                else if (kind == Kind.MODULE_BIND) setHint(key, "Bind", "Key that toggles " + module.title + ". Click, then press a key. Escape clears it.");
                else setHint(key, "All settings", "Opens every setting of " + module.title + " in a window.");
            }
        }
        return y + SETTING;
    }

    private void rowHover(DrawContext context, int x, int y, float h) {
        if (h > 0.01f) fill(context, x + INDENT + 1, y, x + WIDTH - INDENT, y + SETTING, alpha(HOVER, h));
    }

    private void chevron(DrawContext context, int x, int cy, float openness, int color) {
        if (openness > 0.5f) {
            fill(context, x, cy - 1, x + 1, cy, color);
            fill(context, x + 1, cy, x + 2, cy + 1, color);
            fill(context, x + 2, cy + 1, x + 3, cy + 2, color);
            fill(context, x + 3, cy, x + 4, cy + 1, color);
            fill(context, x + 4, cy - 1, x + 5, cy, color);
        } else {
            fill(context, x + 1, cy - 2, x + 2, cy - 1, color);
            fill(context, x + 2, cy - 1, x + 3, cy, color);
            fill(context, x + 3, cy, x + 4, cy + 1, color);
            fill(context, x + 2, cy + 1, x + 3, cy + 2, color);
            fill(context, x + 1, cy + 2, x + 2, cy + 3, color);
        }
    }

    private void setHint(Object key, String title, String text) {
        hoverKey = key;
        hintTitle = title;
        hint = text;
    }

    private void drawDescription(DrawContext context, int mouseX, int mouseY, long now, float dt) {
        if (hoverKey != lastHoverKey) {
            lastHoverKey = hoverKey;
            hoverSince = now;
        }
        boolean ready = hoverKey != null && hint != null && !hint.isEmpty() && !busy() && binding == null
            && (now - hoverSince >= HINT_DELAY || tipAlpha > 0.2f);
        if (ready && (!hint.equals(tipText) || !hintTitle.equals(tipTitle))) {
            tipText = hint;
            tipTitle = hintTitle;
            tipLines = textRenderer.wrapLines(StringVisitable.plain(hint), TIP_WIDTH);
            tipWidth = textRenderer.getWidth(tipTitle);
            for (OrderedText line : tipLines) tipWidth = Math.max(tipWidth, textRenderer.getWidth(line));
        }
        tipAlpha += ((ready ? 1 : 0) - tipAlpha) * Math.min(1, dt * 14);
        if (tipAlpha < 0.02f || tipLines == null) return;

        int w = tipWidth + 16;
        int h = 8 + 10 + 3 + tipLines.size() * 10 + 4;
        int x = mouseX + 12;
        int y = mouseY + 10;
        if (x + w > width - 4) x = Math.max(4, mouseX - 12 - w);
        if (y + h > height - 4) y = Math.max(4, height - 4 - h);

        fade *= tipAlpha;
        round(context, x, y, x + w, y + h, TIP, CORNER, true, true);
        outline(context, x, y, x + w, y + h, OUTLINE, CORNER);
        fill(context, x + 1, y + 5, x + 2, y + h - 5, alpha(accent, 0.8f));
        text(context, tipTitle, x + 8, y + 6, accentText);
        fill(context, x + 8, y + 18, x + w - 8, y + 19, OUTLINE);
        int line = y + 23;
        int color = alpha(VALUE, fade);
        if ((color >>> 24) < 6) return;
        for (OrderedText text : tipLines) {
            context.drawText(textRenderer, text, x + 8, line, color, false);
            line += 10;
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (closing) return true;
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
        if (closing) return true;
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
        if (closing) return true;
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
            if (!search.isEmpty()) {
                if ((input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) search = "";
                else search = search.substring(0, search.length() - 1);
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (closing || binding != null || !input.isValidChar()) return false;
        if (search.length() < 24) search += input.asString();
        return true;
    }

    @Override
    public void close() {
        saveLayout();
        if (closing) return;
        closing = true;
        closedAt = Util.getMeasuringTimeMs();
        dragging = null;
        sliding = null;
        binding = null;
    }

    @Override
    public void removed() {
        saveLayout();
        super.removed();
    }

    private boolean busy() {
        return dragging != null || sliding != null || closing;
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

    private static int visibleCount(SettingGroup group) {
        int count = 0;
        for (Setting<?> setting : group) if (setting.isVisible()) count++;
        return count;
    }

    private int settingsHeight(Module module) {
        int rows = 2;
        boolean groups = module.settings.groups.size() > 1;
        for (SettingGroup group : module.settings.groups) {
            int visible = visibleCount(group);
            if (visible == 0) continue;
            rows += visible + (groups ? 1 : 0);
        }
        return rows * SETTING + 4;
    }

    private float openness(Module module, float dt) {
        boolean target = expanded.contains(module);
        float[] state = open.get(module);
        if (state == null) {
            if (!target) return 0;
            state = new float[2];
            open.put(module, state);
        }
        state[0] = step(state[0], target ? 1 : 0, dt, EXPAND_MS);
        state[1] = smooth(state[0]);
        if (state[0] <= 0 && !target) {
            open.remove(module);
            return 0;
        }
        return state[1];
    }

    private static float animate(Map<Object, float[]> map, Object key, float target, float dt, float speed) {
        float[] value = map.get(key);
        if (value == null) {
            map.put(key, new float[]{target});
            return target;
        }
        value[0] += (target - value[0]) * Math.min(1, dt * speed);
        return value[0];
    }

    private static float step(float value, float target, float dt, float duration) {
        float amount = dt * 1000f / duration;
        return value < target ? Math.min(target, value + amount) : Math.max(target, value - amount);
    }

    private boolean within(int mouseX, int mouseY, int x, int y, int top, int bottom) {
        return !busy() && mouseX >= x && mouseX < x + WIDTH && mouseY >= Math.max(y, top) && mouseY < Math.min(y + SETTING, bottom);
    }

    private String trim(String text, int max) {
        if (textRenderer.getWidth(text) <= max) return text;
        String dots = "…";
        int end = text.length();
        while (end > 0 && textRenderer.getWidth(text.substring(0, end)) + textRenderer.getWidth(dots) > max) end--;
        return text.substring(0, end) + dots;
    }

    private void text(DrawContext context, String text, int x, int y, int color) {
        int c = alpha(color, fade);
        if ((c >>> 24) < 6 || text.isEmpty()) return;
        context.drawText(textRenderer, text, x, y, c, false);
    }

    private void fill(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int c = alpha(color, fade);
        if ((c >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
        context.fill(x1, y1, x2, y2, c);
    }

    private void round(DrawContext context, int x1, int y1, int x2, int y2, int color, int[] corner, boolean top, boolean bottom) {
        int c = alpha(color, fade);
        if ((c >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
        int n = Math.min(corner.length, (y2 - y1) / 2);
        int t = top ? n : 0;
        int b = bottom ? n : 0;
        for (int i = 0; i < t; i++) context.fill(x1 + corner[i], y1 + i, x2 - corner[i], y1 + i + 1, c);
        if (y2 - b > y1 + t) context.fill(x1, y1 + t, x2, y2 - b, c);
        for (int i = 0; i < b; i++) context.fill(x1 + corner[i], y2 - 1 - i, x2 - corner[i], y2 - i, c);
    }

    private void outline(DrawContext context, int x1, int y1, int x2, int y2, int color, int[] corner) {
        int c = alpha(color, fade);
        if ((c >>> 24) == 0 || x2 - x1 < 2 || y2 - y1 < 2) return;
        int n = Math.min(corner.length, (y2 - y1) / 2);
        if (n == 0) {
            context.drawStrokedRectangle(x1, y1, x2 - x1, y2 - y1, c);
            return;
        }
        context.fill(x1 + corner[0], y1, x2 - corner[0], y1 + 1, c);
        context.fill(x1 + corner[0], y2 - 1, x2 - corner[0], y2, c);
        for (int i = 1; i < n; i++) {
            int w = Math.max(1, corner[i - 1] - corner[i]);
            context.fill(x1 + corner[i], y1 + i, x1 + corner[i] + w, y1 + i + 1, c);
            context.fill(x2 - corner[i] - w, y1 + i, x2 - corner[i], y1 + i + 1, c);
            context.fill(x1 + corner[i], y2 - 1 - i, x1 + corner[i] + w, y2 - i, c);
            context.fill(x2 - corner[i] - w, y2 - 1 - i, x2 - corner[i], y2 - i, c);
        }
        context.fill(x1, y1 + n, x1 + 1, y2 - n, c);
        context.fill(x2 - 1, y1 + n, x2, y2 - n, c);
    }

    private static float ease(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static float smooth(float t) {
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
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
