# AFK Cinematics — Forge 1.20.1

Forge port of AFK Cinematics, based on the supplied `afk-cinematics-1.0.5+26.2.jar` reference. This repository targets Minecraft 1.20.1, Forge 47.4.x, and Java 17.

## Controls

- Press **J** to open the settings screen.
- The enable/disable key is unbound by default and can be assigned in Minecraft's Controls menu.
- Use `/afkc start` to start a cinematic immediately.
- Use `/afkc time <seconds>` to set the inactivity delay (1–36,000 seconds).
- Use `/afkc music on|off` to control cinematic music.
- Use `/afkc motion <default|low|medium|high>` to choose the motion level.
- Use `/afkc about` for the author link.

When connected to a server, only the host (single-player owner) or an operator can change mod settings. The server stores those settings and sends them to each player on join and whenever they change. Clients follow the server's enabled state, AFK timeout, music setting, and camera motion setting.

## Server settings

The dedicated/integrated server saves the authoritative configuration to `config/afkcinematics-server.properties`:

- `afk_timeout_seconds`
- `afk_cinematics_enabled`
- `cinematic_music_enabled`
- `cinematic_motion_level`

Use **J** as the host/operator to change these from the settings screen, or use the `/afkc` client commands while hosting. Clients can open the screen but cannot change host settings.

## Custom music

The host places music in **OGG Vorbis** format in:

```
config/afkcinematics/music/
```

Open this folder using **J → Open Music Folder**, then press **Sync Host Music**. The host's playlist is copied to the server, and tracks are sent to clients by the mod when playback starts. Clients only need the mod installed; they do not need to add music files. The playlist syncs again when the host joins, and pressing the sync button after editing the folder applies additions and removals. Tracks are sent to clients in network packets and played through one stable sound event; syncing or starting a cinematic does not reload resource packs. AFK Cinematics does not fall back to Minecraft's vanilla music when the server playlist is empty.

In single-player, the world owner automatically uploads that folder to the integrated server, including when cheats are off. Test immediately in the Overworld with **J → Start Now** or `/afkc start`; playback waits until the host playlist is synchronized. Minecraft loads the stable AFK music sound entry during its normal initial resource load. Adding, syncing, or changing tracks while the game is open does not reload resource packs.

## Build

With Java 17 and Gradle 8.8 installed, run:

```sh
gradle build --no-daemon
```

GitHub Actions builds the mod on each branch push and uploads the JAR as a workflow artifact.
