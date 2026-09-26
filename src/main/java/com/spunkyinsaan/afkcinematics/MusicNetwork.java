package com.spunkyinsaan.afkcinematics;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

final class MusicNetwork {
    private static final String PROTOCOL = "1";
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int MAX_TRACK_BYTES = 32 * 1024 * 1024;
    private static final Path SERVER_MUSIC_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("afkcinematics").resolve("server_music");
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AfkCinematicsMod.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<String, UploadTransfer> uploadTransfers = new HashMap<>();
    private static UUID musicController;
    private static String activeTrackKey;
    private static Path activeTrackPath;
    private static boolean playbackActive;

    private MusicNetwork() {}

    static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, HostStateMessage.class, HostStateMessage::encode,
                HostStateMessage::decode, MusicNetwork::handleHostState, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, HostSettingsMessage.class, HostSettingsMessage::encode,
                HostSettingsMessage::decode, MusicNetwork::handleHostSettings, NetworkDirection.PLAY_TO_SERVER);
        CHANNEL.registerMessage(id++, MusicUploadStartMessage.class, MusicUploadStartMessage::encode,
                MusicUploadStartMessage::decode, MusicNetwork::handleUploadStart, NetworkDirection.PLAY_TO_SERVER);
        CHANNEL.registerMessage(id++, MusicUploadChunkMessage.class, MusicUploadChunkMessage::encode,
                MusicUploadChunkMessage::decode, MusicNetwork::handleUploadChunk, NetworkDirection.PLAY_TO_SERVER);
        CHANNEL.registerMessage(id++, MusicManifestMessage.class, MusicManifestMessage::encode,
                MusicManifestMessage::decode, MusicNetwork::handleMusicManifest, NetworkDirection.PLAY_TO_SERVER);
        CHANNEL.registerMessage(id++, ServerSettingsMessage.class, ServerSettingsMessage::encode,
                ServerSettingsMessage::decode, MusicNetwork::handleServerSettings, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, TrackStartMessage.class, TrackStartMessage::encode,
                TrackStartMessage::decode, MusicNetwork::handleTrackStart, NetworkDirection.PLAY_TO_CLIENT);
        CHANNEL.registerMessage(id++, TrackChunkMessage.class, TrackChunkMessage::encode,
                TrackChunkMessage::decode, MusicNetwork::handleTrackChunk, NetworkDirection.PLAY_TO_CLIENT);
        CHANNEL.registerMessage(id, PlaybackMessage.class, PlaybackMessage::encode,
                PlaybackMessage::decode, MusicNetwork::handlePlayback, NetworkDirection.PLAY_TO_CLIENT);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(MusicNetwork.class);
        try {
            Files.createDirectories(SERVER_MUSIC_DIRECTORY);
        } catch (IOException ignored) {
            // The directory is retried when the host synchronizes music.
        }
    }

    static void sendHostState(boolean active) {
        CHANNEL.sendToServer(new HostStateMessage(active));
    }

    static void sendHostSettings(ServerSettings.Snapshot settings) {
        CHANNEL.sendToServer(new HostSettingsMessage(settings));
    }

    static void sendHostMusic() {
        Path directory = CustomMusicPack.getMusicDirectory();
        try {
            List<Path> files;
            try (var paths = Files.list(directory)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }
            List<String> manifest = new ArrayList<>();
            for (Path path : files) {
                String name = safeName(stripExtension(path.getFileName().toString()));
                if (name == null || manifest.contains(name)) continue;
                long size = Files.size(path);
                if (size <= 0 || size > MAX_TRACK_BYTES) continue;
                byte[] data = Files.readAllBytes(path);
                int chunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
                CHANNEL.sendToServer(new MusicUploadStartMessage(name, data.length, chunks));
                for (int index = 0; index < chunks; index++) {
                    int from = index * CHUNK_SIZE;
                    int to = Math.min(from + CHUNK_SIZE, data.length);
                    byte[] part = java.util.Arrays.copyOfRange(data, from, to);
                    CHANNEL.sendToServer(new MusicUploadChunkMessage(name, index, part));
                }
                manifest.add(name);
            }
            CHANNEL.sendToServer(new MusicManifestMessage(manifest));
        } catch (IOException exception) {
            // Keep the client usable if its music folder is unavailable.
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        sendToPlayer(player, new ServerSettingsMessage(ServerSettings.get()));
        if (playbackActive && activeTrackPath != null) sendTrackAndPlayback(player, activeTrackPath, activeTrackKey);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        uploadTransfers.keySet().removeIf(key -> key.startsWith(player.getUUID().toString() + ":"));
        if (player.getUUID().equals(musicController)) stopPlayback();
    }

    private static void handleHostState(HostStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !canControlServer(sender)) return;
            if (!message.active || !ServerSettings.get().enabled() || !ServerSettings.get().musicEnabled()) {
                if (sender.getUUID().equals(musicController)) stopPlayback();
                return;
            }
            if (playbackActive) return;
            startPlayback(sender);
        });
        context.setPacketHandled(true);
    }

    private static void handleHostSettings(HostSettingsMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !canControlServer(sender)) return;
            ServerSettings.set(message.settings);
            broadcastSettings();
            if (!ServerSettings.get().enabled() || !ServerSettings.get().musicEnabled()) stopPlayback();
        });
        context.setPacketHandled(true);
    }

    private static void handleUploadStart(MusicUploadStartMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !canControlServer(sender) || safeName(message.name) == null
                    || message.size <= 0 || message.size > MAX_TRACK_BYTES
                    || message.chunks <= 0 || message.chunks > (MAX_TRACK_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE) return;
            uploadTransfers.put(transferKey(sender, message.name),
                    new UploadTransfer(message.name, message.size, message.chunks));
        });
        context.setPacketHandled(true);
    }

    private static void handleUploadChunk(MusicUploadChunkMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !canControlServer(sender) || message.data.length > CHUNK_SIZE) return;
            String key = transferKey(sender, message.name);
            UploadTransfer transfer = uploadTransfers.get(key);
            if (transfer == null || message.index < 0 || message.index >= transfer.chunks
                    || transfer.received.get(message.index)) return;
            int offset = message.index * CHUNK_SIZE;
            int expectedLength = Math.min(CHUNK_SIZE, transfer.data.length - offset);
            if (message.data.length != expectedLength) return;
            System.arraycopy(message.data, 0, transfer.data, offset, expectedLength);
            transfer.received.set(message.index);
            if (transfer.received.cardinality() == transfer.chunks) {
                try {
                    Files.createDirectories(SERVER_MUSIC_DIRECTORY);
                    Files.write(SERVER_MUSIC_DIRECTORY.resolve(transfer.name + ".ogg"), transfer.data);
                } catch (IOException ignored) {
                    // Reported by the absence of this track from the server playlist.
                } finally {
                    uploadTransfers.remove(key);
                }
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleMusicManifest(MusicManifestMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !canControlServer(sender)) return;
            Set<String> keep = new HashSet<>();
            for (String name : message.names) {
                String safe = safeName(name);
                if (safe != null) keep.add(safe + ".ogg");
            }
            try {
                Files.createDirectories(SERVER_MUSIC_DIRECTORY);
                try (var files = Files.list(SERVER_MUSIC_DIRECTORY)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                            .filter(path -> !keep.contains(path.getFileName().toString()))
                            .forEach(path -> {
                                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                            });
                }
            } catch (IOException ignored) {
                // Keep the current server playlist if filesystem synchronization fails.
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleServerSettings(ServerSettingsMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientMusicNetwork.applySettings(message.settings)));
        context.setPacketHandled(true);
    }

    private static void handleTrackStart(TrackStartMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientMusicNetwork.beginTrack(message.key, message.size, message.chunks)));
        context.setPacketHandled(true);
    }

    private static void handleTrackChunk(TrackChunkMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientMusicNetwork.receiveTrackChunk(message.key, message.index, message.data)));
        context.setPacketHandled(true);
    }

    private static void handlePlayback(PlaybackMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientMusicNetwork.setPlayback(message.key, message.active)));
        context.setPacketHandled(true);
    }

    private static void startPlayback(ServerPlayer sender) {
        try {
            List<Path> tracks;
            Files.createDirectories(SERVER_MUSIC_DIRECTORY);
            try (var files = Files.list(SERVER_MUSIC_DIRECTORY)) {
                tracks = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                        .filter(path -> {
                            try {
                                long size = Files.size(path);
                                return size > 0 && size <= MAX_TRACK_BYTES;
                            } catch (IOException ignored) {
                                return false;
                            }
                        })
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (tracks.isEmpty()) {
                broadcast(new PlaybackMessage("", false));
                return;
            }
            Path selected = tracks.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(tracks.size()));
            String base = safeName(stripExtension(selected.getFileName().toString()));
            if (base == null) return;
            String key = "server_" + base;
            musicController = sender.getUUID();
            activeTrackPath = selected;
            activeTrackKey = key;
            playbackActive = true;
            for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
                sendTrackAndPlayback(player, selected, key);
            }
        } catch (IOException ignored) {
            // No custom music is available.
        }
    }

    private static void sendTrackAndPlayback(ServerPlayer player, Path path, String key) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_TRACK_BYTES) return;
            byte[] data = Files.readAllBytes(path);
            int chunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            sendToPlayer(player, new TrackStartMessage(key, data.length, chunks));
            for (int index = 0; index < chunks; index++) {
                int from = index * CHUNK_SIZE;
                int to = Math.min(from + CHUNK_SIZE, data.length);
                sendToPlayer(player, new TrackChunkMessage(key, index, java.util.Arrays.copyOfRange(data, from, to)));
            }
            sendToPlayer(player, new PlaybackMessage(key, true));
        } catch (IOException ignored) {
            // The next track selection can retry the transfer.
        }
    }

    private static void stopPlayback() {
        if (!playbackActive) return;
        playbackActive = false;
        activeTrackPath = null;
        activeTrackKey = null;
        musicController = null;
        broadcast(new PlaybackMessage("", false));
    }

    private static void broadcastSettings() {
        broadcast(new ServerSettingsMessage(ServerSettings.get()));
    }

    private static void broadcast(Object message) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), message);
    }

    private static void sendToPlayer(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    private static boolean canControlServer(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && (server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2));
    }

    private static String transferKey(ServerPlayer player, String name) {
        return player.getUUID() + ":" + name;
    }

    private static String stripExtension(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".ogg")
                ? filename.substring(0, filename.length() - 4) : filename;
    }

    private static String safeName(String name) {
        if (name == null) return null;
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (safe.isBlank() || safe.length() > 64 || safe.equals(".") || safe.equals("..")) return null;
        return safe;
    }

    private static final class UploadTransfer {
        final String name;
        final byte[] data;
        final int chunks;
        final BitSet received;

        UploadTransfer(String name, int size, int chunks) {
            this.name = name;
            this.data = new byte[size];
            this.chunks = chunks;
            this.received = new BitSet(chunks);
        }
    }

    private record HostStateMessage(boolean active) {
        void encode(FriendlyByteBuf buffer) { buffer.writeBoolean(active); }
        static HostStateMessage decode(FriendlyByteBuf buffer) { return new HostStateMessage(buffer.readBoolean()); }
    }

    private record HostSettingsMessage(ServerSettings.Snapshot settings) {
        void encode(FriendlyByteBuf buffer) { writeSettings(buffer, settings); }
        static HostSettingsMessage decode(FriendlyByteBuf buffer) { return new HostSettingsMessage(readSettings(buffer)); }
    }

    private record MusicUploadStartMessage(String name, int size, int chunks) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(name, 64);
            buffer.writeVarInt(size);
            buffer.writeVarInt(chunks);
        }
        static MusicUploadStartMessage decode(FriendlyByteBuf buffer) {
            return new MusicUploadStartMessage(buffer.readUtf(64), buffer.readVarInt(), buffer.readVarInt());
        }
    }

    private record MusicUploadChunkMessage(String name, int index, byte[] data) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(name, 64);
            buffer.writeVarInt(index);
            buffer.writeByteArray(data);
        }
        static MusicUploadChunkMessage decode(FriendlyByteBuf buffer) {
            return new MusicUploadChunkMessage(buffer.readUtf(64), buffer.readVarInt(), buffer.readByteArray(CHUNK_SIZE));
        }
    }

    private record MusicManifestMessage(List<String> names) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(Math.min(names.size(), 128));
            names.stream().limit(128).forEach(name -> buffer.writeUtf(name, 64));
        }
        static MusicManifestMessage decode(FriendlyByteBuf buffer) {
            int count = Math.max(0, Math.min(128, buffer.readVarInt()));
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) names.add(buffer.readUtf(64));
            return new MusicManifestMessage(names);
        }
    }

    private record ServerSettingsMessage(ServerSettings.Snapshot settings) {
        void encode(FriendlyByteBuf buffer) { writeSettings(buffer, settings); }
        static ServerSettingsMessage decode(FriendlyByteBuf buffer) {
            return new ServerSettingsMessage(readSettings(buffer));
        }
    }

    private record TrackStartMessage(String key, int size, int chunks) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(key, 80);
            buffer.writeVarInt(size);
            buffer.writeVarInt(chunks);
        }
        static TrackStartMessage decode(FriendlyByteBuf buffer) {
            return new TrackStartMessage(buffer.readUtf(80), buffer.readVarInt(), buffer.readVarInt());
        }
    }

    private record TrackChunkMessage(String key, int index, byte[] data) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(key, 80);
            buffer.writeVarInt(index);
            buffer.writeByteArray(data);
        }
        static TrackChunkMessage decode(FriendlyByteBuf buffer) {
            return new TrackChunkMessage(buffer.readUtf(80), buffer.readVarInt(), buffer.readByteArray(CHUNK_SIZE));
        }
    }

    private record PlaybackMessage(String key, boolean active) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(key, 80);
            buffer.writeBoolean(active);
        }
        static PlaybackMessage decode(FriendlyByteBuf buffer) {
            return new PlaybackMessage(buffer.readUtf(80), buffer.readBoolean());
        }
    }

    private static void writeSettings(FriendlyByteBuf buffer, ServerSettings.Snapshot settings) {
        ServerSettings.Snapshot normalized = settings.normalized();
        buffer.writeBoolean(normalized.enabled());
        buffer.writeVarInt(normalized.afkTimeoutSeconds());
        buffer.writeBoolean(normalized.musicEnabled());
        buffer.writeUtf(normalized.motionLevel(), 16);
    }

    private static ServerSettings.Snapshot readSettings(FriendlyByteBuf buffer) {
        return new ServerSettings.Snapshot(buffer.readBoolean(), buffer.readVarInt(),
                buffer.readBoolean(), buffer.readUtf(16)).normalized();
    }
}
