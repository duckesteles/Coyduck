package dev.coyduck.utils;

import dev.coyduck.managers.LatencyManager;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class ExplosionDamage {
    public static final float CRYSTAL_POWER = 6.0f;
    public static final double CRYSTAL_RANGE = CRYSTAL_POWER * 2.0;

    private ExplosionDamage() {}

    public static float crystal(LivingEntity target, Vec3d crystal) {
        return explosion(target, target.getEntityPos(), target.getBoundingBox(), crystal, CRYSTAL_POWER, mc.world);
    }

    public static float crystal(LivingEntity target, Vec3d position, Box box, Vec3d crystal, BlockView view) {
        return explosion(target, position, box, crystal, CRYSTAL_POWER, view);
    }

    public static float explosion(LivingEntity target, Vec3d position, Box box, Vec3d center, float power, BlockView view) {
        return mitigate(target, raw(target, position, box, center, power, view));
    }

    public static float crystalRaw(LivingEntity target, Vec3d position, Box box, Vec3d crystal, BlockView view) {
        return raw(target, position, box, crystal, CRYSTAL_POWER, view);
    }

    public static float raw(LivingEntity target, Vec3d position, Box box, Vec3d center, float power, BlockView view) {
        return shield(target, position, center, rawUnshielded(target, position, box, center, power, view));
    }

    public static float rawUnshielded(LivingEntity target, Vec3d position, Box box, Vec3d center, float power, BlockView view) {
        if (isInvulnerable(target)) return 0;

        float radius = power * 2.0f;
        double distance = Math.sqrt(position.squaredDistanceTo(center)) / radius;
        if (distance > 1.0) return 0;

        double impact = (1.0 - distance) * exposure(center, box, view, ShapeContext.of(target));
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);
        return target instanceof PlayerEntity ? scale(damage, target.getEntityWorld().getDifficulty()) : damage;
    }

    public static float shield(LivingEntity target, Vec3d position, Vec3d center, float damage) {
        if (damage <= 0) return 0;

        ItemStack blocking = target.getBlockingItem();
        if (blocking == null) return damage;

        BlocksAttacksComponent component = blocking.get(DataComponentTypes.BLOCKS_ATTACKS);
        if (component == null) return damage;
        if (target == mc.player && target.getItemUseTime() < component.getBlockDelayTicks() + MathHelper.ceil(LatencyManager.get().rtt() / 2.0 / 50.0)) return damage;

        DamageSource source = target.getEntityWorld().getDamageSources().explosion(null, null);
        if (component.bypassedBy().map(source::isIn).orElse(false)) return damage;

        Vec3d look = Vec3d.fromPolar(0.0f, target.getHeadYaw());
        Vec3d offset = center.subtract(position);
        double angle = Math.acos(new Vec3d(offset.x, 0.0, offset.z).normalize().dotProduct(look));
        return damage - component.getDamageReductionAmount(source, damage, angle);
    }

    public static float upperBound(LivingEntity target, Vec3d position, Vec3d crystal) {
        if (isInvulnerable(target)) return 0;

        double distance = Math.sqrt(position.squaredDistanceTo(crystal)) / CRYSTAL_RANGE;
        if (distance > 1.0) return 0;

        double impact = 1.0 - distance;
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * CRYSTAL_RANGE + 1.0);
        if (target instanceof PlayerEntity) damage = scale(damage, target.getEntityWorld().getDifficulty());
        return mitigate(target, shield(target, position, crystal, damage));
    }

    public static float mitigate(LivingEntity target, float damage) {
        if (damage <= 0) return 0;

        damage = armor(damage, target.getArmor(), (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));

        StatusEffectInstance resistance = target.getStatusEffect(StatusEffects.RESISTANCE);
        if (resistance != null) {
            int reduction = (resistance.getAmplifier() + 1) * 5;
            damage = Math.max(damage * (25 - reduction) / 25.0f, 0.0f);
            if (damage <= 0) return 0;
        }

        int protection = protection(target);
        return protection > 0 ? DamageUtil.getInflictedDamage(damage, protection) : damage;
    }

    public static float exposure(Vec3d source, Box box, BlockView view, ShapeContext context) {
        double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        if (stepX < 0 || stepY < 0 || stepZ < 0) return 0;

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

    public static boolean isInvulnerable(LivingEntity target) {
        if (!(target instanceof PlayerEntity player)) return false;
        if (player == mc.player) return player.getAbilities().invulnerable;
        if (mc.getNetworkHandler() == null) return false;

        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null && !entry.getGameMode().isSurvivalLike();
    }

    private static float scale(float damage, Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
            case NORMAL -> damage;
            case HARD -> damage * 3.0f / 2.0f;
        };
    }

    private static float armor(float damage, float armor, float toughness) {
        float strength = 2.0f + toughness / 4.0f;
        float effective = MathHelper.clamp(armor - damage / strength, armor * 0.2f, 20.0f);
        return damage * (1.0f - effective / 25.0f);
    }

    private static int protection(LivingEntity target) {
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
