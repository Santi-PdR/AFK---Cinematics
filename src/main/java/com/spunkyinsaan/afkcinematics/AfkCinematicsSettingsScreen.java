package com.spunkyinsaan.afkcinematics;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
        int top = this.height / 4;
        this.addRenderableWidget(Button.builder(enabledLabel(), button -> {
            settings.toggleEnabled();
            button.setMessage(enabledLabel());
        }).bounds(center - 100, top, 200, 20).build());
        this.addRenderableWidget(Button.builder(musicLabel(), button -> {
            settings.setMusicEnabled(!settings.isMusicEnabled());
            button.setMessage(musicLabel());
        }).bounds(center - 100, top + 26, 200, 20).build());
        this.addRenderableWidget(Button.builder(motionLabel(), button -> {
            settings.advanceMotionLevel();
            button.setMessage(motionLabel());
        }).bounds(center - 100, top + 52, 200, 20).build());
        this.addRenderableWidget(new AfkTimeSlider(center - 100, top + 78, 200, 20));
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.start_now"), button -> {
            settings.requestManualStart();
            this.onClose();
        }).bounds(center - 100, top + 112, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.about"), button ->
                Util.getPlatform().openUri("https://modrinth.com/user/spunkyinsaan"))
                .bounds(center - 100, top + 138, 98, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(center + 2, top + 138, 98, 20).build());
    }

    private Component enabledLabel() {
        return Component.translatable("screen.afkcinematics.enabled",
                Component.translatable(settings.isEnabled() ? "screen.afkcinematics.on" : "screen.afkcinematics.off"));
    }

    private Component musicLabel() {
        return Component.translatable("screen.afkcinematics.music",
                Component.translatable(settings.isMusicEnabled() ? "screen.afkcinematics.on" : "screen.afkcinematics.off"));
    }

    private Component motionLabel() {
        return Component.translatable("screen.afkcinematics.motion",
                Component.literal(settings.getMotionLevel().name()));
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

    private final class AfkTimeSlider extends AbstractSliderButton {
        private AfkTimeSlider(int x, int y, int width, int height) {
            super(x, y, width, height,
                    Component.translatable("screen.afkcinematics.afk_time", settings.getAfkTimeoutSeconds()),
                    secondsToValue(settings.getAfkTimeoutSeconds()));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("screen.afkcinematics.afk_time",
                    valueToSeconds(this.value)));
        }

        @Override
        protected void applyValue() {
            settings.setAfkTimeoutSeconds(valueToSeconds(this.value));
        }

        private double secondsToValue(int seconds) {
            return (Math.max(5, Math.min(1800, seconds)) - 5.0) / 1795.0;
        }

        private int valueToSeconds(double value) {
            return (int) Math.round(5.0 + value * 1795.0);
        }
    }
}
