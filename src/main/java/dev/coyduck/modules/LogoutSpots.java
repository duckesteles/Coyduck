package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.hud.CoyHud;
import dev.coyduck.managers.PopTracker;
import dev.coyduck.utils.DuckRender;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LogoutSpots extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Says in chat when someone near you logs out and when they come back, without coordinates.")
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

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the box.")
        .defaultValue(new SettingColor(230, 90, 110, 50))
        .build()
    );

    private record Spot(String name, Box box, float health, int pops, long at) {}
    private record Seen(String name, Box box, float health, int pops) {}

    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<UUID, Spot> spots = new HashMap<>();
    private final Vector3d pos = new Vector3d();
    private final Color line = new Color();
    private final Color background = new Color();

    public LogoutSpots() {
        super(Coyduck.CATEGORY, "logout-spots+", "Marks where players logged out.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.render.LogoutSpots.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it marks the same spots.", meteor.title);
        }
        seen.clear();
        spots.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        seen.clear();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !friends.get() && Friends.get().isFriend(player)) continue;
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
    private void onRender3D(Render3DEvent event) {
        SettingColor fill = color.get();
        line.set(fill.r, fill.g, fill.b, Math.min(255, fill.a * 4));
        for (Spot spot : spots.values()) DuckRender.box(event.renderer, spot.box, fill, line, ShapeMode.Both, 1);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (spots.isEmpty()) return;
        boolean shadow = Config.get().customFont.get();
        TextRenderer text = TextRenderer.get();
        background.set(CoyHud.color(CoyHud.BACKGROUND));
        long now = Util.getMeasuringTimeMs();
        for (Spot spot : spots.values()) {
            pos.set(spot.box.getCenter().x, spot.box.maxY + 0.5, spot.box.getCenter().z);
            if (!NametagUtils.to2D(pos, scale.get())) continue;
            NametagUtils.begin(pos, event.drawContext);
            String name = spot.name;
            String hp = " " + Math.round(spot.health);
            String pops = spot.pops > 0 ? " -" + spot.pops : "";
            String ago = " " + age(now - spot.at);
            text.beginBig();
            double width = text.getWidth(name + hp + pops + ago, shadow);
            double height = text.getHeight(shadow);
            text.end();
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(-width / 2 - 3, -height - 3, width + 6, height + 6, background);
            Renderer2D.COLOR.render();
            text.beginBig();
            double x = -width / 2;
            x = text.render(name, x, -height, CoyHud.color(CoyHud.TEXT), shadow);
            x = text.render(hp, x, -height, CoyHud.color(CoyHud.health(Math.min(1, spot.health / 20))), shadow);
            if (!pops.isEmpty()) x = text.render(pops, x, -height, CoyHud.color(CoyHud.WARN), shadow);
            text.render(ago, x, -height, CoyHud.color(CoyHud.SUBTLE), shadow);
            text.end();
            NametagUtils.end(event.drawContext);
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
