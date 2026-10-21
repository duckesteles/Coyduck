package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.ExplosionDamage;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.Offhand;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;

import java.util.List;

public class AutoTotem extends Module {
    public enum Mainhand {
        Off,
        Smart,
        Always
    }

    private static final int NONE = -1;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DANGER_HOLD = 40;
    private static final long VERIFY_INTERVAL = 1_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mainhand> mainhand = sgGeneral.add(new EnumSetting.Builder<Mainhand>()
        .name("mainhand")
        .description("Holds a second totem in the main hand. The main hand pops first, so a second hit before the refill arrives still finds a totem.")
        .defaultValue(Mainhand.Smart)
        .build()
    );

    private final Setting<Double> combatRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("combat-range")
        .description("Smart holds the main hand totem while an enemy is this close.")
        .defaultValue(12)
        .range(0, 32)
        .sliderRange(0, 32)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health")
        .description("Smart also holds the main hand totem at or below this health, or while a crystal near you is lethal.")
        .defaultValue(12)
        .range(0, 40)
        .sliderRange(0, 40)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Integer> resumeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("resume-delay")
        .description("Ticks to wait after you switch away from the main hand totem, eat or hold food before taking it back.")
        .defaultValue(20)
        .range(0, 200)
        .sliderRange(0, 100)
        .visible(() -> mainhand.get() != Mainhand.Off)
        .build()
    );

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot")
        .description("Hotbar slot a totem is moved into when the hotbar has none.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(() -> mainhand.get() != Mainhand.Off)
        .build()
    );

    private final Setting<Boolean> restore = sgGeneral.add(new BoolSetting.Builder()
        .name("restore")
        .description("Switches back to the previous slot once the main hand totem is no longer needed.")
        .defaultValue(true)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Boolean> totemGap = sgGeneral.add(new BoolSetting.Builder()
        .name("totem-gap")
        .description("Right clicking with a totem in your main hand eats a golden apple, then puts the totem back once you let go.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> closeContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("close-containers")
        .description("Closes an open container when you pop. The server never reports the offhand while a container is open, so the refill could not be confirmed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> warn = sgGeneral.add(new BoolSetting.Builder()
        .name("warn")
        .description("Warns in chat when you run out of totems.")
        .defaultValue(true)
        .build()
    );

    private boolean pending;
    private boolean verifying;
    private long nextVerify;
    private long popExpectedUntil;
    private int dangerHold;
    private int pendingSource = NONE;
    private int skipMask;
    private int pendingSyncId;
    private int barrier;
    private long pendingUntil;
    private int attempts;
    private boolean confirmed;

    private int reserveSlot = NONE;
    private int previousSlot = NONE;
    private int expectedSlot = NONE;
    private int resumeTicks;
    private int gapReturn = NONE;
    private int gapSource = NONE;

    private int totems;
    private boolean warned;

    public AutoTotem() {
        super(Coyduck.CATEGORY, "auto-totem+", "Keeps a totem in your offhand at any ping, under any screen.");
    }

    public static boolean canAffordPop() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        return module != null && module.isActive() && module.canAct() && !module.pending && module.confirmed
            && !popInFlight() && isTotem(module.mc.player.getOffHandStack()) && module.totems >= 2;
    }

    public static boolean popInFlight() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        return module != null && Util.getMeasuringTimeMs() < module.popExpectedUntil;
    }

    public static void notePop() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        if (module == null) return;

        module.popExpectedUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
        module.confirmed = false;
    }

    @Override
    public void onActivate() {
        clearPending();
        attempts = 0;
        confirmed = false;
        reserveSlot = NONE;
        previousSlot = NONE;
        expectedSlot = NONE;
        resumeTicks = 0;
        dangerHold = 0;
        nextVerify = 0;
        popExpectedUntil = 0;
        warned = false;
        disableConflicts();
    }

    @Override
    public void onDeactivate() {
        if (canAct()) release();
        reserveSlot = NONE;
        previousSlot = NONE;
        expectedSlot = NONE;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(totems);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (!canAct()) return;

        disableConflicts();
        if (resumeTicks > 0) resumeTicks--;
        if (dangerHold > 0) dangerHold--;
        if (pending && Util.getMeasuringTimeMs() >= pendingUntil) retry();
        refill(false);
        if (!pending && !confirmed && isTotem(mc.player.getOffHandStack()) && Util.getMeasuringTimeMs() >= nextVerify) verify();
        if (!tickGap()) updateMainhand();

        totems = InventoryManager.get().count(AutoTotem::isTotem);
        if (pending) return;
        if (totems > 0) warned = false;
        else if (warn.get() && !warned) {
            warned = true;
            warning("Out of totems.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        switch (event.packet) {
            case EntityStatusS2CPacket packet when packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING && packet.getEntity(mc.world) == mc.player -> onPop();
            case PlayerActionResponseS2CPacket packet when pending && packet.sequence() >= barrier -> onBarrier();
            case ScreenHandlerSlotUpdateS2CPacket packet when packet.getSyncId() == 0 && packet.getSlot() == PlayerScreenHandler.OFFHAND_ID -> onOffhandUpdate();
            case SetPlayerInventoryS2CPacket packet when packet.slot() == InventoryManager.OFFHAND -> onOffhandUpdate();
            case InventoryS2CPacket packet when packet.syncId() == 0 -> onOffhandUpdate();
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct()) updateMainhand();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        clearPending();
        attempts = 0;
        confirmed = false;
        nextVerify = 0;
        popExpectedUntil = 0;
        dangerHold = 0;
        reserveSlot = NONE;
        previousSlot = NONE;
        expectedSlot = NONE;
    }

    private void onPop() {
        InventoryManager inventory = InventoryManager.get();
        int server = inventory.serverSlot();
        int mask = inventory.heldMask();
        ItemStack main = inventory.stack(server);

        boolean ambiguous = mask != 0 && (isTotem(main) || maskHoldsTotem(mask));

        if (mask == 0 && isTotem(main)) main.decrement(1);
        else mc.player.getOffHandStack().decrement(1);

        skipMask = ambiguous ? mask | (1 << server) : 0;

        confirmed = false;
        popExpectedUntil = 0;
        if (closeContainers.get() && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();

        clearPending();
        attempts = 0;
        refill(true);
        skipMask = 0;
        if (!pending) verify();
    }

    private boolean maskHoldsTotem(int mask) {
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if ((mask & (1 << i)) != 0 && isTotem(InventoryManager.get().stack(i))) return true;
        }
        return false;
    }

    private void verify() {
        int syncId = mc.player.currentScreenHandler.syncId;
        InventoryManager inventory = InventoryManager.get();
        inventory.resync();

        pending = true;
        verifying = true;
        pendingSource = NONE;
        pendingSyncId = syncId;
        pendingUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
        nextVerify = Util.getMeasuringTimeMs() + VERIFY_INTERVAL;
        barrier = inventory.barrier();
    }

    private void onOffhandUpdate() {
        if (pending) return;

        confirmed = isTotem(mc.player.getOffHandStack());
        refill(false);
    }

    private void onBarrier() {
        if (verifying) {
            boolean screen = mc.player.currentScreenHandler.syncId == pendingSyncId && mc.player.currentScreenHandler == mc.player.playerScreenHandler;
            clearPending();
            if (isTotem(mc.player.getOffHandStack())) confirmed = screen;
            else refill(false);
            return;
        }

        if (mc.player.currentScreenHandler.syncId != pendingSyncId) {
            clearPending();
            refill(true);
            return;
        }

        boolean playerScreen = mc.player.currentScreenHandler == mc.player.playerScreenHandler;
        boolean holding = isTotem(mc.player.getOffHandStack());

        clearPending();

        if (holding) {
            attempts = 0;
            confirmed = playerScreen;
            return;
        }

        if (++attempts <= MAX_ATTEMPTS) refill(true);
    }

    private void retry() {
        if (verifying) {
            clearPending();
            return;
        }

        clearPending();
        if (++attempts <= MAX_ATTEMPTS + 2) refill(true);
        else attempts = 0;
    }

    private void refill(boolean force) {
        if (pending) return;
        if (!force && isTotem(mc.player.getOffHandStack())) return;

        int source = offhandSource();
        if (source == NONE) return;

        int syncId = mc.player.currentScreenHandler.syncId;
        InventoryManager inventory = InventoryManager.get();
        InventoryManager.Swap swap = inventory.swapWithOffhand(source);
        if (swap == InventoryManager.Swap.NONE) return;

        if (swap == InventoryManager.Swap.CLICK) inventory.resync();

        pending = true;
        verifying = false;
        pendingSource = swap == InventoryManager.Swap.CLICK ? source : NONE;
        pendingSyncId = syncId;
        pendingUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
        barrier = inventory.barrier();
    }

    private int offhandSource() {
        InventoryManager inventory = InventoryManager.get();
        ScreenHandler handler = mc.player.currentScreenHandler;

        for (int i = InventoryManager.HOTBAR_END; i < InventoryManager.MAIN_END; i++) {
            if (isTotem(inventory.stack(i)) && inventory.handlerSlot(handler, i) != -1) return i;
        }

        int server = inventory.serverSlot();
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (i != server && i != reserveSlot && (skipMask & (1 << i)) == 0 && isTotem(inventory.stack(i))) return i;
        }

        return (skipMask & (1 << server)) == 0 && isTotem(inventory.stack(server)) ? server : NONE;
    }

    private boolean tickGap() {
        InventoryManager inventory = InventoryManager.get();

        if (gapReturn != NONE) {
            if (mc.player.isUsingItem() || mc.options.useKey.isPressed()) return true;

            if (gapSource != NONE) inventory.swapWithHotbar(gapSource, gapReturn);
            else inventory.select(gapReturn);
            gapReturn = NONE;
            gapSource = NONE;
            return false;
        }

        if (!totemGap.get() || !mc.options.useKey.isPressed() || mc.player.isUsingItem() || mc.currentScreen != null) return false;

        int selected = inventory.selectedSlot();
        if (!isTotem(inventory.stack(selected))) return false;

        int hotbar = findGap(0, InventoryManager.HOTBAR_END);
        if (hotbar != NONE) {
            if (!inventory.select(hotbar)) return false;
        } else {
            int source = findGap(InventoryManager.HOTBAR_END, InventoryManager.MAIN_END);
            if (source == NONE || !inventory.swapWithHotbar(source, selected)) return false;
            gapSource = source;
        }

        gapReturn = selected;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        return true;
    }

    private static int findGap(int from, int to) {
        InventoryManager inventory = InventoryManager.get();
        int slot = inventory.find(stack -> stack.isOf(Items.ENCHANTED_GOLDEN_APPLE), from, to);
        return slot != NONE ? slot : inventory.find(stack -> stack.isOf(Items.GOLDEN_APPLE), from, to);
    }

    private void updateMainhand() {
        if (MiningManager.get().isHolding()) return;

        InventoryManager inventory = InventoryManager.get();
        int selected = inventory.selectedSlot();

        if (expectedSlot != NONE && selected != expectedSlot) {
            resumeTicks = resumeDelay.get();
            reserveSlot = NONE;
            previousSlot = NONE;
        }
        expectedSlot = NONE;

        boolean wanted = switch (mainhand.get()) {
            case Off -> false;
            case Smart -> {
                if (danger()) dangerHold = DANGER_HOLD;
                yield dangerHold > 0;
            }
            case Always -> true;
        };

        if (!wanted) {
            release();
            return;
        }

        if (isTotem(inventory.stack(selected))) {
            reserveSlot = selected;
            expectedSlot = selected;
            return;
        }

        if (busy(inventory.stack(selected))) resumeTicks = Math.max(resumeTicks, resumeDelay.get());
        if (resumeTicks > 0 || !isTotem(mc.player.getOffHandStack())) return;

        int hotbar = inventory.find(AutoTotem::isTotem, 0, InventoryManager.HOTBAR_END);
        if (hotbar == NONE) {
            int source = inventory.find(AutoTotem::isTotem, InventoryManager.HOTBAR_END, InventoryManager.MAIN_END);
            if (source == NONE) return;

            hotbar = reserveHotbar();
            if (hotbar == NONE || !inventory.swapWithHotbar(source, hotbar)) return;
        }

        if (!inventory.select(hotbar)) return;
        if (previousSlot == NONE) previousSlot = selected;
        reserveSlot = hotbar;
        expectedSlot = hotbar;
    }

    private boolean busy(ItemStack held) {
        if (mc.player.isUsingItem() || mc.options.useKey.isPressed()) return true;
        if (mc.options.attackKey.isPressed() || mc.interactionManager.isBreakingBlock()) return true;
        return held.contains(DataComponentTypes.FOOD) || held.contains(DataComponentTypes.CONSUMABLE);
    }

    private int reserveHotbar() {
        InventoryManager inventory = InventoryManager.get();
        int preferred = slot.get() - 1;
        if (!busyItem(inventory.stack(preferred))) return preferred;

        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (inventory.stack(i).isEmpty()) return i;
        }
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (!busyItem(inventory.stack(i))) return i;
        }
        return NONE;
    }

    private static boolean busyItem(ItemStack stack) {
        return stack.contains(DataComponentTypes.FOOD) || stack.contains(DataComponentTypes.CONSUMABLE);
    }

    private void release() {
        if (reserveSlot == NONE) return;

        InventoryManager inventory = InventoryManager.get();
        if (restore.get() && previousSlot != NONE && inventory.selectedSlot() == reserveSlot && !inventory.select(previousSlot)) return;

        reserveSlot = NONE;
        previousSlot = NONE;
    }

    private boolean danger() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= health.get()) return true;

        double enemyRange = combatRange.get() * combatRange.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && player.isAlive() && !Friends.get().isFriend(player) && !ExplosionDamage.isInvulnerable(player)
                && player.squaredDistanceTo(mc.player) <= enemyRange) return true;
        }

        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.squaredDistanceTo(mc.player) <= range && ExplosionDamage.crystal(mc.player, crystal.getEntityPos()) >= hp) return true;
        }

        return false;
    }

    private void disableConflicts() {
        for (Class<? extends Module> conflict : List.of(meteordevelopment.meteorclient.systems.modules.combat.AutoTotem.class, Offhand.class)) {
            Module module = Modules.get().get(conflict);
            if (module != null && module.isActive()) {
                module.toggle();
                info("Disabled %s, it fights over the offhand.", module.title);
            }
        }
    }

    private void clearPending() {
        pending = false;
        verifying = false;
        pendingSource = NONE;
        pendingUntil = 0;
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isCreative();
    }

    private static boolean isTotem(ItemStack stack) {
        return stack.contains(DataComponentTypes.DEATH_PROTECTION);
    }
}
