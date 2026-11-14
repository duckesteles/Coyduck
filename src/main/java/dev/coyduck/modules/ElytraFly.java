package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;

public class ElytraFly extends Module {
    public enum Style {
        Casual,
        Ultra
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgLanding = settings.createGroup("Landing");
    private final SettingGroup sgModes = settings.createGroup("Modes");
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgKeybinds = settings.createGroup("Keybinds");

    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("mode")
        .description("Casual flies at one steady speed. Ultra speeds up to max speed while you hold a key, with overdrive and the rubberband cap.")
        .defaultValue(Style.Casual)
        .onChanged(mode -> resetRamp())
        .build()
    );

    private final Setting<Boolean> wallGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("wall-guard")
        .description("Slows you down near walls and shortens a move that would slam into one, so you never take elytra crash damage. Also stops at chunks that are not loaded yet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Hold jump and the glide starts by itself as soon as you start falling, no second press needed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fluids = sgGeneral.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Also forces the flight controls in water and lava instead of swimming normally. Experimental, the server may pull you back.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on && this.stopInWater != null && this.stopInWater.get()) this.stopInWater.set(false);
        })
        .build()
    );

    private final Setting<Boolean> stopInWater = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-in-water")
        .description("Ends the glide as soon as you touch water instead of skimming over it. Turns fluids off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on && fluids.get()) fluids.set(false);
        })
        .build()
    );

    private final Setting<Boolean> instaDrop = sgGeneral.add(new BoolSetting.Builder()
        .name("insta-drop")
        .description("When you turn the module off mid-flight, tells the server you are on the ground so the glide ends at once and you drop straight down.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> limitHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("limit-height")
        .description("Never climbs above max-height.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-height")
        .description("Highest Y you can climb to.")
        .defaultValue(500)
        .range(-64, 2000)
        .sliderRange(0, 640)
        .visible(limitHeight::get)
        .build()
    );

    private final Setting<Double> speed = sgSpeed.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal blocks moved per tick.")
        .defaultValue(2.9)
        .min(0)
        .sliderRange(0, 5)
        .visible(() -> !ultra())
        .build()
    );

    private final Setting<Double> minSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Horizontal blocks per tick you start at and drop back to when you let go of the movement keys.")
        .defaultValue(2.999)
        .min(0)
        .sliderRange(0, 5)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Double> maxSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("max-speed")
        .description("Horizontal blocks per tick you speed up to while holding a movement key.")
        .defaultValue(14.999)
        .min(0)
        .sliderRange(0, 17)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Double> acceleration = sgSpeed.add(new DoubleSetting.Builder()
        .name("acceleration")
        .description("Speed gained per tick at a standstill. The gain shrinks the closer you get to the plateau.")
        .defaultValue(0.3)
        .min(0)
        .sliderRange(0, 2)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Double> plateau = sgSpeed.add(new DoubleSetting.Builder()
        .name("plateau")
        .description("Speed where the gain reaches zero. Higher means you keep speeding up quickly for longer.")
        .defaultValue(14.999)
        .min(0.1)
        .sliderRange(1, 30)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Double> up = sgSpeed.add(new DoubleSetting.Builder()
        .name("up-speed")
        .description("Blocks moved up per tick while holding jump. The 6b6t anticheat flags anything over 3.")
        .defaultValue(MAX_VERTICAL)
        .range(0, MAX_VERTICAL)
        .sliderRange(0, MAX_VERTICAL)
        .build()
    );

    private final Setting<Double> down = sgSpeed.add(new DoubleSetting.Builder()
        .name("down-speed")
        .description("Blocks moved down per tick while holding sneak. The 6b6t anticheat flags anything over 3.")
        .defaultValue(MAX_VERTICAL)
        .range(0, MAX_VERTICAL)
        .sliderRange(0, MAX_VERTICAL)
        .build()
    );

    private final Setting<Boolean> overdrive = sgSpeed.add(new BoolSetting.Builder()
        .name("overdrive")
        .description("Runs extra flight ticks while you speed up, each with its own small step, so you reach max speed far sooner. Never moves you further per tick than max speed.")
        .defaultValue(true)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Boolean> rubberbandCap = sgSpeed.add(new BoolSetting.Builder()
        .name("rubberband-cap")
        .description("Every pull back winds the speed back a little. After 3 pull backs within 3 seconds at or above the cap speed, holds you at the cap speed for 10 seconds.")
        .defaultValue(true)
        .visible(this::ultra)
        .build()
    );

    private final Setting<Double> capSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("cap-speed")
        .description("Blocks per second the rubberband cap holds you at.")
        .defaultValue(200)
        .min(1)
        .sliderRange(20, 340)
        .visible(() -> ultra() && rubberbandCap.get())
        .build()
    );

    private final Setting<Boolean> antiSlam = sgLanding.add(new BoolSetting.Builder()
        .name("anti-slam")
        .description("Slows the descent near the ground so landing never hurts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> slamDistance = sgLanding.add(new DoubleSetting.Builder()
        .name("slow-from")
        .description("Height above the ground where the descent starts slowing down.")
        .defaultValue(10)
        .range(1, 30)
        .sliderRange(1, 30)
        .visible(antiSlam::get)
        .build()
    );

    private final Setting<Double> slamFloor = sgLanding.add(new DoubleSetting.Builder()
        .name("floor-height")
        .description("Height above the ground where the descent reaches its slowest speed.")
        .defaultValue(0.5)
        .range(0, 10)
        .sliderRange(0, 5)
        .visible(antiSlam::get)
        .build()
    );

    private final Setting<Double> slamSpeed = sgLanding.add(new DoubleSetting.Builder()
        .name("landing-speed")
        .description("Slowest descent in blocks per tick. 0.45 was measured safe on 6b6t.")
        .defaultValue(0.45)
        .range(0.05, MAX_VERTICAL)
        .sliderRange(0.05, 1)
        .visible(antiSlam::get)
        .build()
    );

    private final Setting<Boolean> mapping = sgModes.add(new BoolSetting.Builder()
        .name("mapping")
        .description("Waits in place until every chunk within the radius has loaded, so you never fly past unloaded land.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> mappingRadius = sgModes.add(new IntSetting.Builder()
        .name("mapping-radius")
        .description("Chunks around you that must be loaded before you move on.")
        .defaultValue(9)
        .range(1, 32)
        .sliderRange(1, 16)
        .visible(mapping::get)
        .build()
    );

    private final Setting<Boolean> building = sgModes.add(new BoolSetting.Builder()
        .name("building")
        .description("Eases down to a slow speed whenever any block is within the distance, for flying close to builds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> buildingDistance = sgModes.add(new DoubleSetting.Builder()
        .name("building-distance")
        .description("How close a block must be to slow you down.")
        .defaultValue(15)
        .range(1, 32)
        .sliderRange(1, 32)
        .visible(building::get)
        .build()
    );

    private final Setting<Double> buildingSpeed = sgModes.add(new DoubleSetting.Builder()
        .name("building-speed")
        .description("Horizontal blocks per tick near blocks. Vertical speed eases to one and a half times this.")
        .defaultValue(0.7)
        .range(0.05, 5)
        .sliderRange(0.05, 3)
        .visible(building::get)
        .build()
    );

    private final Setting<Boolean> bypass = sgModes.add(new BoolSetting.Builder()
        .name("bypass")
        .description("Keeps the vanilla glide direction and only speeds it up along the same curve. Ultra mode also uses it inside cobwebs on its own.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autopilot = sgAutopilot.add(new BoolSetting.Builder()
        .name("autopilot")
        .description("Flies forward by itself while you are above the minimum height.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autopilotHeight = sgAutopilot.add(new IntSetting.Builder()
        .name("minimum-height")
        .description("Autopilot only flies forward above this Y.")
        .defaultValue(120)
        .range(-64, 640)
        .sliderRange(0, 320)
        .visible(autopilot::get)
        .build()
    );

    private final Setting<Boolean> fireworks = sgAutopilot.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Uses a firework rocket from the hotbar every few seconds while autopilot flies.")
        .defaultValue(false)
        .visible(autopilot::get)
        .build()
    );

    private final Setting<Integer> fireworkDelay = sgAutopilot.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Seconds between fireworks.")
        .defaultValue(8)
        .range(1, 60)
        .sliderRange(1, 30)
        .visible(() -> autopilot.get() && fireworks.get())
        .build()
    );

    private final Setting<Boolean> replace = sgInventory.add(new BoolSetting.Builder()
        .name("replace")
        .description("Swaps in a fresh elytra from your inventory before the worn one breaks, then picks the flight back up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replaceAt = sgInventory.add(new IntSetting.Builder()
        .name("replace-durability")
        .description("Durability left when the elytra gets swapped.")
        .defaultValue(2)
        .range(1, 100)
        .sliderRange(1, 50)
        .visible(replace::get)
        .build()
    );

    private final Setting<Boolean> chestSwap = sgInventory.add(new BoolSetting.Builder()
        .name("chest-swap")
        .description("Puts the elytra on when you turn the module on and the chestplate back when you turn it off, once you are on the ground.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> replenish = sgInventory.add(new BoolSetting.Builder()
        .name("replenish-fireworks")
        .description("Keeps a firework stack in the chosen hotbar slot, refilled from your inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> replenishSlot = sgInventory.add(new IntSetting.Builder()
        .name("replenish-slot")
        .description("Hotbar slot for the fireworks. Slot 9 stays the totem slot.")
        .defaultValue(8)
        .range(1, 8)
        .sliderRange(1, 8)
        .visible(replenish::get)
        .build()
    );

    private final Setting<Keybind> mappingKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("mapping-key")
        .description("Toggles mapping mode.")
        .defaultValue(Keybind.none())
        .action(() -> flip(mapping, "Mapping"))
        .build()
    );

    private final Setting<Keybind> buildingKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("building-key")
        .description("Toggles building mode.")
        .defaultValue(Keybind.none())
        .action(() -> flip(building, "Building"))
        .build()
    );

    private final Setting<Keybind> bypassKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("bypass-key")
        .description("Toggles bypass mode.")
        .defaultValue(Keybind.none())
        .action(() -> flip(bypass, "Bypass"))
        .build()
    );

    private final Setting<Keybind> antiSlamKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("anti-slam-key")
        .description("Toggles anti-slam.")
        .defaultValue(Keybind.none())
        .action(() -> flip(antiSlam, "Anti slam"))
        .build()
    );

    private final Setting<Keybind> modeKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("mode-key")
        .description("Switches between casual and ultra.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            style.set(ultra() ? Style.Casual : Style.Ultra);
            info("Mode %s.", style.get().name().toLowerCase());
        })
        .build()
    );

    private final Setting<Keybind> overdriveKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("overdrive-key")
        .description("Toggles overdrive.")
        .defaultValue(Keybind.none())
        .action(() -> flip(overdrive, "Overdrive"))
        .build()
    );

    private final Setting<Keybind> autopilotKey = sgKeybinds.add(new KeybindSetting.Builder()
        .name("autopilot-key")
        .description("Toggles autopilot.")
        .defaultValue(Keybind.none())
        .action(() -> flip(autopilot, "Autopilot"))
        .build()
    );

    private static final int TAKEOFF_TRIES = 5;
    private static final int TAKEOFF_WINDOW = 40;
    private static final int TAKEOFF_EVERY = 10;

    private static final double MAX_VERTICAL = 2.99;
    private static final double WALL_SLOW = 0.5;
    private static final long REPLACE_EVERY = 1_000;
    private static final int RAMP_DELAY = 1;
    private static final int REWIND_TICKS = 15;
    private static final int CAP_TRIGGERS = 3;
    private static final long CAP_WINDOW = 3_000;
    private static final int CAP_TICKS = 200;
    private static final int BUILDING_EASE = 5;
    private static final long REPLENISH_EVERY = 500;
    private static final int OVERDRIVE_TICKS = 49;
    private static final double OVERDRIVE_TOP = 0.995;

    private static Vec3d pilot;
    private long replacedAt;
    private boolean warnedWorn;

    private boolean fluidFlight;
    private int takeoffTries;
    private int airTicks;
    private boolean wasGliding;
    private int nextTakeoff;

    private double ramp;
    private int rampDelay;
    private double lastSpeed;
    private int capTicks;
    private double capLimit;
    private final ArrayDeque<Long> pullBacks = new ArrayDeque<>();
    private boolean keyed;
    private boolean vanillaFlight;
    private boolean extraTick;
    private boolean overdriveSpent;

    private boolean buildingEngaged;
    private int buildingTicks;
    private double buildingHorizontal;
    private double buildingVertical;
    private boolean waitingChunks;

    private int fireworkTicks;
    private long replenishedAt;

    private final Afterwards afterwards = new Afterwards();

    public ElytraFly() {
        super(Coyduck.CATEGORY, "elytra-fly+", "Flies with an elytra, speeding up the longer you hold a key, lands softly and picks the flight back up after water or lava.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it controls the same flight.", meteor.title);
        }

        afterwards.stop();
        fluidFlight = false;
        wasGliding = false;
        takeoffTries = 0;
        airTicks = 0;
        nextTakeoff = 0;
        resetRamp();
        lastSpeed = 0;
        capTicks = 0;
        overdriveSpent = false;
        keyed = false;
        vanillaFlight = false;
        pullBacks.clear();
        buildingEngaged = false;
        waitingChunks = false;
        fireworkTicks = 0;

        if (mc.player != null && chestSwap.get() && !mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) swapChest();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        boolean drop = instaDrop.get() && mc.player.isGliding();
        boolean landing = chestSwap.get() && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        if (landing && mc.player.isOnGround() && !mc.player.isGliding()) {
            swapChest();
            landing = false;
        }
        if (drop || landing) afterwards.start(mc.world, drop, landing);
    }

    public static void pilot(Vec3d movement) {
        pilot = movement;
    }

    public double horizontalSpeed() {
        return low();
    }

    public double upSpeed() {
        return up.get();
    }

    public double downSpeed() {
        return down.get();
    }

    public double currentSpeed() {
        return lastSpeed;
    }

    public boolean capped() {
        return capTicks > 0;
    }

    public boolean waitingForChunks() {
        return waitingChunks;
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (ultra() && overdrive.get()) overdrive();
        else overdriveSpent = false;
        pilot = null;
    }

    private void overdrive() {
        if (mc.player == null || mc.getNetworkHandler() == null || mc.isPaused()) return;
        if (!mc.player.isGliding()) {
            overdriveSpent = false;
            return;
        }
        double now = vanillaFlight ? lastSpeed : ramp;
        if (now <= low()) overdriveSpent = false;

        double budget = speedCap();
        double moved = lastSpeed;
        for (int i = 0; i < OVERDRIVE_TICKS; i++) {
            if (stalled()) break;
            double current = vanillaFlight ? lastSpeed : ramp;
            if (moved + Math.min(current + gain(current), budget) > budget) break;

            double fall = mc.player.getVelocity().y;
            extraTick = true;
            try {
                mc.player.age++;
                mc.player.tick();
            } finally {
                extraTick = false;
            }
            Vec3d after = mc.player.getVelocity();
            mc.player.setVelocity(after.x, fall, after.z);
            moved += lastSpeed;
        }
    }

    private boolean stalled() {
        if (pilot != null || !mc.player.isAlive() || !mc.player.isGliding() || mc.player.isUsingItem() || inFluid()) return true;
        if (waitingChunks || buildingEngaged || capTicks > 0 || overdriveSpent) return true;
        double top = speedCap() * OVERDRIVE_TOP;
        if (vanillaFlight) {
            if (lastSpeed > 1.0E-4 && lastSpeed < top) return false;
        } else if (keyed && rampDelay >= RAMP_DELAY && ramp < top) {
            return false;
        }
        if ((vanillaFlight ? lastSpeed : ramp) >= high() * OVERDRIVE_TOP) overdriveSpent = true;
        return true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (capTicks > 0) capTicks--;
        if (replace.get() && !mc.player.isOnGround()) replaceElytra();
        if (replenish.get()) replenish();
        if (fireworks.get() && autopilot.get() && mc.player.isGliding()) useFirework();
        else fireworkTicks = 0;

        if (inFluid()) {
            if ((wasGliding || fluidFlight) && !stopInWater.get()) {
                fluidFlight = true;
                takeoffTries = TAKEOFF_TRIES;
            }
            airTicks = 0;
            return;
        }
        if (mc.player.isOnGround()) {
            fluidFlight = false;
            wasGliding = false;
            nextTakeoff = 0;
            return;
        }

        wasGliding = mc.player.isGliding();
        if (nextTakeoff > 0) nextTakeoff--;
        if (!wasGliding && autoTakeoff.get() && !fluidFlight && mc.player.input.playerInput.jump() && mc.player.getVelocity().y < 0 && nextTakeoff == 0) {
            nextTakeoff = TAKEOFF_EVERY;
            takeoff();
        }

        if (!fluidFlight) return;
        if (++airTicks > TAKEOFF_WINDOW || takeoffTries <= 0) {
            fluidFlight = false;
            return;
        }
        if (wasGliding) return;

        takeoffTries--;
        takeoff();
    }

    private void takeoff() {
        if (canTakeoff() && !mc.player.isClimbing() && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket) || mc.player == null || !mc.player.isGliding()) return;

        if (ultra() && rubberbandCap.get() && lastSpeed * 20 >= capSpeed.get()) {
            long now = Util.getMeasuringTimeMs();
            pullBacks.addLast(now);
            while (now - pullBacks.peekFirst() > CAP_WINDOW) pullBacks.pollFirst();
            if (pullBacks.size() >= CAP_TRIGGERS) {
                pullBacks.clear();
                capTicks = CAP_TICKS;
                capLimit = capSpeed.get() / 20;
                Trace.log("elytra rubberband cap at {} b/s", capSpeed.get());
            }
        }

        double floor = low();
        for (int i = 0; i < REWIND_TICKS && ramp > floor; i++) ramp = Math.max(floor, ramp - gain(ramp));
        rampDelay = 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || !hasElytra()) return;

        if (stopInWater.get() && mc.player.isGliding() && mc.player.isTouchingWater()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            idle();
            return;
        }

        boolean fluid = fluids.get() && inFluid();
        if (!fluid && !mc.player.isGliding()) {
            idle();
            return;
        }
        if (fluid) {
            if (!mc.player.isOnGround()) fluidFlight = true;
            mc.player.setSprinting(false);
            mc.player.setSwimming(false);
        }

        PlayerInput input = mc.player.input.playerInput;
        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        if (autopilot.get() && forward == 0 && strafe == 0 && mc.player.getY() > autopilotHeight.get()) forward = 1;

        boolean piloted = pilot != null;
        boolean vanilla = !piloted && !fluid && (bypass.get() || ultra() && inCobweb());
        vanillaFlight = vanilla;
        keyed = !piloted && !vanilla && (forward != 0 || strafe != 0);

        double x = 0;
        double z = 0;
        double y = 0;
        if (input.jump() && !input.sneak()) y = up.get();
        else if (input.sneak() && !input.jump()) y = -down.get();

        if (piloted) {
            x = pilot.x;
            y = pilot.y;
            z = pilot.z;
            resetRamp();
        } else if (vanilla) {
            double now = Math.hypot(event.movement.x, event.movement.z);
            if (now > 1.0E-4) {
                double next = now;
                if (rampDelay < RAMP_DELAY) rampDelay++;
                else next = Math.min(now + gain(now), speedCap());
                x = event.movement.x * next / now;
                z = event.movement.z * next / now;
            } else {
                rampDelay = 0;
                x = event.movement.x;
                z = event.movement.z;
            }
        } else if (forward != 0 || strafe != 0) {
            if (rampDelay < RAMP_DELAY) rampDelay++;
            else ramp = ramp + gain(ramp);
            ramp = Math.min(ramp, speedCap());
            float base = RotationManager.get().physicsYaw(mc.player, mc.player.getYaw());
            double angle = Math.toRadians(base) + Math.atan2(-strafe, forward);
            x = -Math.sin(angle) * ramp;
            z = Math.cos(angle) * ramp;
        } else {
            resetRamp();
        }

        y = extraTick ? 0 : MathHelper.clamp(y, -MAX_VERTICAL, MAX_VERTICAL);
        if (!piloted && antiSlam.get() && y < -slamSpeed.get()) y = -landing(-y);
        if (!piloted && building.get()) {
            double[] eased = building(x, y, z);
            x = eased[0];
            y = eased[1];
            z = eased[2];
        } else {
            buildingEngaged = false;
        }
        if (limitHeight.get() && y > 0) y = Math.max(0, Math.min(y, maxHeight.get() - mc.player.getY()));

        waitingChunks = mapping.get() && !chunksAround(mappingRadius.get());
        if (waitingChunks) {
            x = 0;
            z = 0;
            resetRamp();
        }

        double intended = Math.sqrt(x * x + z * z);
        boolean held = false;
        if (wallGuard.get() && intended > 0) {
            double reach = intended + 1;
            if (!loaded(mc.player.getX() + x, mc.player.getZ() + z) || !loaded(mc.player.getX() + x / intended * reach, mc.player.getZ() + z / intended * reach)) {
                x = 0;
                z = 0;
                held = true;
            } else {
                double free = freeScale(x / intended * reach, z / intended * reach, y) * reach;
                if (free < reach) {
                    double cap = Math.sqrt(WALL_SLOW * free);
                    if (cap < intended) {
                        x *= cap / intended;
                        z *= cap / intended;
                    }
                }
                x *= freeScale(x, 0, y);
                z *= freeScale(0, z, y);
                double both = freeScale(x, z, y);
                x *= both;
                z *= both;
            }
        }

        double moved = Math.sqrt(x * x + z * z);
        if (!piloted && !held && moved < intended - 1.0E-3) {
            if (vanilla) rampDelay = 0;
            else {
                ramp = Math.max(low(), Math.min(ramp, moved));
                rampDelay = 0;
            }
        }
        lastSpeed = moved;

        ((IVec3d) event.movement).meteor$set(x, y, z);
        if (!vanilla) mc.player.setVelocity(0, 0, 0);
    }

    private void idle() {
        keyed = false;
        vanillaFlight = false;
        resetRamp();
        lastSpeed = 0;
        buildingEngaged = false;
        waitingChunks = false;
    }

    private void resetRamp() {
        ramp = low();
        rampDelay = 0;
    }

    private boolean ultra() {
        return style.get() == Style.Ultra;
    }

    private double low() {
        return ultra() ? minSpeed.get() : speed.get();
    }

    private double high() {
        return ultra() ? maxSpeed.get() : speed.get();
    }

    private double gain(double speed) {
        if (!ultra()) return 0;
        double top = plateau.get();
        return acceleration.get() * Math.max(0, top - speed) / top;
    }

    private double speedCap() {
        double cap = high();
        if (capTicks > 0 && ultra()) cap = Math.min(cap, capLimit);
        return Math.max(low(), cap);
    }

    private double[] building(double x, double y, double z) {
        if (!blockNear(buildingDistance.get())) {
            buildingEngaged = false;
            return new double[] {x, y, z};
        }
        resetRamp();

        double target = buildingSpeed.get();
        double verticalTarget = target * 1.5;
        double horizontal = Math.sqrt(x * x + z * z);
        double vertical = Math.abs(y);
        if (!buildingEngaged) {
            buildingEngaged = true;
            buildingTicks = 0;
            buildingHorizontal = Math.max(horizontal, target);
            buildingVertical = Math.max(vertical, verticalTarget);
        } else {
            buildingTicks++;
        }

        double t = Math.min(1, buildingTicks / (double) BUILDING_EASE);
        double horizontalCap = MathHelper.lerp(t, buildingHorizontal, target);
        double verticalCap = MathHelper.lerp(t, buildingVertical, verticalTarget);
        if (horizontal > horizontalCap) {
            x *= horizontalCap / horizontal;
            z *= horizontalCap / horizontal;
        }
        if (vertical > verticalCap) y = Math.signum(y) * verticalCap;
        return new double[] {x, y, z};
    }

    private boolean blockNear(double distance) {
        return mc.world.getBlockCollisions(mc.player, mc.player.getBoundingBox().expand(distance)).iterator().hasNext();
    }

    private boolean chunksAround(int radius) {
        int cx = ChunkSectionPos.getSectionCoord(mc.player.getX());
        int cz = ChunkSectionPos.getSectionCoord(mc.player.getZ());
        int square = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > square) continue;
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    private boolean inCobweb() {
        Box box = mc.player.getBoundingBox();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = MathHelper.floor(box.minX + 1.0E-5); x <= MathHelper.floor(box.maxX - 1.0E-5); x++) {
            for (int y = MathHelper.floor(box.minY + 1.0E-5); y <= MathHelper.floor(box.maxY - 1.0E-5); y++) {
                for (int z = MathHelper.floor(box.minZ + 1.0E-5); z <= MathHelper.floor(box.maxZ - 1.0E-5); z++) {
                    if (mc.world.getBlockState(pos.set(x, y, z)).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    private double freeScale(double x, double z, double y) {
        if (x == 0 && z == 0) return 1;
        Box box = mc.player.getBoundingBox().offset(0, y, 0);
        if (!mc.world.isSpaceEmpty(mc.player, box)) box = mc.player.getBoundingBox();
        if (!mc.world.isSpaceEmpty(mc.player, box)) return 1;
        if (mc.world.isSpaceEmpty(mc.player, box.stretch(x, 0, z))) return 1;

        double low = 0;
        double high = 1;
        for (int i = 0; i < 6; i++) {
            double mid = (low + high) / 2;
            if (mc.world.isSpaceEmpty(mc.player, box.stretch(x * mid, 0, z * mid))) low = mid;
            else high = mid;
        }
        return low;
    }

    private double landing(double speed) {
        double start = slamDistance.get();
        double end = Math.min(slamFloor.get(), start);
        double floorSpeed = slamSpeed.get();
        Box box = mc.player.getBoundingBox();
        double ground = start;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double px = MathHelper.lerp(i / 2.0, box.minX, box.maxX);
                double pz = MathHelper.lerp(j / 2.0, box.minZ, box.maxZ);
                Vec3d from = new Vec3d(px, box.minY, pz);
                BlockHitResult hit = mc.world.raycast(new RaycastContext(from, from.add(0, -start, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, mc.player));
                if (hit.getType() != HitResult.Type.MISS) ground = Math.min(ground, from.y - hit.getPos().y);
            }
        }
        if (ground >= start) return speed;
        double share = MathHelper.clamp((ground - end) / Math.max(start - end, 1.0E-4), 0, 1);
        return Math.min(speed, floorSpeed + share * (speed - floorSpeed));
    }

    private boolean loaded(double x, double z) {
        return mc.world.getChunkManager().isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z));
    }

    private void flip(Setting<Boolean> setting, String label) {
        if (mc.currentScreen != null) return;
        setting.set(!setting.get());
        info("%s %s.", label, setting.get() ? "on" : "off");
    }

    private void swapChest() {
        ChestSwap swap = Modules.get().get(ChestSwap.class);
        if (swap != null) swap.swap();
    }

    private void useFirework() {
        if (--fireworkTicks > 0) return;
        fireworkTicks = fireworkDelay.get() * 20;
        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            mc.player.swingHand(Hand.OFF_HAND);
            return;
        }
        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!rocket.found() || mc.player.isUsingItem()) return;
        if (!InvUtils.swap(rocket.slot(), true)) return;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private void replenish() {
        long now = Util.getMeasuringTimeMs();
        if (now - replenishedAt < REPLENISH_EVERY) return;
        int target = replenishSlot.get() - 1;
        if (!mc.player.getInventory().getStack(target).isEmpty()) return;
        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.FIREWORK_ROCKET), 9, InventoryManager.MAIN_END);
        if (slot == -1) return;
        replenishedAt = now;
        InvUtils.move().from(slot).toHotbar(target);
    }

    private void replaceElytra() {
        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        int keep = replaceAt.get();
        boolean wearing = worn.isOf(Items.ELYTRA);
        if (wearing && worn.getMaxDamage() - worn.getDamage() > keep) {
            warnedWorn = false;
            return;
        }
        if (!wearing && !mc.player.isGliding() && !wasGliding) return;
        long now = Util.getMeasuringTimeMs();
        if (now - replacedAt < REPLACE_EVERY) return;

        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.ELYTRA) && stack.getMaxDamage() - stack.getDamage() > keep, 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            if (!warnedWorn) {
                warnedWorn = true;
                warning("Your elytra is about to break and there is no spare one.");
            }
            return;
        }
        replacedAt = now;
        InvUtils.move().from(slot).toArmor(2);
        fluidFlight = true;
        takeoffTries = TAKEOFF_TRIES;
        airTicks = 0;
        Trace.log("elytra swapped in a fresh one from slot {}", slot);
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

    private class Afterwards {
        private boolean subscribed;
        private ClientWorld world;
        private boolean drop;
        private boolean landing;

        void start(ClientWorld world, boolean drop, boolean landing) {
            this.world = world;
            this.drop = drop;
            this.landing = landing;
            if (subscribed) return;
            subscribed = true;
            MeteorClient.EVENT_BUS.subscribe(this);
        }

        void stop() {
            drop = false;
            landing = false;
            world = null;
            if (!subscribed) return;
            subscribed = false;
            MeteorClient.EVENT_BUS.unsubscribe(this);
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.world != world) {
                stop();
                return;
            }
            if (drop) {
                if (mc.player.isGliding()) {
                    mc.player.setVelocity(0, 0, 0);
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
                } else {
                    drop = false;
                }
            }
            if (landing && mc.player.isOnGround() && !mc.player.isGliding()) {
                landing = false;
                if (mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) swapChest();
            }
            if (!drop && !landing) stop();
        }

        @EventHandler
        private void onLeft(GameLeftEvent event) {
            stop();
        }
    }
}
