package dev.coyduck.logic;

import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;

public final class Blast {
    private Blast() {}

    public static double distance(Vec3d position, Vec3d center, float power) {
        return Math.sqrt(position.squaredDistanceTo(center)) / (power * 2.0f);
    }

    public static float damage(double distance, double exposure, float power) {
        double impact = (1.0 - distance) * exposure;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * (power * 2.0f) + 1.0);
    }

    public static float exposure(Vec3d source, Box box, BlockView view, ShapeContext context) {
        double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        if (stepX < 0 || stepY < 0 || stepZ < 0) return 0;
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;

        BlockPos origin = BlockPos.ofFloored(source);
        if (!view.getBlockState(origin).isAir()) view = new BlockOverlay(view).set(origin, Blocks.AIR.getDefaultState());

        int visible = 0;
        int total = 0;
        for (double x = 0; x <= 1; x += stepX) {
            for (double y = 0; y <= 1; y += stepY) {
                for (double z = 0; z <= 1; z += stepZ) {
                    Vec3d point = new Vec3d(
                        MathHelper.lerp(x, box.minX, box.maxX) + offsetX,
                        MathHelper.lerp(y, box.minY, box.maxY),
                        MathHelper.lerp(z, box.minZ, box.maxZ) + offsetZ
                    );
                    RaycastContext ray = new RaycastContext(point, source, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, context);
                    if (view.raycast(ray).getType() == HitResult.Type.MISS) visible++;
                    total++;
                }
            }
        }
        return (float) visible / (float) total;
    }

    public static float difficulty(float damage, Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
            case NORMAL -> damage;
            case HARD -> damage * 3.0f / 2.0f;
        };
    }

    public static float armor(float damage, float armor, float toughness) {
        float strength = 2.0f + toughness / 4.0f;
        float effective = MathHelper.clamp(armor - damage / strength, armor * 0.2f, 20.0f);
        return damage * (1.0f - effective / 25.0f);
    }

    public static float resistance(float damage, int amplifier) {
        int reduction = (amplifier + 1) * 5;
        return Math.max(damage * (25 - reduction) / 25.0f, 0.0f);
    }

    public static float protection(float damage, int points) {
        return points > 0 ? DamageUtil.getInflictedDamage(damage, points) : damage;
    }

    public static int protectionPoints(LivingEntity target) {
        int total = 0;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemEnchantmentsComponent enchantments = target.getEquippedStack(slot).getEnchantments();
            for (var entry : enchantments.getEnchantmentEntries()) {
                RegistryEntry<Enchantment> enchantment = entry.getKey();
                if (enchantment.matchesKey(Enchantments.PROTECTION)) total += entry.getIntValue();
                else if (enchantment.matchesKey(Enchantments.BLAST_PROTECTION)) total += 2 * entry.getIntValue();
            }
        }
        return total;
    }
}
