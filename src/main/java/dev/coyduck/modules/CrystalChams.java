package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.utils.ExplosionDamage;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class CrystalChams extends Module {
    public enum Mode {
        Owner,
        Danger,
        Static
    }

    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/end_crystal/end_crystal.png");
    private static RenderLayer layer;
    private static final double RANGE = 16.0;

    private record Burst(Vec3d center, Color color, long time) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgBreak = settings.createGroup("Break");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Owner colors your crystals and the enemy's apart. Danger colors each crystal by how much it would hurt you. Static uses one color.")
        .defaultValue(Mode.Owner)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the crystals.")
        .defaultValue(1.0)
        .range(0.1, 2.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("How fast the crystals spin and bob. 0 freezes them.")
        .defaultValue(1.0)
        .range(0, 5)
        .sliderRange(0, 3)
        .build()
    );

    private final Setting<SettingColor> ownColor = sgColors.add(new ColorSetting.Builder()
        .name("own")
        .description("Crystals you placed.")
        .defaultValue(new SettingColor(185, 74, 230, 150))
        .visible(() -> mode.get() == Mode.Owner)
        .build()
    );

    private final Setting<SettingColor> enemyColor = sgColors.add(new ColorSetting.Builder()
        .name("enemy")
        .description("Crystals anyone else placed.")
        .defaultValue(new SettingColor(255, 152, 0, 150))
        .visible(() -> mode.get() == Mode.Owner)
        .build()
    );

    private final Setting<SettingColor> safeColor = sgColors.add(new ColorSetting.Builder()
        .name("safe")
        .description("Crystals that cannot hurt you much.")
        .defaultValue(new SettingColor(185, 74, 230, 150))
        .visible(() -> mode.get() == Mode.Danger)
        .build()
    );

    private final Setting<SettingColor> riskyColor = sgColors.add(new ColorSetting.Builder()
        .name("risky")
        .description("Crystals that would take half your health or more.")
        .defaultValue(new SettingColor(251, 192, 45, 160))
        .visible(() -> mode.get() == Mode.Danger)
        .build()
    );

    private final Setting<SettingColor> lethalColor = sgColors.add(new ColorSetting.Builder()
        .name("lethal")
        .description("Crystals that would pop or kill you.")
        .defaultValue(new SettingColor(255, 82, 82, 170))
        .visible(() -> mode.get() == Mode.Danger)
        .build()
    );

    private final Setting<SettingColor> staticColor = sgColors.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of every crystal.")
        .defaultValue(new SettingColor(185, 74, 230, 150))
        .visible(() -> mode.get() == Mode.Static)
        .build()
    );

    private final Setting<Boolean> burst = sgBreak.add(new BoolSetting.Builder()
        .name("burst")
        .description("Shows a fading burst in the crystal's color where it breaks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> burstTime = sgBreak.add(new DoubleSetting.Builder()
        .name("burst-time")
        .description("Seconds the burst lasts.")
        .defaultValue(0.4)
        .range(0.1, 2.0)
        .sliderRange(0.1, 1.0)
        .visible(burst::get)
        .build()
    );

    private final Int2IntOpenHashMap dangerColors = new Int2IntOpenHashMap();
    private final List<Burst> bursts = new ArrayList<>();

    public CrystalChams() {
        super(Coyduck.CATEGORY, "crystal-chams", "Colors end crystals by owner or by how dangerous they are to you, and shows where they break.");
    }

    private static CrystalChams active() {
        CrystalChams module = Modules.get() == null ? null : Modules.get().get(CrystalChams.class);
        return module != null && module.isActive() ? module : null;
    }

    public static int color(EndCrystalEntity crystal) {
        CrystalChams module = active();
        return module == null || MeteorClient.mc.player == null ? 0 : module.colorOf(crystal);
    }

    public static float scale() {
        CrystalChams module = active();
        return module == null ? 1.0f : module.scale.get().floatValue();
    }

    public static float age(float age) {
        CrystalChams module = active();
        return module == null ? age : age * module.speed.get().floatValue();
    }

    public static RenderLayer layer() {
        if (layer == null) layer = RenderLayers.entityTranslucent(TEXTURE);
        return layer;
    }

    @Override
    public void onDeactivate() {
        dangerColors.clear();
        bursts.clear();
    }

    private int colorOf(EndCrystalEntity crystal) {
        return switch (mode.get()) {
            case Owner -> (CrystalTracker.get().isOwn(crystal) ? ownColor.get() : enemyColor.get()).getPacked();
            case Danger -> dangerColors.getOrDefault(crystal.getId(), safeColor.get().getPacked());
            case Static -> staticColor.get().getPacked();
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        dangerColors.clear();
        if (mode.get() != Mode.Danger || mc.player == null || mc.world == null) return;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double range = RANGE * RANGE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal) || crystal.squaredDistanceTo(mc.player) > range) continue;

            float damage = ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal.getEntityPos(), mc.world);
            SettingColor color = damage >= health ? lethalColor.get() : damage >= health / 2 ? riskyColor.get() : safeColor.get();
            dangerColors.put(crystal.getId(), color.getPacked());
        }
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!burst.get() || !(event.packet instanceof EntitiesDestroyS2CPacket packet) || mc.world == null || mc.player == null) return;

        for (int id : packet.getEntityIds()) {
            if (!(mc.world.getEntityById(id) instanceof EndCrystalEntity crystal)) continue;

            int packed = colorOf(crystal);
            Color color = new Color((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF, (packed >>> 24) & 0xFF);
            bursts.add(new Burst(crystal.getEntityPos().add(0, 1.0, 0), color, Util.getMeasuringTimeMs()));
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (bursts.isEmpty()) return;

        long now = Util.getMeasuringTimeMs();
        long duration = (long) (burstTime.get() * 1000);
        bursts.removeIf(entry -> now - entry.time > duration);

        for (Burst entry : bursts) {
            double progress = (now - entry.time) / (double) duration;
            double half = 0.5 + progress;
            int alpha = (int) (entry.color.a * (1.0 - progress));
            Color side = new Color(entry.color.r, entry.color.g, entry.color.b, alpha / 2);
            Color line = new Color(entry.color.r, entry.color.g, entry.color.b, alpha);

            Vec3d c = entry.center;
            event.renderer.box(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half, side, line, ShapeMode.Both, 0);
        }
    }
}
