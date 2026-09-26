package com.spunkyinsaan.afkcinematics;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AfkCinematicsSettingsScreen extends Screen {
    private final ClientEvents settings;
    private final Screen parent;

    AfkCinematicsSettingsScreen(ClientEvents settings, Screen parent) {
        super(Component.translatable("screen.afkcinematics.title"));
        this.settings = settings;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int top = this.height / 2 - 88;
        int width = 220;
        int left = center - width / 2;
        this.addRenderableWidget(Button.builder(enabledLabel(), button -> {
            settings.toggleEnabled();
            button.setMessage(enabledLabel());
        }).bounds(left, top, width, 20).build());
        this.addRenderableWidget(Button.builder(musicLabel(), button -> {
            settings.setMusicEnabled(!settings.isMusicEnabled());
            button.setMessage(musicLabel());
        }).bounds(left, top + 24, width, 20).build());
        this.addRenderableWidget(Button.builder(motionLabel(), button -> {
            settings.advanceMotionLevel();
            button.setMessage(motionLabel());
        }).bounds(left, top + 48, width, 20).build());
        this.addRenderableWidget(new AfkTimeSlider(left, top + 72, width, 20));
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.start_now"), button -> {
            settings.requestManualStart();
            this.onClose();
        }).bounds(left, top + 104, 106, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.open_music_folder"),
                button -> CustomMusicPack.openMusicFolder())
                .bounds(left, top + 128, width, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.afkcinematics.about"), button ->
                Util.getPlatform().openUri("https://modrinth.com/user/spunkyinsaan"))
                .bounds(left + 114, top + 104, 106, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(left, top + 152, width, 20).build());
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
                Component.literal(settings.getMotionLevel().displayName()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 112, 0xFFFFFF);
        Component openKey = Component.translatable("screen.afkcinematics.open_key",
                Component.literal(settings.getOpenSettingsKeyName()));
        graphics.drawCenteredString(this.font, openKey, this.width / 2, this.height / 2 - 96, 0xA0A0A0);
        Component toggleKey = Component.translatable("screen.afkcinematics.toggle_key",
                Component.literal(settings.getToggleEnabledKeyName()));
        graphics.drawCenteredString(this.font, toggleKey, this.width / 2, this.height / 2 - 86, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
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

        private static double secondsToValue(int seconds) {
            return (Math.max(5, Math.min(1800, seconds)) - 5.0) / 1795.0;
        }

        private static int valueToSeconds(double value) {
            return (int) Math.round(5.0 + value * 1795.0);
        }
    }
}
