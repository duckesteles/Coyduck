package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.utils.ExplosionDamage;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.Offhand;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;

import java.util.List;

public class AutoTotem extends Module {
    public enum Mainhand {
        Off,
        Smart,
        Always
    }

    private enum Pending {
        NONE,
        CLICK,
        VERIFY,
        HANDS
    }

    private static final int NONE = -1;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mainhand> mainhand = sgGeneral.add(new EnumSetting.Builder<Mainhand>()
        .name("mainhand")
        .description("Holds a second totem in the main hand. The main hand pops first, so the offhand totem survives a double pop.")
        .defaultValue(Mainhand.Off)
        .build()
    );

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health")
        .description("Smart holds the main hand totem at or below this health, or while a crystal near you is lethal.")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 40)
        .visible(() -> mainhand.get() == Mainhand.Smart)
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

    private final Setting<Boolean> warn = sgGeneral.add(new BoolSetting.Builder()
        .name("warn")
        .description("Warns in chat when you run out of totems.")
        .defaultValue(true)
        .build()
    );

    private Pending pending = Pending.NONE;
    private int pendingSource = NONE;
    private long pendingUntil;
    private int resyncs;

    private int reserveSlot = NONE;
    private int previousSlot = NONE;
    private boolean suspended;

    private int totems;
    private boolean warned;

    public AutoTotem() {
        super(Coyduck.CATEGORY, "auto-totem+", "Keeps a totem in your offhand at any ping, under any screen.");
    }

    @Override
    public void onActivate() {
        clearPending();
        resyncs = 0;
        reserveSlot = NONE;
        previousSlot = NONE;
        suspended = false;
        warned = false;

        for (Class<? extends Module> conflict : List.of(meteordevelopment.meteorclient.systems.modules.combat.AutoTotem.class, Offhand.class)) {
            Module module = Modules.get().get(conflict);
            if (module != null && module.isActive()) {
                module.toggle();
                info("Disabled %s, it fights over the offhand.", module.title);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (canAct()) release();
        reserveSlot = NONE;
        previousSlot = NONE;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(totems);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (!canAct()) return;

        if (pending != Pending.NONE && Util.getMeasuringTimeMs() >= pendingUntil) onTimeout();
        refill(false);
        updateMainhand();

        totems = InventoryManager.get().count(AutoTotem::isTotem);
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
            case InventoryS2CPacket packet when packet.syncId() == mc.player.currentScreenHandler.syncId -> onInventory();
            case ScreenHandlerSlotUpdateS2CPacket packet when packet.getSyncId() == 0 && packet.getSlot() == PlayerScreenHandler.OFFHAND_ID -> refill(false);
            case SetPlayerInventoryS2CPacket packet when packet.slot() == InventoryManager.OFFHAND -> refill(false);
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct()) updateMainhand();
    }

    private void onPop() {
        InventoryManager inventory = InventoryManager.get();
        ItemStack main = inventory.stack(inventory.serverSlot());

        if (isTotem(main)) {
            main.decrement(1);
        } else {
            mc.player.getOffHandStack().decrement(1);
            clearPending();
            refill(true);
        }

        updateMainhand();
    }

    private void onInventory() {
        if (resyncs > 0) resyncs--;
        if (resyncs > 0) return;
        if (pending != Pending.CLICK && pending != Pending.VERIFY) return;

        if (applied()) {
            clearPending();
            refill(false);
        } else if (pending == Pending.CLICK) {
            verify();
        } else {
            clearPending();
            refill(true);
        }
    }

    private void onTimeout() {
        switch (pending) {
            case CLICK -> verify();
            case VERIFY -> {
                clearPending();
                refill(true);
            }
            default -> clearPending();
        }
    }

    private void verify() {
        pending = Pending.VERIFY;
        pendingUntil = deadline();
        resyncs = 0;
        resync();
    }

    private boolean applied() {
        return !isTotem(InventoryManager.get().stack(pendingSource));
    }

    private void refill(boolean force) {
        if (!force && (pending != Pending.NONE || isTotem(mc.player.getOffHandStack()))) return;

        int source = offhandSource();
        if (source == NONE) return;

        InventoryManager.Swap swap = InventoryManager.get().swapWithOffhand(source);
        if (swap == InventoryManager.Swap.NONE) return;

        pendingSource = source;
        pendingUntil = deadline();

        if (swap == InventoryManager.Swap.CLICK) {
            pending = Pending.CLICK;
            resync();
        } else {
            pending = Pending.HANDS;
        }
    }

    private int offhandSource() {
        InventoryManager inventory = InventoryManager.get();

        int source = inventory.find(AutoTotem::isTotem, InventoryManager.HOTBAR_END, InventoryManager.MAIN_END);
        if (source != NONE) return source;

        int server = inventory.serverSlot();
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (i != server && i != reserveSlot && isTotem(inventory.stack(i))) return i;
        }

        return reserveSlot == NONE && isTotem(inventory.stack(server)) ? server : NONE;
    }

    private void updateMainhand() {
        InventoryManager inventory = InventoryManager.get();
        int selected = inventory.selectedSlot();

        boolean wanted = switch (mainhand.get()) {
            case Off -> false;
            case Smart -> danger();
            case Always -> true;
        };

        if (!wanted) {
            suspended = false;
            release();
            return;
        }

        if (isTotem(inventory.stack(selected))) {
            suspended = false;
            if (reserveSlot == NONE) reserveSlot = selected;
            return;
        }

        if (reserveSlot != NONE && selected != reserveSlot) {
            reserveSlot = NONE;
            previousSlot = NONE;
            suspended = true;
        }
        if (suspended) return;

        boolean eating = mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND;
        if (eating && isTotem(mc.player.getOffHandStack())) return;

        int hotbar = inventory.find(AutoTotem::isTotem, 0, InventoryManager.HOTBAR_END);
        if (hotbar == NONE) {
            int source = inventory.find(AutoTotem::isTotem, InventoryManager.HOTBAR_END, InventoryManager.MAIN_END);
            if (source == NONE) return;

            hotbar = slot.get() - 1;
            if (!inventory.swapWithHotbar(source, hotbar)) return;
            resync();
        }

        if (previousSlot == NONE) previousSlot = selected;
        reserveSlot = hotbar;
        inventory.select(hotbar);
    }

    private void release() {
        if (reserveSlot == NONE) return;

        InventoryManager inventory = InventoryManager.get();
        if (restore.get() && previousSlot != NONE && inventory.selectedSlot() == reserveSlot) inventory.select(previousSlot);

        reserveSlot = NONE;
        previousSlot = NONE;
    }

    private boolean danger() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= health.get()) return true;

        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.squaredDistanceTo(mc.player) <= range && ExplosionDamage.crystal(mc.player, crystal.getEntityPos()) >= hp) return true;
        }

        return false;
    }

    private void resync() {
        resyncs++;
        InventoryManager.get().resync();
    }

    private long deadline() {
        return Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
    }

    private void clearPending() {
        pending = Pending.NONE;
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
