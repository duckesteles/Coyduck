package dev.coyduck.modules;

import dev.coyduck.Coyduck;
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


/**
 * Shows other players through blocks as they are: their skin, armour, held items and every animation, drawn in front
 * of the walls instead of as an outline or a box.
 */
public class Esp extends Module {
    private static final String CULLABLE = "dev.tr7zw.entityculling.access.Cullable";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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
    private static boolean cullingChecked;

    public Esp() {
        super(Coyduck.CATEGORY, "esp+", "Shows players through blocks with their real model, skin, armour and movement.");
    }

    public static boolean shows(int id) {
        return MeteorClient.mc.world != null && shows(MeteorClient.mc.world.getEntityById(id));
    }

    public static boolean shows(Entity entity) {
        Esp module = Modules.get().get(Esp.class);
        if (module == null || !module.isActive() || !(entity instanceof PlayerEntity player) || player == MeteorClient.mc.player) return false;
        if (!module.friends.get() && Friends.get().isFriend(player)) return false;
        return module.invisible.get() || !player.isInvisible();
    }

    // The EntityCulling mod stops drawing entities it cannot see; its own timeout keeps one drawn for a second.
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        Method method = cullingMethod();
        if (method == null) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!shows(player)) continue;
            try {
                method.invoke(player);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                return;
            }
        }
    }

    private static Method cullingMethod() {
        if (cullingChecked) return keepVisible;
        cullingChecked = true;
        try {
            keepVisible = Class.forName(CULLABLE).getMethod("setTimeout");
        } catch (ReflectiveOperationException ignored) {
            keepVisible = null;
        }
        return keepVisible;
    }
}
