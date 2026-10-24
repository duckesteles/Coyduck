package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.PacketBatch;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

public class ElytraFly extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgDurability = settings.createGroup("Durability");

    private final Setting<Boolean> fluids = sgGeneral.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Also forces the flight controls in water and lava instead of swimming normally. Experimental, the server may pull you back.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> horizontal = sgSpeed.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Blocks moved horizontally per tick.")
        .defaultValue(2.9)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> up = sgSpeed.add(new DoubleSetting.Builder()
        .name("up-speed")
        .description("Blocks moved up per tick while holding jump.")
        .defaultValue(2.4)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> down = sgSpeed.add(new DoubleSetting.Builder()
        .name("down-speed")
        .description("Blocks moved down per tick while holding sneak.")
        .defaultValue(2.4)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> infDurability = sgDurability.add(new BoolSetting.Builder()
        .name("inf-durability")
        .description("Lets the server see you stop gliding for a moment before every 20 ticks of flight, so your elytra never loses durability.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> replace = sgDurability.add(new BoolSetting.Builder()
        .name("replace")
        .description("Swaps in a fresh elytra from your inventory when the one you wear is about to break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replaceDurability = sgDurability.add(new IntSetting.Builder()
        .name("replace-durability")
        .description("Durability left at which the elytra is replaced.")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 50)
        .visible(replace::get)
        .build()
    );

    private static final int TAKEOFF_TRIES = 5;
    private static final int CYCLE_AT = 12;
    private static final int CHEST = 38;
    private static final int TAKEOFF_WINDOW = 40;

    private boolean fluidFlight;
    private int takeoffTries;
    private int airTicks;
    private boolean wasGliding;
    private int glideTicks;
    private int cycleTicks = -1;
    private long holdUntil;

    public ElytraFly() {
        super(Coyduck.CATEGORY, "elytra-fly+", "Flies with an elytra at a set speed in any direction and picks the flight back up after water or lava.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it controls the same flight.", meteor.title);
        }

        fluidFlight = false;
        wasGliding = false;
        takeoffTries = 0;
        airTicks = 0;
        glideTicks = 0;
        cycleTicks = -1;
        holdUntil = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (replace.get()) replaceElytra();
        tickDurability();

        if (inFluid()) {
            if (wasGliding || fluidFlight) {
                fluidFlight = true;
                takeoffTries = TAKEOFF_TRIES;
            }
            airTicks = 0;
            return;
        }
        if (mc.player.isOnGround()) {
            fluidFlight = false;
            wasGliding = false;
            return;
        }

        wasGliding = mc.player.isGliding();
        if (!fluidFlight) return;
        if (++airTicks > TAKEOFF_WINDOW || takeoffTries <= 0) {
            fluidFlight = false;
            return;
        }
        if (wasGliding) return;

        takeoffTries--;
        if (canTakeoff() && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || !hasElytra()) return;

        boolean fluid = fluids.get() && inFluid();
        if (!fluid && !mc.player.isGliding() && !holding()) return;
        if (fluid) {
            if (!mc.player.isOnGround()) fluidFlight = true;
            mc.player.setSprinting(false);
            mc.player.setSwimming(false);
        }

        PlayerInput input = mc.player.input.playerInput;
        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);

        double x = 0;
        double z = 0;
        if (forward != 0 || strafe != 0) {
            float base = RotationManager.get().physicsYaw(mc.player, mc.player.getYaw());
            double angle = Math.toRadians(base) + Math.atan2(-strafe, forward);
            x = -Math.sin(angle) * horizontal.get();
            z = Math.cos(angle) * horizontal.get();
        }

        double y = 0;
        if (input.jump() && !input.sneak()) y = up.get();
        else if (input.sneak() && !input.jump()) y = -down.get();

        ((IVec3d) event.movement).meteor$set(x, y, z);
        mc.player.setVelocity(0, 0, 0);
    }

    private void tickDurability() {
        if (!infDurability.get() || !hasElytra() || mc.player.isOnGround() || inFluid()) {
            glideTicks = 0;
            cycleTicks = -1;
            return;
        }

        if (cycleTicks >= 0) {
            if (++cycleTicks < cycleGap()) return;

            sendTakeoff();
            if (!mc.player.isGliding()) mc.player.startGliding();
            glideTicks = 0;
            cycleTicks = -1;
            holdUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
            return;
        }

        if (!mc.player.isGliding() && !holding()) {
            glideTicks = 0;
            return;
        }

        if (++glideTicks < CYCLE_AT) return;
        sendTakeoff();
        cycleTicks = 0;
        holdUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
    }

    private int cycleGap() {
        return 2 + MathHelper.ceil(LatencyManager.get().jitter() / 50.0);
    }

    private boolean holding() {
        return cycleTicks >= 0 || Util.getMeasuringTimeMs() < holdUntil;
    }

    private void sendTakeoff() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void replaceElytra() {
        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!worn.isOf(Items.ELYTRA) || left(worn) > replaceDurability.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler != mc.player.playerScreenHandler || !handler.getCursorStack().isEmpty()) return;

        InventoryManager inventory = InventoryManager.get();
        int best = -1;
        int bestLeft = replaceDurability.get();
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            ItemStack stack = inventory.stack(i);
            if (!stack.isOf(Items.ELYTRA) || !LivingEntity.canGlideWith(stack, EquipmentSlot.CHEST)) continue;
            int remaining = left(stack);
            if (remaining > bestLeft) {
                best = i;
                bestLeft = remaining;
            }
        }
        if (best == -1) return;

        int from = inventory.handlerSlot(handler, best);
        int chest = inventory.handlerSlot(handler, CHEST);
        if (from == -1 || chest == -1) return;

        PacketBatch.run(() -> {
            mc.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(handler.syncId, chest, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, mc.player);
        });
    }

    private static int left(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamage();
    }

    private boolean canTakeoff() {
        return hasElytra() && !mc.player.hasVehicle() && !mc.player.getAbilities().flying;
    }

    private boolean hasElytra() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private boolean inFluid() {
        return mc.player.isTouchingWater() || mc.player.isInLava();
    }
}
