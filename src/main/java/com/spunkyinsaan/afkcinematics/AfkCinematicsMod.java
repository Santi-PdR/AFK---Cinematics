package com.spunkyinsaan.afkcinematics;

import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(AfkCinematicsMod.MOD_ID)
public final class AfkCinematicsMod {
    public static final String MOD_ID = "afkcinematics";

    public AfkCinematicsMod() {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> ClientEvents::register);
    }
}
