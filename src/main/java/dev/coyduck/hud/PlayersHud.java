package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PlayersHud extends HudElement {
    public static final HudElementInfo<PlayersHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-players", "Players you can see, closest first, with their heads.", PlayersHud::new);

    public enum Show {
        Everyone,
        Friends,
        Others
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private record Row(PlayerEntity player, String name, String distance, String health, int healthColor, boolean friend) {}

    public PlayersHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        List<Row> rows = rows();
        double pad = 5;
        double gap = 8;
        double line = r.textHeight() + 4;
        double head = heads.get() ? r.textHeight() + 1 : 0;
        double headGap = heads.get() ? 4 : 0;

        double nameWidth = 0;
        double sideWidth = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, r.textWidth(row.name));
            double side = 0;
            if (row.health != null) side += r.textWidth(row.health);
            if (row.distance != null) side += (side > 0 ? 6 : 0) + r.textWidth(row.distance);
            sideWidth = Math.max(sideWidth, side);
        }
        if (rows.isEmpty()) nameWidth = r.textWidth("No players");

        double w = pad * 2 + head + headGap + nameWidth + (sideWidth > 0 ? gap + sideWidth : 0);
        double h = pad * 2 + line * Math.max(1, rows.size()) - 4;
        setSize(w, h);

        if (rows.isEmpty()) {
            if (!isInEditor()) return;
            CoyHud.panel(r, x, y, w, h, 1);
            r.text("No players", x + pad, y + pad, CoyHud.color(CoyHud.MUTED), false);
            return;
        }

        CoyHud.panel(r, x, y, w, h, 1);
        boolean right = getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
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
            r.text(row.name, nameX, rowY, CoyHud.color(row.friend ? CoyHud.GOOD : CoyHud.TEXT), false);

            double sideX = right ? x + w - pad : x + pad + head + headGap + nameWidth + gap;
            List<String> parts = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            if (row.health != null) {
                parts.add(row.health);
                colors.add(row.healthColor);
            }
            if (row.distance != null) {
                parts.add(row.distance);
                colors.add(CoyHud.SUBTLE);
            }
            double total = 0;
            for (int i = 0; i < parts.size(); i++) total += (i > 0 ? 6 : 0) + r.textWidth(parts.get(i));
            double px = right ? sideX - total : sideX;
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) px += 6;
                r.text(parts.get(i), px, rowY, CoyHud.color(colors.get(i)), false);
                px += r.textWidth(parts.get(i));
            }
            rowY += line;
        }
    }

    private static void drawHead(HudRenderer r, SkinTextures skin, int x, int y, int size) {
        if (r.drawContext == null) return;
        float factor = (float) mc.getWindow().getScaleFactor();
        var matrices = r.drawContext.getMatrices();
        matrices.pushMatrix();
        matrices.scale(1 / factor, 1 / factor);
        PlayerSkinDrawer.draw(r.drawContext, skin, x, y, size);
        matrices.popMatrix();
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (mc.world == null || mc.player == null) return rows;

        List<PlayerEntity> players = new ArrayList<>();
        double max = range.get() * range.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.squaredDistanceTo(mc.player) > max) continue;
            boolean friend = Friends.get().isFriend(player);
            if (show.get() == Show.Friends && !friend) continue;
            if (show.get() == Show.Others && friend) continue;
            players.add(player);
        }
        players.sort(Comparator.comparingDouble(player -> player.squaredDistanceTo(mc.player)));

        for (PlayerEntity player : players) {
            if (rows.size() >= limit.get()) break;
            String dist = distance.get() ? String.format(Locale.ROOT, "%.0fm", Math.sqrt(player.squaredDistanceTo(mc.player))) : null;
            String hp = null;
            int hpColor = CoyHud.TEXT;
            if (health.get()) {
                float total = player.getHealth() + player.getAbsorptionAmount();
                hp = String.format(Locale.ROOT, "%.0f", total);
                hpColor = CoyHud.health(Math.min(1, player.getHealth() / Math.max(1, player.getMaxHealth())));
            }
            rows.add(new Row(player, player.getName().getString(), dist, hp, hpColor, Friends.get().isFriend(player)));
        }
        return rows;
    }
}
