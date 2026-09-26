package com.spunkyinsaan.afkcinematics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side shot director. The source mod keeps all camera motion local and
 * never moves or rotates the actual player entity.
 */
final class CinematicDirector {
    private static final int SHOT_MIN_DURATION_TICKS = 100;
    private static final int SHOT_MAX_DURATION_TICKS = 120;
    private static final int MAX_OCCLUDED_TICKS = 6;
    private static final double MAX_RADIUS_FROM_PLAYER = 16.0;
    private static final double MIN_CAMERA_FOCUS_DISTANCE = 1.15;
    private static final double CAMERA_COLLISION_CLEARANCE = 0.85;
    private static final float TARGET_FOV = 30.0F;
    private static final double GLOBAL_MOTION_MULTIPLIER = 1.28;

    private boolean active;
    private int shotTicksRemaining;
    private int shotElapsedTicks;
    private int currentShotDurationTicks;
    private int occludedTicks;
    private int consecutivePlayerShots;
    private int consecutiveEnvironmentShots;
    private int restoreFov;
    private boolean restoreHudHidden;
    private CameraType restorePerspective;
    private double motionSpeedMultiplier = 1.0;
    private double motionAmplitudeMultiplier = 1.0;
    private double motionShakeMultiplier = 1.0;
    private double orbitAngle;
    private double orbitRadius = 3.3;
    private double orbitSpeed = 0.0045;
    private double orbitHeightOffset = 0.2;
    private double lateralAmplitude = 0.05;
    private double verticalAmplitude = 0.025;
    private double motionPhase;
    private int fadeInTicksRemaining;
    private int creditsTicksRemaining;
    private Vec3 baseCamera = Vec3.ZERO;
    private Vec3 baseFocus = Vec3.ZERO;
    private Vec3 rightAxis = new Vec3(1, 0, 0);
    private Vec3 upAxis = new Vec3(0, 1, 0);
    private Vec3 cameraPosition = Vec3.ZERO;
    private Vec3 focusPosition = Vec3.ZERO;
    private Vec3 lastStableCamera = Vec3.ZERO;

    boolean isActive() {
        return active;
    }

    void setMotionLevel(ClientEvents.MotionLevel level) {
        motionSpeedMultiplier = level.speedMultiplier();
        motionAmplitudeMultiplier = level.amplitudeMultiplier();
        motionShakeMultiplier = level.shakeMultiplier();
    }

    void start(Minecraft minecraft) {
        if (active || minecraft.player == null) return;
        Player player = minecraft.player;
        restoreFov = minecraft.options.fov().get();
        restoreHudHidden = minecraft.options.hideGui;
        restorePerspective = minecraft.options.getCameraType();
        active = true;
        fadeInTicksRemaining = 10;
        creditsTicksRemaining = 220;
        shotTicksRemaining = 0;
        shotElapsedTicks = 0;
        currentShotDurationTicks = SHOT_MIN_DURATION_TICKS;
        motionPhase = 0.0;
        consecutivePlayerShots = 0;
        consecutiveEnvironmentShots = 0;
        Vec3 chest = getPlayerChestPosition(player);
        cameraPosition = chest.add(0.0, 0.25, -3.2);
        focusPosition = chest;
        lastStableCamera = cameraPosition;
        occludedTicks = 0;
        minecraft.options.hideGui = true;
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        CinematicCameraRig.activate(cameraPosition, player.getYRot(), player.getXRot());
    }

    void stop(Minecraft minecraft) {
        if (!active) return;
        active = false;
        fadeInTicksRemaining = 0;
        creditsTicksRemaining = 0;
        shotTicksRemaining = 0;
        minecraft.options.fov().set(restoreFov);
        minecraft.options.hideGui = restoreHudHidden;
        minecraft.options.setCameraType(restorePerspective);
        CinematicCameraRig.deactivate();
    }

    void tick(Minecraft minecraft) {
        if (!active || minecraft.player == null || minecraft.level == null) return;
        motionPhase += 0.028F;
        if (fadeInTicksRemaining > 0) fadeInTicksRemaining--;
        if (creditsTicksRemaining > 0) creditsTicksRemaining--;
        easeFov(minecraft, TARGET_FOV, 0.06F);
        if (shotTicksRemaining <= 0) chooseNextShot(minecraft);
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        applyCurrentShot(minecraft);
        shotElapsedTicks++;
        shotTicksRemaining--;
    }

    void refreshAfterPassiveMovement(Minecraft minecraft) {
        if (!active || minecraft.player == null) return;
        Vec3 chest = getPlayerChestPosition(minecraft.player);
        shotTicksRemaining = 0;
        shotElapsedTicks = 0;
        currentShotDurationTicks = SHOT_MIN_DURATION_TICKS;
        cameraPosition = chest.add(0.0, 0.25, -3.2);
        focusPosition = chest;
        lastStableCamera = cameraPosition;
        occludedTicks = 0;
        CinematicCameraRig.cutTo(cameraPosition, minecraft.player.getYRot(), minecraft.player.getXRot());
        chooseNextShot(minecraft);
    }

    void renderOverlay(net.minecraft.client.gui.GuiGraphics graphics, int width, int height) {
        if (!active && creditsTicksRemaining <= 0) return;
        float fade = fadeInTicksRemaining > 0 ? 1.0F - fadeInTicksRemaining / 10.0F : 1.0F;
        int barHeight = Math.round(height * 0.075F * fade);
        if (barHeight > 0) {
            graphics.fill(0, 0, width, barHeight, 0xFF000000);
            graphics.fill(0, height - barHeight, width, height, 0xFF000000);
        }
        if (creditsTicksRemaining > 0) {
            int alpha = Math.min(255, Math.min((220 - creditsTicksRemaining) * 13, creditsTicksRemaining * 13));
            int color = (alpha << 24) | 0x00FFFFFF;
            String text = "Created By Spunky Insaan";
            graphics.drawString(Minecraft.getInstance().font, text,
                    (width - Minecraft.getInstance().font.width(text)) / 2,
                    height / 2 + height / 5, color, true);
        }
    }

    private void chooseNextShot(Minecraft minecraft) {
        Player player = minecraft.player;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean playerShot = consecutivePlayerShots < 3
                && (consecutiveEnvironmentShots >= 2 || random.nextInt(100) < 62);
        if (playerShot) {
            consecutivePlayerShots++;
            consecutiveEnvironmentShots = 0;
        } else {
            consecutiveEnvironmentShots++;
            consecutivePlayerShots = 0;
        }

        currentShotDurationTicks = random.nextInt(SHOT_MIN_DURATION_TICKS, SHOT_MAX_DURATION_TICKS + 1);
        shotElapsedTicks = 0;
        shotTicksRemaining = currentShotDurationTicks;
        occludedTicks = 0;
        orbitAngle = random.nextDouble(0.0, Math.PI * 2.0);
        Vec3 chest = getPlayerChestPosition(player);
        Vec3 forward = player.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        if (forward.lengthSqr() < 1.0E-4) forward = new Vec3(0, 0, 1);
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);

        if (playerShot) {
            configurePlayerShot(player, chest, forward, right, random.nextInt(12));
        } else {
            configureEnvironmentShot(player, chest, forward, right, random.nextInt(12));
        }

        baseCamera = clampToPlayerRadius(player, baseCamera);
        baseFocus = adaptForEnvironment(player, baseFocus);
        baseCamera = enforceAboveGround(player, baseCamera, 0.95);
        baseFocus = enforceAboveGround(player, baseFocus, 0.3);
        baseCamera = avoidBlockClipping(player, baseCamera, baseFocus, CAMERA_COLLISION_CLEARANCE);
        baseCamera = clampToPlayerRadius(player, baseCamera);
        Vec3 direction = baseFocus.subtract(baseCamera).normalize();
        if (direction.lengthSqr() < 1.0E-4) direction = new Vec3(0, 0, 1);
        rightAxis = new Vec3(-direction.z, 0, direction.x);
        if (rightAxis.lengthSqr() < 1.0E-4) rightAxis = new Vec3(1, 0, 0);
        rightAxis = rightAxis.normalize();
        upAxis = rightAxis.cross(direction).normalize();
        if (upAxis.lengthSqr() < 1.0E-4) upAxis = new Vec3(0, 1, 0);
        cameraPosition = baseCamera;
        focusPosition = baseFocus;
        lastStableCamera = cameraPosition;
    }

    private void configurePlayerShot(Player player, Vec3 chest, Vec3 forward, Vec3 right, int preset) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double sign = random.nextBoolean() ? 1.0 : -1.0;
        Vec3 face = chest.add(0, Math.max(0.05, player.getBbHeight() * 0.16), 0);
        Vec3 heldSide = chest.add(right.scale(sign * 0.55)).add(0, -0.28, 0);
        switch (preset) {
            case 0 -> { baseFocus = chest; orbitRadius = random.nextDouble(2.7, 4.8); orbitHeightOffset = random.nextDouble(-0.2, 0.55); }
            case 1 -> { baseFocus = chest; orbitRadius = random.nextDouble(9.0, 14.0); orbitHeightOffset = random.nextDouble(1.0, 2.8); }
            case 2 -> { baseFocus = face; orbitRadius = random.nextDouble(5.8, 9.6); orbitHeightOffset = random.nextDouble(3.2, 5.6); }
            case 3 -> { baseFocus = face; orbitRadius = random.nextDouble(1.7, 2.8); orbitHeightOffset = random.nextDouble(0.2, 0.8); }
            case 4 -> { baseFocus = chest.add(forward.scale(0.3)); orbitRadius = random.nextDouble(2.0, 3.2); orbitHeightOffset = random.nextDouble(0.4, 1.0); }
            case 5 -> { baseFocus = chest; orbitRadius = random.nextDouble(2.3, 3.8); orbitHeightOffset = random.nextDouble(1.0, 2.0); }
            case 6 -> { baseFocus = heldSide; orbitRadius = random.nextDouble(1.6, 2.8); orbitHeightOffset = random.nextDouble(-0.15, 0.55); }
            case 7 -> { baseFocus = chest; orbitRadius = random.nextDouble(3.6, 5.5); orbitHeightOffset = random.nextDouble(-1.0, -0.15); }
            case 8 -> { baseFocus = chest; orbitRadius = random.nextDouble(4.0, 6.8); orbitHeightOffset = random.nextDouble(0.8, 2.4); }
            case 9 -> { baseFocus = face; orbitRadius = random.nextDouble(2.0, 3.5); orbitHeightOffset = random.nextDouble(0.0, 0.6); }
            case 10 -> { baseFocus = chest; orbitRadius = random.nextDouble(6.5, 10.0); orbitHeightOffset = random.nextDouble(2.0, 4.2); }
            default -> { baseFocus = chest; orbitRadius = random.nextDouble(2.4, 4.0); orbitHeightOffset = random.nextDouble(-0.4, 0.3); }
        }
        orbitSpeed = random.nextDouble(0.0026, 0.0054);
        lateralAmplitude = 0.03;
        verticalAmplitude = 0.018;
        baseCamera = baseFocus.add(Math.cos(orbitAngle) * orbitRadius, orbitHeightOffset,
                Math.sin(orbitAngle) * orbitRadius);
        // Several hero/profile shots are deliberately framed from the direction the player faces.
        if (preset == 3 || preset == 4 || preset == 9) {
            baseCamera = baseFocus.add(forward.scale(orbitRadius)).add(right.scale(sign * 0.45))
                    .add(0, orbitHeightOffset, 0);
        }
        lateralAmplitude = 0.03;
        verticalAmplitude = 0.02;
    }

    private void configureEnvironmentShot(Player player, Vec3 chest, Vec3 forward, Vec3 right, int preset) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean underground = isUnderground(player);
        Vec3 nature = pickNatureTarget(player, chest, forward, right, random, underground);
        Vec3 nearby = pickNearbyEntityOrFallback(player, chest, random);
        Vec3 skyline = pickSkylinePoint(player, chest, forward, right, random, underground);
        Vec3 sky = pickSkyTopPoint(player, chest, forward, random);
        Vec3 caveWall = chest.add(forward.scale(4.0 + random.nextDouble(2.0))).add(0, 0.5, 0);
        Vec3 caveCeiling = chest.add(forward.scale(3.0)).add(0, 4.0 + random.nextDouble(2.0), 0);
        Vec3 caveFloor = chest.add(forward.scale(3.0)).add(0, -0.6, 0);
        Vec3 target = switch (preset) {
            case 0, 6, 7 -> nature;
            case 1, 10 -> sky;
            case 2 -> nearby;
            case 3, 4, 8, 9 -> skyline;
            case 5 -> underground ? caveCeiling : nature;
            case 11 -> underground ? caveWall : nearby;
            default -> nature;
        };
        baseFocus = target;
        double radius = switch (preset) {
            case 0, 4, 8 -> random.nextDouble(7.0, 12.0);
            case 1, 10 -> random.nextDouble(8.0, 14.0);
            case 2, 5, 11 -> random.nextDouble(3.0, 6.0);
            default -> random.nextDouble(5.0, 10.0);
        };
        orbitRadius = radius;
        orbitAngle = random.nextDouble(0, Math.PI * 2);
        orbitSpeed = random.nextDouble(0.0022, 0.0045);
        orbitHeightOffset = switch (preset) {
            case 1, 5, 8, 10 -> random.nextDouble(2.0, 6.0);
            case 3, 9 -> random.nextDouble(-0.4, 0.8);
            default -> random.nextDouble(0.4, 2.8);
        };
        baseCamera = baseFocus.add(Math.cos(orbitAngle) * radius, orbitHeightOffset,
                Math.sin(orbitAngle) * radius);
        if (preset == 4 || preset == 8) baseCamera = baseCamera.add(right.scale(random.nextBoolean() ? 3.0 : -3.0));
        if (preset == 5 && underground) baseFocus = caveCeiling;
        if (preset == 7 && underground) baseFocus = caveFloor;
        lateralAmplitude = 0.04;
        verticalAmplitude = 0.02;
    }

    private Vec3 pickNearbyEntityOrFallback(Player player, Vec3 chest, ThreadLocalRandom random) {
        for (Entity entity : player.level().getEntities(player,
                player.getBoundingBox().inflate(12.0),
                e -> e.isAlive() && !e.isSpectator() && e != player)) {
            if (entity.position().distanceToSqr(chest) > 144.0) continue;
            return entity.position().add(0, entity.getBbHeight() * 0.55, 0);
        }
        return chest.add(random.nextDouble(-7.0, 7.0), random.nextDouble(-1.0, 4.0),
                random.nextDouble(-7.0, 7.0));
    }

    private Vec3 pickNatureTarget(Player player, Vec3 chest, Vec3 forward, Vec3 right,
                                  ThreadLocalRandom random, boolean underground) {
        if (underground) {
            return chest.add(forward.scale(random.nextDouble(3.0, 8.0)))
                    .add(right.scale(random.nextDouble(-4.0, 4.0)))
                    .add(0, random.nextDouble(-2.0, 3.0), 0);
        }
        BlockPos center = player.blockPosition();
        for (int attempt = 0; attempt < 8; attempt++) {
            BlockPos candidate = center.offset(random.nextInt(-10, 11), 0, random.nextInt(-10, 11));
            BlockPos surface = player.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, candidate);
            if (!player.level().getBlockState(surface.below()).isAir()
                    && player.level().canSeeSky(surface)) {
                return Vec3.atCenterOf(surface).add(0, random.nextDouble(1.0, 5.0), 0);
            }
        }
        return chest.add(forward.scale(8.0)).add(right.scale(random.nextDouble(-4.0, 4.0)))
                .add(0, random.nextDouble(1.0, 4.0), 0);
    }

    private Vec3 pickSkylinePoint(Player player, Vec3 chest, Vec3 forward, Vec3 right,
                                   ThreadLocalRandom random, boolean underground) {
        if (underground) return chest.add(forward.scale(6.0)).add(right.scale(random.nextDouble(-3.0, 3.0)));
        long dayTime = player.level().getDayTime() % 24000L;
        double direction = dayTime < 1000 || dayTime > 23000 ? -1.0 :
                dayTime > 12000 && dayTime < 14000 ? 1.0 : (random.nextBoolean() ? -1.0 : 1.0);
        return chest.add(forward.scale(random.nextDouble(7.0, 12.0)))
                .add(right.scale(direction * random.nextDouble(2.0, 8.0)))
                .add(0, random.nextDouble(1.0, 4.0), 0);
    }

    private Vec3 pickSkyTopPoint(Player player, Vec3 chest, Vec3 forward, ThreadLocalRandom random) {
        long dayTime = player.level().getDayTime() % 24000L;
        double y = dayTime > 13000 && dayTime < 23000 ? 14.0 : 9.0;
        return chest.add(forward.scale(random.nextDouble(4.0, 8.0))).add(0, y, 0);
    }

    private Vec3 adaptForEnvironment(Player player, Vec3 target) {
        if (isUnderground(player)) {
            BlockPos pos = BlockPos.containing(target);
            if (player.level().canSeeSky(pos)) return target;
            return target.add(0, 0.25, 0);
        }
        return target;
    }

    private boolean isUnderground(Player player) {
        return !player.level().canSeeSky(player.blockPosition());
    }

    private Vec3 clampToPlayerRadius(Player player, Vec3 point) {
        Vec3 origin = player.position().add(0, player.getBbHeight() * 0.55, 0);
        Vec3 relative = point.subtract(origin);
        return relative.lengthSqr() <= MAX_RADIUS_FROM_PLAYER * MAX_RADIUS_FROM_PLAYER
                ? point : origin.add(relative.normalize().scale(MAX_RADIUS_FROM_PLAYER));
    }

    private Vec3 enforceAboveGround(Player player, Vec3 point, double clearance) {
        if (isUnderground(player)) return point;
        BlockPos ground = player.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(point));
        double minY = ground.getY() + clearance;
        return point.y < minY ? new Vec3(point.x, minY, point.z) : point;
    }

    private Vec3 avoidBlockClipping(Player player, Vec3 camera, Vec3 focus, double clearance) {
        BlockHitResult hit = player.level().clip(new ClipContext(focus, camera,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return camera;
        Vec3 direction = camera.subtract(focus).normalize();
        return hit.getLocation().subtract(direction.scale(clearance));
    }

    private void applyCurrentShot(Minecraft minecraft) {
        Player player = minecraft.player;
        if (player == null) return;
        double elapsed = shotElapsedTicks;
        double angle = orbitAngle + elapsed * orbitSpeed * motionSpeedMultiplier * GLOBAL_MOTION_MULTIPLIER;
        Vec3 radial = new Vec3(Math.cos(angle) * orbitRadius, 0, Math.sin(angle) * orbitRadius);
        Vec3 candidate = baseFocus.add(radial).add(0, orbitHeightOffset, 0);
        double wave = motionPhase + elapsed * 0.04 * motionSpeedMultiplier;
        candidate = candidate.add(rightAxis.scale(Math.sin(wave) * lateralAmplitude * motionAmplitudeMultiplier))
                .add(upAxis.scale(Math.cos(wave * 0.73) * verticalAmplitude * motionAmplitudeMultiplier));
        candidate = clampToPlayerRadius(player, candidate);
        candidate = avoidBlockClipping(player, candidate, baseFocus, CAMERA_COLLISION_CLEARANCE);
        if (candidate.distanceToSqr(baseFocus) < MIN_CAMERA_FOCUS_DISTANCE * MIN_CAMERA_FOCUS_DISTANCE) {
            candidate = lastStableCamera;
        }
        lastStableCamera = candidate;
        cameraPosition = candidate;

        Vec3 focus = baseFocus.add(rightAxis.scale(Math.sin(wave * 0.61) * 0.035));
        Vec3 direction = focus.subtract(cameraPosition);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        double shake = 0.006 * motionShakeMultiplier;
        yaw += (float) (Math.sin(wave * 1.9) * shake);
        pitch += (float) (Math.cos(wave * 1.5) * shake);
        CinematicCameraRig.update(cameraPosition, yaw, Mth.clamp(pitch, -89.0F, 89.0F));
    }

    private Vec3 getPlayerChestPosition(Player player) {
        return player.position().add(0, player.getBbHeight() * 0.55, 0);
    }

    private void easeFov(Minecraft minecraft, float target, float amount) {
        int current = minecraft.options.fov().get();
        int next = Math.round(Mth.lerp(amount, current, target));
        if (next != current) minecraft.options.fov().set(next);
    }
}
