# 🚩 KoTH (King of the Hill) — Ultimate Minecraft Plugin

A highly customizable King of the Hill (KoTH) plugin built for competitive faction servers, network events and mini-games. Made for Spigot/Paper 1.8.8, with integrations for FactionsUUID / SaberFactions, PlaceholderAPI, vanish plugins, DecentHolograms and Discord webhooks.

---

## 📋 Table of Contents

1. [Features](#-features)
2. [Dependencies & Compatibility](#-dependencies--compatibility)
3. [Installation & Building](#️-installation--building)
4. [Commands & Permissions](#-commands--permissions)
5. [Step-by-Step Arena Setup Guide](#️-step-by-step-arena-setup-guide)
6. [Configuration (config.yml)](#️-configuration-configyml)
7. [Scheduling & Arenas (arenas.yml)](#-scheduling--arenas-arenasyml)
8. [KOTH Arena (LMS-style event)](#️-koth-arena-lms-style-event)
9. [Faction KoTH Wins (/fkoth)](#-faction-koth-wins-fkoth)
10. [Staff & Vanish Integration](#️-staff--vanish-integration)
11. [Discord Webhooks](#-discord-webhooks)
12. [PlaceholderAPI Expansion](#-placeholderapi-expansion)

---

## ⚡ Features

- **Modular Type System** — Create unlimited custom KoTH types (`koth`, `superkoth`, `arena`, ...), each with its own capture time, sounds, visuals, protections, command restrictions and rewards.
- **Faction Capture** — Players from the same faction capture together; a zone is only contested by players from different factions. Can be turned off for solo capture.
- **Capture Mechanics & Knock Behaviors** — `RESET` (timer starts over when the holder loses the zone) or `PAUSE` (the next holder continues from where it stopped). A maximum run time ends stalemates in a draw.
- **KOTH Arena (LMS-style event)** — `/koth join` before the start teleports players into the arena with invincibility and a countdown; command lock, single life, post-capture countdown and teleport out.
- **Faction KoTH Wins** — Every capture adds a win to the winner's faction: `/fkoth` top and stats, a DecentHolograms top hologram, placeholders and an API for other plugins (e.g. DinoFtop). Wins follow faction renames and are removed on disband.
- **Protection Flags Engine** — Per-type zone flags for block breaking/placing, buckets, enderpearls, fall damage, lava/water flow, TNT and pistons.
- **Command Restriction Engine** — Restrict escape/utility commands (`/f home`, `/spawn`, `/ec`, `/pv`, `/tpa`) inside active capture zones with either `BLACKLIST` or `WHITELIST` mode.
- **Staff & Vanish Integration** — Vanished staff, spectators and (optionally) creative players never capture or contest a zone.
- **Visuals & Action Bar Engine** — Live action bar timer, `CONTESTED` warning, titles/subtitles with fade controls and victory fireworks.
- **Dynamic Sound Matrix** — Custom volume and pitch for every stage (start, capturing-tick, contested, captured).
- **Weighted Reward Engine** — `ALL` (run every command) or `CHANCE` (percentage roll per command, for rare keys).
- **Timezone-Aware Scheduler** — Weekly schedules per arena (`HH:mm`) in any timezone (e.g. `Europe/Bucharest`), with minimum online player thresholds to prevent off-peak farming.
- **Discord Rich Webhooks** — Announcements with timestamps, server branding and the winner's player head from `mc-heads.net`.

---

## 🔌 Dependencies & Compatibility

| Dependency | Type | Notes |
|---|---|---|
| Spigot / Paper | Required | 1.8.8 |
| FactionsUUID (1.6.9.5-U0.x) or SaberFactions | Soft Depend | Legacy `com.massivecraft.factions` API. Needed for faction capture and faction wins. |
| PlaceholderAPI | Soft Depend | `%koth_...%` and `%fkoth_...%` placeholders, extra placeholders in rewards |
| DecentHolograms | Soft Depend | Faction wins top hologram |
| PremiumVanish / SuperVanish / Essentials | Optional | Detected through the standard `vanished` player metadata; no API needed |

---

## 🛠️ Installation & Building

### Building from Source

1. Clone the repository:

   ```bash
   git clone https://github.com/Puk3p/KoTH.git
   cd KoTH
   ```

2. Build the project using Maven (any JDK from 8 to 25):

   ```bash
   mvn clean package
   ```

3. Locate the compiled JAR inside the `target/` directory:

   ```
   target/koth-1.0.0.jar
   ```

### Server Installation

1. Place `koth-1.0.0.jar` into your Minecraft server's `plugins/` directory.
2. (Optional) Install soft dependencies: Factions, PlaceholderAPI, DecentHolograms.
3. Coming from the **FKothWins** addon? Remove `FKothWins.jar`: its wins are imported automatically on the first start.
4. Start or restart the server to generate the default configuration files.

---

## 📜 Commands & Permissions

### Player Commands

| Command | Permission | Description |
|---|---|---|
| `/koth` | `koth.join` (default: everyone) | Displays the player help menu. |
| `/koth join` | `koth.join` | Joins the KOTH Arena during its join phase (teleports you into the arena). |
| `/koth leave` | `koth.join` | Leaves the KOTH Arena. You can't rejoin the same event. |
| `/fkoth stats` | `fkoth.player` (default: everyone) | Your faction's KoTH wins. |
| `/fkoth top` | `fkoth.player` | Top factions by KoTH wins. |

### Admin Commands

| Command | Permission | Description |
|---|---|---|
| `/koth wand` | `koth.admin` | Gives the selection wand: left click a block for corner 1, right click for corner 2. |
| `/koth pos1` / `/koth pos2` | `koth.admin` | Sets the corners of the capture zone to your current position. |
| `/koth create <name> <type>` | `koth.admin` | Creates an arena from your selection with a type from `config.yml`. |
| `/koth setspawn <name>` | `koth.admin` | Sets an arena's spawn point. |
| `/koth delete <name>` | `koth.admin` | Deletes an arena. |
| `/koth list` | `koth.admin` | Lists arenas with their type and state. |
| `/koth setarena` / `/koth setexit` | `koth.admin` | Sets the KOTH Arena location / exit location (written into `config.yml`). |
| `/koth start <arena>` | `koth.start` or `koth.admin` | Starts a KoTH (KOTH Arena types open `/koth join` first). |
| `/koth stop [arena]` | `koth.stop` or `koth.admin` | Stops the running KoTH or KOTH Arena event. |
| `/koth reload` | `koth.reload` or `koth.admin` | Reloads `config.yml`, `messages.yml`, `arenas.yml` and schedules. |
| `/fkoth add <player> <n>` | `fkoth.admin` | Adds wins to a player's faction. |
| `/fkoth remove <faction> <n>` / `set <faction> <n>` | `fkoth.admin` | Changes a faction's wins. |
| `/fkoth sethologram` | `fkoth.admin` | Places the top factions hologram at your position. |
| `/fkoth reload` / `debug` | `fkoth.admin` | Reloads configs / shows integration status. |

`koth.bypass` (and `koth.admin`) ignore zone protections, command restrictions and the KOTH Arena command lock.

---

## 🛠️ Step-by-Step Arena Setup Guide

1. **Get the Wand** — Type:

   ```
   /koth wand
   ```

2. **Select the Zone** — Left click the block at one corner of the capture zone and right click the block at the opposite corner, a few blocks higher (the zone covers the whole height between the two blocks). The clicked blocks aren't broken, and the selection's size is shown once both corners are set. While you hold the wand (and for 10 seconds after setting a corner) you see the selection in particles, visible only to you: a green column at corner 1, a red one at corner 2, then the zone's full outline.

   No wand? Stand at each corner and use `/koth pos1` and `/koth pos2` instead: they take the block you're standing in.

3. **Create the Arena** — Use an arena name and a type key defined in `config.yml`:

   ```
   /koth create nether koth
   ```

   Zones lower than 3 blocks are extended upward automatically, so a jumping player doesn't leave the zone.

---

## ⚙️ Configuration (config.yml)

The generated `config.yml` documents every option. The main parts:

```yaml
settings:
  timezone: "Europe/Bucharest"
  faction-capture: true      # same-faction players capture together
  ignore-creative: true      # creative players don't capture or contest

discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL"
  server-ip: "play.yourserver.net"
  footer-text: "KoTH Network"

types:
  koth:
    capture-time: 300            # Seconds required to capture (5 mins)
    knock-behavior: "PAUSE"      # Options: RESET, PAUSE
    minimum-players-online: 10   # Scheduled starts need at least 10 players online
    max-run-time: 3600           # 1 hour limit before declaring a DRAW (0 = infinite)

    protections:                 # true = protected inside the zone
      block-breaking: true
      block-placing: true
      bucket-use: true
      enderpearls: false
      fall-damage: false         # false = no fall damage in the zone
      lava-flow: false
      water-flow: false
      tnt: false
      pistons: false

    command-restrictions:
      enabled: true
      mode: "BLACKLIST"          # Options: BLACKLIST, WHITELIST
      blocked-commands: ["ec", "enderchest", "pv", "f home", "spawn", "home"]

    visuals:
      action-bar:
        enabled: true
        text: "&b&lCapturing %arena% &8» &f%time%"
        contested-text: "&c&lCONTESTED! &7Clear the zone!"
      title-on-start: { enabled: true, title: "&a&lKOTH STARTED", subtitle: "&e%arena% &7is now active!", fade-in: 10, stay: 40, fade-out: 10 }
      title-on-capture: { enabled: true, title: "&6&lKOTH CAPTURED", subtitle: "&e%player% &7has captured &e%arena%!" }
      firework-on-capture: true

    sounds:
      start: { sound: "ENDERDRAGON_GROWL", volume: 1.0, pitch: 1.0 }
      capturing-tick: { enabled: true, sound: "CLICK", volume: 0.5, pitch: 1.5 }
      contested: { enabled: true, sound: "NOTE_BASS", volume: 0.8, pitch: 0.5 }
      captured: { sound: "LEVEL_UP", volume: 1.0, pitch: 1.0 }

    rewards:
      mode: "CHANCE"             # ALL or CHANCE
      commands:
        - command: "eco give %player% 50000"
          chance: 100.0
        - command: "crate give %player% admin 1"
          chance: 15.0
```

Reward placeholders: `%player%`, `%arena%`, `%faction%`, `%type%`, plus PlaceholderAPI placeholders.

---

## 📅 Scheduling & Arenas (arenas.yml)

Arenas are saved to `arenas.yml` by `/koth create`; schedules are added by hand, then applied with `/koth reload`. Times use `settings.timezone`. Day keys are English day names (any case) or `daily`. Always quote times.

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
      monday: ["18:00", "22:00"]
      friday: ["16:00", "21:00"]
      daily: ["12:00"]
```

Only one KoTH runs at a time: a slot that comes up while another KoTH is running is skipped (see the console). Arenas with `enabled: false` never start automatically.

---

## ⚔️ KOTH Arena (LMS-style event)

Types with `koth-arena: true` (the `arena` type by default) run as an event, configured in the `koth-arena` section:

1. **Join** — `/koth start` (or the schedule, `join-time` before the slot) announces the event. `/koth join` teleports the player to the arena location with their own inventory. Joined players are invincible, can't hit anyone and can only use `allowed-commands`. A countdown is broadcast (`KOTH începe în 3 minute!`, ...).
2. **Fight** — When the countdown ends, invincibility is removed and the KoTH starts; only joined players can capture. Players camping in the zone who didn't join are sent out. Dying, logging out, `/koth leave`, being teleported away or walking out of `arena-radius` eliminates a player for good; dead players respawn at the exit location.
3. **Capture** — The winner is announced globally. Everyone stays `post-capture-time` seconds (with a countdown), then is teleported to the exit location.

Set the locations in-game with `/koth setarena` and `/koth setexit`.

---

## 🏆 Faction KoTH Wins (/fkoth)

Every capture adds one win to the capturer's faction (`faction-wins` in `config.yml`). Players without a faction don't count by default.

- `/fkoth top`, `/fkoth stats` and the `%fkoth_...%` placeholders.
- A top factions hologram with DecentHolograms (`/fkoth sethologram`).
- Wins follow a faction when it changes its tag and are removed when it disbands.
- Other plugins read the wins through the `ro.puk3p.fkoth.api.FKothApi` service (the same API the FKothWins addon offered), e.g. DinoFtop.

---

## 🕵️ Staff & Vanish Integration

- **Zero Capture Interference** — Vanished players (PremiumVanish, SuperVanish, Essentials), spectators and, by default, creative players inside a capture zone are excluded from capture and contest calculations.
- **Pass-Through Security** — Vanished staff and players with `koth.bypass` / `koth.admin` can enter KOTH Arena zones and aren't swept out at the start of the fight.

---

## 🌐 Discord Webhooks

When enabled, the plugin posts embeds asynchronously when a KoTH starts, is captured, or ends without a winner:

- Timestamps and a `footer-text • server-ip` footer.
- The winner's head (`https://mc-heads.net/avatar/<PlayerName>/100`) and Champion / Faction / Capture Time fields on captures.
- Texts are editable in the `discord` section of `messages.yml`.

---

## 📊 PlaceholderAPI Expansion

Use `%koth_<placeholder>%` in scoreboards, tablists or chat formats:

| Placeholder | Return Value | Example Output |
|---|---|---|
| `%koth_active%` | Running (or KOTH Arena) arena name | `nether` |
| `%koth_status%` | Current state | `RUNNING`, `CONTESTED`, `WAITING` |
| `%koth_state%` | Current state, including KOTH Arena phases | `RUNNING`, `JOIN`, `POST_CAPTURE` |
| `%koth_capture_time%` / `%koth_time_left%` | Remaining capture time | `04:15` |
| `%koth_capturer%` / `%koth_capper%` | Capturing player's username | `Puk3p` |
| `%koth_capturer_faction%` / `%koth_capper_faction%` | Faction tag of the capturer | `Viper` |
| `%koth_players_in_zone%`, `%koth_contested%`, `%koth_in_zone%` | Zone details (`in_zone` for the viewer) | `3`, `true` |
| `%koth_next%`, `%koth_next_time%`, `%koth_next_in%` | Next scheduled KoTH | `castle`, `Sun 18:00`, `2h 15m` |
| `%koth_join_open%`, `%koth_join_time_left%`, `%koth_participants%`, `%koth_joined%` | KOTH Arena join phase | `true`, `03:00`, `12` |

Faction wins use `%fkoth_<placeholder>%`: `faction_name`, `faction_wins`, `faction_rank`, `top_size`, `top_<n>_name`, `top_<n>_wins`, `player_faction_wins_<player>`.
