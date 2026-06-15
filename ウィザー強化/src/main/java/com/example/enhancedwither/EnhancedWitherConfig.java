package com.example.enhancedwither;

import net.minecraftforge.common.ForgeConfigSpec;

public class EnhancedWitherConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue MAX_HEALTH;
    public static final ForgeConfigSpec.DoubleValue ARMOR;
    public static final ForgeConfigSpec.DoubleValue REGEN_PER_SECOND;

    static {
        BUILDER.push("wither");

        MAX_HEALTH = BUILDER
                .comment(
                        "Wither's maximum health (direct/absolute value).",
                        "Vanilla default is 300.0",
                        "Default: 1000.0")
                .defineInRange("maxHealth", 1000.0, 1.0, 1000000.0);

        ARMOR = BUILDER
                .comment(
                        "Wither's armor value (direct/absolute value).",
                        "Vanilla default is 0.0",
                        "Default: 20.0")
                .defineInRange("armor", 20.0, 0.0, 1000.0);

        REGEN_PER_SECOND = BUILDER
                .comment(
                        "Amount of HP the Wither regenerates every second (self-regeneration).",
                        "Set to 0 to disable self-regeneration.",
                        "Default: 1.0")
                .defineInRange("regenPerSecond", 1.0, 0.0, 1000.0);

        BUILDER.pop();
    }

    static {
        SPEC = BUILDER.build();
    }
}
