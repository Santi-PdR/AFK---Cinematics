package com.spunkyinsaan.afkcinematics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CustomMusicPack {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MOD_ID = "afkcinematics";
    private static final String PACK_ID = "afkcinematics_custom_music";
    private static final Path CONFIG_DIRECTORY = FMLPaths.CONFIGDIR.get().resolve("afkcinematics");
    private static final Path MUSIC_DIRECTORY = CONFIG_DIRECTORY.resolve("music");
    private static final Path SYNC_DIRECTORY = CONFIG_DIRECTORY.resolve("server_music_cache");
    private static final Path PACK_DIRECTORY = CONFIG_DIRECTORY.resolve("music_resource_pack");
    private static volatile List<ResourceLocation> tracks = List.of();

    private CustomMusicPack() {}

    static void register(IEventBus modBus) {
        try {
            Files.createDirectories(MUSIC_DIRECTORY);
            Files.createDirectories(SYNC_DIRECTORY);
            prepareMusicPack();
        } catch (IOException exception) {
            LOGGER.error("Could not prepare the AFK Cinematics music folder", exception);
        }
        modBus.addListener(CustomMusicPack::addPackFinder);
    }

    static Path getMusicDirectory() {
        return MUSIC_DIRECTORY;
    }

    static void openMusicFolder() {
        try {
            Files.createDirectories(MUSIC_DIRECTORY);
            Util.getPlatform().openFile(MUSIC_DIRECTORY.toFile());
        } catch (IOException exception) {
            LOGGER.error("Could not open the AFK Cinematics music folder", exception);
        }
    }

    static List<ResourceLocation> getTracks() {
        return tracks;
    }

    static Music asMusic(ResourceLocation track) {
        SoundEvent sound = SoundEvent.createVariableRangeEvent(track);
        return new Music(Holder.direct(sound), 0, 0, true);
    }

    static ResourceLocation saveSynchronizedTrack(String trackName, byte[] data) throws IOException {
        if (trackName == null || !trackName.matches("server_[a-z0-9._-]{1,64}")) {
            throw new IOException("Invalid synchronized music filename");
        }
        Files.createDirectories(SYNC_DIRECTORY);
        Files.write(SYNC_DIRECTORY.resolve(trackName + ".ogg"), data);
        prepareMusicPack();
        return new ResourceLocation(MOD_ID, "custom/" + trackName);
    }

    private static void addPackFinder(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        try {
            prepareMusicPack();
            Pack pack = Pack.readMetaAndCreate(
                    PACK_ID,
                    Component.literal("AFK Cinematics Custom Music"),
                    true,
                    packId -> new PathPackResources(packId, PACK_DIRECTORY, true)
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    PackSource.BUILT_IN);
            if (pack != null) {
                event.addRepositorySource(consumer -> consumer.accept(pack));
            }
        } catch (IOException exception) {
            LOGGER.error("Could not load custom AFK Cinematics music", exception);
        }
    }

    private static void prepareMusicPack() throws IOException {
        Path musicAssets = PACK_DIRECTORY.resolve("assets").resolve(MOD_ID);
        Path customSoundsDirectory = musicAssets.resolve("sounds").resolve("custom");
        Files.createDirectories(MUSIC_DIRECTORY);
        Files.createDirectories(SYNC_DIRECTORY);
        Files.createDirectories(customSoundsDirectory);

        try (var generatedFiles = Files.list(customSoundsDirectory)) {
            generatedFiles.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            LOGGER.warn("Could not remove old generated music file {}", path, exception);
                        }
                    });
        }

        List<ResourceLocation> foundTracks = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        JsonObject soundsJson = new JsonObject();
        copyMusicFiles(MUSIC_DIRECTORY, "", customSoundsDirectory, soundsJson, foundTracks, usedNames);
        copyMusicFiles(SYNC_DIRECTORY, "", customSoundsDirectory, soundsJson, foundTracks, usedNames);

        Files.writeString(musicAssets.resolve("sounds.json"), GSON.toJson(soundsJson), StandardCharsets.UTF_8);
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        pack.addProperty("description", "AFK Cinematics synchronized music");
        JsonObject metadata = new JsonObject();
        metadata.add("pack", pack);
        Files.writeString(PACK_DIRECTORY.resolve("pack.mcmeta"), GSON.toJson(metadata), StandardCharsets.UTF_8);
        tracks = List.copyOf(foundTracks);
    }

    private static void copyMusicFiles(Path sourceDirectory, String prefix, Path outputDirectory,
                                       JsonObject soundsJson, List<ResourceLocation> foundTracks,
                                       Set<String> usedNames) throws IOException {
        try (var files = Files.list(sourceDirectory)) {
            List<Path> musicFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path musicFile : musicFiles) {
                String filename = musicFile.getFileName().toString();
                String basename = prefix + filename.substring(0, filename.length() - 4)
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
                if (basename.isBlank() || !usedNames.add(basename)) continue;

                String soundPath = "custom/" + basename;
                Files.copy(musicFile, outputDirectory.resolve(basename + ".ogg"),
                        StandardCopyOption.REPLACE_EXISTING);
                JsonObject sound = new JsonObject();
                sound.addProperty("name", MOD_ID + ":" + soundPath);
                sound.addProperty("stream", true);
                JsonArray variants = new JsonArray();
                variants.add(sound);
                JsonObject definition = new JsonObject();
                definition.add("sounds", variants);
                soundsJson.add(soundPath, definition);
                foundTracks.add(new ResourceLocation(MOD_ID, soundPath));
            }
        }
    }
}
