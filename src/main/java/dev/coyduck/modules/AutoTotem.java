package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.EntityAddedEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.logic.AckGate;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.Trace;
import dev.coyduck.utils.ExplosionDamage;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;

public class AutoTotem extends Module {
    public enum Mainhand {
        Off,
        Smart,
        Always
    }

    private static final int CRITICAL_GRACE = 6;
    private static final int NONE = -1;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DANGER_HOLD = 40;
    private static final long HURT_WINDOW = 3_000;
    private static final long VERIFY_INTERVAL = 1_000;
    private static final long POP_DANGER = 5_000;
    private static final double DIVER_HEIGHT = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mainhand> mainhand = sgGeneral.add(new EnumSetting.Builder<Mainhand>()
        .name("mainhand")
        .description("Holds a second totem in the main hand.")
        .defaultValue(Mainhand.Smart)
        .build()
    );

    private final Setting<Double> combatRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("combat-range")
        .description("Smart mode: range of enemies that hurt you.")
        .defaultValue(12)
        .range(0, 32)
        .sliderRange(0, 32)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health")
        .description("Smart mode: holds the totem at or below this health.")
        .defaultValue(12)
        .range(0, 40)
        .sliderRange(0, 40)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Boolean> overFood = sgGeneral.add(new BoolSetting.Builder()
        .name("over-food")
        .description("Takes the main hand totem over held food at low health.")
        .defaultValue(true)
        .visible(() -> mainhand.get() == Mainhand.Smart)
        .build()
    );

    private final Setting<Integer> resumeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("resume-delay")
        .description("Ticks before retaking the main hand totem.")
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
        .description("Switches back when the main hand totem is not needed.")
        .defaultValue(true)
        .visible(() -> mainhand.get() != Mainhand.Off)
        .build()
    );

    private final Setting<Boolean> offhandGapple = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand-gapple")
        .description("Holding use eats a gapple from the offhand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hotbarSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-swap")
        .description("Refills the offhand with the swap-hands key.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pearlGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("pearl-guard")
        .description("Cancels pearls that would hit a crystal at your face.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> closeContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("close-containers")
        .description("Closes open containers when you pop.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> warn = sgGeneral.add(new BoolSetting.Builder()
        .name("warn")
        .description("Warns in chat when you run out of totems.")
        .defaultValue(true)
        .build()
    );

    private static final int MAIN_MISSING_TICKS = 1;
    private static final double PEARL_GUARD_RANGE = 2.0;
    private static final double PEARL_SIZE = 0.125;

    private final long[] sourceUsedAt = new long[InventoryManager.MAIN_END];
    private boolean gapple;
    private long refillAt;
    private long pendingSince;
    private static final double SYNC_SHARE = 0.6;
    private long gappleAt;
    private int foodTicks;
    private int mainMissing;
    private boolean pending;
    private boolean verifying;
    private long nextVerify;
    private long popExpectedUntil;
    private int dangerHold;
    private int pendingSource = NONE;
    private int skipMask;
    private int pendingSyncId;
    private int barrier;
    private final AckGate mainGate = new AckGate();
    private long pendingUntil;
    private int attempts;
    private boolean confirmed;

    private int reserveSlot = NONE;
    private int gappleHome = NONE;
    private float lastHp = -1;
    private long lastHurtAt;
    private long lastPopAt;
    private long emptyEatAt;
    private int previousSlot = NONE;
    private int expectedSlot = NONE;
    private int manualTicks = CRITICAL_GRACE;
    private int resumeTicks;

    private int totems;
    private boolean warned;

    public AutoTotem() {
        super(Coyduck.CATEGORY, "auto-totem+", "Keeps a totem in your offhand.");
    }

    public static boolean canAffordPop() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        return module != null && module.isActive() && module.canAct() && !module.pending && module.confirmed
            && !popInFlight() && isTotem(module.mc.player.getOffHandStack()) && module.totems >= 2;
    }

    public static int totemSlot() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        return module == null ? 8 : module.slot.get() - 1;
    }

    public static boolean wantsMainTotem() {
        AutoTotem module = Modules.get().get(AutoTotem.class);
        if (module == null || !module.isActive()) return false;
        return switch (module.mainhand.get()) {
            case Off -> false;
            case Smart -> module.dangerHold > 0;
            case Always -> true;
        };
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
        reset();
        resumeTicks = 0;
        warned = false;
        disableConflicts();
    }

    private void reset() {
        gapple = false;
        mainMissing = 0;
        mainGate.reset();
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
        if (manualTicks < CRITICAL_GRACE) manualTicks++;
        if (foodTicks > 0) foodTicks--;
        if (dangerHold > 0) dangerHold--;
        if (pending && Util.getMeasuringTimeMs() >= pendingUntil) retry();
        refill(false);
        if (!pending && !confirmed && !mc.player.isUsingItem() && isTotem(mc.player.getOffHandStack()) && Util.getMeasuringTimeMs() >= nextVerify) verify();
        updateMainhand();
        updateGapple();

        totems = InventoryManager.get().count(AutoTotem::isTotem);
        if (pending) return;
        if (totems > 0) warned = false;
        else if (warn.get() && !warned) {
            warned = true;
            warning("Out of totems.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!pearlGuard.get() || !canAct() || !(event.packet instanceof PlayerInteractItemC2SPacket packet)) return;
        if (!mc.player.getStackInHand(packet.getHand()).isOf(Items.ENDER_PEARL)) return;

        Vec3d start = mc.player.getEyePos().subtract(0, 0.1, 0);
        Vec3d end = start.add(Vec3d.fromPolar(packet.getPitch(), packet.getYaw()).multiply(PEARL_GUARD_RANGE));
        Box sweep = new Box(start, end).expand(1.0);
        for (Entity entity : mc.world.getOtherEntities(mc.player, sweep, entity -> entity instanceof EndCrystalEntity)) {
            if (entity.getBoundingBox().expand(PEARL_SIZE).raycast(start, end).isPresent() || entity.getBoundingBox().expand(PEARL_SIZE).contains(start)) {
                event.cancel();
                InventoryManager.get().resync();
                warning("Pearl blocked, it would hit a crystal.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        if (event.packet instanceof PlayerActionResponseS2CPacket packet) mainGate.ack(packet.sequence());
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
        if (!(event.entity instanceof EndCrystalEntity crystal) || !canAct()) return;
        if (lethal(crystal, mc.player.getHealth() + mc.player.getAbsorptionAmount())) {
            if (mainhand.get() == Mainhand.Smart) dangerHold = DANGER_HOLD;
            updateMainhand();
        }
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
        Arrays.fill(sourceUsedAt, 0);
        lastPopAt = 0;
    }

    private void onPop() {
        InventoryManager inventory = InventoryManager.get();
        int server = inventory.serverSlot();
        int mask = inventory.heldMask();
        ItemStack main = inventory.stack(server);

        boolean ambiguous = mask != 0 && (isTotem(main) || maskHoldsTotem(mask));

        boolean mainPopped = isTotem(main) && (mask == 0 || !isTotem(mc.player.getOffHandStack()));
        Trace.log("totem pop from {} main {} off {}{}", mainPopped ? "main hand" : "offhand", main.getItem(), mc.player.getOffHandStack().getItem(), ambiguous ? " ambiguous" : "");
        if (mainPopped) main.decrement(1);
        else mc.player.getOffHandStack().decrement(1);
        if (gapple && mainPopped && (mainhand.get() == Mainhand.Off || !hotbarTotem(server, gappleHome))) {
            gapple = false;
            mainMissing = 0;
        }

        skipMask = ambiguous ? mask | (1 << server) : 0;

        confirmed = false;
        popExpectedUntil = 0;
        lastPopAt = Util.getMeasuringTimeMs();
        if (mainhand.get() == Mainhand.Smart) dangerHold = DANGER_HOLD;
        if (closeContainers.get() && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();

        clearPending();
        attempts = 0;
        refill(!mainPopped);
        skipMask = 0;
        if (!pending && !gapple && !mc.player.isUsingItem()) verify();
        updateMainhand();
    }

    private boolean hotbarTotem(int except, int alsoExcept) {
        InventoryManager inventory = InventoryManager.get();
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (i != except && i != alsoExcept && isTotem(inventory.stack(i))) return true;
        }
        return false;
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
        pendingSince = Util.getMeasuringTimeMs();
        nextVerify = Util.getMeasuringTimeMs() + VERIFY_INTERVAL;
        barrier = inventory.barrier();
    }

    private void onOffhandUpdate() {
        if (pending && verifying && !gapple && !isTotem(mc.player.getOffHandStack()) && Util.getMeasuringTimeMs() - refillAt >= LatencyManager.get().rtt() * SYNC_SHARE) {
            Trace.log("totem offhand empty while verifying, refilling now");
            clearPending();
            refill(false);
            return;
        }
        if (pending) {
            long since = Util.getMeasuringTimeMs() - pendingSince;
            if (since >= LatencyManager.get().rtt() * SYNC_SHARE) {
                Trace.log("totem server inventory sync after {}ms stands in for the barrier", since);
                onBarrier();
            }
            return;
        }

        confirmed = isTotem(mc.player.getOffHandStack());
        refill(false);
    }

    private void onBarrier() {
        if (gapple) {
            clearPending();
            attempts = 0;
            return;
        }
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
            Trace.log("totem refill confirmed after {}ms", Util.getMeasuringTimeMs() - refillAt);
            attempts = 0;
            confirmed = playerScreen;
            return;
        }

        Trace.log("totem refill not confirmed after {}ms, attempt {}", Util.getMeasuringTimeMs() - refillAt, attempts + 1);
        if (++attempts <= MAX_ATTEMPTS) refill(true);
    }

    private void retry() {
        if (gapple) {
            clearPending();
            attempts = 0;
            return;
        }
        if (verifying) {
            clearPending();
            return;
        }

        clearPending();
        Trace.log("totem refill timed out after {}ms, attempt {}", Util.getMeasuringTimeMs() - refillAt, attempts + 1);
        if (++attempts <= MAX_ATTEMPTS + 2) refill(true);
        else attempts = 0;
    }

    private void refill(boolean force) {
        if (pending || gapple) return;
        if (!force && isTotem(mc.player.getOffHandStack())) return;

        int source = offhandSource();
        if (source == NONE) return;
        if (mc.player.isUsingItem() && !isTotem(mc.player.getOffHandStack())) {
            Trace.log("totem refill stops item use, the offhand is empty");
            mc.interactionManager.stopUsingItem(mc.player);
        }

        int syncId = mc.player.currentScreenHandler.syncId;
        InventoryManager inventory = InventoryManager.get();
        InventoryManager.Swap swap = inventory.swapWithOffhand(source, hotbarSwap.get());
        if (swap == InventoryManager.Swap.NONE) return;
        sourceUsedAt[source] = Util.getMeasuringTimeMs();
        refillAt = Util.getMeasuringTimeMs();
        Trace.log("totem refill from slot {} by {}{}", source, swap, force ? " forced" : "");

        if (swap == InventoryManager.Swap.CLICK) inventory.resync();

        pending = true;
        verifying = false;
        pendingSource = swap == InventoryManager.Swap.CLICK ? source : NONE;
        pendingSyncId = syncId;
        pendingUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
        pendingSince = Util.getMeasuringTimeMs();
        barrier = inventory.barrier();
    }

    private void updateGapple() {
        InventoryManager inventory = InventoryManager.get();
        if (mainGate.expired(Util.getMeasuringTimeMs())) awaitMain();
        boolean mainTotem = isTotem(mc.player.getMainHandStack()) && mainGate.open();

        if (gapple) {
            mainMissing = mainTotem ? 0 : mainMissing + 1;
            boolean eating = mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.OFF_HAND;
            ItemStack offhand = mc.player.getOffHandStack();
            boolean lost = !isGapple(offhand) && (!isTotem(offhand) || Util.getMeasuringTimeMs() - gappleAt >= LatencyManager.get().timeout());
            if ((!eating && !mc.options.useKey.isPressed()) || lost || mainMissing > MAIN_MISSING_TICKS || !offhandGapple.get()) {
                gapple = false;
                mainMissing = 0;
                refill(false);
            }
            return;
        }

        if (offhandGapple.get() && mc.options.useKey.isPressed() && mc.player.getMainHandStack().isEmpty() && !mc.player.isUsingItem()
            && mc.currentScreen == null && Util.getMeasuringTimeMs() >= emptyEatAt) {
            totemIntoEmptyHand();
            return;
        }

        if (!offhandGapple.get() || !mc.options.useKey.isPressed() || !mainTotem || pending || mc.currentScreen != null) return;
        if (!isTotem(mc.player.getOffHandStack()) || mc.player.isUsingItem()) return;

        int source = gappleSource();
        if (source == NONE || inventory.handlerSlot(mc.player.currentScreenHandler, source) == -1) return;
        InventoryManager.Swap swap = inventory.swapWithOffhand(source, hotbarSwap.get());
        if (swap == InventoryManager.Swap.NONE) return;

        if (swap == InventoryManager.Swap.CLICK) inventory.resync();
        sourceUsedAt[source] = Util.getMeasuringTimeMs();
        gappleAt = Util.getMeasuringTimeMs();
        gappleHome = source;
        gapple = true;
        mainMissing = 0;
    }

    private void totemIntoEmptyHand() {
        InventoryManager inventory = InventoryManager.get();
        int selected = inventory.selectedSlot();
        if (selected != slot.get() - 1) return;

        int source = inventory.find(AutoTotem::isTotem, InventoryManager.HOTBAR_END, InventoryManager.MAIN_END);
        if (source == NONE) {
            for (int i = 0; i < InventoryManager.HOTBAR_END && source == NONE; i++) {
                if (i != selected && isTotem(inventory.stack(i))) source = i;
            }
        }
        if (source != NONE && inventory.swapWithHotbar(source, selected)) {
            emptyEatAt = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
            awaitMain();
        }
    }

    private void awaitMain() {
        mainGate.await(InventoryManager.get().barrier(), Util.getMeasuringTimeMs(), LatencyManager.get().timeout());
    }

    private int gappleSource() {
        InventoryManager inventory = InventoryManager.get();
        int server = inventory.serverSlot();
        for (Item item : new Item[] {Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE}) {
            if (hotbarSwap.get()) {
                for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
                    if (i != server && i != reserveSlot && inventory.stack(i).isOf(item) && !recentSource(i)) return i;
                }
            }
            for (int i = InventoryManager.HOTBAR_END; i < InventoryManager.MAIN_END; i++) {
                if (inventory.stack(i).isOf(item) && !recentSource(i)) return i;
            }
            for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
                if (i != server && i != reserveSlot && inventory.stack(i).isOf(item) && !recentSource(i)) return i;
            }
        }
        return NONE;
    }

    private static boolean isGapple(ItemStack stack) {
        return stack.isOf(Items.ENCHANTED_GOLDEN_APPLE) || stack.isOf(Items.GOLDEN_APPLE);
    }

    private int offhandSource() {
        int home = gappleHome;
        gappleHome = NONE;
        if (home != NONE && home != InventoryManager.get().serverSlot() && !isTotem(mc.player.getOffHandStack()) && isTotem(InventoryManager.get().stack(home))
            && InventoryManager.get().handlerSlot(mc.player.currentScreenHandler, home) != -1) return home;
        int source = offhandSource(true);
        if (source == NONE) source = offhandSource(false);
        return source != NONE ? source : reserveSource();
    }

    private int reserveSource() {
        if (isGapple(mc.player.getOffHandStack())) return NONE;
        InventoryManager inventory = InventoryManager.get();
        int server = inventory.serverSlot();
        for (int i : new int[] {slot.get() - 1, reserveSlot}) {
            if (i >= 0 && i < InventoryManager.HOTBAR_END && i != server && (skipMask & (1 << i)) == 0 && isTotem(inventory.stack(i))) return i;
        }
        return NONE;
    }

    private int offhandSource(boolean avoidRecent) {
        InventoryManager inventory = InventoryManager.get();
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (hotbarSwap.get()) {
            int server = inventory.serverSlot();
            for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
                if (avoidRecent && recentSource(i)) continue;
                if (i != server && i != reserveSlot && i != slot.get() - 1 && (skipMask & (1 << i)) == 0 && isTotem(inventory.stack(i))) return i;
            }
        }

        for (int i = InventoryManager.HOTBAR_END; i < InventoryManager.MAIN_END; i++) {
            if (avoidRecent && recentSource(i)) continue;
            if (isTotem(inventory.stack(i)) && inventory.handlerSlot(handler, i) != -1) return i;
        }

        int server = inventory.serverSlot();
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (avoidRecent && recentSource(i)) continue;
            if (i != server && i != reserveSlot && (skipMask & (1 << i)) == 0 && isTotem(inventory.stack(i))) return i;
        }

        if (avoidRecent && recentSource(server) || isGapple(mc.player.getOffHandStack())) return NONE;
        return (skipMask & (1 << server)) == 0 && isTotem(inventory.stack(server)) ? server : NONE;
    }

    private boolean recentSource(int index) {
        return Util.getMeasuringTimeMs() - sourceUsedAt[index] < 2L * LatencyManager.get().timeout();
    }

    private void updateMainhand() {
        if (MiningManager.get().isHolding()) return;
        if (gapple) dangerHold = DANGER_HOLD;

        InventoryManager inventory = InventoryManager.get();
        int selected = inventory.selectedSlot();

        if (expectedSlot != NONE && selected != expectedSlot) {
            resumeTicks = resumeDelay.get();
            manualTicks = 0;
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

        boolean popped = selected == reserveSlot && inventory.stack(selected).isEmpty() && !inventory.isEating();
        if (overFoodNow()) foodTicks = 0;
        if (!popped && busy(inventory.stack(selected))) {
            if (activelyBusy()) resumeTicks = Math.max(resumeTicks, resumeDelay.get());
            else foodTicks = resumeDelay.get();
        }
        boolean critical = mainhand.get() == Mainhand.Smart && dangerHold > 0 && !inventory.isEating()
            && manualTicks >= CRITICAL_GRACE && !mc.options.useKey.isPressed()
            && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
        if ((!popped && !critical && (resumeTicks > 0 || foodTicks > 0)) || (!gapple && !isTotem(mc.player.getOffHandStack()))) return;

        int hotbar = NONE;
        for (int i = 0; i < InventoryManager.HOTBAR_END && hotbar == NONE; i++) {
            if (!(gapple && i == gappleHome) && isTotem(inventory.stack(i))) hotbar = i;
        }
        if (hotbar == NONE) {
            if (pending && (verifying || pendingSource != NONE)) return;
            int source = NONE;
            for (int i = InventoryManager.HOTBAR_END; i < InventoryManager.MAIN_END && source == NONE; i++) {
                if (isTotem(inventory.stack(i)) && !recentSource(i)) source = i;
            }
            if (source == NONE) return;
            sourceUsedAt[source] = Util.getMeasuringTimeMs();

            hotbar = reserveHotbar();
            if (hotbar == NONE || !inventory.swapWithHotbar(source, hotbar)) return;
            awaitMain();
        }

        if (!inventory.select(hotbar)) return;
        Trace.log("totem main hand from slot {} ({}, hp {})", hotbar, popped ? "after main pop" : critical ? "critical" : mainhand.get() == Mainhand.Always ? "always" : "danger", mc.player.getHealth() + mc.player.getAbsorptionAmount());
        if (previousSlot == NONE) previousSlot = selected;
        reserveSlot = hotbar;
        expectedSlot = hotbar;
    }

    private boolean busy(ItemStack held) {
        if (activelyBusy()) return true;
        if (overFoodNow()) return false;
        return held.contains(DataComponentTypes.FOOD) || held.contains(DataComponentTypes.CONSUMABLE);
    }

    private boolean activelyBusy() {
        return mc.player.isUsingItem() || mc.options.useKey.isPressed() || mc.options.attackKey.isPressed() || mc.interactionManager.isBreakingBlock();
    }

    private boolean overFoodNow() {
        return overFood.get() && mainhand.get() == Mainhand.Smart && !activelyBusy() && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
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
        Trace.log("totem main hand released, back to slot {}", previousSlot);

        reserveSlot = NONE;
        previousSlot = NONE;
    }

    private boolean danger() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        long now = Util.getMeasuringTimeMs();
        if (lastHp >= 0 && hp < lastHp - 0.5f) lastHurtAt = now;
        lastHp = hp;

        Box area = mc.player.getBoundingBox().expand(ExplosionDamage.CRYSTAL_RANGE);
        for (EndCrystalEntity crystal : mc.world.getEntitiesByClass(EndCrystalEntity.class, area, crystal -> true)) {
            if (lethal(crystal, hp)) return true;
        }
        if (now - lastPopAt < POP_DANGER) return true;

        boolean hurt = hp <= health.get() || now - lastHurtAt < HURT_WINDOW;
        double enemyRange = combatRange.get() * combatRange.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player) || ExplosionDamage.isInvulnerable(player)) continue;
            if (hurt && player.squaredDistanceTo(mc.player) <= enemyRange) return true;
            if (diver(player, enemyRange)) return true;
        }
        return false;
    }

    private boolean lethal(EndCrystalEntity crystal, float hp) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        if (crystal.squaredDistanceTo(mc.player) > range) return false;
        Vec3d center = crystal.getEntityPos();
        return ExplosionDamage.upperBound(mc.player, mc.player.getEntityPos(), center) >= hp && ExplosionDamage.crystal(mc.player, center) >= hp;
    }

    private boolean diver(PlayerEntity player, double enemyRange) {
        if (!player.getMainHandStack().isOf(Items.MACE)) return false;
        double above = player.getY() - mc.player.getY();
        if (above < 2 || above > DIVER_HEIGHT || player.isOnGround()) return false;
        double dx = player.getX() - mc.player.getX();
        double dz = player.getZ() - mc.player.getZ();
        return dx * dx + dz * dz <= enemyRange;
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
