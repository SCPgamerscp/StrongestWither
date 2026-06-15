package com.example.enhancedwither;

import com.example.enhancedwither.ai.WitherEnhancedAttackGoal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

public class WitherEventHandler {
    // Tag used to mark skulls that should have power-10 explosion
    public static final String EXPLOSIVE_SKULL_TAG = "enhanced_explosive";
    // Tag used to mark lingering potions that should have radius 100
    public static final String GIANT_POTION_TAG = "enhanced_giant_potion";

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof WitherBoss wither) {
            if (!event.getLevel().isClientSide()) {
                // Apply enhanced stats and AI only once per entity (first spawn),
                // tracked via the "enhanced_wither" tag
                boolean hasEnhancedGoal = wither.getTags().contains("enhanced_wither");
                if (!hasEnhancedGoal) {
                    wither.addTag("enhanced_wither");

                    // Set max health directly from config (absolute value, vanilla default is 300)
                    AttributeInstance healthAttr = wither.getAttribute(Attributes.MAX_HEALTH);
                    if (healthAttr != null) {
                        healthAttr.setBaseValue(EnhancedWitherConfig.MAX_HEALTH.get());
                        // Heal to full after setting max health
                        wither.setHealth(wither.getMaxHealth());
                    }

                    // Set armor directly from config (absolute value, vanilla default is 0)
                    AttributeInstance armorAttr = wither.getAttribute(Attributes.ARMOR);
                    if (armorAttr != null) {
                        armorAttr.setBaseValue(EnhancedWitherConfig.ARMOR.get());
                    }

                    wither.goalSelector.addGoal(1, new WitherEnhancedAttackGoal(wither));
                    EnhancedWitherMod.LOGGER.info("Enhanced Wither spawned with HP: {} and Armor: {}",
                            wither.getMaxHealth(), wither.getAttributeValue(Attributes.ARMOR));
                }
            }
        }
    }

    /**
     * Intercept wither skull impacts to handle explosive skulls tagged with "enhanced_explosive".
     * Intercept potion impacts to handle giant potions tagged with "enhanced_giant_potion".
     */
    @SuppressWarnings("removal")
    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getProjectile() instanceof net.minecraft.world.entity.projectile.ThrownPotion potion) {
            if (potion.getTags().contains(GIANT_POTION_TAG) && !potion.level().isClientSide()) {
                event.setCanceled(true);
                
                net.minecraft.world.entity.AreaEffectCloud cloud = new net.minecraft.world.entity.AreaEffectCloud(
                        potion.level(), potion.getX(), potion.getY(), potion.getZ());
                
                if (potion.getOwner() instanceof net.minecraft.world.entity.LivingEntity owner) {
                    cloud.setOwner(owner);
                }
                
                cloud.setRadius(100.0F); // 100 block radius!
                cloud.setRadiusOnUse(-0.5F);
                cloud.setWaitTime(10);
                cloud.setDuration(200); // 10 seconds
                cloud.setRadiusPerTick(0.0F);
                cloud.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.WITHER, 200, 4)); // Wither V
                
                potion.level().addFreshEntity(cloud);
                potion.discard();
                return;
            }
        }
        
        if (event.getProjectile() instanceof WitherSkull skull) {
            if (skull.getTags().contains(EXPLOSIVE_SKULL_TAG) && !skull.level().isClientSide()) {
                // Cancel the default impact behavior
                event.setCanceled(true);

                // Determine if this should break blocks
                boolean canBreakBlocks = skull.isDangerous() ||
                        net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(skull.level(), skull.getOwner());

                // Create a massive explosion (power 10)
                skull.level().explode(skull, skull.getX(), skull.getY(), skull.getZ(),
                        10.0F, canBreakBlocks,
                        canBreakBlocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);

                // Apply wither effect to nearby living entities
                skull.level().getEntitiesOfClass(
                        net.minecraft.world.entity.LivingEntity.class,
                        skull.getBoundingBox().inflate(10.0)
                ).forEach(living -> {
                    living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.WITHER, 200, 2), skull.getOwner()); // Wither III for 10 seconds, attributed to Wither
                });

                skull.discard();
            }
        }
    }

    /**
     * Check WitherSkulls every second and despawn them if they are more than 128 blocks away from the nearest player.
     * This prevents massive lag from thousands of skulls flying into unloaded chunks.
     */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel serverLevel) {
            // Run once per second (every 20 ticks) to save performance
            if (serverLevel.getGameTime() % 20 == 0) {
                // Fix: collect skulls to discard first, then discard after iteration.
                // Calling discard() inside getAllEntities() loop modifies the underlying
                // Int2ObjectLinkedOpenHashMap mid-iteration, causing ArrayIndexOutOfBoundsException.
                List<WitherSkull> toDiscard = new ArrayList<>();
                double regenPerSecond = EnhancedWitherConfig.REGEN_PER_SECOND.get();

                for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
                    if (entity instanceof WitherSkull skull) {
                        net.minecraft.world.entity.player.Player nearestPlayer = serverLevel.getNearestPlayer(skull, 128.0);
                        if (nearestPlayer == null) {
                            toDiscard.add(skull);
                        }
                    } else if (entity instanceof WitherBoss wither) {
                        // Self-regeneration: heal the wither over time (configurable, 0 disables it)
                        if (regenPerSecond > 0.0 && wither.isAlive() && wither.getHealth() < wither.getMaxHealth()) {
                            float newHealth = (float) Math.min(wither.getHealth() + regenPerSecond, wither.getMaxHealth());
                            wither.setHealth(newHealth);
                        }
                    }
                }
                // Discard after iteration completes — safe to modify the collection now
                toDiscard.forEach(net.minecraft.world.entity.Entity::discard);
            }
        }
    }
}
