package dev.coyduck.utils;

import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.modules.AutoCrystal;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class Targets {
    private static final double EDGE = 1.0E-4;
    private static final int MAX_DROP = 3;

    private Targets() {}

    public static PlayerEntity find(double range, PlayerEntity keep) {
        return valid(keep, range) ? keep : find(range);
    }

    public static PlayerEntity find(double range) {
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        PlayerEntity preferred = autoCrystal == null ? null : autoCrystal.target();
        if (valid(preferred, range)) return preferred;

        PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!valid(player, range)) continue;

            double distance = player.squaredDistanceTo(mc.player);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static boolean valid(PlayerEntity player, double range) {
        return player != null && player != mc.player && player.isAlive() && !player.isRemoved()
            && !Friends.get().isFriend(player) && !ExplosionDamage.isInvulnerable(player)
            && player.squaredDistanceTo(mc.player) <= range * range;
    }

    public static Box box(PlayerEntity player) {
        Vec3d position = MotionTracker.get().position(player);
        return player.getDimensions(player.getPose()).getBoxAt(position);
    }

    public static Vec3d feet(Box box) {
        return new Vec3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
    }

    public static int level(Box box) {
        int top = MathHelper.floor(box.minY + 0.4);
        int lowest = MathHelper.floor(box.minY) - MAX_DROP;

        for (int level = top; level > lowest; level--) {
            if (supported(box, level)) return level;
        }
        return top;
    }

    private static boolean supported(Box box, int level) {
        BlockPos.Mutable below = new BlockPos.Mutable();
        for (int x = MathHelper.floor(box.minX + EDGE); x <= MathHelper.floor(box.maxX - EDGE); x++) {
            for (int z = MathHelper.floor(box.minZ + EDGE); z <= MathHelper.floor(box.maxZ - EDGE); z++) {
                below.set(x, level - 1, z);
                if (!mc.world.getBlockState(below).getCollisionShape(mc.world, below).isEmpty()) return true;
            }
        }
        return false;
    }

    public static boolean isBurrowedIn(Box box, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) return false;

        Box inner = box.contract(1.0E-3);
        for (Box part : shape.offset(pos.getX(), pos.getY(), pos.getZ()).getBoundingBoxes()) {
            if (part.intersects(inner)) return true;
        }
        return false;
    }

    public static LongSet columns(Box box) {
        int y = level(box);
        LongSet columns = new LongOpenHashSet();

        for (int x = MathHelper.floor(box.minX + EDGE); x <= MathHelper.floor(box.maxX - EDGE); x++) {
            for (int z = MathHelper.floor(box.minZ + EDGE); z <= MathHelper.floor(box.maxZ - EDGE); z++) {
                columns.add(BlockPos.asLong(x, y, z));
            }
        }

        return columns;
    }

    public static List<BlockPos> ring(LongSet columns, int dy) {
        List<BlockPos> ring = new ArrayList<>();
        LongSet seen = new LongOpenHashSet();

        for (long key : columns) {
            BlockPos column = BlockPos.fromLong(key);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (columns.contains(side.asLong())) continue;

                BlockPos pos = side.up(dy);
                if (seen.add(pos.asLong())) ring.add(pos);
            }
        }

        return ring;
    }

    public static boolean isHoled(LongSet columns) {
        for (BlockPos pos : ring(columns, 0)) {
            if (mc.world.getBlockState(pos).isReplaceable()) return false;
        }
        return true;
    }

    public static boolean isStill(PlayerEntity player, double speed) {
        Vec3d velocity = MotionTracker.get().velocity(player);
        return velocity.horizontalLengthSquared() <= speed * speed;
    }

    public static List<BlockPos> ownRing() {
        LongSet columns = columns(mc.player.getBoundingBox());
        List<BlockPos> cells = ring(columns, 0);
        for (long key : columns) cells.add(BlockPos.fromLong(key).down());
        return cells;
    }

    public static boolean isBase(BlockState state) {
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    public static boolean canHoldCrystal(BlockView view, BlockPos base) {
        if (!isBase(view.getBlockState(base)) || !view.getBlockState(base.up()).isAir()) return false;

        BlockPos above = base.up();
        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        CrystalTracker tracker = CrystalTracker.get();
        return mc.world.getOtherEntities(null, box, entity -> !(entity instanceof EndCrystalEntity) || tracker.isAlive(entity)).isEmpty();
    }

    public static Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    public static boolean safe(Vec3d crystal, BlockView view, double maxSelfDamage) {
        float self = ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, view);
        if (self >= mc.player.getHealth() + mc.player.getAbsorptionAmount() || self > maxSelfDamage) return false;

        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Friends.get().isFriend(player) || player.squaredDistanceTo(crystal) > range) continue;
            float damage = ExplosionDamage.crystal(player, player.getEntityPos(), player.getBoundingBox(), crystal, view);
            if (damage >= player.getHealth() + player.getAbsorptionAmount() || damage > maxSelfDamage) return false;
        }
        return true;
    }

    public static boolean isMinable(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isReplaceable() && state.getHardness(mc.world, pos) >= 0;
    }
}
