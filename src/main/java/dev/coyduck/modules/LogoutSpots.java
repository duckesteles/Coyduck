package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.RenderScreenEvent;
import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.PopTracker;
import dev.coyduck.render.Argb;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Overlay;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Shape;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LogoutSpots extends Feature {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announces logouts and returns in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> friends = sgGeneral.add(new BoolSetting.Builder()
        .name("friends")
        .description("Also marks friends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> keep = sgGeneral.add(new IntSetting.Builder()
        .name("keep-minutes")
        .description("Minutes a spot stays. 0 keeps it until they log back in.")
        .defaultValue(0)
        .range(0, 240)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the label.")
        .defaultValue(1)
        .range(0.3, 3)
        .sliderRange(0.5, 2)
        .build()
    );

    private final ColorSetting color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the box.")
        .defaultValue(new Rgba(230, 90, 110, 50))
        .build()
    );

    private record Spot(String name, Box box, float health, int pops, long at) {}
    private record Seen(String name, Box box, float health, int pops) {}

    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<UUID, Spot> spots = new HashMap<>();

    public LogoutSpots() {
        super("logout-spots+", "Marks where players logged out.");
    }

    @Override
    public void onActivate() {
        displace("it marks the same spots.", "logout-spots");
        seen.clear();
        spots.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        seen.clear();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !friends.get() && Core.host().isFriend(player)) continue;
            seen.put(player.getUuid(), new Seen(player.getName().getString(), player.getBoundingBox(), player.getHealth() + player.getAbsorptionAmount(), PopTracker.get().pops(player)));
        }
        if (keep.get() > 0) {
            long limit = keep.get() * 60_000L;
            long now = Util.getMeasuringTimeMs();
            spots.values().removeIf(spot -> now - spot.at > limit);
        }
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            for (UUID id : packet.profileIds()) {
                Seen last = seen.get(id);
                if (last == null) continue;
                spots.put(id, new Spot(last.name, last.box, last.health, last.pops, Util.getMeasuringTimeMs()));
                if (notify.get()) info("%s logged out%s.", last.name, last.pops > 0 ? " after popping " + last.pops : "");
            }
        } else if (event.packet instanceof PlayerListS2CPacket packet && packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
            for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                Spot spot = spots.remove(entry.profileId());
                if (spot != null && notify.get()) info("%s logged back in.", spot.name);
            }
        }
    }

    @EventHandler
    private void onRender3D(RenderWorldEvent event) {
        int fill = color.argb();
        int line = Argb.alpha(fill, Argb.a(fill) * 4);
        for (Spot spot : spots.values()) DuckRender.box(event.canvas, spot.box, fill, line, Shape.Both, 1);
    }

    @EventHandler
    private void onRender2D(RenderScreenEvent event) {
        if (spots.isEmpty()) return;
        Overlay overlay = event.overlay;
        boolean shadow = overlay.customFont();
        long now = Util.getMeasuringTimeMs();
        for (Spot spot : spots.values()) {
            Vec3d center = spot.box.getCenter();
            if (!overlay.project(center.x, spot.box.maxY + 0.5, center.z, scale.get())) continue;
            String name = spot.name;
            String hp = " " + Math.round(spot.health);
            String pops = spot.pops > 0 ? " -" + spot.pops : "";
            String ago = " " + age(now - spot.at);
            double width = overlay.textWidth(name + hp + pops + ago, shadow);
            double height = overlay.textHeight(shadow);
            overlay.rect(-width / 2 - 3, -height - 3, width + 6, height + 6, Palette.BACKGROUND);
            double x = -width / 2;
            x = overlay.text(name, x, -height, Palette.TEXT, shadow);
            x = overlay.text(hp, x, -height, Palette.health(Math.min(1, spot.health / 20)), shadow);
            if (!pops.isEmpty()) x = overlay.text(pops, x, -height, Palette.WARN, shadow);
            overlay.text(ago, x, -height, Palette.SUBTLE, shadow);
            overlay.unproject();
        }
    }

    private static String age(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m";
        return String.format(Locale.ROOT, "%dh%02dm", seconds / 3600, seconds / 60 % 60);
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        spots.clear();
        seen.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        spots.clear();
        seen.clear();
    }
}
