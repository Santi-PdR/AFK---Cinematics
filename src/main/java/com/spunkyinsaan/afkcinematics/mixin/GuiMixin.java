package com.spunkyinsaan.afkcinematics.mixin;

import com.spunkyinsaan.afkcinematics.ClientEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void afkcinematics$renderCinematicOverlay(GuiGraphics graphics, float partialTick,
                                                       CallbackInfo ci) {
        ClientEvents.instance().renderCinematicOverlay(graphics);
    }
}
