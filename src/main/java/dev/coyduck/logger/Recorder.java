package dev.coyduck.logger;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Recorder {
    private static final Recorder INSTANCE = new Recorder();

    private static final double START_RANGE = 24;
    private static final double TRACK_RANGE = 48;
    private static final double MAX_SPAN = 96;
    private static final double NEAR_BLOCKS = 6;
    private static final long IDLE_MS = 30_000;
    private static final long COOLDOWN_MS = 20_000;
    private static final int STATE_EVERY = 20;
    private static final int POP = 35;
    private static final int DEATH = 3;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final LeakGuard guard = new LeakGuard();

    private LogFile file;
    private String dimension;
    private long tick;
    private int tickEnds;
    private int movesThisTick;

    private Vec3d anchor;
    private int window;
    private long windowStart;
    private long lastActivity;
    private long cooldownUntil;
    private Stats stats;

    private Vec3d beforeTeleport;
    private final Map<BlockPos, BlockState> before = new HashMap<>();
    private final Map<Integer, Long> crystals = new HashMap<>();
    private final Map<Integer, Float> health = new HashMap<>();
    private final Map<Integer, Boolean> gliding = new HashMap<>();
    private float selfHealth = -1;
    private int selfFood = -1;
    private String lastInput = "";
    private long lastWorldTime = -1;
    private long lastWorldTimeAt;
    private double tps = 20;

    private Recorder() {}

    public static Recorder get() {
        return INSTANCE;
    }

    public synchronized void join() {
        close("joined another world");
        String name = LocalDateTime.now().format(FILE) + ".log";
        Path path = mc.runDirectory.toPath().resolve("coyduck-logger").resolve(name);
        try {
            file = new LogFile(path);
        } catch (IOException e) {
            CoyduckLogger.LOG.error("Could not open {}", path, e);
            file = null;
            return;
        }
        tick = 0;
        anchor = null;
        window = 0;
        cooldownUntil = 0;
        health.clear();
        gliding.clear();
        crystals.clear();
        selfHealth = -1;
        selfFood = -1;
        lastInput = "";
        lastWorldTime = -1;
        dimension = mc.world == null ? "?" : mc.world.getRegistryKey().getValue().getPath();
        header();
        if (mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("§5[Coyduck Logger]§r recording to coyduck-logger/" + name + ", without coordinates."));
        }
    }

    public synchronized void close(String reason) {
        if (file == null) return;
        if (anchor != null) endWindow(reason);
        raw("END " + reason + ", scrubbed " + guard.scrubbed() + " numbers that looked like coordinates");
        file.close();
        file = null;
    }

    private void header() {
        raw("HDR Coyduck Logger " + FabricLoader.getInstance().getModContainer("coyduck-logger").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
            + " on Minecraft " + SharedConstants.getGameVersion().name());
        raw("HDR privacy: positions are written only inside fight windows, relative to where the window opened (W lines). A window closes after "
            + (int) MAX_SPAN + " blocks of travel and the next cannot open for " + COOLDOWN_MS / 1000 + "s, so windows cannot be chained into a route. Any other number of 1000 or more is replaced by ?.");
        raw("HDR player " + (mc.player == null ? "?" : mc.player.getName().getString()) + ", server " + server() + ", dimension " + dimension);
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.startsWith("fabric-") && !id.equals("fabric-api") || id.equals("java") || id.equals("minecraft") || id.equals("fabricloader") || id.equals("mixinextras")) continue;
            mods.add(id + "@" + mod.getMetadata().getVersion().getFriendlyString());
        }
        mods.sort(String::compareTo);
        raw("HDR mods " + String.join(" ", mods));
        raw("HDR tags: W window, > sent by this client, < received, E another player, S state every second, F fight summary");
    }

    private String server() {
        if (mc.getCurrentServerEntry() == null) return mc.isInSingleplayer() ? "singleplayer" : "?";
        String address = mc.getCurrentServerEntry().address;
        int port = address.lastIndexOf(':');
        return port > 0 ? address.substring(0, port) : address;
    }

    public synchronized void tick() {
        if (file == null) return;
        tick++;
        if (mc.player == null || mc.world == null) {
            if (anchor != null) endWindow("left the world");
            return;
        }
        String now = mc.world.getRegistryKey().getValue().getPath();
        if (!now.equals(dimension)) {
            if (anchor != null) endWindow("changed dimension");
            dimension = now;
            line("< dimension " + dimension);
        }

        if (tickEnds > 1) note("> " + tickEnds + " tick ends in one client tick", "tick-ends-burst");
        if (stats != null) stats.max("most tick ends in one tick", tickEnds);
        if (stats != null) stats.max("most moves in one tick", movesThisTick);
        tickEnds = 0;
        movesThisTick = 0;

        long time = Util.getMeasuringTimeMs();
        PlayerEntity nearest = nearestEnemy(START_RANGE);
        if (nearest != null) lastActivity = time;
        if (anchor == null && time >= cooldownUntil && (nearest != null || time - lastActivity < 1000)) {
            startWindow(nearest == null ? "took damage" : nearest.getName().getString() + " within " + (int) START_RANGE + " blocks");
        }
        if (anchor == null) return;

        if (mc.player.getEntityPos().distanceTo(anchor) > MAX_SPAN) {
            endWindow("moved " + (int) MAX_SPAN + " blocks from the start");
            cooldownUntil = time + COOLDOWN_MS;
            return;
        }
        if (time - lastActivity > IDLE_MS) {
            endWindow("nobody near and no damage for " + IDLE_MS / 1000 + "s");
            return;
        }

        trackPlayers();
        if (tick % STATE_EVERY == 0) state();
    }

    private PlayerEntity nearestEnemy(double range) {
        PlayerEntity best = null;
        double bestDistance = range;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator() || !player.isAlive()) continue;
            double distance = player.distanceTo(mc.player);
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void startWindow(String reason) {
        window++;
        anchor = mc.player.getEntityPos();
        windowStart = Util.getMeasuringTimeMs();
        lastActivity = windowStart;
        stats = new Stats();
        health.clear();
        gliding.clear();
        line("W" + window + " start, " + reason + ", positions below are relative to here");
        state();
    }

    private void endWindow(String reason) {
        long took = Util.getMeasuringTimeMs() - windowStart;
        line("W" + window + " end, " + reason + ", lasted " + took / 1000 + "s");
        if (stats != null) {
            for (String summary : stats.summary()) line("F" + window + " " + summary);
        }
        anchor = null;
        stats = null;
        crystals.clear();
        before.clear();
        beforeTeleport = null;
    }

    private void trackPlayers() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.distanceTo(mc.player) > TRACK_RANGE && player != mc.player) continue;
            int id = player.getId();
            float total = player.getHealth() + player.getAbsorptionAmount();
            Float last = health.put(id, total);
            if (player != mc.player && last != null && Math.abs(total - last) >= 0.5f) {
                line("E hp " + player.getName().getString() + " " + Describe.one(last) + " -> " + Describe.one(total) + " (" + (total > last ? "+" : "") + Describe.one(total - last) + ")");
                if (total < last) stats.add("hp taken by " + player.getName().getString(), last - total);
            }
            boolean glide = player.isGliding();
            Boolean wasGliding = gliding.put(id, glide);
            if (player != mc.player && wasGliding != null && wasGliding != glide) line("E " + player.getName().getString() + (glide ? " starts gliding" : " stops gliding") + " at " + rel(player.getEntityPos()));
        }
    }

    private void state() {
        PlayerListEntry entry = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        int ping = entry == null ? -1 : entry.getLatency();
        int totems = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
        }
        line("S me " + rel(mc.player.getEntityPos()) + " " + (mc.player.isGliding() ? "glide" : mc.player.isOnGround() ? "ground" : "air")
            + " v=" + Describe.num(mc.player.getVelocity().length()) + " look=" + Describe.one(mc.player.getYaw()) + "," + Describe.one(mc.player.getPitch())
            + " hp=" + Describe.one(mc.player.getHealth()) + "+" + Describe.one(mc.player.getAbsorptionAmount()) + " food=" + mc.player.getHungerManager().getFoodLevel()
            + " totems=" + totems + " main=" + Describe.item(mc.player.getMainHandStack()) + " off=" + Describe.item(mc.player.getOffHandStack())
            + " sel=" + mc.player.getInventory().getSelectedSlot() + " armor=" + Describe.armor(mc.player) + " ping=" + ping + " tps=" + Describe.one(tps));
        List<PlayerEntity> players = new ArrayList<>(mc.world.getPlayers());
        players.sort(Comparator.comparingDouble(player -> player.distanceTo(mc.player)));
        for (PlayerEntity player : players) {
            if (player == mc.player || player.distanceTo(mc.player) > TRACK_RANGE) continue;
            line("S " + player.getName().getString() + " " + rel(player.getEntityPos()) + " d=" + Describe.one(player.distanceTo(mc.player))
                + (player.isGliding() ? " glide" : player.isOnGround() ? " ground" : " air") + " hp=" + Describe.one(player.getHealth()) + "+" + Describe.one(player.getAbsorptionAmount())
                + " main=" + Describe.item(player.getMainHandStack()) + " off=" + Describe.item(player.getOffHandStack()) + " armor=" + Describe.armor(player)
                + (player.isUsingItem() ? " using=" + Describe.item(player.getActiveItem()) : ""));
        }
    }

    public synchronized void sent(Packet<?> packet) {
        if (file == null || mc.player == null) return;
        try {
            sentPacket(packet);
        } catch (RuntimeException e) {
            CoyduckLogger.LOG.warn("Could not log a sent packet", e);
        }
    }

    private void sentPacket(Packet<?> packet) {
        switch (packet) {
            case ClientTickEndC2SPacket ignored -> tickEnds++;
            case KeepAliveC2SPacket ignored -> {
            }
            case PlayerMoveC2SPacket move -> {
                movesThisTick++;
                count("moves sent");
                if (anchor == null) return;
                StringBuilder out = new StringBuilder("> move");
                if (move.changesPosition()) out.append(" pos=").append(rel(new Vec3d(move.getX(0), move.getY(0), move.getZ(0))));
                if (move.changesLook()) out.append(" look=").append(Describe.one(move.getYaw(0))).append(',').append(Describe.one(move.getPitch(0)));
                out.append(move.isOnGround() ? " ground" : " air");
                if (move.horizontalCollision()) out.append(" wall");
                line(out.toString());
            }
            case PlayerInteractBlockC2SPacket use -> {
                ItemStack held = held(use.getHand());
                String kind = held.isOf(Items.END_CRYSTAL) ? "crystals placed" : held.isOf(Items.RESPAWN_ANCHOR) ? "anchors placed" : held.getItem() instanceof net.minecraft.item.BlockItem ? "blocks placed" : "blocks used";
                count(kind);
                if (anchor == null) return;
                BlockPos pos = use.getBlockHitResult().getBlockPos();
                Vec3d hit = use.getBlockHitResult().getPos().subtract(pos.getX(), pos.getY(), pos.getZ());
                line("> use-block " + relBlock(pos) + " face=" + use.getBlockHitResult().getSide().asString() + " hit=" + Describe.num(hit.x) + "," + Describe.num(hit.y) + "," + Describe.num(hit.z)
                    + (use.getBlockHitResult().isInsideBlock() ? " inside" : "") + " " + hand(use.getHand()) + " item=" + Describe.item(held) + " seq=" + use.getSequence()
                    + " on=" + Describe.block(mc.world.getBlockState(pos)));
            }
            case PlayerInteractItemC2SPacket use -> {
                count("items used");
                line("> use-item " + hand(use.getHand()) + " item=" + Describe.item(held(use.getHand())) + " seq=" + use.getSequence());
            }
            case PlayerInteractEntityC2SPacket interact -> {
                int id = ((dev.coyduck.logger.mixin.PlayerInteractEntityC2SPacketAccessor) interact).coyduckLogger$entityId();
                Entity target = mc.world.getEntityById(id);
                String[] kind = {"interact"};
                interact.handle(new PlayerInteractEntityC2SPacket.Handler() {
                    @Override
                    public void interact(Hand hand) {
                        kind[0] = "interact";
                    }

                    @Override
                    public void interactAt(Hand hand, Vec3d pos) {
                        kind[0] = "interact-at";
                    }

                    @Override
                    public void attack() {
                        kind[0] = "attack";
                    }
                });
                if (kind[0].equals("attack")) count(target instanceof EndCrystalEntity ? "crystals hit" : target instanceof PlayerEntity ? "player hits" : "other hits");
                if (kind[0].equals("attack") && target instanceof EndCrystalEntity && crystals.containsKey(id)) {
                    line("> attack " + Describe.entity(target, id) + " " + (Util.getMeasuringTimeMs() - crystals.get(id)) + "ms after it appeared main=" + Describe.item(mc.player.getMainHandStack()));
                    return;
                }
                line("> " + kind[0] + " " + Describe.entity(target, id) + (target != null && anchor != null ? " d=" + Describe.num(target.distanceTo(mc.player)) : "")
                    + " main=" + Describe.item(mc.player.getMainHandStack()) + (interact.isPlayerSneaking() ? " sneaking" : ""));
            }
            case PlayerActionC2SPacket action -> {
                String name = action.getAction().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                count("action " + name);
                boolean positional = switch (action.getAction()) {
                    case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> true;
                    default -> false;
                };
                if (positional) {
                    if (anchor == null) return;
                    line("> " + name + " " + relBlock(action.getPos()) + " face=" + action.getDirection().asString() + " block=" + Describe.block(mc.world.getBlockState(action.getPos()))
                        + " main=" + Describe.item(mc.player.getMainHandStack()) + " seq=" + action.getSequence());
                } else {
                    line("> " + name + " main=" + Describe.item(mc.player.getMainHandStack()) + " off=" + Describe.item(mc.player.getOffHandStack()));
                }
            }
            case UpdateSelectedSlotC2SPacket select -> {
                count("hotbar selects");
                line("> select " + select.getSelectedSlot() + " " + Describe.item(mc.player.getInventory().getStack(select.getSelectedSlot())));
            }
            case ClickSlotC2SPacket click -> {
                count("inventory clicks");
                line("> click sync=" + click.syncId() + " slot=" + click.slot() + " button=" + click.button() + " " + click.actionType().name().toLowerCase(java.util.Locale.ROOT)
                    + " rev=" + click.revision() + " changes=" + click.modifiedStacks().size());
            }
            case HandSwingC2SPacket swing -> {
                count("swings");
                if (anchor != null) line("> swing " + hand(swing.getHand()));
            }
            case ClientCommandC2SPacket command -> {
                String mode = command.getMode().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                count("command " + mode);
                line("> " + mode);
            }
            case PlayerInputC2SPacket input -> {
                String text = (input.input().forward() ? "W" : "") + (input.input().left() ? "A" : "") + (input.input().backward() ? "S" : "") + (input.input().right() ? "D" : "")
                    + (input.input().jump() ? " jump" : "") + (input.input().sneak() ? " sneak" : "") + (input.input().sprint() ? " sprint" : "");
                if (!text.equals(lastInput) && anchor != null) line("> input " + (text.isEmpty() ? "none" : text.trim()));
                lastInput = text;
            }
            case TeleportConfirmC2SPacket confirm -> line("> teleport-confirm id=" + confirm.getTeleportId());
            case VehicleMoveC2SPacket vehicle -> {
                count("vehicle moves");
                if (anchor != null) line("> vehicle " + rel(vehicle.position()));
            }
            case ChatMessageC2SPacket ignored -> count("chat messages (not recorded)");
            case CommandExecutionC2SPacket ignored -> count("commands (not recorded)");
            case ChatCommandSignedC2SPacket ignored -> count("commands (not recorded)");
            default -> count("other sent " + packet.getPacketType().id().getPath());
        }
    }

    public synchronized void received(Packet<?> packet, boolean after) {
        if (file == null || mc.player == null || mc.world == null) return;
        try {
            if (after) afterReceived(packet);
            else beforeReceived(packet);
        } catch (RuntimeException e) {
            CoyduckLogger.LOG.warn("Could not log a received packet", e);
        }
    }

    private void beforeReceived(Packet<?> packet) {
        switch (packet) {
            case PlayerPositionLookS2CPacket ignored -> beforeTeleport = mc.player.getEntityPos();
            case BlockUpdateS2CPacket update -> remember(update.getPos());
            case ChunkDeltaUpdateS2CPacket delta -> delta.visitUpdates((pos, state) -> remember(pos));
            default -> {
            }
        }
    }

    private void remember(BlockPos pos) {
        if (anchor == null || !near(pos)) return;
        before.put(pos.toImmutable(), mc.world.getBlockState(pos));
    }

    private boolean near(BlockPos pos) {
        return mc.player.getEntityPos().squaredDistanceTo(pos.toCenterPos()) <= NEAR_BLOCKS * NEAR_BLOCKS;
    }

    private void afterReceived(Packet<?> packet) {
        switch (packet) {
            case EntityDamageS2CPacket damage -> onDamage(damage);
            case EntityStatusS2CPacket status -> onStatus(status);
            case HealthUpdateS2CPacket update -> {
                if (update.getHealth() != selfHealth || update.getFood() != selfFood) {
                    line("< health " + Describe.one(update.getHealth()) + " food=" + update.getFood() + " sat=" + Describe.one(update.getSaturation()));
                }
                selfHealth = update.getHealth();
                selfFood = update.getFood();
            }
            case PlayerPositionLookS2CPacket teleport -> {
                count("server teleports");
                lastActivity = anchor != null ? Util.getMeasuringTimeMs() : lastActivity;
                Vec3d moved = beforeTeleport == null ? Vec3d.ZERO : mc.player.getEntityPos().subtract(beforeTeleport);
                line("< teleport id=" + teleport.teleportId() + " moved " + Describe.vec(moved) + " (" + Describe.num(moved.length()) + " blocks)");
                beforeTeleport = null;
            }
            case PlayerActionResponseS2CPacket ack -> {
                if (anchor != null) line("< ack seq=" + ack.sequence());
            }
            case BlockUpdateS2CPacket update -> blockChanged(update.getPos(), update.getState());
            case ChunkDeltaUpdateS2CPacket delta -> delta.visitUpdates(this::blockChanged);
            case BlockBreakingProgressS2CPacket progress -> {
                if (anchor == null || !near(progress.getPos())) return;
                Entity breaker = mc.world.getEntityById(progress.getEntityId());
                line("< breaking " + relBlock(progress.getPos()) + " " + Describe.block(mc.world.getBlockState(progress.getPos())) + " stage=" + progress.getProgress() + " by " + Describe.name(breaker));
            }
            case EntitySpawnS2CPacket spawn -> {
                if (anchor == null || spawn.getEntityType() != EntityType.END_CRYSTAL) return;
                Vec3d pos = new Vec3d(spawn.getX(), spawn.getY(), spawn.getZ());
                if (pos.distanceTo(mc.player.getEntityPos()) > TRACK_RANGE) return;
                crystals.put(spawn.getEntityId(), Util.getMeasuringTimeMs());
                PlayerEntity placer = nearestTo(pos);
                line("< crystal#" + spawn.getEntityId() + " at " + rel(pos) + " d=" + Describe.num(pos.distanceTo(mc.player.getEntityPos()))
                    + (placer == null ? "" : " nearest " + placer.getName().getString() + "(" + Describe.num(placer.getEntityPos().distanceTo(pos)) + ")"));
            }
            case EntitiesDestroyS2CPacket destroy -> {
                for (int id : destroy.getEntityIds()) {
                    Long born = crystals.remove(id);
                    if (born != null) line("< crystal#" + id + " gone after " + (Util.getMeasuringTimeMs() - born) + "ms");
                }
            }
            case ExplosionS2CPacket explosion -> {
                if (anchor == null) return;
                line("< explosion at " + rel(explosion.center()) + " d=" + Describe.num(explosion.center().distanceTo(mc.player.getEntityPos())) + " power=" + Describe.one(explosion.radius())
                    + explosion.playerKnockback().map(push -> " push=" + Describe.vec(push)).orElse(""));
            }
            case EntityEquipmentUpdateS2CPacket equipment -> {
                if (anchor == null) return;
                Entity entity = mc.world.getEntityById(equipment.getEntityId());
                if (!(entity instanceof PlayerEntity) || entity == mc.player) return;
                StringBuilder out = new StringBuilder("E equip ").append(Describe.name(entity));
                equipment.getEquipmentList().forEach(pair -> out.append(' ').append(slot(pair.getFirst())).append('=').append(Describe.item(pair.getSecond())));
                line(out.toString());
            }
            case EntityAnimationS2CPacket animation -> {
                if (anchor == null) return;
                Entity entity = mc.world.getEntityById(animation.getEntityId());
                if (!(entity instanceof PlayerEntity) || entity == mc.player) return;
                if (animation.getAnimationId() == EntityAnimationS2CPacket.SWING_MAIN_HAND || animation.getAnimationId() == EntityAnimationS2CPacket.SWING_OFF_HAND) {
                    line("E swing " + Describe.name(entity) + (animation.getAnimationId() == EntityAnimationS2CPacket.SWING_MAIN_HAND ? " main " : " off ")
                        + Describe.item(animation.getAnimationId() == EntityAnimationS2CPacket.SWING_MAIN_HAND ? ((PlayerEntity) entity).getMainHandStack() : ((PlayerEntity) entity).getOffHandStack()));
                }
            }
            case ScreenHandlerSlotUpdateS2CPacket slot -> {
                if (slot.getSyncId() != 0 || slot.getSlot() < 36) return;
                line("< slot " + slot.getSlot() + " " + Describe.item(slot.getStack()) + " rev=" + slot.getRevision());
            }
            case InventoryS2CPacket inventory -> {
                if (inventory.syncId() == 0) line("< inventory resync rev=" + inventory.revision());
            }
            case WorldTimeUpdateS2CPacket time -> {
                long wall = Util.getMeasuringTimeMs();
                if (lastWorldTime >= 0 && wall > lastWorldTimeAt) {
                    double rate = (time.time() - lastWorldTime) * 1000.0 / (wall - lastWorldTimeAt);
                    if (rate > 0 && rate < 40) tps = tps * 0.7 + Math.min(20, rate) * 0.3;
                }
                lastWorldTime = time.time();
                lastWorldTimeAt = wall;
            }
            default -> {
            }
        }
    }

    private void onDamage(EntityDamageS2CPacket damage) {
        Entity victim = mc.world.getEntityById(damage.entityId());
        if (!(victim instanceof PlayerEntity)) return;
        Entity cause = mc.world.getEntityById(damage.sourceCauseId());
        Entity direct = mc.world.getEntityById(damage.sourceDirectId());
        String type = Describe.damage(damage.sourceType());
        if (victim == mc.player) {
            lastActivity = Util.getMeasuringTimeMs();
            count("damage taken " + type);
            line("< damage " + type + " by " + (cause == null ? "-" : Describe.name(cause)) + (direct != null && direct != cause ? " via " + Describe.name(direct) : ""));
            return;
        }
        if (anchor == null || victim.distanceTo(mc.player) > TRACK_RANGE) return;
        line("E hurt " + Describe.name(victim) + " " + type + " by " + (cause == null ? "-" : cause == mc.player ? "me" : Describe.name(cause)));
        if (cause == mc.player) count("hits on players " + type);
    }

    private void onStatus(EntityStatusS2CPacket status) {
        Entity entity = status.getEntity(mc.world);
        if (!(entity instanceof PlayerEntity player)) return;
        if (status.getStatus() == POP) {
            if (player == mc.player) {
                lastActivity = Util.getMeasuringTimeMs();
                count("pops taken");
                line("< POP me hp=" + Describe.one(player.getHealth()) + " off=" + Describe.item(player.getOffHandStack()));
            } else if (player.distanceTo(mc.player) <= TRACK_RANGE) {
                if (stats != null) stats.add("pops of " + player.getName().getString(), 1);
                line("E pop " + player.getName().getString());
            }
        } else if (status.getStatus() == DEATH && player.distanceTo(mc.player) <= TRACK_RANGE) {
            count(player == mc.player ? "deaths" : "kills seen");
            line((player == mc.player ? "< DEATH me" : "E death " + player.getName().getString()));
        }
    }

    private void blockChanged(BlockPos pos, BlockState state) {
        BlockState old = before.remove(pos);
        if (anchor == null || old == null || old.equals(state) || !near(pos)) return;
        line("< block " + relBlock(pos) + " " + Describe.block(old) + " -> " + Describe.block(state));
    }

    private PlayerEntity nearestTo(Vec3d pos) {
        PlayerEntity best = null;
        double bestDistance = 8;
        for (PlayerEntity player : mc.world.getPlayers()) {
            double distance = player.getEntityPos().distanceTo(pos);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private ItemStack held(Hand hand) {
        return hand == Hand.MAIN_HAND ? mc.player.getMainHandStack() : mc.player.getOffHandStack();
    }

    private static String hand(Hand hand) {
        return hand == Hand.MAIN_HAND ? "main" : "off";
    }

    private static String slot(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> "main";
            case OFFHAND -> "off";
            default -> slot.getName();
        };
    }

    private String rel(Vec3d pos) {
        return anchor == null ? "-" : Describe.vec(pos.subtract(anchor));
    }

    private String relBlock(BlockPos pos) {
        if (anchor == null) return "-";
        BlockPos origin = BlockPos.ofFloored(anchor);
        return (pos.getX() - origin.getX()) + "," + (pos.getY() - origin.getY()) + "," + (pos.getZ() - origin.getZ());
    }

    private void count(String key) {
        if (stats != null) stats.add(key, 1);
    }

    private void note(String text, String key) {
        count(key);
        if (anchor != null) line(text);
    }

    private void line(String text) {
        raw("T" + tick + " " + text);
    }

    private void raw(String text) {
        if (file == null) return;
        file.line(LocalTime.now().format(CLOCK) + " " + guard.clean(text));
    }

    private static final class Stats {
        private final Map<String, Double> values = new TreeMap<>();
        private final Map<String, Double> maxima = new LinkedHashMap<>();

        void add(String key, double amount) {
            values.merge(key, amount, Double::sum);
        }

        void max(String key, double value) {
            maxima.merge(key, value, Math::max);
        }

        List<String> summary() {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                String value = entry.getValue() == Math.rint(entry.getValue()) ? String.valueOf(entry.getValue().longValue()) : Describe.one(entry.getValue());
                String part = entry.getKey() + ": " + value;
                if (line.length() + part.length() > 180) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(", ");
                line.append(part);
            }
            if (!line.isEmpty()) out.add(line.toString());
            if (!maxima.isEmpty()) {
                StringBuilder peaks = new StringBuilder();
                maxima.forEach((key, value) -> {
                    if (!peaks.isEmpty()) peaks.append(", ");
                    peaks.append(key).append(": ").append(value.longValue());
                });
                out.add(peaks.toString());
            }
            return out;
        }
    }
}
