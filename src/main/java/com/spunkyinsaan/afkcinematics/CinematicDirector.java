package com.spunkyinsaan.afkcinematics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;

final class CinematicDirector {
   private static final int SHOT_MIN_DURATION_TICKS = 100;
   private static final int SHOT_MAX_DURATION_TICKS = 120;
   private static final int MAX_OCCLUDED_TICKS = 6;
   private static final double MAX_RADIUS_FROM_PLAYER = 16.0D;
   private static final double MIN_CAMERA_FOCUS_DISTANCE = 1.15D;
   private static final double CAMERA_COLLISION_CLEARANCE = 0.85D;
   private static final float TARGET_FOV = 30.0F;
   private static final double PLAYER_BASE_MOTION_MULTIPLIER = 0.9D;
   private static final double GLOBAL_MOTION_MULTIPLIER = 1.28D;
   private boolean active;
   private double motionSpeedMultiplier = 1.0D;
   private double motionAmplitudeMultiplier = 1.0D;
   private double motionShakeMultiplier = 1.0D;
   private int shotTicksRemaining;
   private int shotElapsedTicks;
   private int currentShotDurationTicks;
   private CinematicDirector.ShotPreset currentShotPreset = CinematicDirector.ShotPreset.PLAYER_HERO_FRONT;
   private Vec3d shotBaseCamera = Vec3d.ZERO;
   private Vec3d shotBaseFocus = Vec3d.ZERO;
   private Vec3d shotRightAxis = Vec3d.ZERO;
   private Vec3d shotUpAxis = Vec3d.ZERO;
   private Vec3d cameraPosition = Vec3d.ZERO;
   private Vec3d focusPosition = Vec3d.ZERO;
   private Vec3d lastStableCameraPosition = Vec3d.ZERO;
   private double shotLateralAmplitude;
   private double shotVerticalAmplitude;
   private double shotMotionSpeed;
   private double orbitAngle;
   private double orbitRadius = 3.2D;
   private double orbitSpeed = 0.0045D;
   private double orbitHeightOffset = 0.2D;
   private float cameraYaw;
   private float cameraPitch;
   private float motionPhase;
   private int occludedTicks;
   private int consecutivePlayerShots;
   private int consecutiveEnvironmentShots;
   private int restoreFov;
   private boolean restoreHudHidden;
   private int fadeInTicksRemaining;
   private int creditsTicksRemaining;
   private CameraType restorePerspective = CameraType.FIRST_PERSON;

   void renderOverlay(net.minecraft.client.gui.GuiGraphics graphics, int width, int height) {
      if (this.fadeInTicksRemaining > 0) {
         float progress = MathHelper.clamp((float)this.fadeInTicksRemaining / 10.0F, 0.0F, 1.0F);
         int alpha = MathHelper.clamp(Math.round(progress * 150.0F), 0, 255);
         graphics.fill(0, 0, width, height, alpha << 24);
      }
      if (this.creditsTicksRemaining > 0) {
         int elapsed = 220 - this.creditsTicksRemaining;
         float progress = elapsed < 20 ? elapsed / 20.0F
                 : elapsed < 200 ? 1.0F : MathHelper.clamp((220 - elapsed) / 20.0F, 0.0F, 1.0F);
         int alpha = MathHelper.clamp(Math.round(progress * 255.0F), 0, 255);
         graphics.drawString(Minecraft.getInstance().font, "Created By Spunky Insaan",
                 8, 8, (alpha << 24) | 0xFFFFFF, true);
      }
   }

   boolean isActive() {
      return this.active;
   }

   void setMotionLevel(String level) {
      CinematicDirector.MotionLevel parsed = CinematicDirector.MotionLevel.from(level);
      this.motionSpeedMultiplier = parsed.speedMultiplier;
      this.motionAmplitudeMultiplier = parsed.amplitudeMultiplier;
      this.motionShakeMultiplier = parsed.shakeMultiplier;
   }

   void start(Minecraft client) {
      if (!this.active && client.player != null) {
         Player player = client.player;
         this.restoreFov = client.options.fov().get();
         this.restoreHudHidden = client.options.hideGui;
         this.restorePerspective = client.options.getCameraType();
         this.active = true;
         this.fadeInTicksRemaining = 10;
         this.creditsTicksRemaining = 220;
         this.shotTicksRemaining = 0;
         this.shotElapsedTicks = 0;
         this.currentShotDurationTicks = 100;
         this.motionPhase = 0.0F;
         this.consecutivePlayerShots = 0;
         this.consecutiveEnvironmentShots = 0;
         Vec3d chest = this.getPlayerChestPosition(player);
         this.cameraPosition = chest.add(0.0D, 0.25D, -3.2D);
         this.focusPosition = chest;
         this.lastStableCameraPosition = this.cameraPosition;
         this.cameraYaw = player.getYRot();
         this.cameraPitch = player.getXRot();
         this.occludedTicks = 0;
         if (!this.restoreHudHidden) {
            client.options.hideGui = !client.options.hideGui;
         }

         client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
         CinematicCameraRig.activate(this.cameraPosition, this.cameraYaw, this.cameraPitch);
      }
   }

   void stop(Minecraft client) {
      if (this.active) {
         this.active = false;
         this.shotTicksRemaining = 0;
         this.creditsTicksRemaining = 0;
         client.options.fov().set(this.restoreFov);
         if (client.options.hideGui != this.restoreHudHidden) {
            client.options.hideGui = !client.options.hideGui;
         }

         client.options.setCameraType(this.restorePerspective);
         CinematicCameraRig.deactivate();
      }
   }

   void tick(Minecraft client) {
      if (this.active && client.player != null && client.level != null) {
         this.motionPhase += 0.028F;
         if (this.fadeInTicksRemaining > 0) --this.fadeInTicksRemaining;
         if (this.creditsTicksRemaining > 0) --this.creditsTicksRemaining;
         this.easeFov(client, 30.0F, 0.06F);
         if (this.shotTicksRemaining <= 0) {
            this.chooseNextShot(client);
         }

         client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
         this.applyCurrentShot(client);
         ++this.shotElapsedTicks;
         --this.shotTicksRemaining;
      }
   }

   void refreshAfterPassiveMovement(Minecraft client) {
      if (this.active && client.player != null) {
         Player player = client.player;
         Vec3d chest = this.getPlayerChestPosition(player);
         this.shotTicksRemaining = 0;
         this.shotElapsedTicks = 0;
         this.currentShotDurationTicks = 100;
         this.cameraPosition = chest.add(0.0D, 0.25D, -3.2D);
         this.focusPosition = chest;
         this.lastStableCameraPosition = this.cameraPosition;
         this.cameraYaw = player.getYRot();
         this.cameraPitch = player.getXRot();
         this.occludedTicks = 0;
         CinematicCameraRig.cutTo(this.cameraPosition, this.cameraYaw, this.cameraPitch);
         this.chooseNextShot(client);
      }
   }

   private void chooseNextShot(Minecraft client) {
      Player player = client.player;
      if (player != null) {
         boolean inNether = player.level().dimension() == World.NETHER;
         boolean underground = this.isUnderground(player);
         double playerShotChance = underground ? 1.0D : 0.4D;
         boolean choosePlayer = ThreadLocalRandom.current().nextDouble() < playerShotChance;
         if (underground && !inNether) {
            choosePlayer = true;
         }

         if (this.consecutivePlayerShots >= 2) {
            choosePlayer = false;
         }

         if (underground && !inNether) {
            choosePlayer = true;
         }

         int maxConsecutiveEnvironment = underground ? 1 : 3;
         if (this.consecutiveEnvironmentShots >= maxConsecutiveEnvironment) {
            choosePlayer = true;
         }

         CinematicDirector.ShotPreset[] heroPresets = new CinematicDirector.ShotPreset[]{CinematicDirector.ShotPreset.PLAYER_HERO_FRONT, CinematicDirector.ShotPreset.PLAYER_HERO_CENTER_LOCK, CinematicDirector.ShotPreset.PLAYER_HERO_CENTER_SWEEP, CinematicDirector.ShotPreset.PLAYER_SIDE_PROFILE, CinematicDirector.ShotPreset.PLAYER_ARC_HIGH, CinematicDirector.ShotPreset.PLAYER_ULTRA_WIDE, CinematicDirector.ShotPreset.PLAYER_NATURE_HERO_WIDE, CinematicDirector.ShotPreset.PLAYER_NATURE_BACKDROP_LEFT, CinematicDirector.ShotPreset.PLAYER_NATURE_BACKDROP_RIGHT, CinematicDirector.ShotPreset.PLAYER_NATURE_RIDGE_HIGH, CinematicDirector.ShotPreset.PLAYER_NATURE_VALLEY_PROFILE, CinematicDirector.ShotPreset.PLAYER_NATURE_SKYLINE_PORTRAIT, CinematicDirector.ShotPreset.PLAYER_HERO_HIGH_ORBIT, CinematicDirector.ShotPreset.PLAYER_CINEMA_FRONT_LONG, CinematicDirector.ShotPreset.PLAYER_CINEMA_FRONT_LOW_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_SIDE_LEFT_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_SIDE_RIGHT_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_RIDGE_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_RIDGE_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_HIGH_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_HIGH_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_PORTRAIT_TOP, CinematicDirector.ShotPreset.PLAYER_CINEMA_EPIC_DISTANCE, CinematicDirector.ShotPreset.PLAYER_CINEMA_VALLEY_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_VALLEY_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_FOREST_BACKDROP, CinematicDirector.ShotPreset.PLAYER_CINEMA_MEADOW_PAN, CinematicDirector.ShotPreset.PLAYER_CINEMA_MOUNTAIN_LOCK, CinematicDirector.ShotPreset.PLAYER_HEAD_ON, CinematicDirector.ShotPreset.PLAYER_ORBIT_WIDE, CinematicDirector.ShotPreset.PLAYER_ORBIT};
         CinematicDirector.ShotPreset[] moviePresets = new CinematicDirector.ShotPreset[]{CinematicDirector.ShotPreset.PLAYER_DUTCH_LEFT, CinematicDirector.ShotPreset.PLAYER_DUTCH_RIGHT, CinematicDirector.ShotPreset.PLAYER_MOVIE_TRACK_LEFT, CinematicDirector.ShotPreset.PLAYER_MOVIE_TRACK_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_FRONT_LONG, CinematicDirector.ShotPreset.PLAYER_CINEMA_FRONT_LOW_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_BACK_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_SIDE_LEFT_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_SIDE_RIGHT_WIDE, CinematicDirector.ShotPreset.PLAYER_CINEMA_RIDGE_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_RIDGE_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_HIGH_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_HIGH_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_PORTRAIT_TOP, CinematicDirector.ShotPreset.PLAYER_CINEMA_EPIC_DISTANCE, CinematicDirector.ShotPreset.PLAYER_CINEMA_VALLEY_LEFT, CinematicDirector.ShotPreset.PLAYER_CINEMA_VALLEY_RIGHT, CinematicDirector.ShotPreset.PLAYER_CINEMA_FOREST_BACKDROP, CinematicDirector.ShotPreset.PLAYER_CINEMA_MEADOW_PAN, CinematicDirector.ShotPreset.PLAYER_CINEMA_MOUNTAIN_LOCK};
         CinematicDirector.ShotPreset[] closePresets = new CinematicDirector.ShotPreset[]{CinematicDirector.ShotPreset.PLAYER_CLOSEUP_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSEUP_RIGHT, CinematicDirector.ShotPreset.PLAYER_CLOSE_CENTER, CinematicDirector.ShotPreset.PLAYER_CLOSE_CHEST, CinematicDirector.ShotPreset.PLAYER_CLOSE_PROFILE_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSE_PROFILE_RIGHT, CinematicDirector.ShotPreset.PLAYER_CLOSE_OVERHEAD, CinematicDirector.ShotPreset.PLAYER_CLOSE_FACE_LOW};
         CinematicDirector.ShotPreset[] rearPresets = new CinematicDirector.ShotPreset[]{CinematicDirector.ShotPreset.PLAYER_OVER_SHOULDER, CinematicDirector.ShotPreset.PLAYER_REAR_SILHOUETTE, CinematicDirector.ShotPreset.PLAYER_CINEMA_BACK_WIDE, CinematicDirector.ShotPreset.PLAYER_BACK_RIGHT, CinematicDirector.ShotPreset.PLAYER_BACK_LEFT, CinematicDirector.ShotPreset.PLAYER_SHOULDER_TRACK_RIGHT, CinematicDirector.ShotPreset.PLAYER_SHOULDER_TRACK_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSE_SHOULDER_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSE_SHOULDER_RIGHT};
         CinematicDirector.ShotPreset[] caveHeroPresets = new CinematicDirector.ShotPreset[]{CinematicDirector.ShotPreset.PLAYER_HERO_FRONT, CinematicDirector.ShotPreset.PLAYER_HERO_CENTER_LOCK, CinematicDirector.ShotPreset.PLAYER_OVER_SHOULDER, CinematicDirector.ShotPreset.PLAYER_SIDE_PROFILE, CinematicDirector.ShotPreset.PLAYER_LOW_ANGLE, CinematicDirector.ShotPreset.PLAYER_HEAD_ON, CinematicDirector.ShotPreset.PLAYER_CLOSEUP_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSEUP_RIGHT, CinematicDirector.ShotPreset.PLAYER_CLOSE_CENTER, CinematicDirector.ShotPreset.PLAYER_CLOSE_CHEST, CinematicDirector.ShotPreset.PLAYER_CLOSE_PROFILE_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSE_PROFILE_RIGHT, CinematicDirector.ShotPreset.PLAYER_CLOSE_OVERHEAD, CinematicDirector.ShotPreset.PLAYER_CLOSE_SHOULDER_LEFT, CinematicDirector.ShotPreset.PLAYER_CLOSE_SHOULDER_RIGHT, CinematicDirector.ShotPreset.PLAYER_CLOSE_FACE_LOW, CinematicDirector.ShotPreset.PLAYER_BACK_RIGHT, CinematicDirector.ShotPreset.PLAYER_BACK_LEFT, CinematicDirector.ShotPreset.PLAYER_SHOULDER_TRACK_RIGHT, CinematicDirector.ShotPreset.PLAYER_SHOULDER_TRACK_LEFT};
         CinematicDirector.ShotPreset next;
         if (choosePlayer && !inNether) {
            double roll = ThreadLocalRandom.current().nextDouble();
            CinematicDirector.ShotPreset[] selectedPool = underground ? caveHeroPresets : (roll < 0.3D ? heroPresets : (roll < 0.6D ? moviePresets : (roll < 0.8D ? closePresets : rearPresets)));
            next = selectedPool[ThreadLocalRandom.current().nextInt(selectedPool.length)];
         } else {
            List<CinematicDirector.ShotPreset> environmentPresets = new ArrayList();
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_WIDE_NATURE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_HORIZON_SWEEP);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_ESTABLISHING_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_TREE_CANOPY);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_RIDGE_LOOK);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_PAN_LEFT);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_PAN_RIGHT);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_SKYLINE_LOW);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_WIDE_MOUNTAIN);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_VALLEY_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_FOREST_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_RIDGE_SLOW);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_RIVER_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_CLIFF_EDGE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_NATURE_MEADOW_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_CINEMA_REVEAL_LEFT);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_CINEMA_REVEAL_RIGHT);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_CRANE_UP_EPIC);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_CINEMA_ESTABLISH_TOP);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_LONG_LENS_PEAK);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_OVERWORLD_RIVER_VISTA);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_OVERWORLD_MOUNTAIN_CREST);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_OVERWORLD_FOREST_CANOPY_WIDE);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_OVERWORLD_PLAINS_PAN);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_OVERLOOK);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_FLYOVER_DIAGONAL);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_FOREGROUND_PARALLAX);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_ULTRA_WIDE_HORIZON);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_GIANT_RIDGE_ESTABLISH);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_REMOTE_PEAK_LOCK);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_PLAINS_CINEMA_LONG);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_SKYLINE_CROSSPAN);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_WIDE_SILHOUETTE_FIELD);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_RIVERBANK_LONG);
            environmentPresets.add(CinematicDirector.ShotPreset.ENV_CANYON_EDGE_WIDE);
            if (!underground && !inNether) {
               environmentPresets.removeAll(List.of(CinematicDirector.ShotPreset.ENV_HORIZON_SWEEP, CinematicDirector.ShotPreset.ENV_TREE_CANOPY, CinematicDirector.ShotPreset.ENV_SKYLINE_LOW, CinematicDirector.ShotPreset.ENV_CRANE_UP_EPIC, CinematicDirector.ShotPreset.ENV_CINEMA_ESTABLISH_TOP, CinematicDirector.ShotPreset.ENV_LONG_LENS_PEAK, CinematicDirector.ShotPreset.ENV_ULTRA_WIDE_HORIZON, CinematicDirector.ShotPreset.ENV_GIANT_RIDGE_ESTABLISH, CinematicDirector.ShotPreset.ENV_REMOTE_PEAK_LOCK, CinematicDirector.ShotPreset.ENV_SKYLINE_CROSSPAN));
               if (ThreadLocalRandom.current().nextDouble() < 0.18D) {
                  environmentPresets.add(CinematicDirector.ShotPreset.ENV_HORIZON_SWEEP);
               }

               if (ThreadLocalRandom.current().nextDouble() < 0.16D) {
                  environmentPresets.add(CinematicDirector.ShotPreset.ENV_SKYLINE_LOW);
               }
            }

            if (underground) {
               environmentPresets.removeAll(List.of(CinematicDirector.ShotPreset.ENV_HORIZON_SWEEP, CinematicDirector.ShotPreset.ENV_TREE_CANOPY, CinematicDirector.ShotPreset.ENV_RIDGE_LOOK, CinematicDirector.ShotPreset.ENV_SKYLINE_LOW, CinematicDirector.ShotPreset.ENV_CRANE_UP_EPIC, CinematicDirector.ShotPreset.ENV_CINEMA_ESTABLISH_TOP, CinematicDirector.ShotPreset.ENV_LONG_LENS_PEAK, CinematicDirector.ShotPreset.ENV_OVERWORLD_MOUNTAIN_CREST, CinematicDirector.ShotPreset.ENV_OVERWORLD_FOREST_CANOPY_WIDE, CinematicDirector.ShotPreset.ENV_OVERLOOK, CinematicDirector.ShotPreset.ENV_FLYOVER_DIAGONAL, CinematicDirector.ShotPreset.ENV_ULTRA_WIDE_HORIZON, CinematicDirector.ShotPreset.ENV_GIANT_RIDGE_ESTABLISH, CinematicDirector.ShotPreset.ENV_REMOTE_PEAK_LOCK, CinematicDirector.ShotPreset.ENV_SKYLINE_CROSSPAN, CinematicDirector.ShotPreset.ENV_CANYON_EDGE_WIDE));
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_GROUND_DRIFT);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_GROUND_DRIFT);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_ENTITY_GLANCE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_WILDLIFE_WATCH);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_FOREGROUND_PARALLAX);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_VALLEY_DIP);
            }

            if (this.isSunriseTime(player)) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_SUNRISE_GAZE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_SUNRISE_HORIZON_GLOW);
            }

            if (this.isSunsetTime(player)) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_SUNSET_GAZE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_SUNSET_GOLDEN_RIM);
               if (!underground && ThreadLocalRandom.current().nextDouble() < 0.35D) {
                  environmentPresets.add(CinematicDirector.ShotPreset.ENV_SUNSET_LONG_HORIZON);
               }
            }

            if (this.isNightTime(player)) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NIGHT_GLOW_TRACK);
               if (!underground && ThreadLocalRandom.current().nextDouble() < 0.4D) {
                  environmentPresets.add(CinematicDirector.ShotPreset.ENV_NIGHT_MOONLINE);
               }
            }

            if (this.isNoonTime(player)) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NOON_WIDE_HEAT);
            }

            if (underground) {
               environmentPresets.removeAll(List.of(CinematicDirector.ShotPreset.ENV_SUNRISE_GAZE, CinematicDirector.ShotPreset.ENV_SUNRISE_HORIZON_GLOW, CinematicDirector.ShotPreset.ENV_SUNRISE_WIDE_SKY, CinematicDirector.ShotPreset.ENV_SUNSET_GAZE, CinematicDirector.ShotPreset.ENV_SUNSET_GOLDEN_RIM, CinematicDirector.ShotPreset.ENV_SUNSET_LONG_HORIZON, CinematicDirector.ShotPreset.ENV_NIGHT_STARS, CinematicDirector.ShotPreset.ENV_NIGHT_MOONLINE, CinematicDirector.ShotPreset.ENV_NIGHT_STARFIELD_WIDE, CinematicDirector.ShotPreset.ENV_NOON_SKY_PEAK));
            }

            if (player.level().dimension() == World.NETHER) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NETHER_LAVA_RIDGE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NETHER_BASALT_CANYON);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NETHER_FORTRESS_LOOK);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_NETHER_CRIMSON_SWEEP);
            } else if (player.level().dimension() == World.END) {
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_END_SPIRE_WIDE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_END_VOID_EDGE);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_END_OBSIDIAN_RING);
               environmentPresets.add(CinematicDirector.ShotPreset.ENV_END_GATEWAY_DRIFT);
            }

            if (inNether) {
               double roll = ThreadLocalRandom.current().nextDouble();
               boolean pickNetherPlayer = roll < 0.3D;
               boolean pickNetherNature = roll >= 0.3D && roll < 0.7D;
               if (this.consecutivePlayerShots >= 2) {
                  pickNetherPlayer = false;
                  pickNetherNature = true;
               }

               if (this.consecutiveEnvironmentShots >= 3) {
                  pickNetherPlayer = true;
                  pickNetherNature = false;
               }

               List<CinematicDirector.ShotPreset> netherNaturePresets = new ArrayList();
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_LAVA_RIDGE);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_LAVA_RIDGE);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_BASALT_CANYON);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_BASALT_CANYON);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_FORTRESS_LOOK);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_FORTRESS_LOOK);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_CRIMSON_SWEEP);
               netherNaturePresets.add(CinematicDirector.ShotPreset.ENV_NETHER_CRIMSON_SWEEP);
               List<CinematicDirector.ShotPreset> netherOtherPresets = new ArrayList();
               netherOtherPresets.addAll(List.of(moviePresets));
               if (pickNetherPlayer) {
                  List<CinematicDirector.ShotPreset> netherPlayerPresets = new ArrayList();
                  netherPlayerPresets.addAll(List.of(heroPresets));
                  netherPlayerPresets.addAll(List.of(closePresets));
                  netherPlayerPresets.addAll(List.of(rearPresets));
                  next = (CinematicDirector.ShotPreset)netherPlayerPresets.get(ThreadLocalRandom.current().nextInt(netherPlayerPresets.size()));
               } else if (pickNetherNature) {
                  next = (CinematicDirector.ShotPreset)netherNaturePresets.get(ThreadLocalRandom.current().nextInt(netherNaturePresets.size()));
               } else {
                  next = (CinematicDirector.ShotPreset)netherOtherPresets.get(ThreadLocalRandom.current().nextInt(netherOtherPresets.size()));
               }
            } else {
               next = (CinematicDirector.ShotPreset)environmentPresets.get(ThreadLocalRandom.current().nextInt(environmentPresets.size()));
            }
         }

         if (next.playerShot) {
            ++this.consecutivePlayerShots;
            this.consecutiveEnvironmentShots = 0;
         } else {
            ++this.consecutiveEnvironmentShots;
            this.consecutivePlayerShots = 0;
         }

         this.currentShotPreset = next;
         this.currentShotDurationTicks = ThreadLocalRandom.current().nextInt(100, 121);
         this.shotTicksRemaining = this.currentShotDurationTicks;
         this.shotElapsedTicks = 0;
         this.configureShot(player, this.currentShotPreset);
         this.cameraPosition = this.enforceAboveGround(player, this.shotBaseCamera, 0.95D);
         this.focusPosition = this.enforceAboveGround(player, this.shotBaseFocus, 0.3D);
         this.cameraPosition = this.avoidBlockClipping(player, this.cameraPosition, this.focusPosition, 0.3D);
         this.cameraPosition = this.enforceAboveGround(player, this.cameraPosition, 0.95D);
         this.lastStableCameraPosition = this.cameraPosition;
         this.occludedTicks = 0;
         Vec3d lookVector = this.focusPosition.subtract(this.cameraPosition);
         double horizontal = Math.sqrt(lookVector.x * lookVector.x + lookVector.z * lookVector.z);
         this.cameraYaw = (float)Math.toDegrees(Math.atan2(lookVector.z, lookVector.x)) - 90.0F;
         this.cameraPitch = (float)(-Math.toDegrees(Math.atan2(lookVector.y, horizontal)));
         CinematicCameraRig.cutTo(this.cameraPosition, this.cameraYaw, this.cameraPitch);
      }
   }

   private Vec3d pickNearbyEntityOrFallback(Player player) {
      AABB scanArea = player.getBoundingBox().inflate(48.0D, 24.0D, 48.0D);
      List<Entity> entities = player.level().getEntities(player, scanArea, (entityx) -> entityx.isAlive() && !entityx.isSpectator());
      if (entities.isEmpty()) {
         return this.pickNatureTarget(player);
      } else {
         Entity entity = (Entity)entities.get(ThreadLocalRandom.current().nextInt(entities.size()));
         return new Vec3d(entity.position().add(0.0D, (double)entity.getBbHeight() * 0.6D, 0.0D));
      }
   }

   private long getDayTime(Player player) {
      return player.level().getLevelData().getGameTime() % 24000L;
   }

   private boolean isSunriseTime(Player player) {
      long dayTime = this.getDayTime(player);
      return dayTime >= 22500L || dayTime <= 1800L;
   }

   private boolean isSunsetTime(Player player) {
      long dayTime = this.getDayTime(player);
      return dayTime >= 10800L && dayTime <= 13800L;
   }

   private boolean isNightTime(Player player) {
      long dayTime = this.getDayTime(player);
      return dayTime >= 13000L && dayTime <= 23000L;
   }

   private boolean isNoonTime(Player player) {
      long dayTime = this.getDayTime(player);
      return dayTime >= 5200L && dayTime <= 7600L;
   }

   private Vec3d pickSunFocusPoint(Player player, boolean sunrise) {
      Vec3d chest = this.getPlayerChestPosition(player);
      Vec3d sunDirection = sunrise ? (new Vec3d(-1.0D, 0.22D, 0.14D)).normalize() : (new Vec3d(1.0D, 0.18D, -0.14D)).normalize();
      Vec3d point = chest.add(sunDirection.multiply(16.0D)).add(0.0D, 5.5D, 0.0D);
      return this.clampToPlayerRadius(player, point);
   }

   private Vec3d pickNightGlowTarget(Player player) {
      Vec3d best = this.pickNatureTarget(player);
      int bestLuminance = -1;
      int baseX = MathHelper.floor(player.getX());
      int baseY = MathHelper.floor(player.getY());
      int baseZ = MathHelper.floor(player.getZ());

      for(int i = 0; i < 28; ++i) {
         int x = baseX + ThreadLocalRandom.current().nextInt(-16, 17);
         int y = baseY + ThreadLocalRandom.current().nextInt(-6, 9);
         int z = baseZ + ThreadLocalRandom.current().nextInt(-16, 17);
         BlockPos pos = new BlockPos(x, y, z);
         int luminance = player.level().getBlockState(pos).getLightEmission();
         if (luminance > bestLuminance) {
            bestLuminance = luminance;
            best = new Vec3d((double)x + 0.5D, (double)y + 0.6D, (double)z + 0.5D);
         }
      }

      return this.clampToPlayerRadius(player, best);
   }

   private Vec3d pickNatureTarget(Player player) {
      int x = MathHelper.floor(player.getX()) + ThreadLocalRandom.current().nextInt(-18, 19);
      int z = MathHelper.floor(player.getZ()) + ThreadLocalRandom.current().nextInt(-18, 19);
      int topY = player.level().getHeight(Types.MOTION_BLOCKING_NO_LEAVES, x, z);
      return this.clampToPlayerRadius(player, new Vec3d((double)x + 0.5D, (double)topY + ThreadLocalRandom.current().nextDouble(1.2D, 5.2D), (double)z + 0.5D));
   }

   private Vec3d pickSkylinePoint(Player player) {
      double angle = ThreadLocalRandom.current().nextDouble(0.0D, (Math.PI * 2D));
      double distance = ThreadLocalRandom.current().nextDouble(12.0D, 24.0D);
      double x = player.getX() + Math.cos(angle) * distance;
      double z = player.getZ() + Math.sin(angle) * distance;
      BlockPos blockPos = BlockPos.containing(x, player.getY(), z);
      int topY = player.level().getHeight(Types.MOTION_BLOCKING_NO_LEAVES, blockPos.getX(), blockPos.getZ());
      return this.clampToPlayerRadius(player, new Vec3d(x, (double)topY + ThreadLocalRandom.current().nextDouble(8.0D, 20.0D), z));
   }

   private Vec3d pickSkyTopPoint(Player player) {
      double angle = ThreadLocalRandom.current().nextDouble(0.0D, (Math.PI * 2D));
      double radius = ThreadLocalRandom.current().nextDouble(1.4D, 3.6D);
      Vec3d chest = this.getPlayerChestPosition(player);
      return this.clampToPlayerRadius(player, chest.add(Math.cos(angle) * radius, ThreadLocalRandom.current().nextDouble(4.0D, 7.0D), Math.sin(angle) * radius));
   }

   private Vec3d pickCaveWallTarget(Player player) {
      Vec3d fallback = this.getPlayerChestPosition(player).add(ThreadLocalRandom.current().nextDouble(-5.5D, 5.5D), ThreadLocalRandom.current().nextDouble(-2.0D, 2.0D), ThreadLocalRandom.current().nextDouble(-5.5D, 5.5D));
      Vec3d best = fallback;
      int bestScore = Integer.MIN_VALUE;
      int baseX = MathHelper.floor(player.getX());
      int baseY = MathHelper.floor(player.getY());
      int baseZ = MathHelper.floor(player.getZ());
      Level world = player.level();

      for(int i = 0; i < 44; ++i) {
         int x = baseX + ThreadLocalRandom.current().nextInt(-12, 13);
         int y = baseY + ThreadLocalRandom.current().nextInt(-7, 8);
         int z = baseZ + ThreadLocalRandom.current().nextInt(-12, 13);
         BlockPos pos = new BlockPos(x, y, z);
         BlockState state = world.getBlockState(pos);
         if (!state.isAir()) {
            boolean exposed = world.getBlockState(pos.above()).isAir() || world.getBlockState(pos.below()).isAir() || world.getBlockState(pos.north()).isAir() || world.getBlockState(pos.south()).isAir() || world.getBlockState(pos.east()).isAir() || world.getBlockState(pos.west()).isAir();
            if (exposed) {
               int score = state.getLightEmission();
               score += Math.abs(y - baseY) <= 3 ? 3 : 0;
               if (score > bestScore) {
                  bestScore = score;
                  best = new Vec3d((double)x + 0.5D, (double)y + 0.5D, (double)z + 0.5D);
               }
            }
         }
      }

      return this.clampToPlayerRadius(player, best);
   }

   private Vec3d pickCaveCeilingTarget(Player player) {
      Vec3d chest = this.getPlayerChestPosition(player);
      Level world = player.level();

      for(int i = 0; i < 28; ++i) {
         int x = MathHelper.floor(player.getX()) + ThreadLocalRandom.current().nextInt(-9, 10);
         int z = MathHelper.floor(player.getZ()) + ThreadLocalRandom.current().nextInt(-9, 10);
         int y = MathHelper.floor(player.getY()) + ThreadLocalRandom.current().nextInt(2, 11);
         BlockPos pos = new BlockPos(x, y, z);
         if (!world.getBlockState(pos).isAir() && world.getBlockState(pos.below()).isAir()) {
            return this.clampToPlayerRadius(player, new Vec3d((double)x + 0.5D, (double)y - 0.15D, (double)z + 0.5D));
         }
      }

      return this.clampToPlayerRadius(player, chest.add(0.0D, 4.0D, 0.0D));
   }

   private Vec3d pickCaveFloorTarget(Player player) {
      Vec3d chest = this.getPlayerChestPosition(player);
      Level world = player.level();

      for(int i = 0; i < 28; ++i) {
         int x = MathHelper.floor(player.getX()) + ThreadLocalRandom.current().nextInt(-9, 10);
         int z = MathHelper.floor(player.getZ()) + ThreadLocalRandom.current().nextInt(-9, 10);
         int y = MathHelper.floor(player.getY()) - ThreadLocalRandom.current().nextInt(1, 8);
         BlockPos pos = new BlockPos(x, y, z);
         if (!world.getBlockState(pos).isAir() && world.getBlockState(pos.above()).isAir()) {
            return this.clampToPlayerRadius(player, new Vec3d((double)x + 0.5D, (double)y + 0.15D, (double)z + 0.5D));
         }
      }

      return this.clampToPlayerRadius(player, chest.add(0.0D, -1.7D, 0.0D));
   }

   private Vec3d pickCaveGlowTarget(Player player) {
      Vec3d best = this.pickCaveWallTarget(player);
      int bestLuminance = -1;
      int baseX = MathHelper.floor(player.getX());
      int baseY = MathHelper.floor(player.getY());
      int baseZ = MathHelper.floor(player.getZ());
      Level world = player.level();

      for(int i = 0; i < 36; ++i) {
         int x = baseX + ThreadLocalRandom.current().nextInt(-12, 13);
         int y = baseY + ThreadLocalRandom.current().nextInt(-7, 8);
         int z = baseZ + ThreadLocalRandom.current().nextInt(-12, 13);
         BlockPos pos = new BlockPos(x, y, z);
         BlockState state = world.getBlockState(pos);
         int luminance = state.getLightEmission();
         if (luminance > bestLuminance && !state.isAir()) {
            bestLuminance = luminance;
            best = new Vec3d((double)x + 0.5D, (double)y + 0.55D, (double)z + 0.5D);
         }
      }

      return this.clampToPlayerRadius(player, best);
   }

   private boolean isUnderground(Player player) {
      if (player.level().dimension() != World.OVERWORLD) {
         return false;
      } else {
         BlockPos eyePos = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
         boolean skyVisible = player.level().canSeeSky(eyePos);
         int topY = player.level().getHeight(Types.MOTION_BLOCKING_NO_LEAVES, eyePos.getX(), eyePos.getZ());
         double coverDepth = (double)topY - player.getY();
         return !skyVisible && coverDepth >= 6.0D;
      }
   }

   private void adaptUndergroundEnvironmentShot(Player player, Vec3d chest) {
      if (this.isUnderground(player) && !this.currentShotPreset.playerShot && this.currentShotPreset.name().startsWith("ENV_")) {
         this.shotBaseFocus = chest.add(0.0D, 0.1D, 0.0D);
         Vec3d offset = this.shotBaseCamera.subtract(chest);
         double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
         if (horizontal < 1.8D) {
            offset = offset.add(0.0D, 0.0D, -2.2D);
         }

         double distance = MathHelper.clamp(offset.length(), 2.8D, 8.0D);
         Vec3d direction = offset.lengthSquared() < 1.0E-4D ? new Vec3d(0.0D, 0.0D, -1.0D) : offset.normalize();
         Vec3d framedCamera = chest.add(direction.multiply(distance));
         framedCamera = new Vec3d(framedCamera.x, MathHelper.clamp(framedCamera.y, chest.y - 1.1D, chest.y + 2.2D), framedCamera.z);
         this.shotBaseCamera = this.clampToPlayerRadius(player, framedCamera);
         this.shotLateralAmplitude = Math.min(this.shotLateralAmplitude, 0.07D);
         this.shotVerticalAmplitude = Math.min(this.shotVerticalAmplitude, 0.032D);
      }
   }

   private Vec3d clampToPlayerRadius(Player player, Vec3d point) {
      Vec3d playerPos = new Vec3d(player.position());
      double dx = point.x - playerPos.x;
      double dz = point.z - playerPos.z;
      double horizontalLength = Math.sqrt(dx * dx + dz * dz);
      if (!(horizontalLength <= 16.0D) && !(horizontalLength < 1.0E-4D)) {
         double scale = 16.0D / horizontalLength;
         return new Vec3d(playerPos.x + dx * scale, point.y, playerPos.z + dz * scale);
      } else {
         return point;
      }
   }

   private Vec3d enforceAboveGround(Player player, Vec3d point, double margin) {
      Level world = player.level();
      double playerChestY = this.getPlayerChestPosition(player).y;
      if (world.dimension() == World.OVERWORLD && this.isUnderground(player)) {
         double y = MathHelper.clamp(point.y, playerChestY - 2.2D, playerChestY + 3.0D);
         return y != point.y ? new Vec3d(point.x, y, point.z) : point;
      } else {
         BlockPos blockPos = BlockPos.containing(point.x, point.y, point.z);
         int terrainY = world.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, blockPos.getX(), blockPos.getZ());
         int roofY = terrainY;
         if (world.dimension() == World.NETHER && (double)terrainY > playerChestY) {
            terrainY = (int)Math.floor(playerChestY);
         }

         double minY = Math.max((double)terrainY + margin, playerChestY);
         double y = Math.max(point.y, minY);
         if (world.dimension() == World.NETHER) {
            double ceiling = (double)roofY - 1.0D;
            if (y > ceiling) {
               y = ceiling;
            }
         }

         return y != point.y ? new Vec3d(point.x, y, point.z) : point;
      }
   }

   private Vec3d avoidBlockClipping(Player player, Vec3d cameraPos, Vec3d focusPos, double clearance) {
      RaycastContext context = new RaycastContext(focusPos, cameraPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
      HitResult hit = player.level().clip(context);
      if (hit.getType() == Type.MISS) {
         return cameraPos;
      } else {
         Vec3d ray = cameraPos.subtract(focusPos);
         if (ray.lengthSquared() < 1.0E-4D) {
            return cameraPos;
         } else {
            Vec3d direction = ray.normalize();
            double hitDistance = (new Vec3d(hit.getLocation())).subtract(focusPos).length();
            double safeDistance = Math.max(hitDistance - Math.max(clearance, 0.85D), 1.15D);

            for(int attempt = 0; attempt < 5; ++attempt) {
               double distance = Math.max(1.15D, safeDistance - (double)attempt * 0.35D);
               Vec3d safe = this.clampToPlayerRadius(player, focusPos.add(direction.multiply(distance)));
               safe = this.enforceAboveGround(player, safe, 0.9D);
               if (!this.isShotOccluded(player, safe, focusPos)) {
                  return safe;
               }
            }

            return this.lastStableCameraPosition.lengthSquared() > 1.0E-4D && !this.isShotOccluded(player, this.lastStableCameraPosition, focusPos) ? this.lastStableCameraPosition : this.clampToPlayerRadius(player, focusPos.add(direction.multiply(1.15D)));
         }
      }
   }

   private boolean isShotOccluded(Player player, Vec3d cameraPos, Vec3d focusPos) {
      RaycastContext context = new RaycastContext(focusPos, cameraPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
      HitResult hit = player.level().clip(context);
      return hit.getType() != Type.MISS;
   }

   private void configureShot(Player player, CinematicDirector.ShotPreset preset) {
      Vec3d chest = this.getPlayerChestPosition(player);
      float yaw = player.getYRot();
      double yawRad = Math.toRadians((double)yaw);
      Vec3d forward = new Vec3d(-Math.sin(yawRad), 0.0D, Math.cos(yawRad));
      Vec3d right = new Vec3d(-forward.z, 0.0D, forward.x);
      Vec3d randomNature = this.pickNatureTarget(player);
      Vec3d randomEntity = this.pickNearbyEntityOrFallback(player);
      Vec3d skyline = this.pickSkylinePoint(player);
      Vec3d skyTop = this.pickSkyTopPoint(player);
      Vec3d caveWall = this.pickCaveWallTarget(player);
      Vec3d caveCeiling = this.pickCaveCeilingTarget(player);
      Vec3d caveFloor = this.pickCaveFloorTarget(player);
      Vec3d caveGlow = this.pickCaveGlowTarget(player);
      this.shotLateralAmplitude = 0.08D;
      this.shotVerticalAmplitude = 0.05D;
      this.shotMotionSpeed = 0.05D;
      this.orbitSpeed = 0.0045D;
      this.orbitRadius = 3.3D;
      this.orbitHeightOffset = 0.2D;
      switch (preset.ordinal()) {
         case 0:
            this.orbitAngle = ThreadLocalRandom.current().nextDouble(0.0D, (Math.PI * 2D));
            this.orbitRadius = ThreadLocalRandom.current().nextDouble(2.7D, 4.8D);
            this.orbitHeightOffset = ThreadLocalRandom.current().nextDouble(-0.2D, 0.55D);
            this.orbitSpeed = ThreadLocalRandom.current().nextDouble(0.0032D, 0.0054D);
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(Math.cos(this.orbitAngle) * this.orbitRadius, this.orbitHeightOffset, Math.sin(this.orbitAngle) * this.orbitRadius));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.04D;
            break;
         case 1:
            this.orbitAngle = ThreadLocalRandom.current().nextDouble(0.0D, (Math.PI * 2D));
            this.orbitRadius = ThreadLocalRandom.current().nextDouble(9.0D, 14.0D);
            this.orbitHeightOffset = ThreadLocalRandom.current().nextDouble(1.0D, 2.8D);
            this.orbitSpeed = ThreadLocalRandom.current().nextDouble(0.0026D, 0.0045D);
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(Math.cos(this.orbitAngle) * this.orbitRadius, this.orbitHeightOffset, Math.sin(this.orbitAngle) * this.orbitRadius));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.015D;
            this.shotMotionSpeed = 0.035D;
            break;
         case 2:
            this.orbitAngle = ThreadLocalRandom.current().nextDouble(0.0D, (Math.PI * 2D));
            this.orbitRadius = ThreadLocalRandom.current().nextDouble(5.8D, 9.6D);
            this.orbitHeightOffset = ThreadLocalRandom.current().nextDouble(3.2D, 5.6D);
            this.orbitSpeed = ThreadLocalRandom.current().nextDouble(0.0028D, 0.0042D);
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(Math.cos(this.orbitAngle) * this.orbitRadius, this.orbitHeightOffset, Math.sin(this.orbitAngle) * this.orbitRadius));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.012D;
            this.shotMotionSpeed = 0.032D;
            break;
         case 3:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(ThreadLocalRandom.current().nextDouble(2.5D, 4.2D))).add(0.0D, ThreadLocalRandom.current().nextDouble(0.1D, 0.8D), 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 4:
            this.shotBaseFocus = chest.add(0.0D, 0.18D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(3.2D)).add(0.0D, 0.42D, 0.0D));
            this.shotLateralAmplitude = 0.024D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 5:
            this.shotBaseFocus = chest.add(0.0D, 0.16D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(5.0D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.15D : -1.15D)).add(0.0D, 0.9D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.022D;
            this.shotMotionSpeed = 0.06D;
            break;
         case 6:
            this.shotBaseFocus = chest.add(forward.multiply(1.8D));
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.7D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 0.8D : -0.8D)).add(0.0D, 0.25D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 7:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.8D : -3.8D)).add(0.0D, ThreadLocalRandom.current().nextDouble(0.05D, 0.6D), 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 8:
            this.shotBaseFocus = chest.add(0.0D, 0.2D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.8D : -1.8D)).subtract(forward.multiply(2.3D)).add(0.0D, -1.35D, 0.0D));
            this.shotLateralAmplitude = 0.035D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 9:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(forward.multiply(3.2D)).add(0.0D, ThreadLocalRandom.current().nextDouble(0.2D, 1.0D), 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 10:
            this.shotBaseFocus = chest.add(0.0D, 0.1D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-1.5D)).subtract(forward.multiply(1.7D)).add(0.0D, 0.55D, 0.0D));
            this.shotLateralAmplitude = 0.028D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 11:
            this.shotBaseFocus = chest.add(0.0D, 0.1D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(1.5D)).subtract(forward.multiply(1.7D)).add(0.0D, 0.55D, 0.0D));
            this.shotLateralAmplitude = 0.028D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 12:
            this.shotBaseFocus = chest.add(0.0D, 0.14D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.75D)).add(0.0D, 0.45D, 0.0D));
            this.shotLateralAmplitude = 0.022D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 13:
            this.shotBaseFocus = chest.add(0.0D, 0.06D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.45D)).add(0.0D, 0.22D, 0.0D));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.012D;
            break;
         case 14:
            this.shotBaseFocus = chest.add(0.0D, 0.1D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-1.65D)).subtract(forward.multiply(0.95D)).add(0.0D, 0.38D, 0.0D));
            this.shotLateralAmplitude = 0.022D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 15:
            this.shotBaseFocus = chest.add(0.0D, 0.1D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(1.65D)).subtract(forward.multiply(0.95D)).add(0.0D, 0.38D, 0.0D));
            this.shotLateralAmplitude = 0.022D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 16:
            this.shotBaseFocus = chest.add(0.0D, 0.15D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(0.7D)).add(0.0D, 2.4D, 0.0D));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.012D;
            break;
         case 17:
            this.shotBaseFocus = chest.add(forward.multiply(1.35D));
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.25D)).add(right.multiply(-0.75D)).add(0.0D, 0.32D, 0.0D));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.012D;
            break;
         case 18:
            this.shotBaseFocus = chest.add(forward.multiply(1.35D));
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.25D)).add(right.multiply(0.75D)).add(0.0D, 0.32D, 0.0D));
            this.shotLateralAmplitude = 0.02D;
            this.shotVerticalAmplitude = 0.012D;
            break;
         case 19:
            this.shotBaseFocus = chest.add(0.0D, 0.24D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.6D)).add(0.0D, -0.35D, 0.0D));
            this.shotLateralAmplitude = 0.022D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 20:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(3.2D)).add(right.multiply(-1.1D)).add(0.0D, 0.35D, 0.0D));
            this.shotRightAxis = (new Vec3d(0.7D, 0.25D, 0.65D)).normalize();
            this.shotUpAxis = (new Vec3d(-0.22D, 0.97D, 0.11D)).normalize();
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 21:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(3.2D)).add(right.multiply(1.1D)).add(0.0D, 0.35D, 0.0D));
            this.shotRightAxis = (new Vec3d(0.7D, -0.25D, 0.65D)).normalize();
            this.shotUpAxis = (new Vec3d(0.22D, 0.97D, -0.11D)).normalize();
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 22:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.6D : -2.6D)).subtract(forward.multiply(1.8D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 23:
            this.shotBaseFocus = chest.add(0.0D, 0.15D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.2D : -2.2D)).subtract(forward.multiply(1.6D)).add(0.0D, -0.9D, 0.0D));
            this.shotLateralAmplitude = 0.032D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 24:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(ThreadLocalRandom.current().nextDouble(10.5D, 15.5D))).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.6D : -2.6D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.055D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 25:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(13.5D)).add(0.0D, 2.6D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 26:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-11.8D)).subtract(forward.multiply(6.8D)).add(0.0D, 2.2D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 27:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(11.8D)).subtract(forward.multiply(6.8D)).add(0.0D, 2.2D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 28:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(9.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 5.8D : -5.8D)).add(0.0D, 4.0D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 29:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 13.2D : -13.2D)).add(0.0D, 1.9D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 30:
            this.shotBaseFocus = chest.add(0.0D, 0.2D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(12.5D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 4.8D : -4.8D)).add(0.0D, 3.2D, 0.0D));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 31:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(15.2D)).add(0.0D, 2.8D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 32:
            this.shotBaseFocus = chest.add(0.0D, 0.15D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(11.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.6D : -3.6D)).add(0.0D, -0.6D, 0.0D));
            this.shotLateralAmplitude = 0.032D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 33:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(forward.multiply(12.2D)).add(0.0D, 2.4D, 0.0D));
            this.shotLateralAmplitude = 0.035D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 34:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-14.0D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.036D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 35:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(14.0D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.036D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 36:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-10.8D)).subtract(forward.multiply(7.2D)).add(0.0D, 3.2D, 0.0D));
            this.shotLateralAmplitude = 0.034D;
            this.shotVerticalAmplitude = 0.015D;
            break;
         case 37:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(10.8D)).subtract(forward.multiply(7.2D)).add(0.0D, 3.2D, 0.0D));
            this.shotLateralAmplitude = 0.034D;
            this.shotVerticalAmplitude = 0.015D;
            break;
         case 38:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-8.5D)).subtract(forward.multiply(4.8D)).add(0.0D, 5.2D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 39:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(8.5D)).subtract(forward.multiply(4.8D)).add(0.0D, 5.2D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 40:
            this.shotBaseFocus = chest.add(0.0D, 0.3D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(9.6D)).add(0.0D, 6.6D, 0.0D));
            this.shotLateralAmplitude = 0.028D;
            this.shotVerticalAmplitude = 0.012D;
            break;
         case 41:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(15.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.8D : -2.8D)).add(0.0D, 3.4D, 0.0D));
            this.shotLateralAmplitude = 0.032D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 42:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-12.6D)).add(0.0D, 1.3D, 0.0D));
            this.shotLateralAmplitude = 0.033D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 43:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(12.6D)).add(0.0D, 1.3D, 0.0D));
            this.shotLateralAmplitude = 0.033D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 44:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(13.2D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.1D : -6.1D)).add(0.0D, 2.3D, 0.0D));
            this.shotLateralAmplitude = 0.034D;
            this.shotVerticalAmplitude = 0.015D;
            break;
         case 45:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(10.4D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 8.0D : -8.0D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.016D;
            this.shotMotionSpeed = 0.045D;
            break;
         case 46:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(14.6D)).add(0.0D, 4.2D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 47:
            this.shotBaseFocus = chest.add(forward.multiply(0.8D)).add(0.0D, 0.12D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-4.4D)).subtract(forward.multiply(4.1D)).add(0.0D, 1.1D, 0.0D));
            this.shotLateralAmplitude = 0.055D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.065D;
            break;
         case 48:
            this.shotBaseFocus = chest.add(forward.multiply(0.8D)).add(0.0D, 0.12D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(4.4D)).subtract(forward.multiply(4.1D)).add(0.0D, 1.1D, 0.0D));
            this.shotLateralAmplitude = 0.055D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.065D;
            break;
         case 49:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(forward.multiply(2.8D)).add(right.multiply(1.5D)).add(0.0D, 0.4D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 50:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(forward.multiply(2.8D)).add(right.multiply(-1.5D)).add(0.0D, 0.4D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 51:
            this.shotBaseFocus = chest.add(0.0D, 0.2D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(2.3D)).add(0.0D, 0.25D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 52:
            this.shotBaseFocus = chest.add(forward.multiply(2.3D));
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.25D)).add(right.multiply(1.05D)).add(0.0D, 0.3D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.016D;
            this.shotMotionSpeed = 0.06D;
            break;
         case 53:
            this.shotBaseFocus = chest.add(forward.multiply(2.3D));
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(1.25D)).add(right.multiply(-1.05D)).add(0.0D, 0.3D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.016D;
            this.shotMotionSpeed = 0.06D;
            break;
         case 54:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(randomNature.subtract(chest).normalize().multiply(-4.4D)).add(0.0D, 1.2D, 0.0D));
            this.shotLateralAmplitude = 0.1D;
            this.shotVerticalAmplitude = 0.06D;
            this.shotMotionSpeed = 0.06D;
            break;
         case 55:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, ThreadLocalRandom.current().nextDouble(3.2D, 6.8D), 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.2D : -1.2D)));
            this.shotLateralAmplitude = 0.09D;
            this.shotVerticalAmplitude = 0.045D;
            this.shotMotionSpeed = 0.055D;
            break;
         case 56:
            this.shotBaseFocus = randomEntity;
            this.shotBaseCamera = this.clampToPlayerRadius(player, randomEntity.add(chest.subtract(randomEntity).normalize().multiply(3.4D)).add(0.0D, 0.8D, 0.0D));
            this.shotLateralAmplitude = 0.07D;
            this.shotVerticalAmplitude = 0.04D;
            break;
         case 57:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 4.6D : -4.6D)).add(0.0D, 1.1D, 0.0D));
            this.shotLateralAmplitude = 0.12D;
            this.shotVerticalAmplitude = 0.05D;
            this.shotMotionSpeed = 0.05D;
            break;
         case 58:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, skyTop);
            this.shotLateralAmplitude = 0.06D;
            this.shotVerticalAmplitude = 0.03D;
            this.shotMotionSpeed = 0.045D;
            break;
         case 59:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.8D : -6.8D)).subtract(forward.multiply(ThreadLocalRandom.current().nextDouble(8.0D, 13.5D))).add(0.0D, 3.0D, 0.0D));
            this.shotLateralAmplitude = 0.09D;
            this.shotVerticalAmplitude = 0.04D;
            break;
         case 60:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, randomNature.add(0.0D, 3.0D, 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.2D : -1.2D)));
            this.shotLateralAmplitude = 0.08D;
            this.shotVerticalAmplitude = 0.035D;
            break;
         case 61:
            this.shotBaseFocus = randomNature.add(0.0D, 0.5D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, randomNature.add(0.0D, 0.15D, 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.2D : -2.2D)));
            this.shotLateralAmplitude = 0.07D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.065D;
            break;
         case 62:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(skyline.subtract(chest).normalize().multiply(4.7D)).add(0.0D, 1.6D, 0.0D));
            this.shotLateralAmplitude = 0.1D;
            this.shotVerticalAmplitude = 0.05D;
            break;
         case 63:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(4.5D)).add(0.0D, 1.2D, 0.0D));
            this.shotLateralAmplitude = 0.14D;
            this.shotVerticalAmplitude = 0.03D;
            this.shotMotionSpeed = 0.055D;
            break;
         case 64:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-4.5D)).add(0.0D, 1.2D, 0.0D));
            this.shotLateralAmplitude = 0.14D;
            this.shotVerticalAmplitude = 0.03D;
            this.shotMotionSpeed = 0.055D;
            break;
         case 65:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, 0.25D, 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.3D : -3.3D)));
            this.shotLateralAmplitude = 0.08D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 66:
            this.shotBaseFocus = randomEntity;
            this.shotBaseCamera = this.clampToPlayerRadius(player, randomEntity.add(chest.subtract(randomEntity).normalize().multiply(4.5D)).add(0.0D, 0.45D, 0.0D));
            this.shotLateralAmplitude = 0.06D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 67:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(-12.0D)).subtract(forward.multiply(3.2D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.11D;
            this.shotVerticalAmplitude = 0.025D;
            this.shotMotionSpeed = 0.07D;
            break;
         case 68:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(12.0D)).subtract(forward.multiply(3.2D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.11D;
            this.shotVerticalAmplitude = 0.025D;
            this.shotMotionSpeed = 0.07D;
            break;
         case 69:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(6.5D)).add(0.0D, 7.4D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.05D;
            break;
         case 70:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(15.8D)).add(0.0D, 3.0D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 71:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 14.8D : -14.8D)).subtract(forward.multiply(5.4D)).add(0.0D, 4.8D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 72:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(skyline.subtract(chest).normalize().multiply(15.8D)).add(0.0D, 3.8D, 0.0D));
            this.shotLateralAmplitude = 0.035D;
            this.shotVerticalAmplitude = 0.015D;
            break;
         case 73:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(14.6D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.7D : -2.7D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 74:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 15.0D : -15.0D)).add(0.0D, 2.6D, 0.0D));
            this.shotLateralAmplitude = 0.06D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.05D;
            break;
         case 75:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(15.4D)).add(0.0D, 1.8D, 0.0D));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 76:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 13.8D : -13.8D)).subtract(forward.multiply(3.5D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 77:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(11.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 8.2D : -8.2D)).add(0.0D, 3.8D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 78:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(13.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.8D : -3.8D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 79:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(skyline.subtract(chest).normalize().multiply(14.8D)).add(0.0D, 3.9D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 80:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, 7.6D, 0.0D).subtract(forward.multiply(3.4D)));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 81:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 14.5D : -14.5D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.02D;
            this.shotMotionSpeed = 0.048D;
            break;
         case 82:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(12.2D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 4.2D : -4.2D)).add(0.0D, 1.8D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 83:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 12.8D : -12.8D)).subtract(forward.multiply(4.8D)).add(0.0D, 2.6D, 0.0D));
            this.shotLateralAmplitude = 0.048D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 84:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(15.2D)).add(0.0D, 3.0D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 85:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 13.2D : -13.2D)).add(0.0D, 2.2D, 0.0D));
            this.shotLateralAmplitude = 0.052D;
            this.shotVerticalAmplitude = 0.021D;
            this.shotMotionSpeed = 0.05D;
            break;
         case 86:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(14.4D)).add(0.0D, 4.6D, 0.0D));
            this.shotLateralAmplitude = 0.036D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 87:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 11.2D : -11.2D)).subtract(forward.multiply(7.8D)).add(0.0D, 2.9D, 0.0D));
            this.shotLateralAmplitude = 0.042D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 88:
            this.shotBaseFocus = chest;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, 8.0D, 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.8D : -2.8D)));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 89:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(10.5D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.6D : -6.6D)).add(0.0D, 2.8D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 90:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(skyline.subtract(chest).normalize().multiply(12.5D)).add(0.0D, 4.2D, 0.0D));
            this.shotLateralAmplitude = 0.07D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 91:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 8.8D : -8.8D)).subtract(forward.multiply(10.2D)).add(0.0D, 2.4D, 0.0D));
            this.shotLateralAmplitude = 0.08D;
            this.shotVerticalAmplitude = 0.032D;
            break;
         case 92:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, randomNature.add(chest.subtract(randomNature).normalize().multiply(11.0D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.09D;
            this.shotVerticalAmplitude = 0.035D;
            break;
         case 93:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 10.8D : -10.8D)).add(0.0D, 3.6D, 0.0D));
            this.shotLateralAmplitude = 0.06D;
            this.shotVerticalAmplitude = 0.026D;
            this.shotMotionSpeed = 0.04D;
            break;
         case 94:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(12.8D)).add(0.0D, 1.9D, 0.0D));
            this.shotLateralAmplitude = 0.08D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 95:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 11.8D : -11.8D)).subtract(forward.multiply(4.8D)).add(0.0D, 4.0D, 0.0D));
            this.shotLateralAmplitude = 0.07D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 96:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(9.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 5.4D : -5.4D)).add(0.0D, 2.2D, 0.0D));
            this.shotLateralAmplitude = 0.08D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 97:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, 8.5D, 0.0D).subtract(forward.multiply(4.8D)));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.022D;
            this.shotMotionSpeed = 0.05D;
            break;
         case 98:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(skyline.subtract(chest).normalize().multiply(15.5D)).add(0.0D, 2.7D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.018D;
            this.shotMotionSpeed = 0.045D;
            break;
         case 99:
            this.shotBaseFocus = this.pickSunFocusPoint(player, true);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(7.0D)).add(0.0D, 2.0D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 100:
            this.shotBaseFocus = this.pickSunFocusPoint(player, true).add(0.0D, 2.6D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 7.2D : -7.2D)).subtract(forward.multiply(5.8D)).add(0.0D, 3.2D, 0.0D));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 101:
            this.shotBaseFocus = this.pickSunFocusPoint(player, true);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(5.6D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.1D : -2.1D)).add(0.0D, 1.9D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 102:
            this.shotBaseFocus = this.pickSunFocusPoint(player, false);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(6.6D)).add(0.0D, 1.8D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 103:
            this.shotBaseFocus = this.pickSunFocusPoint(player, false).add(0.0D, 1.8D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 8.8D : -8.8D)).subtract(forward.multiply(6.5D)).add(0.0D, 2.6D, 0.0D));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.017D;
            break;
         case 104:
            this.shotBaseFocus = this.pickSunFocusPoint(player, false);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(5.0D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.8D : -1.8D)).add(0.0D, 1.7D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.025D;
            break;
         case 105:
            this.shotBaseFocus = chest.add(0.0D, 13.0D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(8.2D)).add(0.0D, 4.8D, 0.0D));
            this.shotLateralAmplitude = 0.032D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 106:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 12.5D : -12.5D)).add(0.0D, 2.4D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.018D;
            break;
         case 107:
            this.shotBaseFocus = chest.add(0.0D, 11.5D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(7.2D)).add(0.0D, 3.0D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 108:
            this.shotBaseFocus = chest.add(0.0D, 14.0D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.8D : -6.8D)).subtract(forward.multiply(5.2D)).add(0.0D, 4.2D, 0.0D));
            this.shotLateralAmplitude = 0.032D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 109:
            this.shotBaseFocus = chest.add(0.0D, 12.0D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.4D : -3.4D)).add(0.0D, 2.5D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.018D;
            this.shotMotionSpeed = 0.04D;
            break;
         case 110:
            this.shotBaseFocus = this.pickNightGlowTarget(player);
            this.shotBaseCamera = this.clampToPlayerRadius(player, this.shotBaseFocus.add(chest.subtract(this.shotBaseFocus).normalize().multiply(5.5D)).add(0.0D, 0.7D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.02D;
            break;
         case 111:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.2D : -6.2D)).subtract(forward.multiply(3.5D)).add(0.0D, 1.1D, 0.0D));
            this.shotLateralAmplitude = 0.11D;
            this.shotVerticalAmplitude = 0.028D;
            this.shotMotionSpeed = 0.07D;
            break;
         case 112:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, -0.7D, 0.0D).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.7D : -2.7D)));
            this.shotLateralAmplitude = 0.075D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 113:
            this.shotBaseFocus = randomNature;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(0.0D, 2.6D, 0.0D).subtract(forward.multiply(1.8D)));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.03D;
            break;
         case 114:
            this.shotBaseFocus = skyline;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.8D : -3.8D)).add(0.0D, 2.1D, 0.0D));
            this.shotLateralAmplitude = 0.09D;
            this.shotVerticalAmplitude = 0.04D;
            this.shotMotionSpeed = 0.06D;
            break;
         case 115:
            this.shotBaseFocus = caveWall;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(8.8D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 4.2D : -4.2D)).add(0.0D, 1.2D, 0.0D));
            this.shotLateralAmplitude = 0.055D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 116:
            this.shotBaseFocus = chest.add(forward.multiply(3.4D)).add(0.0D, 0.15D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(2.2D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 1.4D : -1.4D)).add(0.0D, 0.25D, 0.0D));
            this.shotLateralAmplitude = 0.038D;
            this.shotVerticalAmplitude = 0.016D;
            this.shotMotionSpeed = 0.062D;
            break;
         case 117:
            this.shotBaseFocus = caveWall;
            this.shotBaseCamera = this.clampToPlayerRadius(player, caveWall.add(chest.subtract(caveWall).normalize().multiply(3.8D)).add(0.0D, 0.4D, 0.0D));
            this.shotLateralAmplitude = 0.03D;
            this.shotVerticalAmplitude = 0.013D;
            break;
         case 118:
            this.shotBaseFocus = caveFloor.add(0.0D, 0.25D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, caveFloor.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.0D : -2.0D)).add(0.0D, 0.42D, 0.0D));
            this.shotLateralAmplitude = 0.05D;
            this.shotVerticalAmplitude = 0.016D;
            this.shotMotionSpeed = 0.065D;
            break;
         case 119:
            this.shotBaseFocus = caveCeiling;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.subtract(forward.multiply(4.4D)).add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 2.0D : -2.0D)).add(0.0D, 1.6D, 0.0D));
            this.shotLateralAmplitude = 0.035D;
            this.shotVerticalAmplitude = 0.014D;
            break;
         case 120:
            this.shotBaseFocus = caveGlow;
            this.shotBaseCamera = this.clampToPlayerRadius(player, caveGlow.add(chest.subtract(caveGlow).normalize().multiply(4.8D)).add(0.0D, 0.65D, 0.0D));
            this.shotLateralAmplitude = 0.04D;
            this.shotVerticalAmplitude = 0.016D;
            break;
         case 121:
            this.shotBaseFocus = caveWall;
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 6.0D : -6.0D)).subtract(forward.multiply(6.8D)).add(0.0D, 1.8D, 0.0D));
            this.shotLateralAmplitude = 0.06D;
            this.shotVerticalAmplitude = 0.022D;
            break;
         case 122:
            this.shotBaseFocus = caveFloor.add(0.0D, 0.2D, 0.0D);
            this.shotBaseCamera = this.clampToPlayerRadius(player, chest.add(right.multiply(ThreadLocalRandom.current().nextBoolean() ? 3.6D : -3.6D)).subtract(forward.multiply(2.8D)).add(0.0D, 0.75D, 0.0D));
            this.shotLateralAmplitude = 0.045D;
            this.shotVerticalAmplitude = 0.017D;
      }

      this.adaptUndergroundEnvironmentShot(player, chest);
      this.shotBaseCamera = this.enforceAboveGround(player, this.shotBaseCamera, 0.95D);
      this.shotBaseFocus = this.enforceAboveGround(player, this.shotBaseFocus, 0.3D);
      this.shotBaseCamera = this.avoidBlockClipping(player, this.shotBaseCamera, this.shotBaseFocus, 0.3D);
      this.shotBaseCamera = this.enforceAboveGround(player, this.shotBaseCamera, 0.95D);
      Vec3d look = this.shotBaseFocus.subtract(this.shotBaseCamera);
      Vec3d normalizedLook = look.lengthSquared() < 1.0E-4D ? new Vec3d(0.0D, 0.0D, 1.0D) : look.normalize();
      Vec3d rightAxis = new Vec3d(-normalizedLook.z, 0.0D, normalizedLook.x);
      if (rightAxis.lengthSquared() < 1.0E-4D) {
         rightAxis = new Vec3d(1.0D, 0.0D, 0.0D);
      }

      this.shotRightAxis = rightAxis.normalize();
      Vec3d upAxis = this.shotRightAxis.crossProduct(normalizedLook);
      if (upAxis.lengthSquared() < 1.0E-4D) {
         upAxis = new Vec3d(0.0D, 1.0D, 0.0D);
      }

      this.shotUpAxis = upAxis.normalize();
   }

   private void applyCurrentShot(Minecraft client) {
      Player player = client.player;
      if (player != null) {
         if (this.currentShotPreset != CinematicDirector.ShotPreset.PLAYER_ORBIT && this.currentShotPreset != CinematicDirector.ShotPreset.PLAYER_ORBIT_WIDE && this.currentShotPreset != CinematicDirector.ShotPreset.PLAYER_HERO_HIGH_ORBIT) {
            boolean playerShot = this.currentShotPreset.playerShot;
            double movementMultiplier = playerShot ? 0.9D : 1.0D;
            movementMultiplier *= 1.28D * this.motionSpeedMultiplier;
            double phase = (double)this.shotElapsedTicks * this.shotMotionSpeed * movementMultiplier;
            double lateralAmplitude = this.shotLateralAmplitude * this.motionAmplitudeMultiplier;
            double verticalAmplitude = this.shotVerticalAmplitude * this.motionAmplitudeMultiplier;
            Vec3d oscillation = new Vec3d(Math.sin(phase) * lateralAmplitude, Math.sin(phase * 0.82D + 1.4D) * verticalAmplitude, Math.cos(phase * 0.73D) * lateralAmplitude);
            Vec3d subtleShake = this.getSubtleShake((playerShot ? 0.95D : 1.28D) * this.motionShakeMultiplier);
            Vec3d desiredPosition = this.shotBaseCamera.add(this.shotRightAxis.multiply(oscillation.x)).add(this.shotUpAxis.multiply(oscillation.y)).add(subtleShake);
            Vec3d desiredFocus = this.shotBaseFocus.add(playerShot ? Vec3d.ZERO : this.shotRightAxis.multiply(Math.sin(phase * 0.55D) * 0.24D)).add(subtleShake.multiply(0.22D));
            desiredPosition = this.clampToPlayerRadius(player, desiredPosition);
            desiredFocus = this.clampToPlayerRadius(player, desiredFocus);
            desiredPosition = this.enforceAboveGround(player, desiredPosition, 0.95D);
            desiredFocus = this.enforceAboveGround(player, desiredFocus, 0.3D);
            double positionBlend = playerShot ? 0.04D : 0.07D;
            double focusBlend = playerShot ? 0.05D : 0.08D;
            this.cameraPosition = this.cameraPosition.lerp(desiredPosition, positionBlend);
            this.focusPosition = this.focusPosition.lerp(desiredFocus, focusBlend);
            this.cameraPosition = this.enforceAboveGround(player, this.cameraPosition, 0.95D);
            this.focusPosition = this.enforceAboveGround(player, this.focusPosition, 0.3D);
            this.cameraPosition = this.avoidBlockClipping(player, this.cameraPosition, this.focusPosition, 0.25D);
            this.cameraPosition = this.enforceAboveGround(player, this.cameraPosition, 0.95D);
            if (this.isShotOccluded(player, this.cameraPosition, this.focusPosition)) {
               ++this.occludedTicks;
               if (this.lastStableCameraPosition.lengthSquared() > 1.0E-4D && !this.isShotOccluded(player, this.lastStableCameraPosition, this.focusPosition)) {
                  this.cameraPosition = this.lastStableCameraPosition;
               }

               if (this.occludedTicks >= 6) {
                  this.shotTicksRemaining = 0;
                  this.chooseNextShot(client);
                  return;
               }
            } else {
               this.occludedTicks = 0;
               this.lastStableCameraPosition = this.cameraPosition;
            }

            Vec3d lookVector = this.focusPosition.subtract(this.cameraPosition);
            double horizontal = Math.sqrt(lookVector.x * lookVector.x + lookVector.z * lookVector.z);
            float targetYaw = (float)Math.toDegrees(Math.atan2(lookVector.z, lookVector.x)) - 90.0F;
            float targetPitch = (float)(-Math.toDegrees(Math.atan2(lookVector.y, horizontal)));
            targetPitch = MathHelper.clamp(targetPitch, -86.0F, 86.0F);
            this.cameraYaw = MathHelper.lerpAngleDegrees(playerShot ? 0.05F : 0.095F, this.cameraYaw, targetYaw);
            this.cameraPitch = MathHelper.lerp(playerShot ? 0.05F : 0.085F, this.cameraPitch, targetPitch);
            CinematicCameraRig.update(this.cameraPosition, this.cameraYaw, this.cameraPitch);
         } else {
            this.applyPlayerOrbitShot(client, player);
         }
      }
   }

   private void applyPlayerOrbitShot(Minecraft client, Player player) {
      this.orbitAngle += this.orbitSpeed * 0.9D * this.motionSpeedMultiplier;
      Vec3d chest = this.getPlayerChestPosition(player);
      Vec3d desiredPosition = chest.add(Math.cos(this.orbitAngle) * this.orbitRadius, this.orbitHeightOffset, Math.sin(this.orbitAngle) * this.orbitRadius).add(this.getSubtleShake(0.75D * this.motionShakeMultiplier));
      desiredPosition = this.clampToPlayerRadius(player, desiredPosition);
      desiredPosition = this.enforceAboveGround(player, desiredPosition, 0.9D);
      Vec3d desiredFocus = this.clampToPlayerRadius(player, chest);
      desiredFocus = this.enforceAboveGround(player, desiredFocus, 0.25D);
      this.cameraPosition = this.cameraPosition.lerp(desiredPosition, 0.038D);
      this.focusPosition = desiredFocus;
      this.cameraPosition = this.enforceAboveGround(player, this.cameraPosition, 0.9D);
      this.focusPosition = this.enforceAboveGround(player, this.focusPosition, 0.25D);
      this.cameraPosition = this.avoidBlockClipping(player, this.cameraPosition, this.focusPosition, 0.25D);
      this.cameraPosition = this.enforceAboveGround(player, this.cameraPosition, 0.9D);
      if (this.isShotOccluded(player, this.cameraPosition, this.focusPosition)) {
         ++this.occludedTicks;
         if (this.lastStableCameraPosition.lengthSquared() > 1.0E-4D && !this.isShotOccluded(player, this.lastStableCameraPosition, this.focusPosition)) {
            this.cameraPosition = this.lastStableCameraPosition;
         }

         if (this.occludedTicks >= 6) {
            this.shotTicksRemaining = 0;
            this.chooseNextShot(client);
            return;
         }
      } else {
         this.occludedTicks = 0;
         this.lastStableCameraPosition = this.cameraPosition;
      }

      Vec3d lookVector = this.focusPosition.subtract(this.cameraPosition);
      double horizontal = Math.sqrt(lookVector.x * lookVector.x + lookVector.z * lookVector.z);
      float targetYaw = (float)Math.toDegrees(Math.atan2(lookVector.z, lookVector.x)) - 90.0F;
      float targetPitch = (float)(-Math.toDegrees(Math.atan2(lookVector.y, horizontal)));
      targetPitch = MathHelper.clamp(targetPitch, -82.0F, 82.0F);
      this.cameraYaw = MathHelper.lerpAngleDegrees(0.045F, this.cameraYaw, targetYaw);
      this.cameraPitch = MathHelper.lerp(0.045F, this.cameraPitch, targetPitch);
      CinematicCameraRig.update(this.cameraPosition, this.cameraYaw, this.cameraPitch);
   }

   private Vec3d getSubtleShake(double intensity) {
      double t = (double)this.motionPhase;
      double x = Math.sin(t * 1.2D) * 0.014D + Math.sin(t * 2.2D + 0.7D) * 0.0055D;
      double y = Math.sin(t * 1.35D + 1.1D) * 0.01D + Math.sin(t * 2.4D + 0.3D) * 0.0038D;
      double z = Math.cos(t * 1.1D) * 0.014D + Math.cos(t * 2.0D + 1.0D) * 0.0055D;
      return (new Vec3d(x, y, z)).multiply(intensity);
   }

   private Vec3d getPlayerChestPosition(Player player) {
      return new Vec3d(player.position().add(0.0D, (double)player.getEyeHeight() * 0.62D, 0.0D));
   }

   private void easeFov(Minecraft client, float targetFov, float speed) {
      int currentFov = client.options.fov().get();
      int next = Math.round(MathHelper.lerp(speed, (float)currentFov, targetFov));
      client.options.fov().set(next);
   }

   private static enum MotionLevel {
      DEFAULT(1.0D, 1.0D, 1.0D),
      LOW(1.15D, 1.12D, 1.1D),
      MEDIUM(1.35D, 1.26D, 1.25D),
      HIGH(1.65D, 1.45D, 1.45D);

      private final double speedMultiplier;
      private final double amplitudeMultiplier;
      private final double shakeMultiplier;

      private MotionLevel(double speedMultiplier, double amplitudeMultiplier, double shakeMultiplier) {
         this.speedMultiplier = speedMultiplier;
         this.amplitudeMultiplier = amplitudeMultiplier;
         this.shakeMultiplier = shakeMultiplier;
      }

      private static CinematicDirector.MotionLevel from(String value) {
         if (value == null) {
            return DEFAULT;
         } else {
            String normalized = value.trim().toUpperCase(Locale.ROOT);

            for(CinematicDirector.MotionLevel level : values()) {
               if (level.name().equals(normalized)) {
                  return level;
               }
            }

            return DEFAULT;
         }
      }

      
   }

   private static enum ShotPreset {
      PLAYER_ORBIT(true),
      PLAYER_ORBIT_WIDE(true),
      PLAYER_HERO_HIGH_ORBIT(true),
      PLAYER_HERO_FRONT(true),
      PLAYER_HERO_CENTER_LOCK(true),
      PLAYER_HERO_CENTER_SWEEP(true),
      PLAYER_OVER_SHOULDER(true),
      PLAYER_SIDE_PROFILE(true),
      PLAYER_LOW_ANGLE(true),
      PLAYER_REAR_SILHOUETTE(true),
      PLAYER_CLOSEUP_LEFT(true),
      PLAYER_CLOSEUP_RIGHT(true),
      PLAYER_CLOSE_CENTER(true),
      PLAYER_CLOSE_CHEST(true),
      PLAYER_CLOSE_PROFILE_LEFT(true),
      PLAYER_CLOSE_PROFILE_RIGHT(true),
      PLAYER_CLOSE_OVERHEAD(true),
      PLAYER_CLOSE_SHOULDER_LEFT(true),
      PLAYER_CLOSE_SHOULDER_RIGHT(true),
      PLAYER_CLOSE_FACE_LOW(true),
      PLAYER_DUTCH_LEFT(true),
      PLAYER_DUTCH_RIGHT(true),
      PLAYER_ARC_HIGH(true),
      PLAYER_ARC_LOW(true),
      PLAYER_ULTRA_WIDE(true),
      PLAYER_NATURE_HERO_WIDE(true),
      PLAYER_NATURE_BACKDROP_LEFT(true),
      PLAYER_NATURE_BACKDROP_RIGHT(true),
      PLAYER_NATURE_RIDGE_HIGH(true),
      PLAYER_NATURE_VALLEY_PROFILE(true),
      PLAYER_NATURE_SKYLINE_PORTRAIT(true),
      PLAYER_CINEMA_FRONT_LONG(true),
      PLAYER_CINEMA_FRONT_LOW_WIDE(true),
      PLAYER_CINEMA_BACK_WIDE(true),
      PLAYER_CINEMA_SIDE_LEFT_WIDE(true),
      PLAYER_CINEMA_SIDE_RIGHT_WIDE(true),
      PLAYER_CINEMA_RIDGE_LEFT(true),
      PLAYER_CINEMA_RIDGE_RIGHT(true),
      PLAYER_CINEMA_HIGH_LEFT(true),
      PLAYER_CINEMA_HIGH_RIGHT(true),
      PLAYER_CINEMA_PORTRAIT_TOP(true),
      PLAYER_CINEMA_EPIC_DISTANCE(true),
      PLAYER_CINEMA_VALLEY_LEFT(true),
      PLAYER_CINEMA_VALLEY_RIGHT(true),
      PLAYER_CINEMA_FOREST_BACKDROP(true),
      PLAYER_CINEMA_MEADOW_PAN(true),
      PLAYER_CINEMA_MOUNTAIN_LOCK(true),
      PLAYER_MOVIE_TRACK_LEFT(true),
      PLAYER_MOVIE_TRACK_RIGHT(true),
      PLAYER_BACK_RIGHT(true),
      PLAYER_BACK_LEFT(true),
      PLAYER_HEAD_ON(true),
      PLAYER_SHOULDER_TRACK_RIGHT(true),
      PLAYER_SHOULDER_TRACK_LEFT(true),
      ENV_WIDE_NATURE(false),
      ENV_SKY_VIEW(false),
      ENV_ENTITY_GLANCE(false),
      ENV_HORIZON_SWEEP(false),
      ENV_TOP_DOWN(false),
      ENV_ESTABLISHING_WIDE(false),
      ENV_TREE_CANOPY(false),
      ENV_GROUND_DRIFT(false),
      ENV_RIDGE_LOOK(false),
      ENV_PAN_LEFT(false),
      ENV_PAN_RIGHT(false),
      ENV_SKYLINE_LOW(false),
      ENV_WILDLIFE_WATCH(false),
      ENV_CINEMA_REVEAL_LEFT(false),
      ENV_CINEMA_REVEAL_RIGHT(false),
      ENV_CRANE_UP_EPIC(false),
      ENV_ULTRA_WIDE_HORIZON(false),
      ENV_GIANT_RIDGE_ESTABLISH(false),
      ENV_REMOTE_PEAK_LOCK(false),
      ENV_PLAINS_CINEMA_LONG(false),
      ENV_SKYLINE_CROSSPAN(false),
      ENV_WIDE_SILHOUETTE_FIELD(false),
      ENV_RIVERBANK_LONG(false),
      ENV_CANYON_EDGE_WIDE(false),
      ENV_OVERWORLD_RIVER_VISTA(false),
      ENV_OVERWORLD_MOUNTAIN_CREST(false),
      ENV_OVERWORLD_FOREST_CANOPY_WIDE(false),
      ENV_OVERWORLD_PLAINS_PAN(false),
      ENV_NETHER_LAVA_RIDGE(false),
      ENV_NETHER_BASALT_CANYON(false),
      ENV_NETHER_FORTRESS_LOOK(false),
      ENV_NETHER_CRIMSON_SWEEP(false),
      ENV_END_SPIRE_WIDE(false),
      ENV_END_VOID_EDGE(false),
      ENV_END_OBSIDIAN_RING(false),
      ENV_END_GATEWAY_DRIFT(false),
      ENV_NATURE_WIDE_MOUNTAIN(false),
      ENV_NATURE_VALLEY_WIDE(false),
      ENV_NATURE_FOREST_WIDE(false),
      ENV_NATURE_RIDGE_SLOW(false),
      ENV_NATURE_RIVER_WIDE(false),
      ENV_NATURE_CLIFF_EDGE(false),
      ENV_NATURE_MEADOW_WIDE(false),
      ENV_CINEMA_ESTABLISH_TOP(false),
      ENV_LONG_LENS_PEAK(false),
      ENV_SUNRISE_HORIZON_GLOW(false),
      ENV_SUNRISE_WIDE_SKY(false),
      ENV_SUNRISE_GAZE(false),
      ENV_SUNSET_GOLDEN_RIM(false),
      ENV_SUNSET_LONG_HORIZON(false),
      ENV_SUNSET_GAZE(false),
      ENV_NOON_SKY_PEAK(false),
      ENV_NOON_WIDE_HEAT(false),
      ENV_NIGHT_MOONLINE(false),
      ENV_NIGHT_STARFIELD_WIDE(false),
      ENV_NIGHT_STARS(false),
      ENV_NIGHT_GLOW_TRACK(false),
      ENV_FOREGROUND_PARALLAX(false),
      ENV_VALLEY_DIP(false),
      ENV_OVERLOOK(false),
      ENV_FLYOVER_DIAGONAL(false),
      CAVE_CHAMBER_WIDE(false),
      CAVE_PASSAGE_TRACK(false),
      CAVE_WALL_DETAIL(false),
      CAVE_FLOOR_DRIFT(false),
      CAVE_CEILING_ARC(false),
      CAVE_GLOW_POCKET(false),
      CAVE_DEPTH_REVEAL(false),
      CAVE_WATER_EDGE(false);

      private final boolean playerShot;

      private ShotPreset(boolean playerShot) {
         this.playerShot = playerShot;
      }

      
   }
}
