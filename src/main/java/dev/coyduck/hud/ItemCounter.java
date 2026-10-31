package dev.coyduck.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ItemCounter extends HudElement {
    public static final HudElementInfo<ItemCounter> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-items", "Totems, crystals, gapples, XP and obsidian you carry.", ItemCounter::new);

    private static final Item[][] GROUPS = {
        {Items.TOTEM_OF_UNDYING},
        {Items.END_CRYSTAL},
        {Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE},
        {Items.EXPERIENCE_BOTTLE},
        {Items.OBSIDIAN}
    };

    public ItemCounter() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        double pad = 5;
        double cell = 30;
        double w = pad * 2 + cell * GROUPS.length - 4;
        double h = 22;
        setSize(w, h);
        CoyHud.panel(r, x, y, w, h, 1);

        double cx = x + pad;
        for (int i = 0; i < GROUPS.length; i++) {
            Item[] group = GROUPS[i];
            int count = count(group);
            ItemStack icon = new ItemStack(group[0]);
            int ix = (int) cx;
            int iy = (int) (y + 3);
            r.post(() -> r.item(icon, ix, iy, 1f, false));
            String text = count > 999 ? (count / 1000) + "k" : String.valueOf(count);
            int color = count == 0 ? (i == 0 ? CoyHud.BAD : CoyHud.MUTED) : (i == 0 && count <= 2 ? CoyHud.WARN : CoyHud.TEXT);
            r.text(text, cx + 15, y + h - r.textHeight() - 3, CoyHud.color(color), false);
            cx += cell;
        }
    }

    private static int count(Item[] items) {
        if (mc.player == null) return 0;
        int total = 0;
        var inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            for (Item item : items) if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }
}
