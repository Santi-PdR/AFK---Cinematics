package com.spunkyinsaan.afkcinematics.mixin;

import com.spunkyinsaan.afkcinematics.ClientEvents;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void afkcinematics$markKeyActivity(long window, int key, int scanCode,
                                                int action, int modifiers, CallbackInfo ci) {
        if (action != 0) ClientEvents.markInputActivity();
    }
}
