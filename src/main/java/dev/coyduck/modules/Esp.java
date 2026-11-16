package dev.coyduck.modules;

import dev.coyduck.MeteorColors;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.utils.ShaderGlow;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.lang.reflect.Method;

public class Esp extends Feature implements ShaderGlow.Source {
    private static final String CULLABLE = "dev.tr7zw.entityculling.access.Cullable";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgOutline = settings.createGroup("Outline");

    private final Setting<Boolean> fullBody = sgGeneral.add(new BoolSetting.Builder()
        .name("full-body")
        .description("Draws full player models through blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> outline = sgOutline.add(new BoolSetting.Builder()
        .name("outline")
        .description("Draws a shader outline and fill around players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Rgba> outlineColor = sgOutline.add(new ColorSetting.Builder()
        .name("players-color")
        .description("Color for players without a name color.")
        .defaultValue(new Rgba(255, 255, 255, 255))
        .visible(outline::get)
        .build()
    );

    private final Setting<Boolean> nameColor = sgOutline.add(new BoolSetting.Builder()
        .name("name-color")
        .description("Uses friend and team name colors.")
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
        .description("Fill strength; 0 draws only the outline.")
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
        .description("Also shows invisible players, body included.")
        .defaultValue(false)
        .build()
    );

    private static Method keepVisible;
    private static Method setCulled;
    private static boolean cullingChecked;

    public Esp() {
        super("esp+", "Shows players through walls.");
    }

    public static boolean shows(int id) {
        return MeteorClient.mc.world != null && shows(MeteorClient.mc.world.getEntityById(id));
    }

    public static boolean showsBody(int id) {
        Esp module = Features.get(Esp.class);
        return module != null && module.fullBody.get() && shows(id);
    }

    public static ShaderGlow.Source outlineSource() {
        Esp module = Features.get(Esp.class);
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
        Color color = MeteorColors.of(outlineColor);
        return new Color(color.r, color.g, color.b, 255);
    }

    @Override
    public Color color(EntityRenderState state) {
        if (!nameColor.get() || !(state instanceof PlayerEntityRenderState player) || mc.world == null) return maskColor();
        if (!(mc.world.getEntityById(player.id) instanceof PlayerEntity entity)) return maskColor();
        Color color = PlayerUtils.getPlayerColor(entity, MeteorColors.of(outlineColor));
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
        Esp module = Features.get(Esp.class);
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
