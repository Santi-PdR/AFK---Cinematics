package com.spunkyinsaan.afkcinematics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AfkCinematicsSettingsScreen extends Screen {
    private final ClientEvents settings;

    AfkCinematicsSettingsScreen(ClientEvents settings) {
        super(Component.translatable("screen.afkcinematics.title"));
        this.settings = settings;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int y = this.height / 4;
        this.addRenderableWidget(Button.builder(enabledLabel(), button -> {
            settings.toggleEnabled();
            button.setMessage(enabledLabel());
        }).bounds(center - 100, y, 200, 20).build());
        this.addRenderableWidget(Button.builder(motionLabel(), button -> {})
                .bounds(center - 100, y + 28, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.afk_time", settings.getAfkTimeoutSeconds()), button -> {
            int next = settings.getAfkTimeoutSeconds() >= 600 ? 5 : settings.getAfkTimeoutSeconds() + 5;
            settings.setAfkTimeoutSeconds(next);
            button.setMessage(Component.translatable("screen.afkcinematics.afk_time", next));
        }).bounds(center - 100, y + 56, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.start_now"), button -> {
            settings.requestManualStart();
            this.onClose();
        }).bounds(center - 100, y + 84, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(center - 100, y + 120, 200, 20).build());
    }

    private Component enabledLabel() {
        return Component.translatable("screen.afkcinematics.enabled",
                Component.translatable(settings.isEnabled() ? "screen.afkcinematics.on" : "screen.afkcinematics.off"));
    }

    private Component motionLabel() {
        return Component.translatable("screen.afkcinematics.motion", Component.literal("DEFAULT"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
