package com.example.enhancedwither;

import com.example.enhancedwither.ai.WitherEnhancedAttackGoal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
import java.util.UUID;

public class WitherEventHandler {
    private static final UUID HEALTH_MODIFIER_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID ARMOR_MODIFIER_UUID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final String HEALTH_MODIFIER_NAME = "enhanced_wither_health";
    private static final String ARMOR_MODIFIER_NAME = "enhanced_wither_armor";

    // Tag used to mark skulls that should have power-10 explosion
    public static final String EXPLOSIVE_SKULL_TAG = "enhanced_explosive";
    // Tag used to mark lingering potions that should have radius 100
    public static final String GIANT_POTION_TAG = "enhanced_giant_potion";

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof WitherBoss wither) {
            if (!event.getLevel().isClientSide()) {
                // Modify HP: vanilla is 300, we want 1000, so add 700
                AttributeInstance healthAttr = wither.getAttribute(Attributes.MAX_HEALTH);
                if (healthAttr != null && healthAttr.getModifier(HEALTH_MODIFIER_UUID) == null) {
                    healthAttr.addPermanentModifier(new AttributeModifier(
                            HEALTH_MODIFIER_UUID, HEALTH_MODIFIER_NAME, 700.0,
                            AttributeModifier.Operation.ADDITION));
                    // Heal to full after modifying max health
                    wither.setHealth(wither.getMaxHealth());
                }

                // Modify Armor: vanilla is 0, we want 20
                AttributeInstance armorAttr = wither.getAttribute(Attributes.ARMOR);
                if (armorAttr != null && armorAttr.getModifier(ARMOR_MODIFIER_UUID) == null) {
                    armorAttr.addPermanentModifier(new AttributeModifier(
                            ARMOR_MODIFIER_UUID, ARMOR_MODIFIER_NAME, 20.0,
                            AttributeModifier.Operation.ADDITION));
                }

                // Add enhanced attack goal (check if not already added by using a tag)
                boolean hasEnhancedGoal = wither.getTags().contains("enhanced_wither");
                if (!hasEnhancedGoal) {
                    wither.addTag("enhanced_wither");
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
                for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
                    if (entity instanceof WitherSkull skull) {
                        net.minecraft.world.entity.player.Player nearestPlayer = serverLevel.getNearestPlayer(skull, 128.0);
                        if (nearestPlayer == null) {
                            toDiscard.add(skull);
                        }
                    }
                }
                // Discard after iteration completes — safe to modify the collection now
                toDiscard.forEach(net.minecraft.world.entity.Entity::discard);
            }
        }
    }
}
