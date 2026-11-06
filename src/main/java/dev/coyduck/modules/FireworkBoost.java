package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InventoryManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public class FireworkBoost extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> minSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Uses a firework when you glide slower than this many blocks per second.")
        .defaultValue(25)
        .range(1, 60)
        .sliderRange(5, 40)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks to wait between fireworks, so one boost can finish before the next.")
        .defaultValue(20)
        .range(5, 100)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<Boolean> needForward = sgGeneral.add(new BoolSetting.Builder()
        .name("need-forward")
        .description("Only boosts while you hold the forward key.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lookingUp = sgGeneral.add(new BoolSetting.Builder()
        .name("not-diving")
        .description("Skips the boost while you dive steeply, since you already pick up speed.")
        .defaultValue(true)
        .build()
    );

    private int ticks;
    private int lastBoost = -1000;

    public FireworkBoost() {
        super(Coyduck.CATEGORY, "firework-boost", "Uses a firework from your inventory while you glide with an elytra and slow down. Your offhand is never touched.");
    }

    @Override
    public void onActivate() {
        lastBoost = -1000;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null || mc.interactionManager == null || !mc.player.isGliding()) return;
        if (Modules.get().isActive(ElytraFly.class) || Modules.get().isActive(ElytraPilot.class)) return;
        if (InventoryManager.get().isEating() || ticks - lastBoost < cooldown.get()) return;
        if (needForward.get() && !mc.options.forwardKey.isPressed()) return;
        if (lookingUp.get() && mc.player.getPitch() > 40) return;

        double speed = mc.player.getVelocity().length() * 20.0;
        if (speed >= minSpeed.get()) return;

        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.FIREWORK_ROCKET), 0, InventoryManager.MAIN_END);
        if (slot == -1) return;

        if (InventoryManager.get().withItem(slot, InventoryManager.SwitchMode.Silent, () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND))) {
            lastBoost = ticks;
        }
    }
}
