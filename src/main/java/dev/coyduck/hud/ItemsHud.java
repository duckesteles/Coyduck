package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ItemsHud extends HudElement {
    public static final HudElementInfo<ItemsHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-items", "Counts of your fight items.", ItemsHud::new);

    public enum Layout {
        Horizontal,
        Vertical
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items to count, in this order.")
        .defaultValue(Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.OBSIDIAN, Items.ENCHANTED_GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE, Items.ENDER_PEARL, Items.COBWEB)
        .build()
    );

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("One row or one column.")
        .defaultValue(Layout.Horizontal)
        .build()
    );

    private final Setting<Boolean> empty = sgGeneral.add(new BoolSetting.Builder()
        .name("show-empty")
        .description("Keeps items you have none of, with a red zero.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> low = sgGeneral.add(new IntSetting.Builder()
        .name("low")
        .description("Counts at or below this turn yellow.")
        .defaultValue(2)
        .range(0, 64)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the items and numbers.")
        .defaultValue(1)
        .range(0.5, 3)
        .sliderRange(0.5, 2)
        .build()
    );

    private final CoyHud.Background background = new CoyHud.Background(settings);

    private record Entry(ItemStack stack, int count, String text) {}

    public ItemsHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        double s = scale.get();
        float icon = (float) (r.textHeight() * 1.7 / 16 * s);
        double size = 16 * icon;
        double pad = Math.max(2, 3 * icon);
        double gap = 4 * s;
        double textGap = 2 * s;
        double textHeight = r.textHeight(false, s);

        List<Entry> entries = entries();
        boolean vertical = layout.get() == Layout.Vertical;
        double countWidth = 0;
        for (Entry entry : entries) countWidth = Math.max(countWidth, r.textWidth(entry.text, false, s));
        double cell = size + textGap + countWidth;

        double w;
        double h;
        if (entries.isEmpty()) {
            w = r.textWidth("Items", false, s) + pad * 2;
            h = textHeight + pad * 2;
        } else if (vertical) {
            w = pad * 2 + cell;
            h = pad * 2 + entries.size() * size + (entries.size() - 1) * gap / 2;
        } else {
            w = pad * 2 + entries.size() * cell + (entries.size() - 1) * gap;
            h = pad * 2 + size;
        }
        setSize(w, h);
        int x = box.getRenderX();
        int y = box.getRenderY();
        CoyHud.panel(r, x, y, w, h, background.alpha());
        if (entries.isEmpty()) {
            if (isInEditor()) r.text("Items", x + pad, y + pad, CoyHud.color(CoyHud.MUTED), background.shadow(), s);
            return;
        }

        boolean right = vertical && getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            double cellX = vertical ? x + pad : x + pad + i * (cell + gap);
            double cellY = vertical ? y + pad + i * (size + gap / 2) : y + pad;
            double iconX = right ? x + w - pad - size : cellX;
            double textX = right ? iconX - textGap - r.textWidth(entry.text, false, s) : cellX + size + textGap;
            int ix = (int) Math.round(iconX);
            int iy = (int) Math.round(cellY);
            ItemStack stack = entry.stack;
            r.post(() -> r.item(stack, ix, iy, icon, false, ""));
            int color = entry.count == 0 ? CoyHud.BAD : entry.count <= low.get() ? CoyHud.WARN : CoyHud.TEXT;
            r.text(entry.text, textX, cellY + (size - textHeight) / 2, CoyHud.color(color), background.shadow(), s);
        }
    }

    private List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        for (Item item : items.get()) {
            int count = count(item);
            if (count == 0 && !empty.get()) continue;
            list.add(new Entry(item.getDefaultStack(), count, String.valueOf(count)));
        }
        return list;
    }

    private static int count(Item item) {
        if (mc.player == null) return 0;
        PlayerInventory inventory = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(item)) total += offhand.getCount();
        return total;
    }
}
