package com.spunkyinsaan.afkcinematics;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

final class ServerSettings {
    record Snapshot(boolean enabled, int afkTimeoutSeconds, boolean musicEnabled, String motionLevel) {
        Snapshot normalized() {
            String motion = motionLevel == null ? "default" : motionLevel.trim().toLowerCase(Locale.ROOT);
            if (!motion.equals("low") && !motion.equals("medium") && !motion.equals("high")) motion = "default";
            return new Snapshot(enabled, Math.max(5, Math.min(36000, afkTimeoutSeconds)), musicEnabled, motion);
        }
    }

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("afkcinematics-server.properties");
    private static volatile Snapshot current = new Snapshot(true, 25, true, "default");

    private ServerSettings() {}

    static void initialize() {
        Properties properties = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                properties.load(reader);
                current = new Snapshot(
                        Boolean.parseBoolean(properties.getProperty("enabled", "true")),
                        Integer.parseInt(properties.getProperty("afk_timeout_seconds", "25")),
                        Boolean.parseBoolean(properties.getProperty("music_enabled", "true")),
                        properties.getProperty("motion_level", "default")).normalized();
            } catch (IOException | RuntimeException exception) {
                current = new Snapshot(true, 25, true, "default");
            }
        }
        save();
    }

    static Snapshot get() {
        return current;
    }

    static void set(Snapshot settings) {
        current = settings.normalized();
        save();
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(current.enabled()));
        properties.setProperty("afk_timeout_seconds", Integer.toString(current.afkTimeoutSeconds()));
        properties.setProperty("music_enabled", Boolean.toString(current.musicEnabled()));
        properties.setProperty("motion_level", current.motionLevel());
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                properties.store(writer, "AFK Cinematics server settings");
            }
        } catch (IOException ignored) {
            // Server can still run with the in-memory settings.
        }
    }
}
