package com.spunkyinsaan.afkcinematics;

import net.minecraft.util.Mth;

final class MathHelper {
   private MathHelper() {
   }

   static int floor(double value) {
      return Mth.floor(value);
   }

   static float clamp(float value, float min, float max) {
      return Mth.clamp(value, min, max);
   }

   static int clamp(int value, int min, int max) {
      return Mth.clamp(value, min, max);
   }

   static double clamp(double value, double min, double max) {
      return Mth.clamp(value, min, max);
   }

   static float lerp(float delta, float start, float end) {
      return Mth.lerp(delta, start, end);
   }

   static float lerpAngleDegrees(float delta, float start, float end) {
      return Mth.rotLerp(delta, start, end);
   }
}
