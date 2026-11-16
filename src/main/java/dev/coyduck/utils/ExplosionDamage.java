package dev.coyduck.utils;

import dev.coyduck.logic.Blast;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.RotationManager;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import static dev.coyduck.core.Core.mc;

public final class ExplosionDamage {
    public static final float CRYSTAL_POWER = 6.0f;
    public static final double CRYSTAL_RANGE = CRYSTAL_POWER * 2.0;

    private ExplosionDamage() {}

    public static boolean isWind(ExplosionS2CPacket packet) {
        ParticleType<?> type = packet.explosionParticle().getType();
        return type == ParticleTypes.GUST_EMITTER_SMALL || type == ParticleTypes.GUST_EMITTER_LARGE;
    }

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

        double distance = Blast.distance(position, center, power);
        if (distance > 1.0) return 0;

        float damage = Blast.damage(distance, Blast.exposure(center, box, view, ShapeContext.of(target)), power);
        return target instanceof PlayerEntity ? Blast.difficulty(damage, target.getEntityWorld().getDifficulty()) : damage;
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

        float yaw = target == mc.player ? RotationManager.get().serverYaw() : target.getHeadYaw();
        Vec3d look = Vec3d.fromPolar(0.0f, yaw);
        Vec3d offset = center.subtract(position);
        double angle = Math.acos(new Vec3d(offset.x, 0.0, offset.z).normalize().dotProduct(look));
        return damage - component.getDamageReductionAmount(source, damage, angle);
    }

    public static float upperBound(LivingEntity target, Vec3d position, Vec3d crystal) {
        return upperBound(target, position, crystal, CRYSTAL_POWER);
    }

    public static float upperBound(LivingEntity target, Vec3d position, Vec3d center, float power) {
        return mitigate(target, rawBound(target, position, center, power));
    }

    public static float rawBound(LivingEntity target, Vec3d position, Vec3d center, float power) {
        if (isInvulnerable(target)) return 0;

        double distance = Blast.distance(position, center, power);
        if (distance > 1.0) return 0;

        float damage = Blast.damage(distance, 1.0, power);
        if (target instanceof PlayerEntity) damage = Blast.difficulty(damage, target.getEntityWorld().getDifficulty());
        return shield(target, position, center, damage);
    }

    public static float mitigate(LivingEntity target, float damage) {
        return Blast.Defense.of(target).reduce(damage);
    }

    public static boolean isInvulnerable(LivingEntity target) {
        if (!(target instanceof PlayerEntity player)) return false;
        if (player == mc.player) return player.getAbilities().invulnerable;
        if (mc.getNetworkHandler() == null) return false;

        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null && !entry.getGameMode().isSurvivalLike();
    }
}
