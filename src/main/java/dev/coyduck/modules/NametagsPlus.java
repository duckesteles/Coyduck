package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.RenderScreenEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.hud.CoyHud;
import dev.coyduck.mixin.PlayerLikeEntityAccessor;
import dev.coyduck.render.Overlay;
import dev.coyduck.render.Palette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.Perspective;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public class NametagsPlus extends Feature {
    public static final int MARK = 0x80;

    private static final Identifier MASCOT = Identifier.of("coyduck", "textures/mascot.png");
    private static final EquipmentSlot[] ITEMS = {EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
    private static final double ITEM_SIZE = 32;
    private static final double ITEM_SPACING = 2;
    private static final double PAD = 3;
    private static final double ROW_GAP = 4;
    private static final double EAT_BAR = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Shows other Coyduck users you use it too.")
        .defaultValue(true)
        .onChanged(on -> {
            if (mc.player != null) mc.options.sendClientSettings();
        })
        .build()
    );

    private final Setting<Boolean> mascot = sgGeneral.add(new BoolSetting.Builder()
        .name("mascot")
        .description("Shows the mascot on Coyduck users' tags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> health = sgGeneral.add(new BoolSetting.Builder()
        .name("health")
        .description("Health with absorption.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("ping")
        .description("Their ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> eating = sgGeneral.add(new BoolSetting.Builder()
        .name("eating")
        .description("A bar that fills up while they eat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> items = sgGeneral.add(new BoolSetting.Builder()
        .name("items")
        .description("Their armor and held items with durability bars.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> invisible = sgGeneral.add(new BoolSetting.Builder()
        .name("invisible")
        .description("Also tags invisible players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the tags.")
        .defaultValue(1.1)
        .range(0.3, 3)
        .sliderRange(0.5, 2)
        .build()
    );

    private final Setting<Boolean> self = sgGeneral.add(new BoolSetting.Builder()
        .name("self")
        .description("Shows your own tag in third person.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Draws a box behind the tag.")
        .defaultValue(false)
        .build()
    );

    private final ColorSetting fill = sgGeneral.add(new ColorSetting.Builder()
        .name("fill")
        .description("Tag background.")
        .defaultValue(new Rgba(15, 15, 20, 165))
        .visible(background::get)
        .build()
    );

    private final Setting<Boolean> outline = sgGeneral.add(new BoolSetting.Builder()
        .name("outline")
        .description("Thin accent colored border around the tag.")
        .defaultValue(true)
        .visible(background::get)
        .build()
    );

    private static final int BAR_BACKGROUND = 0xC8000000;

    private final ItemStack[] shown = new ItemStack[ITEMS.length];

    public NametagsPlus() {
        super("nametags+", "Clean player nametags.");
    }

    public static boolean announces() {
        NametagsPlus module = Features.get(NametagsPlus.class);
        return module == null || module.announce.get();
    }

    public static boolean replacesVanilla() {
        NametagsPlus module = Features.get(NametagsPlus.class);
        return module != null && module.isActive();
    }

    public static boolean usesCoyduck(PlayerEntity player) {
        return (player.getDataTracker().get(PlayerLikeEntityAccessor.coyduck$modelParts()) & MARK) != 0;
    }

    @Override
    public void onActivate() {
        displace("it draws the same tags.", "nametags");
    }

    @EventHandler
    private void onRender2D(RenderScreenEvent event) {
        if (mc.world == null || mc.player == null) return;
        Overlay overlay = event.overlay;
        boolean shadow = !background.get() || overlay.customFont();
        boolean thirdPerson = mc.options.getPerspective() != Perspective.FIRST_PERSON;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!player.isAlive() || player.isInvisible() && !invisible.get()) continue;
            if (player == mc.player && (!self.get() || !thirdPerson)) continue;

            double x = MathHelper.lerp(event.tickDelta, player.lastRenderX, player.getX());
            double y = MathHelper.lerp(event.tickDelta, player.lastRenderY, player.getY()) + player.getEyeHeight(player.getPose()) + 0.5;
            double z = MathHelper.lerp(event.tickDelta, player.lastRenderZ, player.getZ());
            if (!overlay.project(x, y, z, scale.get())) continue;
            draw(overlay, player, shadow);
            overlay.unproject();
        }
    }

    private void draw(Overlay overlay, PlayerEntity player, boolean shadow) {
        String name = player.getName().getString();
        String healthText = null;
        String pingText = null;
        int healthColor = 0;
        int pingColor = 0;
        if (health.get()) {
            float total = player.getHealth() + player.getAbsorptionAmount();
            healthText = " " + Math.round(total);
            healthColor = Palette.health(Math.min(1, player.getHealth() / Math.max(1, player.getMaxHealth())));
        }
        if (ping.get() && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null && entry.getLatency() > 0) {
                int latency = entry.getLatency();
                pingText = " " + latency + "ms";
                pingColor = latency > 250 ? Palette.BAD : latency > 150 ? Palette.WARN : Palette.SUBTLE;
            }
        }
        int nameColor = Core.host().isFriend(player) ? Palette.GOOD : Palette.TEXT;

        double height = overlay.textHeight(shadow);
        double textWidth = overlay.textWidth(name, shadow);
        if (healthText != null) textWidth += overlay.textWidth(healthText, shadow);
        if (pingText != null) textWidth += overlay.textWidth(pingText, shadow);

        boolean duck = mascot.get() && usesCoyduck(player);
        double icon = duck ? height + PAD : 0;
        double width = icon + textWidth;
        double progress = eating.get() ? eatProgress(player) : -1;
        double bar = progress >= 0 ? EAT_BAR + 1 : 0;
        double left = -width / 2;
        double top = -height - bar;

        if (background.get()) overlay.rect(left - PAD, top - PAD, width + PAD * 2, height + bar + PAD * 2, fill.argb());
        if (progress >= 0) {
            double barY = top + height + 1;
            overlay.rect(left, barY, width, EAT_BAR, BAR_BACKGROUND);
            overlay.rect(left, barY, width * progress, EAT_BAR, Palette.health(1 - progress));
        }
        if (background.get() && outline.get()) {
            int line = CoyHud.accent();
            double x0 = left - PAD;
            double y0 = top - PAD;
            double w = width + PAD * 2;
            double h = height + bar + PAD * 2;
            overlay.rect(x0, y0, w, 1, line);
            overlay.rect(x0, y0 + h - 1, w, 1, line);
            overlay.rect(x0, y0, 1, h, line);
            overlay.rect(x0 + w - 1, y0, 1, h, line);
        }

        if (duck) {
            int size = (int) Math.round(height);
            overlay.context().drawTexture(RenderPipelines.GUI_TEXTURED, MASCOT, (int) Math.round(left), (int) Math.round(top), 0, 0, size, size, size, size);
        }

        double x = left + icon;
        x = overlay.text(name, x, top, nameColor, shadow);
        if (healthText != null) x = overlay.text(healthText, x, top, healthColor, shadow);
        if (pingText != null) overlay.text(pingText, x, top, pingColor, shadow);

        if (items.get()) drawItems(overlay, player, top - PAD - ROW_GAP);
    }

    private static double eatProgress(PlayerEntity player) {
        if (!player.isUsingItem()) return -1;
        ItemStack stack = player.getActiveItem();
        if (!stack.contains(DataComponentTypes.FOOD)) return -1;
        int total = stack.getMaxUseTime(player);
        if (total <= 0) return -1;
        return Math.min(1, player.getItemUseTime() / (double) total);
    }

    private void drawItems(Overlay overlay, PlayerEntity player, double bottom) {
        int count = 0;
        for (EquipmentSlot slot : ITEMS) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isEmpty()) shown[count++] = stack;
        }
        if (count == 0) return;

        double total = count * ITEM_SIZE + (count - 1) * ITEM_SPACING;
        double y = bottom - ITEM_SIZE - 3;
        double x = -total / 2;
        boolean bars = false;
        DrawContext context = overlay.context();
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(2, 2);
        for (int i = 0; i < count; i++) {
            context.drawItem(shown[i], (int) Math.round(x + i * (ITEM_SIZE + ITEM_SPACING)) / 2, (int) Math.round(y) / 2);
            bars |= shown[i].isDamageable();
        }
        context.getMatrices().popMatrix();
        if (bars) {
            for (int i = 0; i < count; i++) {
                ItemStack stack = shown[i];
                if (!stack.isDamageable()) continue;
                double left = 1 - (double) stack.getDamage() / stack.getMaxDamage();
                double bx = x + i * (ITEM_SIZE + ITEM_SPACING) + 3;
                double by = y + ITEM_SIZE;
                overlay.rect(bx, by, ITEM_SIZE - 6, 3, BAR_BACKGROUND);
                overlay.rect(bx, by, (ITEM_SIZE - 6) * left, 2, 0xFF000000 | stack.getItemBarColor());
            }
        }
        for (int i = 0; i < count; i++) shown[i] = null;
    }
}
