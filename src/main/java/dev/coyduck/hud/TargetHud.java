package dev.coyduck.hud;

import dev.coyduck.modules.PopCounter;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;

import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TargetHud extends HudElement {
    public static final HudElementInfo<TargetHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-target", "The closest enemy: health, armor and pops.", TargetHud::new);

    private static final double WIDTH = 150;
    private static final double HEIGHT = 56;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How close an enemy has to be to show up.")
        .defaultValue(14)
        .range(2, 64)
        .sliderRange(4, 32)
        .build()
    );

    private PlayerEntity last;
    private double health;
    private double absorption;
    private double alpha;
    private long lastFrame;

    public TargetHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        setSize(WIDTH, HEIGHT);
        long now = Util.getMeasuringTimeMs();
        double delta = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1000.0, 0.1);
        lastFrame = now;

        PlayerEntity target = find();
        if (target == null && isInEditor()) target = mc.player;
        alpha = CoyHud.approach(alpha, target != null ? 1 : 0, delta, 10);
        if (target != null) {
            if (target != last) {
                health = target.getHealth();
                absorption = target.getAbsorptionAmount();
            }
            last = target;
        }
        if (alpha < 0.02 || last == null) return;
        PlayerEntity player = last;

        health = CoyHud.approach(health, player.getHealth(), delta, 9);
        absorption = CoyHud.approach(absorption, player.getAbsorptionAmount(), delta, 9);

        CoyHud.panel(r, x, y, WIDTH, HEIGHT, alpha);
        CoyHud.round(r, x + 4, y + 4, 38, HEIGHT - 8, 2, CoyHud.color(0x24FFFFFF, alpha));
        int ex = x + 4;
        int ey = y + 5;
        r.post(() -> r.entity(player, ex, ey, 38, (int) HEIGHT - 10, 0, 0));

        double left = x + 48;
        double right = x + WIDTH - 6;
        r.text(player.getName().getString(), left, y + 5, CoyHud.color(CoyHud.TEXT, alpha), false);
        String distance = String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(player));
        r.text(distance, right - r.textWidth(distance), y + 5, CoyHud.color(CoyHud.SUBTLE, alpha), false);

        double max = Math.max(1, player.getMaxHealth());
        double barY = y + 7 + r.textHeight() + 3;
        double barW = right - left;
        CoyHud.round(r, left, barY, barW, 4, 2, CoyHud.color(CoyHud.TRACK, alpha));
        double fraction = Math.min(1, health / max);
        CoyHud.round(r, left, barY, barW * fraction, 4, 2, CoyHud.color(CoyHud.health(fraction), alpha));
        if (absorption > 0.05) {
            CoyHud.round(r, left, barY, barW * Math.min(1, absorption / max), 4, 2, CoyHud.color(0xC0F4C660, alpha));
        }

        String hp = String.format(Locale.ROOT, "%.1f", player.getHealth() + player.getAbsorptionAmount());
        int pops = PopCounter.pops(player);
        String popText = pops == 1 ? "1 pop" : pops + " pops";
        double infoY = barY + 8;
        r.text(hp, left, infoY, CoyHud.color(CoyHud.TEXT, alpha), false);
        r.text(popText, right - r.textWidth(popText), infoY, CoyHud.color(pops > 0 ? CoyHud.accent() : CoyHud.SUBTLE, alpha), false);

        int itemY = (int) (y + HEIGHT - 15);
        int itemX = (int) left - 1;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isEmpty()) {
                int ix = itemX;
                r.post(() -> r.item(stack, ix, itemY, 0.75f, true));
            }
            itemX += 14;
        }
    }

    private PlayerEntity find() {
        if (mc.world == null || mc.player == null) return null;
        PlayerEntity best = null;
        double bestDistance = range.get() * range.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player)) continue;
            double distance = player.squaredDistanceTo(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }
}
