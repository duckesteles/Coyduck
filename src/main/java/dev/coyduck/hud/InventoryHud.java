package dev.coyduck.hud;

import dev.coyduck.render.Palette;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.minecraft.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class InventoryHud extends HudElement {
    public static final HudElementInfo<InventoryHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-inventory", "Your inventory.", InventoryHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar")
        .description("Adds the hotbar as a fourth row.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> slots = sgGeneral.add(new BoolSetting.Builder()
        .name("slots")
        .description("Draws a faint square behind every slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the items.")
        .defaultValue(1)
        .range(0.5, 3)
        .sliderRange(0.5, 2)
        .build()
    );

    private final CoyHud.Background background = new CoyHud.Background(settings);

    public InventoryHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        float icon = (float) (r.textHeight() * 1.7 / 16 * scale.get());
        double size = 16 * icon;
        double gap = Math.max(1, 2 * icon);
        double pad = Math.max(2, 3 * icon);
        int rows = hotbar.get() ? 4 : 3;
        double w = pad * 2 + 9 * size + 8 * gap;
        double h = pad * 2 + rows * size + (rows - 1) * gap + (hotbar.get() ? gap : 0);
        setSize(w, h);
        int x = box.getRenderX();
        int y = box.getRenderY();

        CoyHud.panel(r, x, y, w, h, background.alpha());
        if (mc.player == null) return;

        for (int row = 0; row < rows; row++) {
            double rowY = y + pad + row * (size + gap) + (row == 3 ? gap : 0);
            for (int column = 0; column < 9; column++) {
                double cellX = x + pad + column * (size + gap);
                if (slots.get()) r.quad(cellX, rowY, size, size, CoyHud.color(Palette.TRACK));
                int index = row == 3 ? column : 9 + row * 9 + column;
                ItemStack stack = mc.player.getInventory().getStack(index);
                if (stack.isEmpty()) continue;
                int ix = (int) Math.round(cellX);
                int iy = (int) Math.round(rowY);
                r.post(() -> r.item(stack, ix, iy, icon, true));
            }
        }
    }
}
