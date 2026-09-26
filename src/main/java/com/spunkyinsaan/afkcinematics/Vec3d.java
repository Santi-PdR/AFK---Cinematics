package com.spunkyinsaan.afkcinematics;

import net.minecraft.world.phys.Vec3;

public final class Vec3d extends Vec3 {
   static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

   Vec3d(double x, double y, double z) {
      super(x, y, z);
   }

   Vec3d(Vec3 vec) {
      this(vec.x, vec.y, vec.z);
   }

   public Vec3d normalize() {
      return new Vec3d(super.normalize());
   }

   public Vec3d subtract(Vec3 vec) {
      return new Vec3d(super.subtract(vec));
   }

   public Vec3d subtract(double x, double y, double z) {
      return new Vec3d(super.subtract(x, y, z));
   }

   public Vec3d add(Vec3 vec) {
      return new Vec3d(super.add(vec));
   }

   public Vec3d add(double x, double y, double z) {
      return new Vec3d(super.add(x, y, z));
   }

   public Vec3d multiply(double scalar) {
      return new Vec3d(super.scale(scalar));
   }

   public double lengthSquared() {
      return super.lengthSqr();
   }

   public Vec3d crossProduct(Vec3 vec) {
      return new Vec3d(super.cross(vec));
   }

   public double squaredDistanceTo(Vec3 vec) {
      return super.distanceToSqr(vec);
   }

   public Vec3d lerp(Vec3 vec, double delta) {
      return new Vec3d(super.lerp(vec, delta));
   }
}
