package com.spunkyinsaan.afkcinematics;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class ClientMusicNetwork {
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int MAX_TRACK_BYTES = 32 * 1024 * 1024;
    private static final Map<String, IncomingTrack> incomingTracks = new HashMap<>();
    private static final Set<String> loadedTracks = new HashSet<>();
    private static String pendingTrack;
    private static boolean musicSyncSent;
    private static ResourceLocation lastSingleplayerTrack;

    private ClientMusicNetwork() {}

    static void register() {
        MinecraftForge.EVENT_BUS.register(ClientMusicNetwork.class);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        incomingTracks.clear();
        loadedTracks.clear();
        pendingTrack = null;
        musicSyncSent = false;
    }

    static void applySettings(ServerSettings.Snapshot settings) {
        ClientEvents.instance().applyServerSettings(settings);
        Minecraft minecraft = Minecraft.getInstance();
        if (!musicSyncSent && canManageServerMusic(minecraft)) {
            musicSyncSent = true;
            if (minecraft.getSingleplayerServer() == null) MusicNetwork.sendHostMusic();
        }
    }

    static void sendHostSettings(ServerSettings.Snapshot settings) {
        MusicNetwork.sendHostSettings(settings);
    }

    static void sendHostState(boolean active) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            if (active) startSingleplayerMusic();
            else ClientEvents.instance().stopSynchronizedMusic();
        }
        MusicNetwork.sendHostState(active);
    }

    private static void startSingleplayerMusic() {
        if (!ClientEvents.instance().isMusicEnabled()) return;
        List<ResourceLocation> tracks = CustomMusicPack.getLocalTracks();
        if (tracks.isEmpty()) return;
        List<ResourceLocation> choices = tracks;
        if (lastSingleplayerTrack != null && tracks.size() > 1) {
            choices = tracks.stream().filter(track -> !track.equals(lastSingleplayerTrack)).toList();
        }
        lastSingleplayerTrack = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        ClientEvents.instance().startSynchronizedMusic(lastSingleplayerTrack);
    }

    private static boolean canManageServerMusic(Minecraft minecraft) {
        return minecraft.player != null && (minecraft.player.hasPermissions(2)
                || minecraft.getSingleplayerServer() != null);
    }

    static void syncHostMusic() {
        musicSyncSent = true;
        MusicNetwork.sendHostMusic();
    }

    static void beginTrack(String key, int size, int chunks) {
        if (!validTrackKey(key) || size <= 0 || size > MAX_TRACK_BYTES
                || chunks <= 0 || chunks != (size + CHUNK_SIZE - 1) / CHUNK_SIZE) return;
        incomingTracks.put(key, new IncomingTrack(size, chunks));
        loadedTracks.remove(key);
    }

    static void receiveTrackChunk(String key, int index, byte[] data) {
        IncomingTrack transfer = incomingTracks.get(key);
        if (transfer == null || index < 0 || index >= transfer.chunks || data.length > CHUNK_SIZE
                || transfer.received.get(index)) return;
        int offset = index * CHUNK_SIZE;
        int expectedLength = Math.min(CHUNK_SIZE, transfer.data.length - offset);
        if (data.length != expectedLength) return;
        System.arraycopy(data, 0, transfer.data, offset, expectedLength);
        transfer.received.set(index);
        if (transfer.received.cardinality() != transfer.chunks) return;

        incomingTracks.remove(key);
        try {
            CustomMusicPack.saveSynchronizedTrack(key, transfer.data);
            loadedTracks.add(key);
            if (key.equals(pendingTrack)) {
                startPendingTrack(new ResourceLocation(AfkCinematicsMod.MOD_ID, "custom/" + key));
            }
        } catch (IOException exception) {
            LogUtils.getLogger().error("Could not save synchronized AFK music {}", key, exception);
        }
    }

    static void finishTrackSync() {
        // Track bytes are cached on disk and played through one pre-registered sound event.
        // No resource-pack reload is needed when the server playlist changes.
    }

    static void setPlayback(String key, boolean active) {
        if (!active) {
            pendingTrack = null;
            ClientEvents.instance().stopSynchronizedMusic();
            return;
        }
        if (!validTrackKey(key)) return;
        pendingTrack = key;
        if (loadedTracks.contains(key)) {
            startPendingTrack(new ResourceLocation(AfkCinematicsMod.MOD_ID, "custom/" + key));
        }
    }

    private static void startPendingTrack(ResourceLocation track) {
        if (pendingTrack == null || !track.getPath().equals("custom/" + pendingTrack)) return;
        if (!ClientEvents.instance().isMusicEnabled()) return;
        ClientEvents.instance().startSynchronizedMusic(track);
    }

    private static boolean validTrackKey(String key) {
        return key != null && key.matches("server_[a-z0-9._-]{1,64}");
    }

    private static final class IncomingTrack {
        final byte[] data;
        final int chunks;
        final BitSet received;

        IncomingTrack(int size, int chunks) {
            this.data = new byte[size];
            this.chunks = chunks;
            this.received = new BitSet(chunks);
        }
    }
}
