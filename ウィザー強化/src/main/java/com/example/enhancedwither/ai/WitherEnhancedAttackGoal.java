package com.example.enhancedwither.ai;

import com.example.enhancedwither.WitherEventHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

public class WitherEnhancedAttackGoal extends Goal {

    private final WitherBoss wither;
    private final Random random = new Random();

    // Attack state
    private AttackType currentAttack = null;
    private int attackTicksRemaining = 0;
    private int cooldownTicks = 0;
    private boolean useBlueSkull = false;

    // Timing constants
    private static final int BARRAGE_DURATION_TICKS = 200; // 10 seconds
    private static final int MIN_COOLDOWN_TICKS = 40;  // 2 seconds
    private static final int MAX_COOLDOWN_TICKS = 60;  // 3 seconds
    private static final double BLUE_SKULL_CHANCE = 0.05; // 5%

    // Machine gun fire rate (ticks between shots)
    private static final int MACHINE_GUN_INTERVAL = 2; // every 2 ticks = 10 per second
    // Cone spread shot interval (same as machine gun for continuous fire)
    private static final int CONE_SHOT_INTERVAL = 2; // every 2 ticks, machine-gun style
    // Omni directional interval
    private static final int OMNI_SHOT_INTERVAL = 2; // every 2 ticks

    private enum AttackType {
        MACHINE_GUN,      // Rapid fire at target for 10 seconds
        SPREAD_SHOT,      // 45-degree cone spread, machine-gun style for 10 seconds
        OMNI_DIRECTIONAL, // 360-degree spherical barrage for 10 seconds
        EXPLOSIVE_SKULL,  // Single explosive skull (power 10) - uses tag
        LIGHTNING,        // Strike lightning at target
        DASH_ATTACK,      // Charge toward target
        WITHER_POTION     // Fire a Wither V lingering potion at target
    }

    public WitherEnhancedAttackGoal(WitherBoss wither) {
        this.wither = wither;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Can use when the wither is alive, has a target, and not in invulnerability phase
        return wither.isAlive() && wither.getInvulnerableTicks() <= 0
                && getTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldownTicks = MIN_COOLDOWN_TICKS; // Initial cooldown before first attack
    }

    @Override
    public void tick() {
        LivingEntity target = getTarget();
        if (target == null) return;

        if (currentAttack != null && attackTicksRemaining > 0) {
            // Currently executing an attack
            executeAttackTick(target);
            attackTicksRemaining--;

            if (attackTicksRemaining <= 0) {
                // Attack finished, start cooldown
                currentAttack = null;
                cooldownTicks = MIN_COOLDOWN_TICKS + random.nextInt(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS);
            }
        } else if (cooldownTicks > 0) {
            // Cooling down
            cooldownTicks--;
        } else {
            // Pick a new attack
            startNewAttack();
        }
    }

    /**
     * Finds the nearest living entity (not just players) that the wither is targeting.
     * Uses the wither's own target if available, otherwise finds the nearest living entity.
     */
    private LivingEntity getTarget() {
        // First, check if the wither already has a target (this targets any LivingEntity, not just players)
        LivingEntity witherTarget = wither.getTarget();
        if (witherTarget != null && witherTarget.isAlive()) {
            return witherTarget;
        }

        // Fallback: find nearest living entity within 100 blocks
        Level level = wither.level();
        List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class,
                wither.getBoundingBox().inflate(100.0),
                e -> e != wither && e.isAlive() && !(e instanceof WitherBoss));
        if (entities.isEmpty()) return null;

        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (LivingEntity e : entities) {
            double dist = wither.distanceToSqr(e);
            if (dist < closestDist) {
                closestDist = dist;
                closest = e;
            }
        }
        return closest;
    }

    private void startNewAttack() {
        AttackType[] types = AttackType.values();
        currentAttack = types[random.nextInt(types.length)];

        // Determine if blue skull for this attack
        useBlueSkull = random.nextDouble() < BLUE_SKULL_CHANCE;

        // Set duration based on attack type
        switch (currentAttack) {
            case MACHINE_GUN:
            case SPREAD_SHOT:
            case OMNI_DIRECTIONAL:
                attackTicksRemaining = BARRAGE_DURATION_TICKS; // 10 seconds
                break;
            case EXPLOSIVE_SKULL:
            case LIGHTNING:
            case DASH_ATTACK:
            case WITHER_POTION:
                attackTicksRemaining = 1; // Instant attacks (execute once)
                break;
        }
    }

    private void executeAttackTick(LivingEntity target) {
        switch (currentAttack) {
            case MACHINE_GUN:
                tickMachineGun(target);
                break;
            case SPREAD_SHOT:
                tickConeSpread(target);
                break;
            case OMNI_DIRECTIONAL:
                tickSphericalBarrage(target);
                break;
            case EXPLOSIVE_SKULL:
                fireExplosiveSkull(target);
                break;
            case LIGHTNING:
                strikeLightning(target);
                break;
            case DASH_ATTACK:
                dashAttack(target);
                break;
            case WITHER_POTION:
                fireWitherPotion(target);
                break;
        }
    }

    // ========== MACHINE GUN ==========
    private void tickMachineGun(LivingEntity target) {
        int elapsed = BARRAGE_DURATION_TICKS - attackTicksRemaining;
        if (elapsed % MACHINE_GUN_INTERVAL == 0) {
            shootSkullAt(target, useBlueSkull);
        }
    }

    // ========== 45-DEGREE 3D CONE SPREAD (Machine-gun style) ==========
    private void tickConeSpread(LivingEntity target) {
        int elapsed = BARRAGE_DURATION_TICKS - attackTicksRemaining;
        if (elapsed % CONE_SHOT_INTERVAL == 0) {
            // Direction to target
            Vec3 dirToTarget = target.position().subtract(wither.position()).normalize();

            // Generate a random direction within a 45-degree cone (half-angle = 22.5 degrees)
            double halfAngle = Math.toRadians(22.5);

            // First, find perpendicular vectors to the target direction
            Vec3 up = new Vec3(0, 1, 0);
            Vec3 right = dirToTarget.cross(up).normalize();
            if (right.lengthSqr() < 0.001) {
                // Target direction is nearly vertical, use a different up vector
                right = dirToTarget.cross(new Vec3(1, 0, 0)).normalize();
            }
            Vec3 perpUp = right.cross(dirToTarget).normalize();

            // Fire 15 skulls randomly distributed within the cone every 2 ticks
            for (int i = 0; i < 15; i++) {
                double theta = random.nextDouble() * halfAngle;
                double phi = random.nextDouble() * Math.PI * 2.0;

                double sinTheta = Math.sin(theta);
                double cosTheta = Math.cos(theta);
                double cosPhi = Math.cos(phi);
                double sinPhi = Math.sin(phi);

                Vec3 direction = dirToTarget.scale(cosTheta)
                        .add(right.scale(sinTheta * cosPhi))
                        .add(perpUp.scale(sinTheta * sinPhi));

                shootSkullInDirection(direction.x, direction.y, direction.z, useBlueSkull);
            }
        }
    }

    // ========== 360-DEGREE SPHERICAL BARRAGE ==========
    private void tickSphericalBarrage(LivingEntity target) {
        int elapsed = BARRAGE_DURATION_TICKS - attackTicksRemaining;
        if (elapsed % OMNI_SHOT_INTERVAL == 0) {
            // Fire skulls distributed over a sphere using fibonacci sphere distribution
            int numSkulls = 50; // 50 skulls every 2 ticks = 250 skulls/second
            double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0)); // ~2.3999 radians
            double offset = elapsed * 0.1; // Rotate the sphere pattern each wave

            for (int i = 0; i < numSkulls; i++) {
                // Fibonacci sphere: evenly distribute points on a sphere
                double y = 1.0 - (2.0 * i / (numSkulls - 1)); // -1 to 1 (bottom to top)
                double radiusAtY = Math.sqrt(1.0 - y * y);
                double angle = goldenAngle * i + offset;

                double dx = Math.cos(angle) * radiusAtY;
                double dz = Math.sin(angle) * radiusAtY;
                double dy = y;

                shootSkullInDirection(dx, dy, dz, useBlueSkull);
            }
        }
    }

    // ========== EXPLOSIVE SKULL (Power 10) - Tag-based ==========
    private void fireExplosiveSkull(LivingEntity target) {
        if (!(wither.level() instanceof ServerLevel serverLevel)) return;

        double dx = target.getX() - wither.getX();
        double dy = target.getY(0.5) - wither.getY(0.5);
        double dz = target.getZ() - wither.getZ();

        WitherSkull skull = new WitherSkull(serverLevel, wither, dx, dy, dz);
        skull.setPos(wither.getX(), wither.getY() + 3.0, wither.getZ());
        skull.setDangerous(useBlueSkull);
        // Tag the skull so the event handler knows to create a power-10 explosion on impact
        skull.addTag(WitherEventHandler.EXPLOSIVE_SKULL_TAG);
        serverLevel.addFreshEntity(skull);
    }

    // ========== LIGHTNING STRIKE ==========
    private void strikeLightning(LivingEntity target) {
        if (!(wither.level() instanceof ServerLevel serverLevel)) return;

        // Strike 5 lightning bolts around the target
        for (int i = 0; i < 5; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 6.0;
            double offsetZ = (random.nextDouble() - 0.5) * 6.0;
            LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(serverLevel);
            if (lightning != null) {
                lightning.moveTo(target.getX() + offsetX, target.getY(), target.getZ() + offsetZ);
                serverLevel.addFreshEntity(lightning);

                // Manually deal Wither-attributed damage around the strike (Lightning doesn't attribute to mobs)
                List<LivingEntity> hitEntities = serverLevel.getEntitiesOfClass(
                        LivingEntity.class, lightning.getBoundingBox().inflate(3.0),
                        e -> e != wither && e.isAlive());
                for (LivingEntity e : hitEntities) {
                    e.hurt(wither.damageSources().mobAttack(wither), 5.0F);
                }
            }
        }
    }

    // ========== DASH ATTACK ==========
    private void dashAttack(LivingEntity target) {
        Vec3 direction = target.position().subtract(wither.position()).normalize();
        double speed = 3.0; // Very fast dash
        wither.setDeltaMovement(direction.x * speed, direction.y * speed + 0.5, direction.z * speed);
        wither.hurtMarked = true; // Sync movement to client

        // Deal damage to entities in the path
        List<LivingEntity> nearbyEntities = wither.level().getEntitiesOfClass(
                LivingEntity.class, wither.getBoundingBox().inflate(2.0),
                e -> e != wither && e.isAlive());
        for (LivingEntity entity : nearbyEntities) {
            entity.hurt(wither.damageSources().mobAttack(wither), 20.0F);
            // Knock them back
            Vec3 knockback = entity.position().subtract(wither.position()).normalize().scale(2.0);
            entity.setDeltaMovement(knockback.x, 0.5, knockback.z);
            entity.hurtMarked = true;
        }
    }

    // ========== WITHER POTION (Lingering Potion with Wither V) ==========
    private void fireWitherPotion(LivingEntity target) {
        if (!(wither.level() instanceof ServerLevel serverLevel)) return;

        // Create a lingering potion item with Wither V effect
        ItemStack potionStack = new ItemStack(Items.LINGERING_POTION);
        PotionUtils.setPotion(potionStack, Potions.WATER);
        PotionUtils.setCustomEffects(potionStack,
                List.of(new MobEffectInstance(MobEffects.WITHER, 200, 4))); // Wither V for 10 seconds

        // Create and throw the potion at the target
        ThrownPotion thrownPotion = new ThrownPotion(serverLevel, wither);
        thrownPotion.setItem(potionStack);
        thrownPotion.setPos(wither.getX(), wither.getY() + 3.0, wither.getZ());
        thrownPotion.addTag(WitherEventHandler.GIANT_POTION_TAG);

        // Calculate trajectory to target
        double dx = target.getX() - wither.getX();
        double dy = target.getY(0.5) - (wither.getY() + 3.0);
        double dz = target.getZ() - wither.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Shoot with arc: add upward component based on distance
        thrownPotion.shoot(dx, dy + dist * 0.2, dz, 1.5F, 0.0F);
        serverLevel.addFreshEntity(thrownPotion);
    }

    // ========== UTILITY METHODS ==========
    private void shootSkullAt(LivingEntity target, boolean dangerous) {
        if (!(wither.level() instanceof ServerLevel serverLevel)) return;

        double dx = target.getX() - wither.getX();
        double dy = target.getY(0.5) - wither.getY(0.5);
        double dz = target.getZ() - wither.getZ();

        WitherSkull skull = new WitherSkull(serverLevel, wither, dx, dy, dz);
        skull.setPos(wither.getX(), wither.getY() + 3.0, wither.getZ());
        skull.setDangerous(dangerous);
        serverLevel.addFreshEntity(skull);
    }

    private void shootSkullInDirection(double dx, double dy, double dz, boolean dangerous) {
        if (!(wither.level() instanceof ServerLevel serverLevel)) return;

        WitherSkull skull = new WitherSkull(serverLevel, wither, dx, dy, dz);
        skull.setPos(wither.getX(), wither.getY() + 3.0, wither.getZ());
        skull.setDangerous(dangerous);
        serverLevel.addFreshEntity(skull);
    }
}
