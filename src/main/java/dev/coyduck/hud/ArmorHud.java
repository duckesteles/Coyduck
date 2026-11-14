package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ArmorHud extends HudElement {
    public static final HudElementInfo<ArmorHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-armor", "Your armor with how much durability each piece has left.", ArmorHud::new);

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final ItemStack[] PLACEHOLDERS = {
        Items.NETHERITE_HELMET.getDefaultStack(),
        Items.NETHERITE_CHESTPLATE.getDefaultStack(),
        Items.NETHERITE_LEGGINGS.getDefaultStack(),
        Items.NETHERITE_BOOTS.getDefaultStack()
    };

    public enum Layout {
        Horizontal,
        Vertical
    }

    public enum Durability {
        Percent,
        Remaining,
        None
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("One row or one column.")
        .defaultValue(Layout.Horizontal)
        .build()
    );

    private final Setting<Durability> durability = sgGeneral.add(new EnumSetting.Builder<Durability>()
        .name("durability")
        .description("How the durability left is written.")
        .defaultValue(Durability.Percent)
        .build()
    );

    private final Setting<Boolean> bar = sgGeneral.add(new BoolSetting.Builder()
        .name("bar")
        .description("Draws a thin durability bar under each piece.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> empty = sgGeneral.add(new BoolSetting.Builder()
        .name("show-empty")
        .description("Keeps a slot for missing pieces.")
        .defaultValue(true)
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

    public ArmorHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        double s = scale.get();
        float icon = (float) (r.textHeight() * 1.7 / 16 * s);
        double size = 16 * icon;
        double pad = Math.max(2, 3 * icon);
        double gap = 4 * s;
        double textHeight = r.textHeight(false, s);
        boolean vertical = layout.get() == Layout.Vertical;
        boolean text = durability.get() != Durability.None;
        double barHeight = bar.get() ? Math.max(1, 2 * s) : 0;

        ItemStack[] stacks = new ItemStack[SLOTS.length];
        String[] labels = new String[SLOTS.length];
        double labelWidth = 0;
        int shownCount = 0;
        boolean editor = mc.player == null;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = editor ? PLACEHOLDERS[i] : mc.player.getEquippedStack(SLOTS[i]);
            stacks[i] = stack;
            if (stack.isEmpty() && !empty.get()) continue;
            shownCount++;
            labels[i] = text && !stack.isEmpty() && stack.isDamageable() ? label(stack) : null;
            if (labels[i] != null) labelWidth = Math.max(labelWidth, r.textWidth(labels[i], false, s));
        }

        double cellWidth = vertical ? size + (labelWidth > 0 ? gap / 2 + labelWidth : 0) : Math.max(size, labelWidth);
        double cellHeight = vertical ? size + barHeight : size + barHeight + (labelWidth > 0 ? textHeight + 1 : 0);
        int count = Math.max(shownCount, 1);
        double w = pad * 2 + (vertical ? cellWidth : count * cellWidth + (count - 1) * gap);
        double h = pad * 2 + (vertical ? count * cellHeight + (count - 1) * gap / 2 : cellHeight);
        setSize(w, h);
        int x = box.getRenderX();
        int y = box.getRenderY();
        CoyHud.panel(r, x, y, w, h, background.alpha());

        boolean right = vertical && getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
        int index = 0;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = stacks[i];
            if (stack.isEmpty() && !empty.get()) continue;
            double cellX = vertical ? x + pad : x + pad + index * (cellWidth + gap);
            double cellY = vertical ? y + pad + index * (cellHeight + gap / 2) : y + pad;
            double iconX = vertical ? (right ? x + w - pad - size : cellX) : cellX + (cellWidth - size) / 2;
            index++;

            if (stack.isEmpty()) {
                r.quad(iconX, cellY, size, size, CoyHud.color(CoyHud.TRACK));
                continue;
            }
            int ix = (int) Math.round(iconX);
            int iy = (int) Math.round(cellY);
            r.post(() -> r.item(stack, ix, iy, icon, false, ""));

            double fraction = stack.isDamageable() ? 1 - (double) stack.getDamage() / stack.getMaxDamage() : 1;
            int color = CoyHud.health(fraction);
            if (bar.get() && stack.isDamageable()) {
                double barY = cellY + size + barHeight * 0.25;
                r.quad(iconX, barY, size, barHeight, CoyHud.color(CoyHud.TRACK));
                r.quad(iconX, barY, size * fraction, barHeight, CoyHud.color(color));
            }
            String label = labels[i];
            if (label == null) continue;
            double labelX;
            double labelY;
            if (vertical) {
                double lw = r.textWidth(label, false, s);
                labelX = right ? iconX - gap / 2 - lw : iconX + size + gap / 2;
                labelY = cellY + (size - textHeight) / 2;
            } else {
                labelX = cellX + (cellWidth - r.textWidth(label, false, s)) / 2;
                labelY = cellY + size + barHeight + 1;
            }
            r.text(label, labelX, labelY, CoyHud.color(color), false, s);
        }
    }

    private String label(ItemStack stack) {
        int left = stack.getMaxDamage() - stack.getDamage();
        if (durability.get() == Durability.Remaining) return String.valueOf(left);
        return String.format(Locale.ROOT, "%d%%", Math.round(100.0 * left / stack.getMaxDamage()));
    }
}
