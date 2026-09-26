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

The settings screen also provides an inactivity slider from 5 to 1,800 seconds, music and motion controls, and a manual start button. Defaults are 25 seconds, enabled, music enabled, and default motion.

## Configuration

Settings are saved to `config/afkcinematics.properties`:

- `afk_timeout_seconds`
- `afk_cinematics_enabled`
- `cinematic_music_enabled`
- `cinematic_motion_level`

## Build

With Java 17 and Gradle 8.8 installed, run:

```sh
gradle build --no-daemon
```

GitHub Actions builds the mod on each branch push and uploads the JAR as a workflow artifact.
