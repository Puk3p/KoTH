# 🚩 KoTH (King of the Hill) — Ultimate Minecraft Plugin

An enterprise-grade, highly customizable King of the Hill (KoTH) Spigot/Paper plugin built for competitive faction servers, network events, and mini-games. Designed for Paper/Spigot 1.8.8+, featuring high performance and deep integrations with FactionsUUID, PlaceholderAPI, PremiumVanish, and Discord Webhooks.

---

## 📋 Table of Contents

1. [Features](#-features)
2. [Dependencies & Compatibility](#-dependencies--compatibility)
3. [Installation & Building](#️-installation--building)
4. [Commands & Permissions](#-commands--permissions)
5. [Step-by-Step Arena Setup Guide](#️-step-by-step-arena-setup-guide)
6. [Configuration (config.yml)](#️-configuration-configyml)
7. [Scheduling & Arenas (arenas.yml)](#-scheduling--arenas-arenasyml)
8. [Staff & Vanish Integration](#-staff--vanish-integration)
9. [Discord Webhooks](#-discord-webhooks)
10. [PlaceholderAPI Expansion](#-placeholderapi-expansion)

---

## ⚡ Features

- **Modular Type System** — Create unlimited custom KoTH types (`koth`, `superkoth`, `tournament`, etc.), each with unique capture times, sounds, visuals, protections, and rewards.
- **Capture Mechanics & Knock Behaviors** — Configurable `RESET` (timer resets on knock), `PAUSE` (timer pauses until re-entered), and `PENALTY` modes. Includes a maximum runtime limit to end stalemates in a draw.
- **Tournament & Elimination Mode** — Automated pre-registration phase (`/koth join`), arena sweeping (teleports unregistered players to spawn), single-life elimination upon death, and blocked commands.
- **Protection Flags Engine** — Per-type region flags for block breaking, block placing, bucket usage (lava/water), enderpearl throwing, fall damage, and PvP.
- **Command Restriction Engine** — Restrict escape/utility commands (`/f home`, `/spawn`, `/ec`, `/pv`, `/tpa`) inside active capture zones with either `BLACKLIST` or `WHITELIST` modes.
- **Staff & Vanish Integration** — Seamless integration with PremiumVanish and SuperVanish. Vanished staff members or admins can fly through capture zones without interrupting captures, triggering contests, or getting swept by tournament security.
- **Visuals & Action Bar Engine** — Live action bar countdown timers, full-screen titles/subtitles with fade controls, and victory fireworks upon capture.
- **Dynamic Sound Matrix** — Custom pitch and volume controls for every event stage (start, capturing-tick, contested, captured).
- **Weighted Reward Engine** — Flexible reward engine supporting `ALL` (execute all commands) or `CHANCE` (percentage-based random roll for rare keys).
- **Timezone-Aware Scheduler** — Automatically start KoTH events based on real-world schedules (`HH:mm`) with custom timezones (e.g., `Europe/Bucharest`, `UTC`, `America/New_York`). Includes minimum online player thresholds to prevent off-peak farming.
- **Discord Rich Webhooks** — Auto-posting Discord announcements featuring ISO-8601 dynamic timestamps, server branding, and real-time 3D player head avatars fetched directly from `mc-heads.net`.

---

## 🔌 Dependencies & Compatibility

| Dependency | Type | Required Version / Repository |
|---|---|---|
| Spigot / Paper | Required | 1.8.8+ |
| FactionsUUID | Soft Depend | `dev.kitteh:factions:4.4.0` |
| PlaceholderAPI | Soft Depend | `me.clip:placeholderapi:2.11.5` |
| PremiumVanish / SuperVanish | Soft Depend | `com.github.LeonMangler:PremiumVanishAPI:2.7.35` |

---

## 🛠️ Installation & Building

### Building from Source

1. Clone the repository:

   ```bash
   git clone https://github.com/your-username/koth.git
   cd koth
   ```

2. Build the project using Maven:

   ```bash
   mvn clean package
   ```

3. Locate the compiled JAR inside the `target/` directory:

   ```
   target/koth-1.0.0.jar
   ```

### Server Installation

1. Place `koth-1.0.0.jar` into your Minecraft server's `plugins/` directory.
2. (Optional) Install soft dependencies: FactionsUUID, PlaceholderAPI, and PremiumVanish.
3. Start or restart the server to generate default configuration files.

---

## 📜 Commands & Permissions

### Player Commands

| Command | Permission | Description |
|---|---|---|
| `/koth` | None | Displays the basic player help menu. |
| `/koth join` | None | Registers the player for an active Tournament Mode KoTH during the registration phase. |

### Admin Commands

| Command | Permission | Description |
|---|---|---|
| `/koth pos1` | `koth.admin` | Sets the first corner of a selection area to your current position. |
| `/koth pos2` | `koth.admin` | Sets the second corner of a selection area to your current position. |
| `/koth create <name> <type>` | `koth.admin` | Creates a new arena using your selected points and assigns a type from `config.yml`. |
| `/koth start <arena>` | `koth.start` or `koth.admin` | Manually triggers the start of a specific KoTH arena. |
| `/koth stop` | `koth.stop` or `koth.admin` | Instantly stops the active KoTH event and resets all states. |
| `/koth reload` | `koth.reload` or `koth.admin` | Reloads `config.yml`, `arenas.yml`, `messages.yml`, and resynchronizes schedules. |

---

## 🛠️ Step-by-Step Arena Setup Guide

To create a new KoTH arena in-game:

1. **Set Corner 1** — Stand at the first corner of your capture zone and type:

   ```
   /koth pos1
   ```

2. **Set Corner 2** — Walk diagonally across to the opposite corner (covering the full Y-height of the cap zone) and type:

   ```
   /koth pos2
   ```

3. **Instantiate Arena** — Run the create command using an arena name and a type key defined in `config.yml`:

   ```
   /koth create nether koth
   ```

---

## ⚙️ Configuration (config.yml)

```yaml
settings:
  timezone: "Europe/Bucharest"

discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL"
  server-ip: "play.yourserver.net"
  footer-text: "KoTH Network"
  colors:
    started: "#00FF00"
    captured: "#FFA500"
    ended: "#FF0000"

types:
  koth:
    capture-time: 300            # Seconds required to capture (5 mins)
    knock-behavior: "PAUSE"      # Options: RESET, PAUSE, PENALTY
    minimum-players-online: 10   # Requires at least 10 online players to start via schedule
    max-run-time: 3600           # 1 hour limit before declaring a DRAW (0 = infinite)

    protections:
      block-breaking: true
      block-placing: true
      bucket-use: true
      enderpearls: false
      fall-damage: false

    command-restrictions:
      enabled: true
      mode: "BLACKLIST"          # Options: BLACKLIST, WHITELIST
      blocked-commands:
        - "ec"
        - "enderchest"
        - "pv"
        - "f home"
        - "spawn"
        - "home"

    visuals:
      action-bar:
        enabled: true
        text: "&b&lCapturing %arena% &8» &f%time%"
      title-on-start:
        enabled: true
        title: "&a&lKOTH STARTED"
        subtitle: "&e%arena% &7is now active!"
        fade-in: 10
        stay: 40
        fade-out: 10
      title-on-capture:
        enabled: true
        title: "&6&lKOTH CAPTURED"
        subtitle: "&e%player% &7(&b%faction%&7) has captured &e%arena%!"
      firework-on-capture: true

    sounds:
      start:
        sound: "ENDERDRAGON_GROWL"
        volume: 1.0
        pitch: 1.0
      capturing-tick:
        enabled: true
        sound: "CLICK"
        volume: 0.5
        pitch: 1.5
      contested:
        enabled: true
        sound: "NOTE_BASS"
        volume: 0.8
        pitch: 0.5
      captured:
        sound: "LEVEL_UP"
        volume: 1.0
        pitch: 1.0

    rewards:
      mode: "ALL"                # Options: ALL, CHANCE
      commands:
        - command: "give %player% diamond 16"
          chance: 100.0
        - command: "eco give %player% 5000"
          chance: 100.0

    tournament-mode:
      enabled: false

  superkoth:
    capture-time: 600
    knock-behavior: "RESET"
    minimum-players-online: 25
    max-run-time: 7200

    protections:
      block-breaking: true
      block-placing: true
      bucket-use: true
      enderpearls: true
      fall-damage: false

    command-restrictions:
      enabled: true
      mode: "BLACKLIST"
      blocked-commands:
        - "ec"
        - "enderchest"
        - "pv"
        - "f home"
        - "spawn"
        - "home"
        - "fix"

    visuals:
      action-bar:
        enabled: true
        text: "&c&lCapturing %arena% &8» &f%time%"
      title-on-start:
        enabled: true
        title: "&c&lSUPER KOTH STARTED"
        subtitle: "&e%arena% &7is now active!"
        fade-in: 10
        stay: 40
        fade-out: 10
      title-on-capture:
        enabled: true
        title: "&6&lSUPER KOTH CAPTURED"
        subtitle: "&e%player% &7dominated &e%arena%!"
      firework-on-capture: true

    sounds:
      start:
        sound: "WITHER_SPAWN"
        volume: 1.0
        pitch: 0.8
      capturing-tick:
        enabled: true
        sound: "NOTE_PLING"
        volume: 0.7
        pitch: 2.0
      contested:
        enabled: true
        sound: "NOTE_BASS"
        volume: 1.0
        pitch: 0.5
      captured:
        sound: "ENDERDRAGON_DEATH"
        volume: 1.0
        pitch: 1.0

    rewards:
      mode: "CHANCE"             # Random roll percentage
      commands:
        - command: "eco give %player% 50000"
          chance: 100.0
        - command: "crate give %player% legendary 3"
          chance: 100.0
        - command: "crate give %player% admin 1"
          chance: 15.0

    tournament-mode:
      enabled: false

  tournament:
    capture-time: 900
    knock-behavior: "PAUSE"
    minimum-players-online: 30
    max-run-time: 7200

    protections:
      block-breaking: true
      block-placing: true
      bucket-use: true
      enderpearls: true
      fall-damage: true

    command-restrictions:
      enabled: true
      mode: "BLACKLIST"
      blocked-commands:
        - "ec"
        - "enderchest"
        - "bp"
        - "backpack"
        - "back"
        - "home"
        - "spawn"
        - "tpa"

    visuals:
      action-bar:
        enabled: true
        text: "&6&lTournament %arena% &8» &f%time%"
      title-on-start:
        enabled: true
        title: "&6&lTOURNAMENT STARTED"
        subtitle: "&eFight for %arena%!"
        fade-in: 10
        stay: 40
        fade-out: 10
      title-on-capture:
        enabled: true
        title: "&6&lTOURNAMENT WON"
        subtitle: "&e%player% &7is the champion of &e%arena%!"
      firework-on-capture: true

    sounds:
      start:
        sound: "ENDERDRAGON_GROWL"
        volume: 1.0
        pitch: 0.5
      capturing-tick:
        enabled: true
        sound: "CLICK"
        volume: 0.8
        pitch: 1.0
      contested:
        enabled: true
        sound: "NOTE_BASS"
        volume: 1.0
        pitch: 0.5
      captured:
        sound: "LEVEL_UP"
        volume: 1.0
        pitch: 0.5

    rewards:
      mode: "ALL"
      commands:
        - command: "crate give %player% event 1"
          chance: 100.0

    tournament-mode:
      enabled: true
      registration-time: 300
      allow-spectator: false
```

---

## 📅 Scheduling & Arenas (arenas.yml)

The schedule checks every 60 seconds against the configured timezone in `config.yml`.

```yaml
arenas:
  nether:
    type: "koth"
    enabled: true
    world: "world_nether"
    region:
      min-x: -150
      min-y: 60
      min-z: 200
      max-x: -140
      max-y: 70
      max-z: 210
    schedule:
      MONDAY:
        - "18:00"
        - "22:00"
      WEDNESDAY:
        - "20:00"
      FRIDAY:
        - "16:00"
        - "21:00"
      SATURDAY:
        - "15:00"
        - "19:00"
        - "23:00"

  castle:
    type: "superkoth"
    enabled: true
    world: "world"
    region:
      min-x: 500
      min-y: 70
      min-z: 500
      max-x: 520
      max-y: 85
      max-z: 520
    schedule:
      SUNDAY:
        - "18:00"
```

---

## 🕵️ Staff & Vanish Integration

- **Zero Capture Interference** — Vanished players inside a capture zone are automatically excluded from capture and contest calculations.
- **Bypass Tournament Barriers** — Vanished staff or users with `koth.admin` are bypass-exempt from tournament registration sweeps.
- **Pass-Through Security** — Flying into/out of capture regions in vanish will not produce chat/action bar triggers or block movement.

---

## 🌐 Discord Webhooks

When enabled, the plugin dispatches asynchronous POST requests to Discord featuring:

- Real-time ISO-8601 timestamps.
- Embedded 3D player head renders (`https://mc-heads.net/avatar/<PlayerName>/100`).
- Custom server name & IP footers.

---

## 📊 PlaceholderAPI Expansion

Use `%koth_<placeholder>%` in scoreboards, tablists, or chat formats:

| Placeholder | Return Value | Example Output |
|---|---|---|
| `%koth_active%` | Currently active KoTH arena name | `nether` |
| `%koth_status%` | Current state | `RUNNING`, `CONTESTED`, `WAITING` |
| `%koth_capture_time%` | Remaining capture time | `04:15` |
| `%koth_capturer%` | Capturing player's username | `Puk3p` |
| `%koth_capturer_faction%` | Faction tag of the capturer | `Viper` |