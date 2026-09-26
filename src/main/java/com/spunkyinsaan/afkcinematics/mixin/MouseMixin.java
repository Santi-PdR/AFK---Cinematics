package com.spunkyinsaan.afkcinematics.mixin;

import com.spunkyinsaan.afkcinematics.ClientEvents;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {
    @Inject(method = "onMove", at = @At("HEAD"))
    private void afkcinematics$markCursorActivity(long window, double x, double y, CallbackInfo ci) {
        ClientEvents.markInputActivity();
    }

    @Inject(method = "onPress", at = @At("HEAD"))
    private void afkcinematics$markMouseActivity(long window, int button, int action,
                                                  int modifiers, CallbackInfo ci) {
        if (action != 0) ClientEvents.markInputActivity();
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void afkcinematics$markScrollActivity(long window, double xOffset, double yOffset,
                                                   CallbackInfo ci) {
        if (xOffset != 0.0 || yOffset != 0.0) ClientEvents.markInputActivity();
    }
}
