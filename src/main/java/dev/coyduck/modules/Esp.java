package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.ShaderGlow;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.lang.reflect.Method;

public class Esp extends Module implements ShaderGlow.Source {
    private static final String CULLABLE = "dev.tr7zw.entityculling.access.Cullable";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgOutline = settings.createGroup("Outline");

    private final Setting<Boolean> fullBody = sgGeneral.add(new BoolSetting.Builder()
        .name("full-body")
        .description("Draws players through blocks with their real model, skin, armor and movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> outline = sgOutline.add(new BoolSetting.Builder()
        .name("outline")
        .description("Draws a shader outline and fill around players, like Meteor's shader ESP. Works together with full body or alone.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgOutline.add(new ColorSetting.Builder()
        .name("players-color")
        .description("Outline and fill color for players whose name has no color, white like Meteor's shader ESP.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(outline::get)
        .build()
    );

    private final Setting<Boolean> nameColor = sgOutline.add(new BoolSetting.Builder()
        .name("name-color")
        .description("Colors each outline like Meteor's shader ESP: friends get the friend color, others the color of their name when Meteor's team color option is on.")
        .defaultValue(true)
        .visible(outline::get)
        .build()
    );

    private final Setting<Integer> outlineWidth = sgOutline.add(new IntSetting.Builder()
        .name("outline-width")
        .description("Outline thickness in pixels.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 6)
        .visible(outline::get)
        .build()
    );

    private final Setting<Double> outlineFill = sgOutline.add(new DoubleSetting.Builder()
        .name("outline-fill")
        .description("How strongly the color fills the model. 0 draws only the outline.")
        .defaultValue(0.2)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(outline::get)
        .build()
    );

    private final Setting<Double> outlineGlow = sgOutline.add(new DoubleSetting.Builder()
        .name("outline-glow")
        .description("How bright the outline glows.")
        .defaultValue(1)
        .range(0, 10)
        .sliderRange(0, 6)
        .visible(outline::get)
        .build()
    );

    private final Setting<Boolean> friends = sgGeneral.add(new BoolSetting.Builder()
        .name("friends")
        .description("Also shows your friends through walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> invisible = sgGeneral.add(new BoolSetting.Builder()
        .name("invisible")
        .description("Also shows players with invisibility, as far as the game draws them.")
        .defaultValue(false)
        .build()
    );

    private static Method keepVisible;
    private static Method setCulled;
    private static boolean cullingChecked;

    public Esp() {
        super(Coyduck.CATEGORY, "esp+", "Shows players through blocks with their real model, skin, armour and movement, and optionally a shader outline around them.");
    }

    public static boolean shows(int id) {
        return MeteorClient.mc.world != null && shows(MeteorClient.mc.world.getEntityById(id));
    }

    public static boolean showsBody(int id) {
        Esp module = Modules.get().get(Esp.class);
        return module != null && module.fullBody.get() && shows(id);
    }

    public static ShaderGlow.Source outlineSource() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        Esp module = modules.get(Esp.class);
        return module != null && module.isActive() && module.outline.get() ? module : null;
    }

    @Override
    public boolean glowing() {
        return isActive() && outline.get();
    }

    @Override
    public boolean shows(EntityRenderState state) {
        return state instanceof PlayerEntityRenderState player && shows(player.id);
    }

    @Override
    public Color maskColor() {
        Color color = outlineColor.get();
        return new Color(color.r, color.g, color.b, 255);
    }

    @Override
    public Color color(EntityRenderState state) {
        if (!nameColor.get() || !(state instanceof PlayerEntityRenderState player) || mc.world == null) return maskColor();
        if (!(mc.world.getEntityById(player.id) instanceof PlayerEntity entity)) return maskColor();
        Color color = PlayerUtils.getPlayerColor(entity, outlineColor.get());
        return new Color(color.r, color.g, color.b, 255);
    }

    @Override
    public int glowWidth() {
        return outlineWidth.get();
    }

    @Override
    public float fillOpacity() {
        return outlineFill.get().floatValue();
    }

    @Override
    public float glowStrength() {
        return outlineGlow.get().floatValue();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (outline.get()) ShaderGlow.esp().render();
    }

    public static boolean shows(Entity entity) {
        if (!(entity instanceof PlayerEntity player) || player == MeteorClient.mc.player) return false;
        Esp module = Modules.get().get(Esp.class);
        if (module == null || !module.isActive() || !module.fullBody.get() && !module.outline.get()) return false;
        if (!module.friends.get() && Friends.get().isFriend(player)) return false;
        return module.invisible.get() || !player.isInvisible();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        onTick(null);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        Method method = cullingMethod();
        if (method == null) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!shows(player)) continue;
            try {
                method.invoke(player);
                if (setCulled != null) setCulled.invoke(player, false);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                return;
            }
        }
    }

    private static Method cullingMethod() {
        if (cullingChecked) return keepVisible;
        cullingChecked = true;
        try {
            Class<?> cullable = Class.forName(CULLABLE);
            keepVisible = cullable.getMethod("setTimeout");
            setCulled = cullable.getMethod("setCulled", boolean.class);
        } catch (ReflectiveOperationException ignored) {
            keepVisible = null;
            setCulled = null;
        }
        return keepVisible;
    }
}
