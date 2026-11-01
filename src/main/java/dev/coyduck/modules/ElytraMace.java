package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ElytraMace extends Module {
    private static final int CHEST_SLOT = 6;
    private static final int PRIORITY = 110;
    private static final int HOTBAR_SIZE = 9;
    private static final int COOLDOWN_TICKS = 10;
    private static final double MARGIN_TICKS = 2;
    private static final double AIM_TICKS = 3;
    private static final double MISS_FALL = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player can be to dive at.")
        .defaultValue(32)
        .range(4, 128)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("How close the target must be to hit it.")
        .defaultValue(3)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> anyFall = sgGeneral.add(new BoolSetting.Builder()
        .name("any-fall")
        .description("Also hits while falling without an elytra dive, such as from a pearl or a ledge.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reGlide = sgGeneral.add(new BoolSetting.Builder()
        .name("re-glide")
        .description("Puts the elytra back on and opens it after a hit, after a miss, or when you press jump in the air.")
        .defaultValue(true)
        .build()
    );

    private boolean committed;
    private boolean struck;
    private boolean warned;
    private boolean jumpHeld;
    private boolean jumpPressed;
    private int cooldown;
    private String status;

    public ElytraMace() {
        super(Coyduck.CATEGORY, "elytra-mace", "Dive with an elytra and it swaps to a chestplate before the target, then hits with the mace while you fall.");
    }

    @Override
    public void onActivate() {
        committed = false;
        struck = false;
        warned = false;
        cooldown = 0;
        status = null;
    }

    @Override
    public String getInfoString() {
        return status;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (cooldown > 0) cooldown--;

        boolean jump = mc.options.jumpKey.isPressed();
        jumpPressed = jump && !jumpHeld;
        jumpHeld = jump;

        if (mc.player.isOnGround() || mc.player.hasVehicle() || mc.player.isTouchingWater()) {
            committed = false;
            struck = false;
            status = null;
            return;
        }

        int mace = InventoryManager.get().find(stack -> stack.isOf(Items.MACE), 0, HOTBAR_SIZE);
        if (mace == -1) {
            status = "no mace";
            return;
        }

        PlayerEntity target = findTarget();
        if (mc.player.isGliding()) dive(target);
        else fall(target, mace);
    }

    private void dive(PlayerEntity target) {
        if (target == null) {
            status = null;
            return;
        }

        status = target.getName().getString();
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.y > -0.2) return;

        Vec3d eye = mc.player.getEyePos();
        double distance = distance(eye, target.getBoundingBox());
        double lead = attackRange.get() + velocity.length() * (LatencyManager.get().rtt() / 50.0 + MARGIN_TICKS);
        if (distance > lead || velocity.dotProduct(target.getBoundingBox().getCenter().subtract(eye)) <= 0) return;

        int chestplate = bestChestplate();
        if (chestplate == -1) {
            if (!warned) warning("No chestplate in the inventory, the mace won't get its bonus while gliding.");
            warned = true;
            return;
        }

        if (swapChest(chestplate)) committed = true;
    }

    private void fall(PlayerEntity target, int mace) {
        Vec3d eye = mc.player.getEyePos();
        boolean smash = MaceItem.shouldDealAdditionalDamage(mc.player);

        if (target != null && smash && (committed || anyFall.get())) {
            Box box = target.getBoundingBox();
            double distance = distance(eye, box);
            double speed = mc.player.getVelocity().length();
            status = target.getName().getString();

            if (distance <= attackRange.get() + speed * AIM_TICKS) {
                boolean aimed = InteractionManager.get().aimed(box.getCenter(), box, PRIORITY);
                if (aimed && cooldown == 0 && distance <= attackRange.get()) hit(target, mace);
            }
        }

        if (reGlide.get() && wantsGlide(target, eye)) glide();
    }

    private void hit(PlayerEntity target, int mace) {
        boolean done = InventoryManager.get().withItem(mace, InventoryManager.SwitchMode.Silent, () -> {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        if (!done) return;

        cooldown = COOLDOWN_TICKS;
        struck = true;
    }

    private boolean wantsGlide(PlayerEntity target, Vec3d eye) {
        if (mc.player.getVelocity().y >= 0) return false;
        if (jumpPressed) return true;
        if (struck) return true;
        if (!committed || mc.player.fallDistance <= MISS_FALL) return false;
        return target == null || eye.y < target.getBoundingBox().minY;
    }

    private void glide() {
        boolean wearing = LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
        if (!wearing) {
            int elytra = InventoryManager.get().find(stack -> stack.isOf(Items.ELYTRA) && LivingEntity.canGlideWith(stack, EquipmentSlot.CHEST), 0, InventoryManager.MAIN_END);
            if (elytra == -1 || !swapChest(elytra)) return;
        }

        if (mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            committed = false;
            struck = false;
        }
    }

    private boolean swapChest(int index) {
        if (InventoryManager.get().isEating()) return false;

        ScreenHandler handler = mc.player.playerScreenHandler;
        if (mc.player.currentScreenHandler != handler || !handler.getCursorStack().isEmpty()) return false;

        int slot = InventoryManager.get().handlerSlot(handler, index);
        if (slot == -1) return false;

        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, CHEST_SLOT, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    private int bestChestplate() {
        for (Item item : new Item[]{Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE}) {
            int found = InventoryManager.get().find(stack -> stack.isOf(item), 0, InventoryManager.MAIN_END);
            if (found != -1) return found;
        }
        return InventoryManager.get().find((ItemStack stack) -> stack.isIn(ItemTags.CHEST_ARMOR), 0, InventoryManager.MAIN_END);
    }

    private PlayerEntity findTarget() {
        PlayerEntity best = null;
        double bestDistance = targetRange.get() * targetRange.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || Friends.get().isFriend(player)) continue;
            double squared = mc.player.squaredDistanceTo(player);
            if (squared < bestDistance) {
                bestDistance = squared;
                best = player;
            }
        }
        return best;
    }

    private static double distance(Vec3d point, Box box) {
        double x = point.x - MathHelper.clamp(point.x, box.minX, box.maxX);
        double y = point.y - MathHelper.clamp(point.y, box.minY, box.maxY);
        double z = point.z - MathHelper.clamp(point.z, box.minZ, box.maxZ);
        return Math.sqrt(x * x + y * y + z * z);
    }
}
