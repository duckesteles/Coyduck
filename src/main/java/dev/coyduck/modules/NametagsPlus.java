package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.hud.CoyHud;
import dev.coyduck.managers.PopTracker;
import dev.coyduck.mixin.LivingEntityAccessor;
import dev.coyduck.mixin.PlayerLikeEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
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
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.util.Identifier;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NametagsPlus extends Module {
    public static final int MARK = 0x80;

    private static final Identifier MASCOT = Identifier.of("coyduck", "textures/mascot.png");
    private static final EquipmentSlot[] ITEMS = {EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
    private static final Map<RegistryEntry<StatusEffect>, String> SHOWN = Map.of(
        StatusEffects.STRENGTH, "Str",
        StatusEffects.WEAKNESS, "Weak",
        StatusEffects.RESISTANCE, "Res",
        StatusEffects.SPEED, "Spd",
        StatusEffects.SLOW_FALLING, "Fall",
        StatusEffects.FIRE_RESISTANCE, "Fire"
    );
    private static final Int2ObjectOpenHashMap<String> EFFECT_LABEL = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap EFFECT_COLOR = new Int2IntOpenHashMap();

    static {
        for (Map.Entry<RegistryEntry<StatusEffect>, String> entry : SHOWN.entrySet()) {
            int rgb = entry.getKey().value().getColor() & 0xFFFFFF;
            EFFECT_LABEL.put(rgb, entry.getValue());
            EFFECT_COLOR.put(rgb, 0xFF000000 | rgb);
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInfo = settings.createGroup("Info");
    private final SettingGroup sgRender = settings.createGroup("Render");

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

    private final Setting<Boolean> ping = sgInfo.add(new BoolSetting.Builder()
        .name("ping")
        .description("Their ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> health = sgInfo.add(new BoolSetting.Builder()
        .name("health")
        .description("Health with absorption.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pops = sgInfo.add(new BoolSetting.Builder()
        .name("pops")
        .description("Totems popped since their last death.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> effects = sgInfo.add(new BoolSetting.Builder()
        .name("effects")
        .description("Strength, weakness, resistance, speed, slow falling and fire resistance, read from their potion swirls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> eating = sgInfo.add(new BoolSetting.Builder()
        .name("eating")
        .description("A bar under the tag filling up while they eat, so you see a gapple coming.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> items = sgInfo.add(new BoolSetting.Builder()
        .name("items")
        .description("Their armor and held items above the tag.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> durability = sgInfo.add(new BoolSetting.Builder()
        .name("durability")
        .description("A durability bar under each item.")
        .defaultValue(true)
        .visible(items::get)
        .build()
    );

    private final Setting<Boolean> enchants = sgInfo.add(new BoolSetting.Builder()
        .name("enchants")
        .description("Short enchantment names next to each item, God on enchanted golden apples.")
        .defaultValue(true)
        .visible(items::get)
        .build()
    );

    private final Setting<Boolean> itemName = sgInfo.add(new BoolSetting.Builder()
        .name("item-name")
        .description("The name of the item in their main hand above the items.")
        .defaultValue(true)
        .visible(items::get)
        .build()
    );

    private final Setting<SettingColor> fill = sgRender.add(new ColorSetting.Builder()
        .name("fill")
        .description("Tag background.")
        .defaultValue(new SettingColor(15, 15, 20, 165))
        .build()
    );

    private final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Thin accent colored border around the tag.")
        .defaultValue(true)
        .build()
    );

    private static final Color BAR_BACKGROUND = new Color(0, 0, 0, 255);
    private static final Color ENCHANT = new Color(235, 235, 245, 255);
    private static final Color GOD = new Color(255, 85, 85, 255);

    private record Part(String text, Color color) {}

    private final Vector3d pos = new Vector3d();
    private final Color line = new Color();
    private final Color track = new Color(255, 255, 255, 48);
    private final Color bar = new Color();
    private final List<ItemStack> shown = new ArrayList<>();

    public NametagsPlus() {
        super(Coyduck.CATEGORY, "nametags+", "Clean player tags with ping, health, pops, potion effects, a gapple bar, armor durability and the Coyduck mascot for other Coyduck users.");
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

        List<Part> parts = parts(player);
        text.beginBig();
        double gap = text.getWidth(" ", shadow);
        double textWidth = 0;
        for (int i = 0; i < parts.size(); i++) textWidth += (i > 0 ? gap : 0) + text.getWidth(parts.get(i).text, shadow);
        double height = text.getHeight(shadow);
        text.end();

        boolean duck = mascot.get() && usesCoyduck(player);
        double icon = duck ? height : 0;
        double iconGap = duck ? gap : 0;
        double pad = 3;
        double width = icon + iconGap + textWidth;
        double progress = eating.get() ? eatProgress(player) : -1;
        double barHeight = progress >= 0 ? 3 : 0;
        double left = -width / 2;
        double top = -height - barHeight;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(left - pad, top - pad, width + pad * 2, height + barHeight + pad * 2, fill.get());
        if (outline.get()) {
            line.set(CoyHud.color(CoyHud.accent()));
            double x0 = left - pad;
            double y0 = top - pad;
            double w = width + pad * 2;
            double h = height + barHeight + pad * 2;
            Renderer2D.COLOR.quad(x0, y0, w, 1, line);
            Renderer2D.COLOR.quad(x0, y0 + h - 1, w, 1, line);
            Renderer2D.COLOR.quad(x0, y0, 1, h, line);
            Renderer2D.COLOR.quad(x0 + w - 1, y0, 1, h, line);
        }
        if (progress >= 0) {
            double barY = top + height + 1;
            bar.set(CoyHud.color(CoyHud.health(1 - progress)));
            Renderer2D.COLOR.quad(left, barY, width, 2, track);
            Renderer2D.COLOR.quad(left, barY, width * progress, 2, bar);
        }
        Renderer2D.COLOR.render();

        if (duck) {
            int size = (int) Math.round(icon);
            event.drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, MASCOT, (int) Math.round(left), (int) Math.round(top), 0, 0, size, size, size, size);
        }

        text.beginBig();
        double x = left + icon + iconGap;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) x += gap;
            x = text.render(parts.get(i).text, x, top, parts.get(i).color, shadow);
        }
        text.end();

        if (items.get()) drawItems(event, player, top - pad - 2, shadow);
        NametagUtils.end(event.drawContext);
    }

    private void drawItems(Render2DEvent event, PlayerEntity player, double bottom, boolean shadow) {
        shown.clear();
        for (EquipmentSlot slot : ITEMS) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isEmpty()) shown.add(stack);
        }
        if (shown.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        double size = 32;
        double spacing = 4;
        double total = shown.size() * size + (shown.size() - 1) * spacing;
        double barGap = durability.get() ? 4 : 0;
        double x = -total / 2;
        double y = bottom - size - barGap;

        double top = y;

        for (ItemStack stack : shown) {
            RenderUtils.drawItem(event.drawContext, stack, (int) x, (int) y, 2, false, null, false);

            if (durability.get() && stack.isDamageable()) {
                double left = 1 - (double) stack.getDamage() / stack.getMaxDamage();
                Color color = CoyHud.color(0xFF000000 | stack.getItemBarColor());
                Renderer2D.COLOR.begin();
                Renderer2D.COLOR.quad(x + 2, y + size + 1, size - 4, 3, BAR_BACKGROUND);
                Renderer2D.COLOR.quad(x + 2, y + size + 1, (size - 4) * left, 2, color);
                Renderer2D.COLOR.render();
            }

            text.begin(0.5, false, true);
            double line = text.getHeight(shadow);
            double ey = y;
            if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                text.render("God", x, ey, GOD, shadow);
            } else if (enchants.get()) {
                for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries()) {
                    if (entry.getKey().isIn(EnchantmentTags.CURSE)) continue;
                    int level = entry.getIntValue();
                    boolean single = entry.getKey().value().getMaxLevel() == 1;
                    String name = Utils.getEnchantSimpleName(entry.getKey(), single ? 3 : 2) + (single ? "" : level);
                    text.render(name, x, ey, ENCHANT, shadow);
                    ey += line;
                }
            }
            text.end();

            if (stack.getCount() > 1) {
                String count = String.valueOf(stack.getCount());
                text.begin(0.75, false, true);
                text.render(count, x + size - text.getWidth(count, shadow), y + size - text.getHeight(shadow), CoyHud.color(CoyHud.TEXT), shadow);
                text.end();
            }
            x += size + spacing;
        }

        ItemStack held = player.getMainHandStack();
        if (itemName.get() && !held.isEmpty()) {
            String name = held.getName().getString();
            Integer rgb = held.getRarity().getFormatting().getColorValue();
            text.begin(0.75, false, true);
            text.render(name, -text.getWidth(name, shadow) / 2, top - text.getHeight(shadow) - 1, CoyHud.color(0xFF000000 | (rgb == null ? 0xFFFFFF : rgb)), shadow);
            text.end();
        }
    }

    private List<Part> parts(PlayerEntity player) {
        List<Part> parts = new ArrayList<>();
        int nameColor = Friends.get().isFriend(player) ? CoyHud.GOOD : player.isSneaking() ? CoyHud.SUBTLE : CoyHud.TEXT;
        parts.add(new Part(player.getName().getString(), CoyHud.color(nameColor)));

        if (ping.get() && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null && entry.getLatency() > 0) {
                int latency = entry.getLatency();
                parts.add(new Part(latency + "ms", CoyHud.color(latency > 250 ? CoyHud.BAD : latency > 150 ? CoyHud.WARN : CoyHud.SUBTLE)));
            }
        }
        if (health.get()) {
            float total = player.getHealth() + player.getAbsorptionAmount();
            parts.add(new Part(String.valueOf(Math.round(total)), CoyHud.color(CoyHud.health(Math.min(1, player.getHealth() / Math.max(1, player.getMaxHealth()))))));
        }
        if (pops.get()) {
            int popped = PopTracker.get().pops(player);
            if (popped > 0) parts.add(new Part("-" + popped, CoyHud.color(popped >= 5 ? CoyHud.BAD : CoyHud.WARN)));
        }
        if (effects.get()) {
            for (ParticleEffect particle : player.getDataTracker().get(LivingEntityAccessor.coyduck$potionSwirls())) {
                if (!(particle instanceof TintedParticleEffect tinted)) continue;
                int rgb = Math.round(tinted.getRed() * 255) << 16 | Math.round(tinted.getGreen() * 255) << 8 | Math.round(tinted.getBlue() * 255);
                String label = EFFECT_LABEL.get(rgb);
                if (label != null) parts.add(new Part(label, CoyHud.color(EFFECT_COLOR.get(rgb))));
            }
        }
        return parts;
    }

    private static double eatProgress(PlayerEntity player) {
        if (!player.isUsingItem()) return -1;
        ItemStack stack = player.getActiveItem();
        if (!stack.contains(DataComponentTypes.FOOD)) return -1;
        int total = stack.getMaxUseTime(player);
        if (total <= 0) return -1;
        return Math.min(1, player.getItemUseTime() / (double) total);
    }
}
