package dev.coyduck.modules;

import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.PlayerMoveEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.RotationManager;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

public class SpeedPlus extends Feature {
    public enum Mode { Strafe, Vanilla }

    private static final double BASE = 0.2873;
    private static final double PROBE = 0.35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPause = settings.createGroup("Pause");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Strafe hops, Vanilla moves at a flat speed.")
        .defaultValue(Mode.Strafe)
        .build()
    );

    private final Setting<Double> strafeSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("strafe-speed")
        .description("Boost of each hop in strafe mode.")
        .defaultValue(1.6)
        .range(1, 3)
        .sliderRange(1, 3)
        .visible(() -> mode.get() == Mode.Strafe)
        .build()
    );

    private final Setting<Double> vanillaSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vanilla-speed")
        .description("Speed in blocks per second in vanilla mode.")
        .defaultValue(5.6)
        .range(1, 20)
        .sliderRange(1, 12)
        .visible(() -> mode.get() == Mode.Vanilla)
        .build()
    );

    private final Setting<Boolean> whenSneaking = sgGeneral.add(new BoolSetting.Builder()
        .name("when-sneaking")
        .description("Keeps the speed while you sneak.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> liquidGrace = sgPause.add(new IntSetting.Builder()
        .name("liquid-grace")
        .description("Ticks to wait after leaving water or lava.")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> slowBlocks = sgPause.add(new BoolSetting.Builder()
        .name("slow-blocks")
        .description("Pauses in webs, powder snow and slowing blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> oddBlocks = sgPause.add(new BoolSetting.Builder()
        .name("odd-blocks")
        .description("Pauses near ladders, scaffolding, vines and candles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> setbackPause = sgPause.add(new IntSetting.Builder()
        .name("setback-pause")
        .description("Ticks to wait after a setback.")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    private int stage;
    private double speed;
    private double distance;
    private int liquidTicks;
    private int pauseTicks;
    private String reason;

    public SpeedPlus() {
        super("speed+", "Strafe speed that avoids setbacks.");
    }

    @Override
    public void onActivate() {
        displace("it moves you the same way.", "speed");
        reset();
        liquidTicks = 0;
        pauseTicks = 0;
    }

    @Override
    public String getInfoString() {
        return reason != null ? reason : mode.get().name();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        double dx = mc.player.getX() - mc.player.lastX;
        double dz = mc.player.getZ() - mc.player.lastZ;
        distance = Math.sqrt(dx * dx + dz * dz);
        if (pauseTicks > 0) pauseTicks--;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) liquidTicks = liquidGrace.get();
        else if (liquidTicks > 0) liquidTicks--;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        mc.execute(() -> {
            reset();
            pauseTicks = setbackPause.get();
        });
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || mc.world == null) return;

        reason = pauseReason();
        if (reason != null) {
            reset();
            return;
        }

        Vec2f input = mc.player.input.getMovementInput();
        if (input.lengthSquared() < 1.0E-4f) {
            reset();
            return;
        }

        if (mode.get() == Mode.Vanilla) {
            double flat = vanillaSpeed.get() / 20.0 * effectScale();
            setHorizontal(event, flat, input);
            return;
        }

        switch (stage) {
            case 0 -> {
                stage = 1;
                speed = 1.18 * base() - 0.01;
            }
            case 1 -> {
                if (mc.player.isOnGround()) {
                    event.setY(hop());
                    speed *= strafeSpeed.get();
                    stage = 2;
                }
            }
            case 2 -> {
                speed = distance - 0.76 * (distance - base());
                stage = 3;
            }
            default -> {
                if (mc.player.isOnGround() || mc.player.verticalCollision) stage = 1;
                speed = distance - distance / 159.0;
            }
        }

        speed = Math.max(speed, base());
        setHorizontal(event, speed, input);
    }

    private String pauseReason() {
        if (mc.player.isGliding() || mc.player.isClimbing() || mc.player.hasVehicle() || mc.player.getAbilities().flying) return "paused";
        if (!whenSneaking.get() && mc.player.isSneaking()) return "sneaking";
        if (mc.player.isUsingItem()) return "eating";
        if (pauseTicks > 0) return "pulled back";
        if (liquidTicks > 0) return "liquid";
        if (slowBlocks.get() && slowed()) return "slowed";
        if (oddBlocks.get() && oddBlock()) return "odd block";
        return null;
    }

    private boolean slowed() {
        if (mc.player.inPowderSnow) return true;
        Box box = mc.player.getBoundingBox();
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ), MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
            Block block = mc.world.getBlockState(pos).getBlock();
            if (block == Blocks.COBWEB || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW) return true;
        }
        BlockPos below = BlockPos.ofFloored(mc.player.getX(), box.minY - 0.5, mc.player.getZ());
        Block ground = mc.world.getBlockState(below).getBlock();
        return mc.player.isOnGround() && ground.getVelocityMultiplier() < 1.0f;
    }

    private boolean oddBlock() {
        Box box = mc.player.getBoundingBox();
        if (oddIn(box)) return true;

        Vec2f input = mc.player.input.getMovementInput();
        float yaw = RotationManager.get().physicsYaw(mc.player, mc.player.getYaw()) * MathHelper.RADIANS_PER_DEGREE;
        double x = input.x * MathHelper.cos(yaw) - input.y * MathHelper.sin(yaw);
        double z = input.y * MathHelper.cos(yaw) + input.x * MathHelper.sin(yaw);
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-4) return false;
        return oddIn(box.offset(x / length * PROBE, 0, z / length * PROBE));
    }

    private boolean oddIn(Box box) {
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY - 0.5), MathHelper.floor(box.minZ), MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.isIn(BlockTags.CLIMBABLE) || state.isIn(BlockTags.CANDLES) || state.isIn(BlockTags.CANDLE_CAKES) || state.isOf(Blocks.SCAFFOLDING)) return true;
        }
        return false;
    }

    private void setHorizontal(PlayerMoveEvent event, double amount, Vec2f input) {
        float yaw = RotationManager.get().physicsYaw(mc.player, mc.player.getYaw()) * MathHelper.RADIANS_PER_DEGREE;
        double x = input.x * MathHelper.cos(yaw) - input.y * MathHelper.sin(yaw);
        double z = input.y * MathHelper.cos(yaw) + input.x * MathHelper.sin(yaw);
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-4) return;
        event.setXZ(x / length * amount, z / length * amount);
    }

    private double base() {
        return BASE * effectScale();
    }

    private double effectScale() {
        double scale = 1;
        StatusEffectInstance swift = mc.player.getStatusEffect(StatusEffects.SPEED);
        if (swift != null) scale *= 1.0 + 0.2 * (swift.getAmplifier() + 1);
        StatusEffectInstance slow = mc.player.getStatusEffect(StatusEffects.SLOWNESS);
        if (slow != null) scale /= 1.0 + 0.2 * (slow.getAmplifier() + 1);
        return scale;
    }

    private double hop() {
        double height = 0.40123128;
        StatusEffectInstance boost = mc.player.getStatusEffect(StatusEffects.JUMP_BOOST);
        if (boost != null) height += (boost.getAmplifier() + 1) * 0.1f;
        return height;
    }

    private void reset() {
        stage = 0;
        speed = BASE;
    }
}
