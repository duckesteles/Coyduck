package dev.coyduck.hud;

import dev.coyduck.core.Core;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.PopTracker;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Panel;
import dev.coyduck.widget.Widget;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static dev.coyduck.core.Core.mc;

public class PlayersHud extends Widget {
    public static final Info INFO = new Info("coy-players", "Nearby players.", PlayersHud::new);

    public enum Show {
        Everyone,
        Friends,
        Others
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final CoyHud.Background background = new CoyHud.Background(settings);

    private final Setting<Show> show = sgGeneral.add(new EnumSetting.Builder<Show>()
        .name("show")
        .description("Who to list.")
        .defaultValue(Show.Everyone)
        .build()
    );

    private final Setting<Boolean> heads = sgGeneral.add(new BoolSetting.Builder()
        .name("heads")
        .description("Shows each player's head.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> distance = sgGeneral.add(new BoolSetting.Builder()
        .name("distance")
        .description("Shows how far away each player is.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> health = sgGeneral.add(new BoolSetting.Builder()
        .name("health")
        .description("Shows each player's health.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pops = sgGeneral.add(new BoolSetting.Builder()
        .name("pops")
        .description("Shows how many totems each player popped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> elytra = sgGeneral.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Marks players wearing an elytra.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bots = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-bots")
        .description("Leaves out players missing from the tab list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-nakeds")
        .description("Leaves out players wearing no armor.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Only players closer than this.")
        .defaultValue(256)
        .range(4, 1024)
        .sliderRange(16, 512)
        .build()
    );

    private final Setting<Integer> limit = sgGeneral.add(new IntSetting.Builder()
        .name("limit")
        .description("Most players to list.")
        .defaultValue(8)
        .range(1, 40)
        .sliderRange(1, 20)
        .build()
    );

    private record Part(String text, int color) {}

    private record Row(PlayerEntity player, String name, List<Part> parts, boolean friend) {}

    public PlayersHud() {
        super(INFO);
    }

    @Override
    public void render(Panel r) {
        List<Row> rows = rows();
        double pad = 5;
        double gap = 8;
        double line = r.textHeight(false, Panel.DEFAULT_SCALE) + 4;
        double head = heads.get() ? r.textHeight(false, Panel.DEFAULT_SCALE) + 1 : 0;
        double headGap = heads.get() ? 4 : 0;

        double nameWidth = 0;
        double sideWidth = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, r.textWidth(row.name, false, Panel.DEFAULT_SCALE));
            sideWidth = Math.max(sideWidth, partsWidth(r, row.parts));
        }
        if (rows.isEmpty()) nameWidth = r.textWidth("No players", false, Panel.DEFAULT_SCALE);

        double w = pad * 2 + head + headGap + nameWidth + (sideWidth > 0 ? gap + sideWidth : 0);
        double h = pad * 2 + line * Math.max(1, rows.size()) - 4;
        size(w, h);
        int x = x();
        int y = y();

        if (rows.isEmpty()) {
            if (!inEditor()) return;
            CoyHud.panel(r, x, y, w, h, background.alpha());
            r.text("No players", x + pad, y + pad, Palette.MUTED, background.shadow(), Panel.DEFAULT_SCALE);
            return;
        }

        CoyHud.panel(r, x, y, w, h, background.alpha());
        boolean right = rightSide();
        double rowY = y + pad;
        for (Row row : rows) {
            double nameX = x + pad + head + headGap;
            if (heads.get() && row.player instanceof AbstractClientPlayerEntity client) {
                SkinTextures skin = client.getSkin();
                int hx = (int) Math.round(x + pad);
                int hy = (int) Math.round(rowY - 0.5);
                int size = (int) Math.round(head);
                r.post(() -> drawHead(r, skin, hx, hy, size));
            }
            r.text(row.name, nameX, rowY, (row.friend ? Palette.GOOD : Palette.TEXT), background.shadow(), Panel.DEFAULT_SCALE);

            double sideX = right ? x + w - pad : x + pad + head + headGap + nameWidth + gap;
            double px = right ? sideX - partsWidth(r, row.parts) : sideX;
            for (int i = 0; i < row.parts.size(); i++) {
                Part part = row.parts.get(i);
                if (i > 0) px += 6;
                r.text(part.text, px, rowY, (part.color), background.shadow(), Panel.DEFAULT_SCALE);
                px += r.textWidth(part.text, false, Panel.DEFAULT_SCALE);
            }
            rowY += line;
        }
    }

    private static double partsWidth(Panel r, List<Part> parts) {
        double total = 0;
        for (int i = 0; i < parts.size(); i++) total += (i > 0 ? 6 : 0) + r.textWidth(parts.get(i).text, false, Panel.DEFAULT_SCALE);
        return total;
    }

    private static void drawHead(Panel r, SkinTextures skin, int x, int y, int size) {
        if (r.context() == null) return;
        float factor = (float) mc.getWindow().getScaleFactor();
        var matrices = r.context().getMatrices();
        matrices.pushMatrix();
        matrices.scale(1 / factor, 1 / factor);
        PlayerSkinDrawer.draw(r.context(), skin, x, y, size);
        matrices.popMatrix();
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (mc.world == null || mc.player == null) return rows;

        List<PlayerEntity> players = new ArrayList<>();
        double max = range.get() * range.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.squaredDistanceTo(mc.player) > max) continue;
            boolean friend = Core.host().isFriend(player);
            if (show.get() == Show.Friends && !friend) continue;
            if (show.get() == Show.Others && friend) continue;
            if (bots.get() && mc.getNetworkHandler() != null && mc.getNetworkHandler().getPlayerListEntry(player.getUuid()) == null) continue;
            if (nakeds.get() && naked(player)) continue;
            players.add(player);
        }
        players.sort(Comparator.comparingDouble(player -> player.squaredDistanceTo(mc.player)));

        for (PlayerEntity player : players) {
            if (rows.size() >= limit.get()) break;
            List<Part> parts = new ArrayList<>();
            if (elytra.get() && player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) parts.add(new Part("elytra", Palette.SUBTLE));
            int popped = pops.get() ? PopTracker.get().pops(player) : 0;
            if (popped > 0) parts.add(new Part(popped + (popped == 1 ? " pop" : " pops"), Palette.WARN));
            if (health.get()) {
                float total = player.getHealth() + player.getAbsorptionAmount();
                parts.add(new Part(String.format(Locale.ROOT, "%.0f", total), Palette.health(Math.min(1, player.getHealth() / Math.max(1, player.getMaxHealth())))));
            }
            if (distance.get()) parts.add(new Part(String.format(Locale.ROOT, "%.0fm", Math.sqrt(player.squaredDistanceTo(mc.player))), Palette.SUBTLE));
            rows.add(new Row(player, player.getName().getString(), parts, Core.host().isFriend(player)));
        }
        return rows;
    }

    private static boolean naked(PlayerEntity player) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!player.getEquippedStack(slot).isEmpty()) return false;
        }
        return true;
    }
}
