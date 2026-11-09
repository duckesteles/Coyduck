package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Trace;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class MaceStrike extends Module {
    private static final int PRIORITY = 85;
    private static final float ROTATE_SPEED = 180.0f;
    private static final double MIN_DROP = 2.0;
    private static final double HEIGHT_STEP = 0.5;
    private static final double AIM_INSET = 0.05;
    private static final double STANDING_EYE = 1.62;
    private static final double GLIDING_EYE = 0.4;
    private static final double STRIKE_GAP = 0.25;
    private static final double OVER_RANGE = 1.0;
    private static final double LEAD_TICKS = 2;
    private static final double HOVER_SLACK = 0.75;
    private static final float CRIT_CHARGE = 0.9f;
    private static final double STILL_SPEED = 0.08;
    private static final double ALIGN = 0.35;
    private static final double MIN_HOVER = 4.0;
    private static final float REGEN_MARGIN = 1.0f;
    private static final long LEARN_DELAY = 600;
    private static final double LEARN_WEIGHT = 0.3;
    private static final double MAX_FALL = 40;
    private static final long HURT_MS = 500;
    private static final double CHASE_LEAD = 4;
    private static final double CHASE_ALIGN = 1.2;
    private static final double CHASE_OVER = 2.0;
    private static final float MIN_LEARN = 2.0f;
    private static final long SETBACK_PAUSE = 1000;
    private static final int MAX_RAPID_HITS = 2;
    private static final double CLOSED_PENALTY = 1000;
    private static final double STICK = 4;
    private static final double BODY_INSET = 0.1;

    private enum Phase {
        Idle,
        Lift,
        Ground,
        Dive,
        Descend,
        Ascend
    }

    public enum Mode {
        Ticks,
        Burst
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAir = settings.createGroup("Elytra");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Reach from your eyes to the closest point of the target's hitbox at the moment of the hit.")
        .defaultValue(3.0)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("On foot: how far up the server is told you went before the hit. The fall back down is what the mace smash counts. Lowered on its own when blocks are above you.")
        .defaultValue(8.0)
        .range(MIN_DROP, 9.5)
        .sliderRange(MIN_DROP, 9.5)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Burst sends the whole dive inside one tick with a tick end after each move, so the server counts the full fall for the smash, then skips your next ticks to keep the packet rate even. Ticks sends one move per tick; the server then only counts the last step of the fall while you glide.")
        .defaultValue(Mode.Burst)
        .build()
    );

    private final Setting<Boolean> onFoot = sgGeneral.add(new BoolSetting.Builder()
        .name("on-foot")
        .description("Also strikes while you stand, by faking a jump up and the fall back down. Every on-foot strike got a setback on 6b6t so far.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> step = sgGeneral.add(new DoubleSetting.Builder()
        .name("step")
        .description("Longest move in one packet. The trip down and back is cut into moves of at most this length.")
        .defaultValue(2.0)
        .range(0.5, 10)
        .sliderRange(0.5, 5)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval")
        .description("Extra milliseconds between smashes. Dives already wait until you are back at full height and the target's hurt time is over, so 0 is fastest without wasted hits.")
        .defaultValue(0)
        .range(0, 3000)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the mace gets into your hand for the hit. Your hand goes back right after.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> smash = sgGeneral.add(new BoolSetting.Builder()
        .name("smash")
        .description("Stops the glide right before the hit and starts it again right after, inside the same tick. A mace only smashes when you are not gliding, and the next move is a glide again.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> holdMace = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-mace")
        .description("Keeps a hotbar mace in hand while you follow a target so it charges. Each dive waits until the charged hit would pop the target, or until the mace is charged enough for a critical hit. Auto Totem+ still gets the main hand when it wants a totem there. Your slot comes back when there is no target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fullCharge = sgGeneral.add(new BoolSetting.Builder()
        .name("full-charge")
        .description("Always waits for a full charge (about 1.7 seconds after the mace is in hand) before a smash. Off: from the target's health, absorption, armor, enchantments and the fall the server really counted on earlier hits, it picks whichever pops sooner: a smash every time the hurt time ends at whatever charge (most smash damage comes from the fall and ignores the charge), or fewer charged critical smashes.")
        .defaultValue(false)
        .visible(holdMace::get)
        .build()
    );

    private final Setting<Double> assumedFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("assumed-fall")
        .description("Fall the server is assumed to count before any hit has been measured. After every smash the real health loss replaces it.")
        .defaultValue(3.0)
        .range(0, 30)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<Double> minHealth = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("At or below this much health plus absorption it stops diving and lets go of the mace, so Auto Totem+ can put a totem in your main hand.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the target before the smash starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> follow = sgAir.add(new BoolSetting.Builder()
        .name("follow")
        .description("While you fly with elytra-fly+, steers you over the target and keeps you there, then dives for the hit and comes back up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hover = sgAir.add(new DoubleSetting.Builder()
        .name("hover")
        .description("How far above the target's feet you stay while following. Every extra block of fall adds about 5 damage to a charged smash, and the dive still fits in the time the mace needs to charge.")
        .defaultValue(16.0)
        .range(5, 30)
        .sliderRange(5, 30)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> chaseHover = sgAir.add(new DoubleSetting.Builder()
        .name("chase-hover")
        .description("Height kept above a target that flies with an elytra. A flying target gets chased and cut off ahead of its path, and hit with short drops from just above, while a target on the ground or in a hole gets the steady hover from high up.")
        .defaultValue(4)
        .range(2.5, 12)
        .sliderRange(2.5, 12)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followRange = sgAir.add(new DoubleSetting.Builder()
        .name("follow-range")
        .description("Targets this far away are chased.")
        .defaultValue(32)
        .range(5, 128)
        .sliderRange(5, 64)
        .visible(follow::get)
        .build()
    );

    private final Setting<Boolean> stopGlide = sgAir.add(new BoolSetting.Builder()
        .name("stop-glide")
        .description("Tells the server you touched ground one tick before the dive. Stopping the glide got setbacks on 6b6t, so it is off by default.")
        .defaultValue(false)
        .visible(follow::get)
        .build()
    );

    private Phase phase = Phase.Idle;
    private PlayerEntity target;
    private double lift;
    private double strikeY;
    private long lastStrike;
    private boolean sending;
    private double serverY;
    private Vec3d lastTargetPos;
    private PlayerEntity lastTarget;
    private int heldFrom = -1;
    private int owed;
    private double learnedFall = Double.NaN;
    private int learnVictim = -1;
    private float learnHealth;
    private int learnPops;
    private float learnCharge;
    private float learnBase;
    private int learnDensity;
    private long learnAt;
    private boolean waitTraced;
    private boolean roofTraced;
    private Boolean chasing;
    private Vec3d strikeLead = Vec3d.ZERO;
    private float learnAbsorption;
    private boolean learnImmune;
    private boolean learnSmash;
    private boolean learnMixed;
    private long pausedUntil;

    public MaceStrike() {
        super(Coyduck.CATEGORY, "mace-strike", "Smashes players with a mace from your hotbar or inventory. On foot the server is told you went up and fell back down. While flying with elytra-fly+ it stays over the target and dives for each hit with fake moves of a few blocks per tick while you stay put on screen. The worst elytra mace on Earth.");
    }

    @Override
    public void onDeactivate() {
        chasing = null;
        phase = Phase.Idle;
        owed = 0;
        target = null;
        giveBack();
    }

    public boolean hunting() {
        return isActive() && target != null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPre(TickEvent.Pre event) {
        if (mc.world != null) learn();
        if (phase != Phase.Idle) return;
        if (Util.getMeasuringTimeMs() < pausedUntil) return;
        if (!canAct() || maceSlot() == -1) {
            target = null;
            return;
        }

        if (flying()) airTick();
        else groundTick();
    }

    private void groundTick() {
        if (!onFoot.get() || mc.player.isGliding()) {
            target = null;
            return;
        }

        target = choose(range.get(), true);
        if (target == null) return;

        Box box = Targets.box(target);
        if (rotate.get()) {
            RotationManager rotations = RotationManager.get();
            rotations.request(PRIORITY, aim(box, mc.player.getEyePos()), ROTATE_SPEED, true);
            if (!rotations.isLookingAt(box, range.get())) return;
        }
        if (!due()) return;

        lift = headroom();
        if (lift < MIN_DROP) return;
        phase = Phase.Lift;
    }

    private void airTick() {
        if (owed > 0) return;
        target = choose(followRange.get(), false);
        if (target == null) {
            lastTargetPos = null;
            giveBack();
            return;
        }

        boolean low = mc.player.getHealth() + mc.player.getAbsorptionAmount() <= minHealth.get();
        if (low) giveBack();
        else takeMace();
        Vec3d now = target.getEntityPos();
        Vec3d motion = lastTargetPos == null || lastTarget != target ? Vec3d.ZERO : now.subtract(lastTargetPos);
        lastTargetPos = now;
        lastTarget = target;

        boolean chase = target.isGliding();
        if (chasing == null || chasing != chase) Trace.log("mace {} {}", chase ? "chases flying" : "hovers over", target.getName().getString());
        chasing = chase;
        double wanted = chase ? chaseHover.get() : hover.get();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double leadTicks = chase ? CHASE_LEAD : Math.ceil(wanted / step.get()) + 1;
        double goalX = speed < STILL_SPEED ? Math.floor(now.x) + 0.5 : now.x + motion.x * leadTicks;
        double goalZ = speed < STILL_SPEED ? Math.floor(now.z) + 0.5 : now.z + motion.z * leadTicks;
        double goalY = chase ? motion.y * leadTicks : 0;
        double above = reachableHover(goalX, now.y, goalZ, wanted);
        steer(new Vec3d(goalX, now.y + goalY + above, goalZ));

        double arrival = chase ? LatencyManager.get().rtt() / 2.0 / 50.0 : 0;
        strikeLead = chase ? motion.multiply(arrival) : Vec3d.ZERO;
        double align = chase ? CHASE_ALIGN : ALIGN;
        double over = chase ? CHASE_OVER : OVER_RANGE;
        double dx = goalX - mc.player.getX();
        double dz = goalZ - mc.player.getZ();
        double tx = now.x + strikeLead.x - mc.player.getX();
        double tz = now.z + strikeLead.z - mc.player.getZ();
        if (low) return;
        if (dx * dx + dz * dz > align * align || tx * tx + tz * tz > over * over) return;
        if (mc.player.getY() - now.y < above - HOVER_SLACK) return;

        double y = strikeHeight();
        if (Double.isNaN(y)) return;
        boolean burst = mode.get() == Mode.Burst;
        int diveTicks = burst ? 0 : (int) Math.ceil((mc.player.getY() - y) / step.get());
        if (HurtTracker.get().releaseTime(target) > Util.getMeasuringTimeMs() + diveTicks * 50L) return;
        if (mc.player.getMainHandStack().isOf(Items.MACE)) {
            float charge = mc.player.getAttackCooldownProgress(diveTicks + 0.5f);
            float needed = target.getHealth() + target.getAbsorptionAmount() + REGEN_MARGIN;
            double drop = mc.player.getY() - y;
            double fall = Math.min(burst ? drop : Math.min(drop, step.get()) + 1, countedFall());
            float damage = smashDamage(fall, charge);
            boolean go = charge > CRIT_CHARGE || damage >= needed || (!fullCharge.get() && rapidFirst(fall, charge, needed));
            if (!go) {
                if (!waitTraced && Trace.on()) {
                    waitTraced = true;
                    Trace.log("mace waits, charge {} gives {} of {} needed, counted fall {}", charge, damage, needed, fall);
                }
                Box box = Targets.box(target);
                Vec3d eye = new Vec3d(mc.player.getX(), y + STANDING_EYE, mc.player.getZ());
                float[] rotation = rotationTo(eye, aim(box, eye));
                RotationManager.get().request(PRIORITY, rotation[0], rotation[1], ROTATE_SPEED, false);
                return;
            }
        }

        Box box = Targets.box(target);
        Vec3d eye = new Vec3d(mc.player.getX(), y + STANDING_EYE, mc.player.getZ());
        float[] rotation = rotationTo(eye, aim(box, eye));
        RotationManager.get().request(PRIORITY, rotation[0], rotation[1], ROTATE_SPEED, false);
        if (!looksAt(eye, box) || !due()) return;

        waitTraced = false;
        strikeY = y;
        serverY = mc.player.getY();
        if (stopGlide.get()) phase = Phase.Ground;
        else phase = mode.get() == Mode.Ticks ? Phase.Descend : Phase.Dive;
    }

    private double reachableHover(double x, double feetY, double z, double wanted) {
        Box own = mc.player.getBoundingBox();
        double half = own.getLengthX() / 2;
        double tall = own.getLengthY();
        double floor = Math.min(MIN_HOVER, wanted);
        for (double h = wanted; h > floor; h -= HEIGHT_STEP) {
            Box column = new Box(x - half, feetY + 2, z - half, x + half, feetY + h + tall, z + half);
            if (mc.world.isSpaceEmpty(mc.player, column)) return h;
        }
        return floor;
    }

    private void steer(Vec3d goal) {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d delta = goal.subtract(mc.player.getEntityPos());

        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double speed = Math.min(horizontal, fly.horizontalSpeed());
        double x = horizontal < 1.0E-4 ? 0 : delta.x / horizontal * speed;
        double z = horizontal < 1.0E-4 ? 0 : delta.z / horizontal * speed;
        double y = MathHelper.clamp(delta.y, -fly.downSpeed(), fly.upSpeed());
        ElytraFly.pilot(new Vec3d(x, y, z));
    }

    private double strikeHeight() {
        Box box = mc.player.getBoundingBox();
        double top = mc.player.getY() - MIN_DROP;
        double reach = range.get();
        Box victim = Targets.box(target).offset(strikeLead);

        for (double y = victim.maxY + STRIKE_GAP; y <= top; y += HEIGHT_STEP) {
            Box path = box.stretch(0, y - mc.player.getY(), 0);
            if (!mc.world.isSpaceEmpty(mc.player, path)) {
                if (Trace.on() && !roofTraced) Trace.log("mace skips {}, blocks between you and them", target.getName().getString());
                roofTraced = true;
                return Double.NaN;
            }

            Vec3d standing = new Vec3d(mc.player.getX(), y + STANDING_EYE, mc.player.getZ());
            Vec3d gliding = new Vec3d(mc.player.getX(), y + GLIDING_EYE, mc.player.getZ());
            if (victim.squaredMagnitude(standing) > reach * reach || victim.squaredMagnitude(gliding) > reach * reach) return Double.NaN;
            if (!clearSight(gliding, victim) && !clearSight(standing, victim)) {
                if (Trace.on() && !roofTraced) Trace.log("mace skips {}, no clear line from the strike point", target.getName().getString());
                roofTraced = true;
                return Double.NaN;
            }
            roofTraced = false;
            return y;
        }
        return Double.NaN;
    }

    private boolean clearSight(Vec3d eye, Box victim) {
        Vec3d point = aim(victim, eye);
        net.minecraft.util.hit.BlockHitResult hit = mc.world.raycast(new net.minecraft.world.RaycastContext(eye, point, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    private boolean looksAt(Vec3d eye, Box box) {
        if (!rotate.get()) return true;
        RotationManager rotations = RotationManager.get();
        Vec3d end = eye.add(Vec3d.fromPolar(rotations.serverPitch(), rotations.serverYaw()).multiply(range.get() + 1));
        return box.raycast(eye, end).isPresent();
    }

    private static float[] rotationTo(Vec3d eye, Vec3d point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (sending) return;
        if ((phase != Phase.Idle || owed > 0) && event.packet instanceof PlayerMoveC2SPacket) event.cancel();
        else if (owed > 0 && phase == Phase.Idle && event.packet instanceof ClientTickEndC2SPacket) {
            event.cancel();
            owed--;
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        mc.execute(() -> {
            phase = Phase.Idle;
            owed = 0;
            pausedUntil = Util.getMeasuringTimeMs() + SETBACK_PAUSE;
        });
    }

    @EventHandler
    private void onDamage(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityDamageS2CPacket packet) || mc.player == null || learnVictim == -1 || packet.entityId() != learnVictim) return;
        if (packet.sourceType().matchesKey(DamageTypes.MACE_SMASH) && packet.sourceCauseId() == mc.player.getId()) learnSmash = true;
        else learnMixed = true;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        phase = Phase.Idle;
        owed = 0;
        target = null;
        heldFrom = -1;
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPost(TickEvent.Post event) {
        if (phase == Phase.Idle || mc.player == null || mc.getNetworkHandler() == null) {
            phase = Phase.Idle;
            return;
        }

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        boolean collision = mc.player.horizontalCollision;

        switch (phase) {
            case Lift -> {
                phase = Phase.Idle;
                if (!ready()) return;
                path(x, y, y + lift, z, collision);
                path(x, y + lift, y, z, collision);
                strike();
            }
            case Ground -> {
                move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, true, collision));
                phase = mode.get() == Mode.Ticks ? Phase.Descend : Phase.Dive;
            }
            case Descend -> {
                serverY = Math.max(strikeY, serverY - step.get());
                move(new PlayerMoveC2SPacket.PositionAndOnGround(x, serverY, z, false, collision));
                if (serverY > strikeY) return;
                if (ready() && inReachFrom(new Vec3d(x, serverY, z))) strike();
                phase = Phase.Ascend;
            }
            case Ascend -> {
                serverY = Math.min(y, serverY + step.get());
                move(new PlayerMoveC2SPacket.PositionAndOnGround(x, serverY, z, false, collision));
                if (serverY >= y) {
                    phase = Phase.Idle;
                    if (stopGlide.get() && mc.player.isGliding()) {
                        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    }
                }
            }
            case Dive -> {
                phase = Phase.Idle;
                if (!ready()) {
                    move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, collision));
                    return;
                }
                path(x, y, strikeY, z, collision);
                strike();
                path(x, strikeY, y, z, collision);
                if (stopGlide.get() && mc.player.isGliding()) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
            default -> phase = Phase.Idle;
        }
    }

    private void path(double x, double from, double to, double z, boolean collision) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(to - from) / step.get()));
        for (int i = 1; i <= steps; i++) {
            move(new PlayerMoveC2SPacket.PositionAndOnGround(x, from + (to - from) * i / steps, z, false, collision));
            endTick();
        }
    }

    private void endTick() {
        sending = true;
        try {
            mc.getNetworkHandler().sendPacket(ClientTickEndC2SPacket.INSTANCE);
        } finally {
            sending = false;
        }
        owed++;
    }

    private boolean inReachFrom(Vec3d feet) {
        double reach = range.get() + 0.5;
        Box box = Targets.box(target);
        return box.squaredMagnitude(feet.add(0, GLIDING_EYE, 0)) <= reach * reach || box.squaredMagnitude(feet.add(0, STANDING_EYE, 0)) <= reach * reach;
    }

    private boolean ready() {
        return maceSlot() != -1 && target != null && target.isAlive() && !target.isRemoved();
    }

    private void strike() {
        PlayerEntity victim = target;
        int slot = maceSlot();
        if (victim != null && mc.player.getMainHandStack().isOf(Items.MACE)) {
            learnVictim = victim.getId();
            learnHealth = victim.getHealth() + victim.getAbsorptionAmount();
            learnAbsorption = victim.getAbsorptionAmount();
            learnImmune = HurtTracker.get().releaseTime(victim) > Util.getMeasuringTimeMs();
            learnPops = PopCounter.pops(victim);
            learnCharge = mc.player.getAttackCooldownProgress(0.5f);
            learnBase = (float) mc.player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
            learnDensity = Utils.getEnchantmentLevel(mc.player.getMainHandStack(), Enchantments.DENSITY);
            learnAt = Util.getMeasuringTimeMs();
            learnSmash = false;
            learnMixed = false;
        }
        Runnable hit = () -> {
            boolean toggle = smash.get() && mc.player.isGliding();
            boolean sprinting = mc.player.isSprinting();
            if (sprinting) mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            if (toggle) glideCommand();
            mc.interactionManager.attackEntity(mc.player, victim);
            InteractionManager.get().swing(InteractionManager.SwingMode.Packet);
            if (toggle) glideCommand();
            if (sprinting) mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        };
        if (InventoryManager.get().selectedSlot() == slot) hit.run();
        else InventoryManager.get().withItem(slot, switchMode.get(), hit);
        lastStrike = Util.getMeasuringTimeMs();
        if (!holdMace.get()) giveBack();
    }

    private float smashDamage(double fall, float charge) {
        ItemStack mace = mc.player.getMainHandStack();
        return smashDamage(fall, charge, target, (float) mc.player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE), Utils.getEnchantmentLevel(mace, Enchantments.DENSITY));
    }

    private float smashDamage(double fall, float charge, PlayerEntity victim, float base, int density) {
        double bonus = fall <= 1.5 ? 0 : fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + (fall - 8);
        if (fall > 1.5) bonus += 0.5 * density * fall;
        float damage = base * (0.2f + charge * charge * 0.8f) + (float) bonus;
        if (charge > CRIT_CHARGE) damage *= 1.5f;
        return DamageUtils.calculateReductions(damage, victim, mc.world.getDamageSources().playerAttack(mc.player));
    }

    private boolean rapidFirst(double fall, float charge, float needed) {
        float fullTicks = Math.max(1, mc.player.getAttackCooldownProgressPerTick());
        float rapidCharge = Math.min(1, HURT_MS / 50f / fullTicks);
        float rapid = Math.max(0.1f, smashDamage(fall, rapidCharge));
        float full = Math.max(0.1f, smashDamage(fall, 1));
        if (Math.ceil(needed / rapid) > MAX_RAPID_HITS) return false;
        long rapidTime = (long) (Math.ceil(needed / rapid) - 1) * HURT_MS;
        long waitFull = (long) (Math.max(0, CRIT_CHARGE + 0.01f - charge) * fullTicks * 50);
        long fullTime = waitFull + (long) (Math.ceil(needed / full) - 1) * (long) (fullTicks * 50);
        boolean rapidWins = rapidTime <= fullTime;
        if (rapidWins && Trace.on()) Trace.log("mace hits at once, {} hits of {} every {}ms beat waiting {}ms for {}", (int) Math.ceil(needed / rapid), rapid, HURT_MS, waitFull, full);
        return rapidWins;
    }

    private double countedFall() {
        return Double.isNaN(learnedFall) ? assumedFall.get() : learnedFall;
    }

    private void learn() {
        if (learnVictim == -1 || Util.getMeasuringTimeMs() - learnAt < LEARN_DELAY) return;
        int id = learnVictim;
        learnVictim = -1;
        if (!(mc.world.getEntityById(id) instanceof PlayerEntity victim) || !victim.isAlive()) return;

        boolean popped = PopCounter.pops(victim) > learnPops;
        if (!learnSmash || learnMixed) {
            Trace.log("mace learns nothing from that hit, {}", !learnSmash ? "the server did not count it as a smash" : "they took other damage too");
            return;
        }
        if (!popped && (learnImmune || victim.getAbsorptionAmount() > learnAbsorption + 0.5f)) {
            Trace.log("mace learns nothing from that hit, {}", learnImmune ? "it landed in their hurt time" : "they gained absorption");
            return;
        }
        float lost = learnHealth - (victim.getHealth() + victim.getAbsorptionAmount());
        float observed = popped ? learnHealth : lost;
        if (observed < MIN_LEARN) return;

        double low = 0;
        double high = MAX_FALL;
        for (int i = 0; i < 24; i++) {
            double mid = (low + high) / 2;
            if (smashDamage(mid, learnCharge, victim, learnBase, learnDensity) < observed) low = mid;
            else high = mid;
        }
        double fall = popped ? Math.max(countedFall(), high) : high;
        learnedFall = Double.isNaN(learnedFall) ? fall : learnedFall + (fall - learnedFall) * LEARN_WEIGHT;
        Trace.log("mace learned counted fall {} from {} damage{}", learnedFall, observed, popped ? " (popped, at least)" : "");
    }

    private void glideCommand() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void takeMace() {
        if (!holdMace.get() || AutoTotem.wantsMainTotem()) return;
        int slot = maceSlot();
        int selected = InventoryManager.get().selectedSlot();
        if (slot < 0 || slot >= InventoryManager.HOTBAR_END || slot == selected) return;
        if (InventoryManager.get().select(slot) && heldFrom == -1) heldFrom = selected;
    }

    private void giveBack() {
        if (heldFrom == -1 || mc.player == null) return;
        int back = heldFrom;
        heldFrom = -1;
        InventoryManager.get().select(back);
    }

    private void move(PlayerMoveC2SPacket packet) {
        sending = true;
        try {
            mc.getNetworkHandler().sendPacket(packet);
        } finally {
            sending = false;
        }
    }

    private boolean due() {
        return Util.getMeasuringTimeMs() - lastStrike >= interval.get();
    }

    private boolean flying() {
        return follow.get() && mc.player.isGliding() && Modules.get().isActive(ElytraFly.class);
    }

    private boolean canAct() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || !mc.player.isAlive()) return false;
        if (mc.player.hasVehicle() || mc.player.isSpectator()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return false;
        return !InventoryManager.get().isEating() && !mc.player.isUsingItem();
    }

    private int maceSlot() {
        InventoryManager inventory = InventoryManager.get();
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            ItemStack stack = inventory.stack(i);
            if (!stack.isOf(Items.MACE)) continue;
            int score = Utils.getEnchantmentLevel(stack, Enchantments.DENSITY) * 4 + Utils.getEnchantmentLevel(stack, Enchantments.BREACH) * 3 + (i < InventoryManager.HOTBAR_END ? 1 : 0);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private PlayerEntity choose(double distance, boolean reachOnly) {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Targets.valid(player, distance + 3)) continue;
            if (reachOnly && !inReach(player)) continue;
            if (!reachOnly && player.squaredDistanceTo(mc.player) > distance * distance) continue;

            double score;
            if (reachOnly) {
                score = player == target ? -1 : player.getHealth() + player.getAbsorptionAmount();
            } else {
                score = Math.sqrt(player.squaredDistanceTo(mc.player));
                if (!open(player)) score += CLOSED_PENALTY;
                if (player == target) score -= STICK;
            }
            if (score < bestScore) {
                best = player;
                bestScore = score;
            }
        }
        if (!reachOnly && best != null && target != null && best != target && Trace.on()) {
            Trace.log("mace switches from {} to {}, the nearest one it can hit", target.getName().getString(), best.getName().getString());
        }
        return best;
    }

    private boolean open(PlayerEntity player) {
        Box body = Targets.box(player);
        if (!mc.world.isSpaceEmpty(player, body.contract(BODY_INSET))) return false;
        Box own = mc.player.getBoundingBox();
        double half = own.getLengthX() / 2;
        Vec3d center = body.getCenter();
        double bottom = body.maxY + STRIKE_GAP;
        Box column = new Box(center.x - half, bottom, center.z - half, center.x + half, bottom + MIN_HOVER + own.getLengthY(), center.z + half);
        if (!mc.world.isSpaceEmpty(mc.player, column)) return false;
        return clearSight(new Vec3d(center.x, bottom + GLIDING_EYE, center.z), body);
    }

    private boolean inReach(PlayerEntity player) {
        double reach = range.get();
        return Targets.box(player).squaredMagnitude(mc.player.getEyePos()) <= reach * reach;
    }

    private double headroom() {
        Box box = mc.player.getBoundingBox();
        for (double h = height.get(); h >= MIN_DROP; h -= HEIGHT_STEP) {
            if (mc.world.isSpaceEmpty(mc.player, box.stretch(0, h, 0))) return h;
        }
        return 0;
    }

    private static Vec3d aim(Box box, Vec3d eye) {
        if (box.contains(eye)) return box.getCenter();
        Box inner = box.contract(AIM_INSET);
        return new Vec3d(
            MathHelper.clamp(eye.x, inner.minX, inner.maxX),
            MathHelper.clamp(eye.y, inner.minY, inner.maxY),
            MathHelper.clamp(eye.z, inner.minZ, inner.maxZ)
        );
    }
}
