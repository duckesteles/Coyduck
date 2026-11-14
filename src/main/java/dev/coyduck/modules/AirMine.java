package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public class AirMine extends Module {
    private static final double FALL_MARGIN = 0.5;
    private static final double LANDING_OFFSET = 0.2;
    private static final float AIR_PENALTY = 5.0f;
    private static final float STOP_NEED = 1.1f;
    private static final int WIND_GRACE = 40;

    private static AirMine instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> falling = sgGeneral.add(new BoolSetting.Builder()
        .name("falling")
        .description("Also mines at ground speed while falling, jumping or swimming, as long as the fall is still too short to hurt you. Elytra gliding counts even when this is off.")
        .defaultValue(true)
        .build()
    );

    private PlayerActionC2SPacket spoofed;
    private BlockPos startPos;
    private BlockState startState;
    private int startTick;
    private int ticks;
    private volatile boolean windHit;
    private boolean windGuard;
    private boolean windLifted;
    private int windTick;

    public AirMine() {
        super(Coyduck.CATEGORY, "air-mine", "Mines at full speed in the air.");
        instance = this;
    }

    public static boolean spoofing() {
        AirMine module = instance;
        return module != null && module.isActive() && module.applies();
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        spoofed = null;
        startPos = null;
        startState = null;
        windHit = false;
        windGuard = false;
    }

    private boolean applies() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.isOnGround() || player.hasVehicle() || player.getAbilities().flying) return false;
        if (!player.isGliding() && !falling.get()) return false;
        if (Modules.get().get(MaceStrike.class).hunting()) return false;
        return harmless(player);
    }

    private boolean harmless(ClientPlayerEntity player) {
        double fall = player.fallDistance;
        if (fall <= 0) return true;
        if (windHit || windGuard || Modules.get().get(KillAura.class).critting()) return false;
        if (mc.world.getBlockState(BlockPos.ofFloored(player.getX(), player.getY() - LANDING_OFFSET, player.getZ())).isOf(Blocks.POINTED_DRIPSTONE)) return false;

        double safe = player.getAttributeValue(EntityAttributes.SAFE_FALL_DISTANCE);
        double scale = player.getAttributeValue(EntityAttributes.FALL_DAMAGE_MULTIPLIER);
        return MathHelper.floor((fall + FALL_MARGIN - safe) * scale) <= 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player != null) guardWind(mc.player);
        if (spoofed == null) return;

        spoofed = null;
        if (mc.player != null && !mc.player.isOnGround()) ground(false);
    }

    private void guardWind(ClientPlayerEntity player) {
        if (windHit) {
            windHit = false;
            windGuard = true;
            windLifted = false;
            windTick = ticks;
        }
        if (!windGuard) return;

        boolean settled = player.isOnGround() || player.isTouchingWater();
        if (!settled) windLifted = true;
        else if (windLifted || ticks - windTick > WIND_GRACE) windGuard = false;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ExplosionS2CPacket packet) || mc.player == null) return;
        if (!packet.explosionSound().value().id().equals(SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value().id())) return;

        double reach = packet.radius() * 2 + 1;
        if (packet.playerKnockback().isPresent() || mc.player.squaredDistanceTo(packet.center()) <= reach * reach) windHit = true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerActionC2SPacket packet) || mc.player == null || mc.world == null) return;

        boolean spoof = switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> start(packet.getPos());
            case STOP_DESTROY_BLOCK -> applies() && stopNeedsGround(packet.getPos());
            default -> false;
        };
        if (!spoof) return;

        ground(true);
        spoofed = packet;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (spoofed == null || event.packet != spoofed) return;

        spoofed = null;
        ground(false);
    }

    private boolean start(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        startPos = pos.toImmutable();
        startState = state.isReplaceable() ? null : state;
        startTick = ticks;
        if (!applies()) return false;
        if (startState == null) return true;

        return delta(state, pos, true) >= 1 && delta(state, pos, false) / AIR_PENALTY < 1;
    }

    private boolean stopNeedsGround(BlockPos pos) {
        if (!pos.equals(startPos)) return true;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable()) state = startState;
        if (state == null) return true;

        float delta = delta(state, pos, false);
        if (delta <= 0) return false;

        float rate = Math.min(TickRate.INSTANCE.getTickRate() / 20.0f, 1.0f);
        int margin = (int) Math.ceil(LatencyManager.get().jitter() / 50.0) + 1;
        return delta / AIR_PENALTY * ((ticks - startTick) * rate - margin) < STOP_NEED;
    }

    private float delta(BlockState state, BlockPos pos, boolean efficiencyCounts) {
        InventoryManager inventory = InventoryManager.get();
        return MiningManager.get().groundDelta(state, pos, inventory.stack(inventory.serverSlot()), efficiencyCounts);
    }

    private void ground(boolean onGround) {
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(onGround, mc.player.horizontalCollision));
    }
}
