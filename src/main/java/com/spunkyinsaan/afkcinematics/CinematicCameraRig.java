package com.spunkyinsaan.afkcinematics;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Interpolated camera pose consumed by the Camera mixin after vanilla setup. */
public final class CinematicCameraRig {
    private static boolean active;
    private static Vec3 previousPosition = Vec3.ZERO;
    private static Vec3 currentPosition = Vec3.ZERO;
    private static float previousYaw;
    private static float currentYaw;
    private static float previousPitch;
    private static float currentPitch;

    private CinematicCameraRig() {}

    public static boolean isActive() {
        return active;
    }

    public static Vec3 getInterpolatedPosition(float partialTick) {
        return previousPosition.lerp(currentPosition, Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    public static float getInterpolatedYaw(float partialTick) {
        return Mth.rotLerp(Mth.clamp(partialTick, 0.0F, 1.0F), previousYaw, currentYaw);
    }

    public static float getInterpolatedPitch(float partialTick) {
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), previousPitch, currentPitch);
    }

    public static void activate(Vec3 position, float yaw, float pitch) {
        active = true;
        previousPosition = currentPosition = position;
        previousYaw = currentYaw = yaw;
        previousPitch = currentPitch = pitch;
    }

    public static void update(Vec3 position, float yaw, float pitch) {
        if (!active) return;
        previousPosition = currentPosition;
        previousYaw = currentYaw;
        previousPitch = currentPitch;
        currentPosition = position;
        currentYaw = yaw;
        currentPitch = pitch;
    }

    public static void cutTo(Vec3 position, float yaw, float pitch) {
        if (!active) return;
        previousPosition = currentPosition = position;
        previousYaw = currentYaw = yaw;
        previousPitch = currentPitch = pitch;
    }

    public static void deactivate() {
        active = false;
    }
}
