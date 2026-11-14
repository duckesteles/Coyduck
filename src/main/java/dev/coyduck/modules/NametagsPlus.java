package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.hud.CoyHud;
import dev.coyduck.mixin.PlayerLikeEntityAccessor;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Nametags;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.Perspective;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Vector3d;

public class NametagsPlus extends Module {
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
        .description("Lets other Coyduck users know you use it too, so they see the mascot on your tag. Works even with this module off.")
        .defaultValue(true)
        .onChanged(on -> {
            if (mc.player != null) mc.options.sendClientSettings();
        })
        .build()
    );

    private final Setting<Boolean> mascot = sgGeneral.add(new BoolSetting.Builder()
        .name("mascot")
        .description("Shows the Coyduck mascot on the tags of players using Coyduck.")
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
        .description("A bar inside the tag filling up while they eat, so you see a gapple coming.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> items = sgGeneral.add(new BoolSetting.Builder()
        .name("items")
        .description("Their armor and held items above the tag, with a durability bar under each.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Only players closer than this.")
        .defaultValue(64)
        .range(4, 512)
        .sliderRange(16, 256)
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

    private final Setting<SettingColor> fill = sgGeneral.add(new ColorSetting.Builder()
        .name("fill")
        .description("Tag background.")
        .defaultValue(new SettingColor(15, 15, 20, 165))
        .build()
    );

    private final Setting<Boolean> outline = sgGeneral.add(new BoolSetting.Builder()
        .name("outline")
        .description("Thin accent colored border around the tag.")
        .defaultValue(true)
        .build()
    );

    private static final Color BAR_BACKGROUND = new Color(0, 0, 0, 200);

    private final Vector3d pos = new Vector3d();
    private final Color line = new Color();
    private final Color nameColor = new Color();
    private final Color healthColor = new Color();
    private final Color pingColor = new Color();
    private final Color barColor = new Color();
    private final ItemStack[] shown = new ItemStack[ITEMS.length];

    public NametagsPlus() {
        super(Coyduck.CATEGORY, "nametags+", "Clean player tags: name, health and ping, a bar while they eat, their armor and held items with durability bars, and the Coyduck mascot for other Coyduck users.");
    }

    public static boolean announces() {
        NametagsPlus module = Modules.get() == null ? null : Modules.get().get(NametagsPlus.class);
        return module == null || module.announce.get();
    }

    public static boolean replacesVanilla() {
        NametagsPlus module = Modules.get() == null ? null : Modules.get().get(NametagsPlus.class);
        return module != null && module.isActive();
    }

    public static boolean usesCoyduck(PlayerEntity player) {
        return (player.getDataTracker().get(PlayerLikeEntityAccessor.coyduck$modelParts()) & MARK) != 0;
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(Nametags.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it draws the same tags.", meteor.title);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.world == null || mc.player == null) return;
        boolean shadow = Config.get().customFont.get();
        double max = range.get() * range.get();
        boolean thirdPerson = mc.options.getPerspective() != Perspective.FIRST_PERSON;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!player.isAlive() || player.isInvisible()) continue;
            if (player == mc.player ? !self.get() || !thirdPerson : player.squaredDistanceTo(mc.player) > max) continue;

            Utils.set(pos, player, event.tickDelta);
            pos.add(0, player.getEyeHeight(player.getPose()) + 0.5, 0);
            if (!NametagUtils.to2D(pos, scale.get())) continue;
            draw(event, player, shadow);
        }
    }

    private void draw(Render2DEvent event, PlayerEntity player, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos, event.drawContext);

        String name = player.getName().getString();
        String healthText = null;
        String pingText = null;
        if (health.get()) {
            float total = player.getHealth() + player.getAbsorptionAmount();
            healthText = " " + Math.round(total);
            healthColor.set(CoyHud.color(CoyHud.health(Math.min(1, player.getHealth() / Math.max(1, player.getMaxHealth())))));
        }
        if (ping.get() && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null && entry.getLatency() > 0) {
                int latency = entry.getLatency();
                pingText = " " + latency + "ms";
                pingColor.set(CoyHud.color(latency > 250 ? CoyHud.BAD : latency > 150 ? CoyHud.WARN : CoyHud.SUBTLE));
            }
        }
        nameColor.set(CoyHud.color(Friends.get().isFriend(player) ? CoyHud.GOOD : CoyHud.TEXT));

        text.beginBig();
        double height = text.getHeight(shadow);
        double textWidth = text.getWidth(name, shadow);
        if (healthText != null) textWidth += text.getWidth(healthText, shadow);
        if (pingText != null) textWidth += text.getWidth(pingText, shadow);
        text.end();

        boolean duck = mascot.get() && usesCoyduck(player);
        double icon = duck ? height + PAD : 0;
        double width = icon + textWidth;
        double progress = eating.get() ? eatProgress(player) : -1;
        double bar = progress >= 0 ? EAT_BAR + 1 : 0;
        double left = -width / 2;
        double top = -height - bar;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(left - PAD, top - PAD, width + PAD * 2, height + bar + PAD * 2, fill.get());
        if (progress >= 0) {
            double barY = top + height + 1;
            barColor.set(CoyHud.color(CoyHud.health(1 - progress)));
            Renderer2D.COLOR.quad(left, barY, width, EAT_BAR, BAR_BACKGROUND);
            Renderer2D.COLOR.quad(left, barY, width * progress, EAT_BAR, barColor);
        }
        if (outline.get()) {
            line.set(CoyHud.color(CoyHud.accent()));
            double x0 = left - PAD;
            double y0 = top - PAD;
            double w = width + PAD * 2;
            double h = height + bar + PAD * 2;
            Renderer2D.COLOR.quad(x0, y0, w, 1, line);
            Renderer2D.COLOR.quad(x0, y0 + h - 1, w, 1, line);
            Renderer2D.COLOR.quad(x0, y0, 1, h, line);
            Renderer2D.COLOR.quad(x0 + w - 1, y0, 1, h, line);
        }
        Renderer2D.COLOR.render();

        if (duck) {
            int size = (int) Math.round(height);
            event.drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, MASCOT, (int) Math.round(left), (int) Math.round(top), 0, 0, size, size, size, size);
        }

        text.beginBig();
        double x = left + icon;
        x = text.render(name, x, top, nameColor, shadow);
        if (healthText != null) x = text.render(healthText, x, top, healthColor, shadow);
        if (pingText != null) text.render(pingText, x, top, pingColor, shadow);
        text.end();

        if (items.get()) drawItems(event, player, top - PAD - ROW_GAP);
        NametagUtils.end(event.drawContext);
    }

    private static double eatProgress(PlayerEntity player) {
        if (!player.isUsingItem()) return -1;
        ItemStack stack = player.getActiveItem();
        if (!stack.contains(DataComponentTypes.FOOD)) return -1;
        int total = stack.getMaxUseTime(player);
        if (total <= 0) return -1;
        return Math.min(1, player.getItemUseTime() / (double) total);
    }

    private void drawItems(Render2DEvent event, PlayerEntity player, double bottom) {
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
        for (int i = 0; i < count; i++) {
            RenderUtils.drawItem(event.drawContext, shown[i], (int) Math.round(x + i * (ITEM_SIZE + ITEM_SPACING)), (int) Math.round(y), 2, false, null, false);
            bars |= shown[i].isDamageable();
        }
        if (bars) {
            Renderer2D.COLOR.begin();
            for (int i = 0; i < count; i++) {
                ItemStack stack = shown[i];
                if (!stack.isDamageable()) continue;
                double left = 1 - (double) stack.getDamage() / stack.getMaxDamage();
                double bx = x + i * (ITEM_SIZE + ITEM_SPACING) + 3;
                double by = y + ITEM_SIZE;
                barColor.set(CoyHud.color(0xFF000000 | stack.getItemBarColor()));
                Renderer2D.COLOR.quad(bx, by, ITEM_SIZE - 6, 3, BAR_BACKGROUND);
                Renderer2D.COLOR.quad(bx, by, (ITEM_SIZE - 6) * left, 2, barColor);
            }
            Renderer2D.COLOR.render();
        }
        for (int i = 0; i < count; i++) shown[i] = null;
    }
}
