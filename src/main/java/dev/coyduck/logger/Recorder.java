package dev.coyduck.logger;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Recorder {
    private static final Recorder INSTANCE = new Recorder();

    private static final double START_RANGE = 24;
    private static final double TRACK_RANGE = 48;
    private static final double MAX_SPAN = 96;
    private static final double MAX_REL = MAX_SPAN + 48;
    private static final double NEAR_BLOCKS = 6;
    private static final long IDLE_MS = 30_000;
    private static final long COOLDOWN_MS = 20_000;
    private static final int STATE_EVERY = 20;
    private static final int POP = 35;
    private static final int DEATH = 3;
    private static final int OFFHAND_SLOT = 45;
    private static final long PLACE_FORGET_MS = 5_000;
    private static final long CRYSTAL_WAIT_MS = 1_500;
    private static final long STOP_WAIT_MS = 1_200;
    private static final long BOOM_SETTLE_MS = 350;
    private static final long TOTEM_WAIT_MS = 3_000;
    private static final long RECENT_MS = 1_000;
    private static final long PEARL_GRACE_MS = 3_000;
    private static final double BOOM_RANGE = 12;
    private static final double SETBACK_MAX = 10;
    private static final double JUMP = 3;
    private static final double GLIDE_JUMP = 8;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<String> PUBLIC_SERVERS = List.of("6b6t.org", "2b2t.org", "9b9t.com", "0b0t.org", "8b8t.me", "constantiam.net", "anarchy.pw", "simpleanarchy.net");

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final LeakGuard guard = new LeakGuard();

    private LogFile file;
    private LogFile closed;
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
    private Entity beforeTeleportEntity;
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

    private record SentPlace(int sequence, BlockPos target, BlockPos alternative, String item, long at) {}

    private record CrystalPlace(Vec3d expected, long at) {}

    private record Boom(Vec3d center, long at, Map<Integer, Float> before, Set<Integer> popped) {}

    private final List<SentPlace> places = new ArrayList<>();
    private final List<CrystalPlace> crystalPlaces = new ArrayList<>();
    private final Map<BlockPos, Long> starts = new HashMap<>();
    private final Map<BlockPos, Long> stops = new HashMap<>();
    private final List<Boom> booms = new ArrayList<>();
    private final Map<Integer, Vec3d> lastPos = new HashMap<>();
    private final Map<Integer, Long> lastHurt = new HashMap<>();
    private final Map<Integer, String> using = new HashMap<>();
    private final Map<Integer, Long> usingSince = new HashMap<>();
    private final ArrayDeque<Long> recentPlaces = new ArrayDeque<>();
    private final ArrayDeque<Long> recentAttacks = new ArrayDeque<>();
    private final ArrayDeque<Long> recentUses = new ArrayDeque<>();
    private long popAt;
    private long lastSetback;
    private long lastOwnPearl;
    private long lastSelfHurt;
    private String lastDamageType = "?";
    private long lastDamageAt;
    private Item eating;
    private Hand eatingHand;
    private int eatingCount;
    private long eatingSince;

    private Recorder() {}

    public static Recorder get() {
        return INSTANCE;
    }

    public synchronized void join() {
        try {
            joinSafely();
        } catch (RuntimeException e) {
            CoyduckLogger.LOG.warn("Coyduck Logger join failed", e);
        }
    }

    private void joinSafely() {
        closeSafely("joined another world");
        Path directory = mc.runDirectory.toPath().resolve("coyduck-logger");
        try {
            file = LogFile.open(directory, LocalDateTime.now().format(FILE));
        } catch (IOException e) {
            CoyduckLogger.LOG.error("Could not open a log in {}", directory, e);
            file = null;
            return;
        }
        String name = file.path().getFileName().toString();
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
        try {
            closeSafely(reason);
        } catch (RuntimeException e) {
            CoyduckLogger.LOG.warn("Coyduck Logger close failed", e);
        }
    }

    public void shutdown() {
        LogFile last;
        synchronized (this) {
            close("game closed");
            last = closed;
        }
        if (last != null) last.await();
    }

    private void closeSafely(String reason) {
        if (file == null) return;
        if (anchor != null) endWindow(reason);
        raw("END " + reason + ", scrubbed " + guard.scrubbed() + " numbers that looked like coordinates" + (file.dropped() > 0 ? ", " + file.dropped() + " lines dropped" : ""));
        file.close();
        closed = file;
        file = null;
    }

    private void header() {
        raw("HDR Coyduck Logger " + FabricLoader.getInstance().getModContainer("coyduck-logger").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
            + " on Minecraft " + SharedConstants.getGameVersion().name());
        raw("HDR server brand " + (mc.getNetworkHandler() == null || mc.getNetworkHandler().getBrand() == null ? "?" : mc.getNetworkHandler().getBrand()));
        raw("HDR privacy: positions are written only inside fight windows, relative to where the window opened (W lines). A window closes after "
            + (int) MAX_SPAN + " blocks of travel, a far teleport or a respawn, and after any window ends the next cannot open for " + COOLDOWN_MS / 1000 + "s, so windows cannot be chained into a route. Teleports outside windows are written without distances. Any other number of 1000 or more is replaced by ?.");
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
        raw("HDR outcomes: '< place REFUSED' when the server undid a block, '> crystal place got no crystal', '< boom' damage per explosion, '< SETBACK' for server pull-backs, '< broke' and '> stop ... did not break' for mining, '< offhand totem' refill time after a pop");
    }

    private String server() {
        if (mc.getCurrentServerEntry() == null) return mc.isInSingleplayer() ? "singleplayer" : "?";
        String address = mc.getCurrentServerEntry().address.trim();
        if (address.startsWith("[") || address.chars().filter(c -> c == ':').count() > 1) return "an ip address";
        int port = address.lastIndexOf(':');
        String host = (port > 0 ? address.substring(0, port) : address).toLowerCase(Locale.ROOT);
        if (host.matches("[0-9.]+")) return "an ip address";
        for (String known : PUBLIC_SERVERS) if (host.equals(known) || host.endsWith("." + known)) return known;
        return "a private server";
    }

    public synchronized void tick() {
        try {
            tickSafely();
        } catch (RuntimeException e) {
            CoyduckLogger.LOG.warn("Coyduck Logger tick failed", e);
        }
    }

    private void tickSafely() {
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
            return;
        }
        if (time - lastActivity > IDLE_MS) {
            endWindow("nobody near and no damage for " + IDLE_MS / 1000 + "s");
            return;
        }

        trackPlayers();
        trackEating(time);
        expire(time);
        if (tick % STATE_EVERY == 0) state();
    }

    private void trackEating(long time) {
        boolean food = mc.player.isUsingItem() && mc.player.getActiveItem().get(DataComponentTypes.FOOD) != null;
        if (food && eating == null) {
            eatingHand = mc.player.getActiveHand();
            ItemStack stack = held(eatingHand);
            eating = stack.getItem();
            eatingCount = stack.getCount();
            eatingSince = time;
            count("eats started");
            line("> eat start " + Describe.item(stack) + " " + hand(eatingHand));
        } else if (!food && eating != null) {
            ItemStack stack = held(eatingHand);
            int left = stack.isOf(eating) ? stack.getCount() : 0;
            boolean finished = left < eatingCount;
            count(finished ? "eats finished" : "eats cut");
            sample(finished ? "eat took ms" : "eat cut after ms", time - eatingSince);
            line("> eat " + (finished ? "finished" : "cut") + " after " + (time - eatingSince) + "ms");
            eating = null;
        }
    }

    private void expire(long time) {
        places.removeIf(place -> time - place.at() > PLACE_FORGET_MS);
        for (Iterator<CrystalPlace> it = crystalPlaces.iterator(); it.hasNext(); ) {
            CrystalPlace place = it.next();
            if (time - place.at() <= CRYSTAL_WAIT_MS) continue;
            it.remove();
            count("crystal places with no crystal");
            line("> crystal place got no crystal at " + rel(place.expected()) + " within " + CRYSTAL_WAIT_MS + "ms");
        }
        for (Iterator<Map.Entry<BlockPos, Long>> it = stops.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> stop = it.next();
            if (time - stop.getValue() <= STOP_WAIT_MS) continue;
            it.remove();
            count("stops that did not break");
            line("> stop " + relBlock(stop.getKey()) + " did not break within " + STOP_WAIT_MS + "ms, block=" + Describe.block(mc.world.getBlockState(stop.getKey())));
        }
        starts.values().removeIf(at -> time - at > 30_000);
        for (Iterator<Boom> it = booms.iterator(); it.hasNext(); ) {
            Boom boom = it.next();
            if (time - boom.at() < BOOM_SETTLE_MS) continue;
            it.remove();
            settle(boom);
        }
        if (popAt > 0 && time - popAt > TOTEM_WAIT_MS) {
            count("pops with no offhand totem after 3s");
            line("< offhand still has no totem " + TOTEM_WAIT_MS + "ms after the pop");
            popAt = 0;
        }
        prune(recentPlaces, time);
        prune(recentAttacks, time);
        prune(recentUses, time);
    }

    private static void prune(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > RECENT_MS) times.pollFirst();
    }

    private void settle(Boom boom) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, Float> entry : boom.before().entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (!(entity instanceof PlayerEntity player)) continue;
            float lost = entry.getValue() - (player.getHealth() + player.getAbsorptionAmount());
            boolean popped = boom.popped().contains(entry.getKey());
            if (lost < 0.5f && !popped) continue;
            String who = player == mc.player ? "me" : player.getName().getString();
            out.append(out.isEmpty() ? "" : ", ").append(who).append(' ').append(popped ? "POP" : "-" + Describe.one(lost));
            if (player == mc.player) {
                count("explosions that hurt me");
                if (!popped) sample("my hp per explosion", lost);
            } else {
                count("explosions that hurt " + who);
                if (!popped) sample("hp per explosion on " + who, lost);
            }
        }
        line("< boom at " + rel(boom.center()) + " result " + (out.isEmpty() ? "nobody hurt" : out));
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
        resetTracking();
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
        resetTracking();
        beforeTeleport = null;
        beforeTeleportEntity = null;
        cooldownUntil = Util.getMeasuringTimeMs() + COOLDOWN_MS;
    }

    private void resetTracking() {
        places.clear();
        crystalPlaces.clear();
        starts.clear();
        stops.clear();
        booms.clear();
        lastPos.clear();
        lastHurt.clear();
        using.clear();
        usingSince.clear();
        recentPlaces.clear();
        recentAttacks.clear();
        recentUses.clear();
        popAt = 0;
        eating = null;
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
            if (player == mc.player && last != null && total < last - 0.01f) {
                boolean recent = Util.getMeasuringTimeMs() - lastDamageAt < 250;
                stats.add("my hp lost to " + (recent ? lastDamageType : "other"), last - total);
            }
            if (player != mc.player) trackOther(player, id);
            boolean glide = player.isGliding();
            Boolean wasGliding = gliding.put(id, glide);
            if (player != mc.player && wasGliding != null && wasGliding != glide) line("E " + player.getName().getString() + (glide ? " starts gliding" : " stops gliding") + " at " + rel(player.getEntityPos()));
        }
    }

    private void trackOther(PlayerEntity player, int id) {
        String name = player.getName().getString();
        Vec3d pos = player.getEntityPos();
        Vec3d last = lastPos.put(id, pos);
        if (last != null) {
            double moved = pos.distanceTo(last);
            if (moved > (player.isGliding() ? GLIDE_JUMP : JUMP)) {
                count("jumps by " + name);
                line("E jump " + name + " " + Describe.vec(pos.subtract(last)) + " (" + Describe.num(moved) + " blocks) to " + rel(pos));
            }
        }
        String item = player.isUsingItem() ? Describe.item(player.getActiveItem()) : null;
        String before = item == null ? using.remove(id) : using.put(id, item);
        long now = Util.getMeasuringTimeMs();
        if (item != null && before == null) {
            usingSince.put(id, now);
            line("E " + name + " starts using " + item);
        } else if (item == null && before != null) {
            Long since = usingSince.remove(id);
            long took = since == null ? -1 : now - since;
            line("E " + name + " stops using " + before + (took >= 0 ? " after " + took + "ms" : ""));
            if (took >= 0 && before.startsWith("enchanted_golden_apple")) sample("gapple use by " + name + " ms", took);
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
        if (ping >= 0) sample("ping ms", ping);
        sample("tps", tps);
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
                if (move.changesPosition() && new Vec3d(move.getX(0), move.getY(0), move.getZ(0)).distanceTo(anchor) > MAX_SPAN) {
                    endWindow("moved or was moved " + (int) MAX_SPAN + " blocks from the start");
                    return;
                }
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
                recentPlaces.addLast(Util.getMeasuringTimeMs());
                if (anchor == null) return;
                BlockPos pos = use.getBlockHitResult().getBlockPos();
                expectPlacement(pos, use.getBlockHitResult().getSide(), held, use.getSequence());
                Vec3d hit = use.getBlockHitResult().getPos().subtract(pos.getX(), pos.getY(), pos.getZ());
                line("> use-block " + relBlock(pos) + " face=" + use.getBlockHitResult().getSide().asString() + " hit=" + Describe.num(hit.x) + "," + Describe.num(hit.y) + "," + Describe.num(hit.z)
                    + (use.getBlockHitResult().isInsideBlock() ? " inside" : "") + " " + hand(use.getHand()) + " item=" + Describe.item(held) + " seq=" + use.getSequence()
                    + " on=" + Describe.block(mc.world.getBlockState(pos)));
            }
            case PlayerInteractItemC2SPacket use -> {
                count("items used");
                recentUses.addLast(Util.getMeasuringTimeMs());
                if (held(use.getHand()).isOf(Items.ENDER_PEARL) || held(use.getHand()).isOf(Items.CHORUS_FRUIT)) lastOwnPearl = Util.getMeasuringTimeMs();
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
                if (kind[0].equals("attack")) recentAttacks.addLast(Util.getMeasuringTimeMs());
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
                    long now = Util.getMeasuringTimeMs();
                    BlockPos mined = action.getPos().toImmutable();
                    String timing = "";
                    if (action.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                        starts.put(mined, now);
                    } else if (action.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
                        Long started = starts.get(mined);
                        if (started != null) {
                            timing = " after=" + (now - started) + "ms";
                            sample("start to stop ms", now - started);
                        }
                        if (!mc.world.getBlockState(mined).isAir()) stops.put(mined, now);
                    } else {
                        starts.remove(mined);
                    }
                    line("> " + name + " " + relBlock(action.getPos()) + " face=" + action.getDirection().asString() + " block=" + Describe.block(mc.world.getBlockState(action.getPos()))
                        + " main=" + Describe.item(mc.player.getMainHandStack()) + " seq=" + action.getSequence() + timing);
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

    private void expectPlacement(BlockPos hit, Direction side, ItemStack held, int sequence) {
        long now = Util.getMeasuringTimeMs();
        if (held.isOf(Items.END_CRYSTAL)) {
            crystalPlaces.add(new CrystalPlace(new Vec3d(hit.getX() + 0.5, hit.getY() + 1, hit.getZ() + 0.5), now));
            return;
        }
        if (!(held.getItem() instanceof BlockItem blockItem)) return;
        BlockState clicked = mc.world.getBlockState(hit);
        if (clicked.getBlock() instanceof BlockWithEntity || clicked.isOf(Blocks.RESPAWN_ANCHOR)) return;
        Block block = blockItem.getBlock();
        BlockPos offset = hit.offset(side).toImmutable();
        BlockState next = mc.world.getBlockState(offset);
        BlockPos target;
        BlockPos alternative = null;
        if (next.isOf(block)) {
            target = offset;
        } else if (clicked.isReplaceable()) {
            target = hit.toImmutable();
        } else if (clicked.isOf(block) && next.isReplaceable()) {
            target = offset;
            alternative = hit.toImmutable();
        } else {
            target = offset;
        }
        places.add(new SentPlace(sequence, target, alternative, Describe.item(held), now));
    }

    private void answered(int acked) {
        long now = Util.getMeasuringTimeMs();
        for (Iterator<SentPlace> it = places.iterator(); it.hasNext(); ) {
            SentPlace place = it.next();
            if (place.sequence() > acked) continue;
            it.remove();
            boolean placed = !mc.world.getBlockState(place.target()).isReplaceable()
                || place.alternative() != null && !mc.world.getBlockState(place.alternative()).isReplaceable();
            long took = now - place.at();
            if (placed) {
                count("blocks accepted");
                sample("block answer ms", took);
            } else {
                count("blocks refused");
                line("< place REFUSED " + relBlock(place.target()) + " " + place.item() + " seq=" + place.sequence() + " after " + took + "ms");
            }
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
            case PlayerPositionLookS2CPacket ignored -> {
                beforeTeleport = mc.player.getEntityPos();
                beforeTeleportEntity = mc.player;
            }
            case net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket ignored -> {
                if (anchor != null) endWindow("respawned");
                beforeTeleport = null;
                beforeTeleportEntity = null;
            }
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
                boolean known = beforeTeleport != null && beforeTeleportEntity == mc.player;
                Vec3d moved = known ? mc.player.getEntityPos().subtract(beforeTeleport) : null;
                beforeTeleport = null;
                beforeTeleportEntity = null;
                if (anchor == null) {
                    line("< teleport id=" + teleport.teleportId());
                } else if (moved == null || moved.length() > MAX_SPAN || mc.player.getEntityPos().distanceTo(anchor) > MAX_SPAN) {
                    line("< teleport id=" + teleport.teleportId() + " far");
                    endWindow("teleported far");
                } else {
                    long now = Util.getMeasuringTimeMs();
                    lastActivity = now;
                    line("< teleport id=" + teleport.teleportId() + " moved " + Describe.vec(moved) + " (" + Describe.num(moved.length()) + " blocks)");
                    if (moved.length() <= SETBACK_MAX && now - lastOwnPearl > PEARL_GRACE_MS) setback(now);
                }
            }
            case PlayerActionResponseS2CPacket ack -> {
                if (anchor != null) line("< ack seq=" + ack.sequence());
                answered(ack.sequence());
            }
            case BlockUpdateS2CPacket update -> blockChanged(update.getPos(), update.getState());
            case ChunkDeltaUpdateS2CPacket delta -> delta.visitUpdates(this::blockChanged);
            case BlockBreakingProgressS2CPacket progress -> {
                if (anchor == null || !near(progress.getPos())) return;
                Entity breaker = mc.world.getEntityById(progress.getEntityId());
                line("< breaking " + relBlock(progress.getPos()) + " " + Describe.block(mc.world.getBlockState(progress.getPos())) + " stage=" + progress.getProgress() + " by " + Describe.name(breaker));
            }
            case EntitySpawnS2CPacket spawn -> {
                if (anchor != null && spawn.getEntityType() == EntityType.ENDER_PEARL) {
                    pearlThrown(spawn);
                    return;
                }
                if (anchor == null || spawn.getEntityType() != EntityType.END_CRYSTAL) return;
                Vec3d pos = new Vec3d(spawn.getX(), spawn.getY(), spawn.getZ());
                if (pos.distanceTo(mc.player.getEntityPos()) > TRACK_RANGE) return;
                crystals.put(spawn.getEntityId(), Util.getMeasuringTimeMs());
                crystalAppeared(pos);
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
                Map<Integer, Float> snapshot = new HashMap<>();
                for (PlayerEntity player : mc.world.getPlayers()) {
                    if (player.getEntityPos().distanceTo(explosion.center()) <= BOOM_RANGE) snapshot.put(player.getId(), health.getOrDefault(player.getId(), player.getHealth() + player.getAbsorptionAmount()));
                }
                if (!snapshot.isEmpty()) booms.add(new Boom(explosion.center(), Util.getMeasuringTimeMs(), snapshot, new HashSet<>()));
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
                if (slot.getSlot() == OFFHAND_SLOT) offhandChanged(slot.getStack());
                line("< slot " + slot.getSlot() + " " + Describe.item(slot.getStack()) + " rev=" + slot.getRevision());
            }
            case InventoryS2CPacket inventory -> {
                if (inventory.syncId() == 0) {
                    line("< inventory resync rev=" + inventory.revision());
                    if (inventory.contents().size() > OFFHAND_SLOT) offhandChanged(inventory.contents().get(OFFHAND_SLOT));
                }
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

    private void setback(long now) {
        count("setbacks");
        boolean inside = mc.world.getBlockCollisions(mc.player, mc.player.getBoundingBox().contract(0.05)).iterator().hasNext();
        line("< SETBACK since-last=" + (lastSetback == 0 ? "-" : (now - lastSetback) + "ms") + " last-1s places=" + recentPlaces.size() + " attacks=" + recentAttacks.size()
            + " uses=" + recentUses.size() + (inside ? " inside-block" : "") + (mc.player.isOnGround() ? " ground" : " air") + (mc.player.isGliding() ? " glide" : "")
            + (mc.player.hasVehicle() ? " vehicle" : ""));
        if (lastSetback > 0) sample("between setbacks ms", now - lastSetback);
        lastSetback = now;
    }

    private void crystalAppeared(Vec3d pos) {
        long now = Util.getMeasuringTimeMs();
        for (Iterator<CrystalPlace> it = crystalPlaces.iterator(); it.hasNext(); ) {
            CrystalPlace place = it.next();
            Vec3d expected = place.expected();
            double dx = expected.x - pos.x;
            double dz = expected.z - pos.z;
            if (dx * dx + dz * dz > 0.36 || Math.abs(expected.y - pos.y) > 0.6) continue;
            it.remove();
            count("crystal places that appeared");
            sample("crystal appear ms", now - place.at());
            return;
        }
    }

    private void pearlThrown(EntitySpawnS2CPacket spawn) {
        Entity owner = mc.world.getEntityById(spawn.getEntityData());
        if (owner == mc.player) return;
        Vec3d pos = new Vec3d(spawn.getX(), spawn.getY(), spawn.getZ());
        if (pos.distanceTo(mc.player.getEntityPos()) > TRACK_RANGE) return;
        String name = owner instanceof PlayerEntity ? Describe.name(owner) : "?";
        count("pearls by " + name);
        line("E pearl thrown by " + name + " at " + rel(pos) + " d=" + Describe.num(pos.distanceTo(mc.player.getEntityPos())));
    }

    private void offhandChanged(ItemStack stack) {
        if (popAt <= 0) return;
        long took = Util.getMeasuringTimeMs() - popAt;
        if (stack.isOf(Items.TOTEM_OF_UNDYING)) {
            sample("totem refill ms", took);
            line("< offhand totem back " + took + "ms after the pop");
            popAt = 0;
        } else {
            line("< offhand " + Describe.item(stack) + " " + took + "ms after the pop");
        }
    }

    private void onDamage(EntityDamageS2CPacket damage) {
        Entity victim = mc.world.getEntityById(damage.entityId());
        if (!(victim instanceof PlayerEntity)) return;
        Entity cause = mc.world.getEntityById(damage.sourceCauseId());
        Entity direct = mc.world.getEntityById(damage.sourceDirectId());
        String type = Describe.damage(damage.sourceType());
        long now = Util.getMeasuringTimeMs();
        if (victim == mc.player) {
            lastActivity = now;
            lastDamageType = type;
            lastDamageAt = now;
            if (lastSelfHurt > 0 && now - lastSelfHurt < 3000) sample("my hurt gaps ms", now - lastSelfHurt);
            lastSelfHurt = now;
            count("damage taken " + type);
            line("< damage " + type + " by " + (cause == null ? "-" : Describe.name(cause)) + (direct != null && direct != cause ? " via " + Describe.name(direct) : ""));
            return;
        }
        if (anchor == null || victim.distanceTo(mc.player) > TRACK_RANGE) return;
        Long previous = lastHurt.put(victim.getId(), now);
        if (previous != null && now - previous < 3000) sample("enemy hurt gaps ms", now - previous);
        line("E hurt " + Describe.name(victim) + " " + type + " by " + (cause == null ? "-" : cause == mc.player ? "me" : Describe.name(cause)));
        if (cause == mc.player) count("hits on players " + type);
    }

    private void onStatus(EntityStatusS2CPacket status) {
        Entity entity = status.getEntity(mc.world);
        if (!(entity instanceof PlayerEntity player)) return;
        if (status.getStatus() == POP) {
            for (Boom boom : booms) boom.popped().add(player.getId());
            if (player == mc.player) {
                lastActivity = Util.getMeasuringTimeMs();
                popAt = lastActivity;
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
        Long stopped = stops.get(pos);
        if (stopped != null && state.isAir()) {
            stops.remove(pos);
            long took = Util.getMeasuringTimeMs() - stopped;
            count("stops that broke");
            sample("break after stop ms", took);
            line("< broke " + relBlock(pos) + " " + took + "ms after the stop");
        }
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
        if (anchor == null) return "-";
        Vec3d offset = pos.subtract(anchor);
        return offset.length() > MAX_REL ? "far" : Describe.vec(offset);
    }

    private String relBlock(BlockPos pos) {
        if (anchor == null) return "-";
        BlockPos origin = BlockPos.ofFloored(anchor);
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (Math.sqrt((double) x * x + (double) y * y + (double) z * z) > MAX_REL) return "far";
        return x + "," + y + "," + z;
    }

    private void count(String key) {
        if (stats != null) stats.add(key, 1);
    }

    private void sample(String key, double value) {
        if (stats != null) stats.sample(key, value);
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
        private final Map<String, List<Double>> samples = new TreeMap<>();

        void sample(String key, double value) {
            samples.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

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
                String part = entry.getKey() + ": " + format(entry.getKey(), entry.getValue());
                if (line.length() + part.length() > 180) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(", ");
                line.append(part);
            }
            if (!line.isEmpty()) out.add(line.toString());
            for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
                List<Double> values = new ArrayList<>(entry.getValue());
                values.sort(Double::compare);
                int n = values.size();
                String key = entry.getKey();
                out.add(key + ": count " + format("", n) + ", min " + format(key, values.getFirst()) + ", median " + format(key, values.get(n / 2))
                    + ", p90 " + format(key, values.get(Math.min(n - 1, n * 9 / 10))) + ", max " + format(key, values.getLast()));
            }
            if (!maxima.isEmpty()) {
                StringBuilder peaks = new StringBuilder();
                maxima.forEach((key, value) -> {
                    if (!peaks.isEmpty()) peaks.append(", ");
                    peaks.append(key).append(": ").append(format(key, value));
                });
                out.add(peaks.toString());
            }
            return out;
        }

        static String format(String key, double value) {
            if (key.endsWith(" ms")) return Math.round(value) + "ms";
            if (Math.abs(value) >= 1000) return Describe.one(value / 1000) + "k";
            return value == Math.rint(value) ? String.valueOf((long) value) : Describe.one(value);
        }
    }
}
