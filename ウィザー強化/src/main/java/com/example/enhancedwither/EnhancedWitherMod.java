package com.example.enhancedwither;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(EnhancedWitherMod.MOD_ID)
public class EnhancedWitherMod {
    public static final String MOD_ID = "enhancedwither";
    public static final Logger LOGGER = LogManager.getLogger();

    public EnhancedWitherMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, EnhancedWitherConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new WitherEventHandler());
        LOGGER.info("Enhanced Wither Mod loaded! The Wither is now terrifying.");
    }
}
