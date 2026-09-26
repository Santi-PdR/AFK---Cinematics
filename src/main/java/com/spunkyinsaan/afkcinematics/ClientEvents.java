package com.spunkyinsaan.afkcinematics;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.Musics;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientEvents {
    private static final ClientEvents INSTANCE = new ClientEvents();
    private static final int DEFAULT_AFK_TICKS = 500;
    private static final double MOVEMENT_EPSILON_SQUARED = 1.0E-4;
    private static final double PASSIVE_REPOSITION_LIMIT_SQUARED = 4.0;

    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.afkcinematics.open_settings", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.afkcinematics");
    private static final KeyMapping TOGGLE_ENABLED = new KeyMapping(
            "key.afkcinematics.toggle_enabled", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, "key.categories.afkcinematics");

    private final CinematicDirector director = new CinematicDirector();
    private boolean enabled = true;
    private boolean musicEnabled = true;
    private MotionLevel motionLevel = MotionLevel.DEFAULT;
    private int afkTimeoutTicks = DEFAULT_AFK_TICKS;
    private int inactivityTicks;
    private int startGraceTicks;
    private int passiveMovementTicks;
    private boolean activityPending;
    private boolean cinematicActive;
    private boolean cinematicMusicActive;
    private int lastMusicIndex = -1;
    private Float restoreMusicVolume;
    private Float fadeStartMusicVolume;
    private int musicFadeOutTicksRemaining;
    private boolean forceStartRequested;
    private Vec3 lastPosition;

    private ClientEvents() {
        loadConfig();
        director.setMotionLevel(motionLevel);
    }

    static void register() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ClientEvents::registerKeyMappings);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    public static ClientEvents instance() { return INSTANCE; }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
        event.register(TOGGLE_ENABLED);
    }

    public static void markInputActivity() {
        INSTANCE.activityPending = true;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_RELEASE) markInputActivity();
    }

    @SubscribeEvent
    public void onMouseButton(InputEvent.MouseButton event) {
        if (event.getAction() != GLFW.GLFW_RELEASE) markInputActivity();
    }

    @SubscribeEvent
    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (event.getScrollDelta() != 0) markInputActivity();
    }

    @SubscribeEvent
    public void onTitleScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null || minecraft.getWindow() == null) return;
        String text = "AFK Cinematics By Spunky Insaan";
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int textWidth = minecraft.font.width(text);
        int x = width - textWidth - 4;
        int y = height - 22;
        boolean hovered = event.getMouseX() >= x && event.getMouseX() <= x + textWidth
                && event.getMouseY() >= y && event.getMouseY() <= y + 10;
        event.getGuiGraphics().drawString(minecraft.font, text, x, y,
                hovered ? 0xA0FFFFFF : 0xFFFFFFFF, true);
    }

    @SubscribeEvent
    public void onTitleScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof TitleScreen) || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_1) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null || minecraft.getWindow() == null) return;
        String text = "AFK Cinematics By Spunky Insaan";
        int x = minecraft.getWindow().getGuiScaledWidth() - minecraft.font.width(text) - 4;
        int y = minecraft.getWindow().getGuiScaledHeight() - 22;
        if (event.getMouseX() >= x && event.getMouseX() <= x + minecraft.font.width(text)
                && event.getMouseY() >= y && event.getMouseY() <= y + 10) {
            Util.getPlatform().openUri("https://modrinth.com/user/spunkyinsaan");
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        tickMusicFadeOut(minecraft);
        if (minecraft.player == null || minecraft.level == null || minecraft.isPaused()) {
            stopDirector(minecraft);
            inactivityTicks = 0;
            lastPosition = null;
            activityPending = false;
            return;
        }

        while (OPEN_SETTINGS.consumeClick()) minecraft.setScreen(new AfkCinematicsSettingsScreen(this));
        while (TOGGLE_ENABLED.consumeClick()) setEnabled(!enabled);

        Vec3 position = minecraft.player.position();
        double movementSquared = lastPosition == null ? 0.0 : position.distanceToSqr(lastPosition);
        boolean moved = movementSquared > MOVEMENT_EPSILON_SQUARED;
        boolean input = activityPending;
        activityPending = false;

        if (!enabled || minecraft.screen != null) {
            inactivityTicks = 0;
            stopDirector(minecraft);
        } else if (forceStartRequested) {
            forceStartRequested = false;
            inactivityTicks = afkTimeoutTicks;
            startDirector(minecraft);
        } else if (cinematicActive && input) {
            inactivityTicks = 0;
            stopDirector(minecraft);
        } else if (cinematicActive && moved) {
            if (startGraceTicks > 0 && movementSquared <= 0.36) {
                startGraceTicks--;
            } else {
                passiveMovementTicks++;
                if (movementSquared >= PASSIVE_REPOSITION_LIMIT_SQUARED || passiveMovementTicks >= 5) {
                    director.refreshAfterPassiveMovement(minecraft);
                    passiveMovementTicks = 0;
                    startGraceTicks = 60;
                }
            }
        } else if (input || moved) {
            inactivityTicks = 0;
        } else {
            inactivityTicks = Math.min(inactivityTicks + 1, afkTimeoutTicks);
            if (!cinematicActive && inactivityTicks >= afkTimeoutTicks) startDirector(minecraft);
        }

        if (cinematicActive) director.tick(minecraft);
        lastPosition = position;
        if (startGraceTicks > 0 && !moved) startGraceTicks--;
    }

    public void renderCinematicOverlay(net.minecraft.client.gui.GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() != null) {
            director.renderOverlay(graphics, minecraft.getWindow().getGuiScaledWidth(),
                    minecraft.getWindow().getGuiScaledHeight());
        }
    }

    private void startDirector(Minecraft minecraft) {
        if (!enabled || cinematicActive) return;
        cinematicActive = true;
        startGraceTicks = 60;
        passiveMovementTicks = 0;
        director.setMotionLevel(motionLevel);
        director.start(minecraft);
        playRandomCinematicMusic(minecraft);
    }

    private void stopDirector(Minecraft minecraft) {
        if (!cinematicActive && !director.isActive()) return;
        cinematicActive = false;
        startGraceTicks = 0;
        passiveMovementTicks = 0;
        director.stop(minecraft);
        fadeOutCinematicMusic(minecraft);
    }

    boolean isEnabled() { return enabled; }
    boolean isMusicEnabled() { return musicEnabled; }
    MotionLevel getMotionLevel() { return motionLevel; }
    int getAfkTimeoutSeconds() { return afkTimeoutTicks / 20; }
    boolean isCinematicActive() { return cinematicActive; }

    void setEnabled(boolean value) {
        enabled = value;
        inactivityTicks = 0;
        if (!value) cinematicActive = false;
        saveConfig();
    }

    void toggleEnabled() { setEnabled(!enabled); }

    void setMusicEnabled(boolean value) {
        musicEnabled = value;
        if (!value) fadeOutCinematicMusic(Minecraft.getInstance());
        saveConfig();
    }

    void advanceMotionLevel() {
        motionLevel = MotionLevel.values()[(motionLevel.ordinal() + 1) % MotionLevel.values().length];
        director.setMotionLevel(motionLevel);
        saveConfig();
    }

    void setAfkTimeoutSeconds(int seconds) {
        afkTimeoutTicks = Math.max(5, Math.min(1800, seconds)) * 20;
        inactivityTicks = 0;
        saveConfig();
    }

    void requestManualStart() {
        if (enabled) forceStartRequested = true;
    }

    private void playRandomCinematicMusic(Minecraft minecraft) {
        if (!musicEnabled || minecraft.level == null) return;
        cancelMusicFadeOut(minecraft);
        stopBackgroundMusic(minecraft);
        Music[] pool = {Musics.GAME, Musics.CREATIVE, Musics.END, Musics.UNDER_WATER};
        int index = java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.length);
        if (index == lastMusicIndex) index = (index + 1
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.length - 1)) % pool.length;
        lastMusicIndex = index;
        ensureMusicAudible(minecraft);
        minecraft.getMusicManager().startPlaying(pool[index]);
        cinematicMusicActive = true;
    }

    private void fadeOutCinematicMusic(Minecraft minecraft) {
        if (!cinematicMusicActive) return;
        if (musicFadeOutTicksRemaining <= 0) {
            fadeStartMusicVolume = minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
            musicFadeOutTicksRemaining = 20;
        }
    }

    private void tickMusicFadeOut(Minecraft minecraft) {
        if (!cinematicMusicActive || musicFadeOutTicksRemaining <= 0) return;
        float startVolume = fadeStartMusicVolume != null ? fadeStartMusicVolume
                : minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
        musicFadeOutTicksRemaining--;
        float remaining = musicFadeOutTicksRemaining / 20.0F;
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set((double) (startVolume * remaining));
        if (musicFadeOutTicksRemaining <= 0) {
            stopBackgroundMusic(minecraft);
            restoreMusicVolume(minecraft);
            cinematicMusicActive = false;
            fadeStartMusicVolume = null;
        }
    }

    private void cancelMusicFadeOut(Minecraft minecraft) {
        if (musicFadeOutTicksRemaining <= 0) return;
        musicFadeOutTicksRemaining = 0;
        if (fadeStartMusicVolume != null) {
            minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set((double) fadeStartMusicVolume);
            fadeStartMusicVolume = null;
        } else {
            restoreMusicVolume(minecraft);
        }
    }

    private void stopCinematicMusic(Minecraft minecraft) {
        if (!cinematicMusicActive) return;
        musicFadeOutTicksRemaining = 0;
        stopBackgroundMusic(minecraft);
        restoreMusicVolume(minecraft);
        cinematicMusicActive = false;
        fadeStartMusicVolume = null;
    }

    private void stopBackgroundMusic(Minecraft minecraft) {
        minecraft.getMusicManager().stopPlaying();
    }

    private void ensureMusicAudible(Minecraft minecraft) {
        if (restoreMusicVolume == null) {
            restoreMusicVolume = minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
        }
        if (restoreMusicVolume < 1.0E-4F) {
            minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(1.0D);
        }
    }

    private void restoreMusicVolume(Minecraft minecraft) {
        if (restoreMusicVolume == null) return;
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set((double) restoreMusicVolume);
        restoreMusicVolume = null;
    }

    private void loadConfig() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("afkcinematics.properties");
        if (!Files.exists(path)) {
            saveConfig();
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            afkTimeoutTicks = Math.max(1, Math.min(36000,
                    Integer.parseInt(properties.getProperty("afk_timeout_seconds", "25")))) * 20;
            enabled = Boolean.parseBoolean(properties.getProperty("afk_cinematics_enabled", "true"));
            musicEnabled = Boolean.parseBoolean(properties.getProperty("cinematic_music_enabled", "true"));
            motionLevel = MotionLevel.from(properties.getProperty("cinematic_motion_level", "default"));
        } catch (IOException | RuntimeException ignored) {
            afkTimeoutTicks = DEFAULT_AFK_TICKS;
            enabled = true;
            musicEnabled = true;
            motionLevel = MotionLevel.DEFAULT;
        }
    }

    private void saveConfig() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("afkcinematics.properties");
        Properties properties = new Properties();
        properties.setProperty("afk_timeout_seconds", Integer.toString(getAfkTimeoutSeconds()));
        properties.setProperty("afk_cinematics_enabled", Boolean.toString(enabled));
        properties.setProperty("cinematic_music_enabled", Boolean.toString(musicEnabled));
        properties.setProperty("cinematic_motion_level", motionLevel.name().toLowerCase(java.util.Locale.ROOT));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "AFK Cinematics settings");
            }
        } catch (IOException ignored) {
            // Keep the client usable if the config directory is read-only.
        }
    }

    enum MotionLevel {
        DEFAULT, LOW, MEDIUM, HIGH;

        static MotionLevel from(String value) {
            if (value == null) return DEFAULT;
            try { return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return DEFAULT; }
        }

        String displayName() {
            return switch (this) {
                case DEFAULT -> "Default";
                case LOW -> "Low";
                case MEDIUM -> "Medium";
                case HIGH -> "High";
            };
        }

        double speedMultiplier() {
            return switch (this) {
                case DEFAULT -> 1.0;
                case LOW -> 1.15;
                case MEDIUM -> 1.35;
                case HIGH -> 1.65;
            };
        }

        double amplitudeMultiplier() {
            return switch (this) {
                case DEFAULT -> 1.0;
                case LOW -> 1.12;
                case MEDIUM -> 1.26;
                case HIGH -> 1.45;
            };
        }

        double shakeMultiplier() {
            return switch (this) {
                case DEFAULT -> 1.0;
                case LOW -> 1.1;
                case MEDIUM -> 1.25;
                case HIGH -> 1.45;
            };
        }
    }
}
