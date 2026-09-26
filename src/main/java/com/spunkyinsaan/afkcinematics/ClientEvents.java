package com.spunkyinsaan.afkcinematics;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    private static final ClientEvents INSTANCE = new ClientEvents();
    private static final int DEFAULT_AFK_TICKS = 500;

    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.afkcinematics.open_settings", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.afkcinematics");
    private static final KeyMapping TOGGLE_ENABLED = new KeyMapping(
            "key.afkcinematics.toggle_enabled", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, "key.categories.afkcinematics");

    private boolean enabled = true;
    private int afkTimeoutTicks = DEFAULT_AFK_TICKS;
    private int inactivityTicks;

    private ClientEvents() {}

    static void register() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ClientEvents::registerKeyMappings);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
        event.register(TOGGLE_ENABLED);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.isPaused()) {
            inactivityTicks = 0;
            return;
        }

        while (OPEN_SETTINGS.consumeClick()) {
            minecraft.setScreen(new AfkCinematicsSettingsScreen(this));
        }
        while (TOGGLE_ENABLED.consumeClick()) {
            enabled = !enabled;
            inactivityTicks = 0;
        }
        if (!enabled) return;
        inactivityTicks = Math.min(inactivityTicks + 1, afkTimeoutTicks);
    }

    boolean isEnabled() {
        return enabled;
    }

    void toggleEnabled() {
        enabled = !enabled;
        inactivityTicks = 0;
    }

    int getAfkTimeoutSeconds() {
        return afkTimeoutTicks / 20;
    }

    void setAfkTimeoutSeconds(int seconds) {
        afkTimeoutTicks = Math.max(5, Math.min(600, seconds)) * 20;
        inactivityTicks = 0;
    }

    void requestManualStart() {
        inactivityTicks = afkTimeoutTicks;
    }

    boolean isCinematicActive() {
        return enabled && inactivityTicks >= afkTimeoutTicks;
    }
}
