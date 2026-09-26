package com.spunkyinsaan.afkcinematics;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class World {
   static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
   static final ResourceKey<Level> NETHER = Level.NETHER;
   static final ResourceKey<Level> END = Level.END;

   private World() {
   }
}
