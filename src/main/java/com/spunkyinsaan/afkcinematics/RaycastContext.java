package com.spunkyinsaan.afkcinematics;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;

final class RaycastContext extends ClipContext {
   RaycastContext(Vec3d from, Vec3d to, RaycastContext.ShapeType shapeType, RaycastContext.FluidHandling fluidHandling, Entity entity) {
      super(from, to, shapeType.block, fluidHandling.fluid, entity);
   }

   static enum FluidHandling {
      NONE(Fluid.NONE);

      private final ClipContext.Fluid fluid;

      private FluidHandling(ClipContext.Fluid fluid) {
         this.fluid = fluid;
      }

      
   }

   static enum ShapeType {
      COLLIDER(Block.COLLIDER);

      private final ClipContext.Block block;

      private ShapeType(ClipContext.Block block) {
         this.block = block;
      }

      
   }
}
