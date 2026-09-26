package com.spunkyinsaan.afkcinematics.mixin;

import com.spunkyinsaan.afkcinematics.CinematicCameraRig;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void afkcinematics$applyCinematicPose(BlockGetter level, Entity entity,
                                                   boolean detached, boolean thirdPersonReverse,
                                                   float partialTick, CallbackInfo ci) {
        if (!CinematicCameraRig.isActive()) return;
        var position = CinematicCameraRig.getInterpolatedPosition(partialTick);
        setPosition(position.x, position.y, position.z);
        setRotation(CinematicCameraRig.getInterpolatedYaw(partialTick),
                CinematicCameraRig.getInterpolatedPitch(partialTick));
    }
}
