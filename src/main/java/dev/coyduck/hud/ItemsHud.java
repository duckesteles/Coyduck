package dev.coyduck.hud;

import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.ItemListSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Panel;
import dev.coyduck.widget.Widget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

import static dev.coyduck.core.Core.mc;

public class ItemsHud extends Widget {
    public static final Info INFO = new Info("coy-items", "Counts of your fight items.", ItemsHud::new);

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
    public void render(Panel r) {
        double s = scale.get();
        float icon = (float) (r.textHeight(false, Panel.DEFAULT_SCALE) * 1.7 / 16 * s);
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
        size(w, h);
        int x = x();
        int y = y();
        CoyHud.panel(r, x, y, w, h, background.alpha());
        if (entries.isEmpty()) {
            if (inEditor()) r.text("Items", x + pad, y + pad, Palette.MUTED, background.shadow(), s);
            return;
        }

        boolean right = vertical && rightSide();
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
            int color = entry.count == 0 ? Palette.BAD : entry.count <= low.get() ? Palette.WARN : Palette.TEXT;
            r.text(entry.text, textX, cellY + (size - textHeight) / 2, (color), background.shadow(), s);
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
