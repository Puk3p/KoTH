# 🚩 KoTH (King of the Hill)

A King of the Hill plugin for competitive faction servers on **Spigot / Paper 1.8.8**. Faction capture, an LMS-style KOTH Arena event, faction win tracking (`/fkoth`), a selection wand with particle preview, per-type protections and rewards, a weekly scheduler, PlaceholderAPI, DecentHolograms and Discord webhooks.

[![Build](https://github.com/Puk3p/KoTH/actions/workflows/build.yml/badge.svg)](https://github.com/Puk3p/KoTH/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Puk3p/KoTH)](https://github.com/Puk3p/KoTH/releases/latest)

---

## 📋 Table of Contents

1. [Features](#-features)
2. [Requirements](#-requirements)
3. [Installation](#-installation)
4. [Commands](#-commands)
5. [Permissions](#-permissions)
6. [Arena Setup](#️-arena-setup)
7. [Configuration (config.yml)](#️-configuration-configyml)
8. [Arenas & Schedules (arenas.yml)](#-arenas--schedules-arenasyml)
9. [KOTH Arena (LMS-style event)](#️-koth-arena-lms-style-event)
10. [Faction KoTH Wins (/fkoth)](#-faction-koth-wins-fkoth)
11. [Messages (messages.yml)](#-messages-messagesyml)
12. [Staff & Vanish](#️-staff--vanish)
13. [Discord Webhooks](#-discord-webhooks)
14. [PlaceholderAPI](#-placeholderapi)
15. [Developer API](#-developer-api)
16. [Building & Releases](#️-building--releases)

---

## ⚡ Features

- **KoTH types** — As many types as you want (`koth`, `superkoth`, `arena` by default), each with its own capture time, knock behavior, protections, command restrictions, visuals, sounds and rewards.
- **Faction capture** — Players of the same faction capture together; the zone is contested only by players of different factions. Can be turned off for solo capture.
- **Knock behavior** — `PAUSE` (the next holder continues where it stopped) or `RESET` (the timer starts over). A maximum run time ends an uncaptured KoTH as a draw, so it never blocks later ones.
- **KOTH Arena** — An LMS-style event: `/koth join` before the start, invincibility and command lock until the fight, one life, a post-capture countdown and a teleport out.
- **Faction KoTH wins** — Every capture adds a win to the winner's faction: `/fkoth top` and `stats`, a DecentHolograms top hologram, placeholders and an API other plugins (e.g. DinoFtop) read from. Built in; replaces the old FKothWins addon.
- **Selection wand** — `/koth wand`: left / right click two corner blocks. The selection is drawn in particles, visible only to you.
- **Protections** — Per-type zone flags for block breaking / placing, buckets, enderpearls, fall damage, lava / water flow, TNT and pistons.
- **Command restrictions** — `BLACKLIST` or `WHITELIST` commands (`/f home`, `/spawn`, `/ec`, ...) for players standing in a running zone.
- **Visuals & sounds** — Action bar timer with a `CONTESTED` warning, start / capture titles, a firework on capture, and a sound with volume and pitch for every stage.
- **Rewards** — `ALL` (every command) or `CHANCE` (a percentage roll per command, for rare keys).
- **Scheduler** — Weekly times per arena in any timezone, with a minimum number of online players per type.
- **Staff friendly** — Vanished staff, spectators and (optionally) creative players never capture or contest.
- **Discord webhooks** — Start, capture and draw embeds with the winner's head.

---

## 🔌 Requirements

| | Type | Notes |
|---|---|---|
| Spigot / Paper 1.8.8 | Required | Java 8 or newer. Kotlin is bundled in the jar. |
| Factions (FactionsUUID or SaberFactions) | Optional | Legacy `com.massivecraft.factions` API. Needed for faction capture and faction wins; without it every player captures alone. |
| PlaceholderAPI | Optional | `%koth_...%` and `%fkoth_...%` placeholders, PlaceholderAPI placeholders in reward commands. |
| DecentHolograms | Optional | The faction wins top hologram. |
| Multiverse-Core | Optional | Only so its worlds are loaded before arenas and locations are read. |
| PremiumVanish / SuperVanish / Essentials | Optional | Detected through the standard `vanished` metadata; no API needed. |

---

## 📦 Installation

1. Download `KoTH-<version>.jar` from the [latest release](https://github.com/Puk3p/KoTH/releases/latest).
2. Put it in `plugins/` and install the optional plugins you want.
3. Coming from the **FKothWins** addon? Remove `FKothWins.jar`. On the first start its `plugins/FKothWins/faction-wins.yml` is imported automatically. While FKothWins is still installed, faction wins stay disabled.
4. Start the server. `config.yml`, `messages.yml` and `arenas.yml` are created in `plugins/KoTH/`.

Updating: replace the jar and restart. Messages added in a new version work right away, even with an older `messages.yml` (see [Messages](#-messages-messagesyml)).

---

## 📜 Commands

`/koth` (alias `/koths`) and `/koth help` list only the commands you have permission for. Tab completion follows the same rule.

### Players

| Command | Permission | Description |
|---|---|---|
| `/koth join` | `koth.join` | Join the KOTH Arena during its join phase (teleports you in). |
| `/koth leave` | `koth.join` | Leave the KOTH Arena. You can't rejoin the same event. |
| `/fkoth stats` | `fkoth.player` | Your faction's KoTH wins. |
| `/fkoth top` | `fkoth.player` | Top factions by KoTH wins. |

### Admins

| Command | Permission | Description |
|---|---|---|
| `/koth wand` | `koth.admin` | Get the selection wand: left click a block for corner 1, right click for corner 2. |
| `/koth pos1` / `pos2` | `koth.admin` | Set a corner to the block you're standing in. |
| `/koth create <name> <type>` | `koth.admin` | Create an arena from your selection. `<type>` is a key under `types` in `config.yml`. |
| `/koth setspawn <name>` | `koth.admin` | Save your position as the arena's spawn point in `arenas.yml` (stored only; nothing teleports there yet). |
| `/koth delete <name>` | `koth.admin` | Delete an arena. |
| `/koth list` | `koth.admin` | List arenas with their type, state and whether they're enabled. |
| `/koth setarena` / `setexit` | `koth.admin` | Set the KOTH Arena location / exit location to your position (saved in `config.yml`). |
| `/koth start <name>` | `koth.start` | Start a KoTH. KOTH Arena types open `/koth join` first. |
| `/koth stop [name]` | `koth.stop` | Stop the running KoTH or KOTH Arena event. |
| `/koth reload` | `koth.reload` | Reload `config.yml`, `messages.yml`, `arenas.yml` and the schedules. Refused while a KoTH runs. |
| `/fkoth add <player> <n>` | `fkoth.admin` | Add wins to a player's faction (offline players too, if `allow-offline-player-lookup`). |
| `/fkoth remove <faction> <n>` | `fkoth.admin` | Remove wins from a faction. |
| `/fkoth set <faction> <n>` | `fkoth.admin` | Set a faction's wins. |
| `/fkoth sethologram` | `fkoth.admin` | Place the top factions hologram at your position. |
| `/fkoth reload` / `debug` | `fkoth.admin` | Reload the faction wins settings / show the status of every integration. |

`/fkoth` also has the alias `/fkw`.

---

## 🔐 Permissions

| Permission | Default | Allows |
|---|---|---|
| `koth.admin` | op | Every `/koth` admin command; also ignores zone protections and command restrictions. |
| `koth.start` | op | `/koth start` only. |
| `koth.stop` | op | `/koth stop` only. |
| `koth.reload` | op | `/koth reload` only. |
| `koth.join` | everyone | `/koth join` and `/koth leave`. |
| `koth.bypass` | op | Ignore zone protections, command restrictions and the KOTH Arena command lock. |
| `fkoth.admin` | op | `/fkoth add`, `remove`, `set`, `reload`, `debug`, `sethologram`. |
| `fkoth.player` | everyone | `/fkoth stats` and `/fkoth top`. |

---

## 🛠️ Arena Setup

1. **Get the wand**

   ```
   /koth wand
   ```

2. **Select the zone** — Left click the block at one corner and right click the block at the opposite corner, a few blocks higher: the zone covers the whole height between them. The clicked blocks aren't broken, and the size is shown once both corners are set.

   While you hold the wand (and for 10 seconds after setting a corner) the selection is drawn in particles that only you see: a green column at corner 1, a red one at corner 2, then the zone's full outline.

   No wand? Stand at each corner and use `/koth pos1` and `/koth pos2`.

3. **Create the arena** — Pick a name (letters, digits, `_` and `-`, up to 32) and a type:

   ```
   /koth create nether koth
   ```

   Zones lower than 3 blocks are extended upward, so a jumping player doesn't leave the zone.

4. **Schedule it** (optional) — Add a `schedule` to the arena in `arenas.yml`, then `/koth reload`. Or start it by hand with `/koth start nether`.

For a KOTH Arena (type `arena`), also set `/koth setarena` (where `/koth join` teleports players) and `/koth setexit` (where they go afterwards).

---

## ⚙️ Configuration (config.yml)

The generated `config.yml` documents every option. The main parts:

```yaml
settings:
  timezone: "UTC"            # scheduler timezone, e.g. Europe/Bucharest
  faction-capture: true      # same-faction players capture together
  ignore-creative: true      # creative players don't capture or contest

types:
  koth:
    capture-time: 300            # seconds to hold the zone
    knock-behavior: "PAUSE"      # PAUSE or RESET
    minimum-players-online: 10   # scheduled starts are skipped below this (0 = always)
    max-run-time: 3600           # uncaptured after this = draw (0 = never)

    protections:                 # true = protected inside the zone (running or not)
      block-breaking: true
      block-placing: true
      bucket-use: true
      enderpearls: false         # throwing in / landing in the zone
      fall-damage: false         # false = no fall damage in the zone
      lava-flow: false
      water-flow: false
      tnt: false                 # no block damage
      pistons: false

    command-restrictions:        # for players standing in the running zone
      enabled: true
      mode: "BLACKLIST"          # BLACKLIST or WHITELIST
      blocked-commands: ["ec", "enderchest", "pv", "f home", "spawn", "home"]

    visuals:
      action-bar:
        enabled: true
        text: "&b&lCapturing %arena% &8» &f%time%"
        contested-text: "&c&lCONTESTED! &7Clear the zone!"
      title-on-start: { enabled: true, title: "&a&lKOTH STARTED", subtitle: "&e%arena% &7is now active!", fade-in: 10, stay: 40, fade-out: 10 }
      title-on-capture: { enabled: true, title: "&6&lKOTH CAPTURED", subtitle: "&e%player% &7has captured &e%arena%!" }
      firework-on-capture: true

    sounds:                      # 1.8 sound names
      start: { sound: "ENDERDRAGON_GROWL", volume: 1.0, pitch: 1.0 }
      capturing-tick: { enabled: true, sound: "CLICK", volume: 0.5, pitch: 1.5 }
      contested: { enabled: true, sound: "NOTE_BASS", volume: 0.8, pitch: 0.5 }
      captured: { sound: "LEVEL_UP", volume: 1.0, pitch: 1.0 }

    rewards:
      mode: "ALL"                # ALL runs every command; CHANCE rolls each one's chance (percent)
      commands:
        - command: "give %player% diamond 16"
          chance: 100.0
        - command: "eco give %player% 5000"
          chance: 100.0
```

- Command restrictions match whole words: `"f home"` blocks `/f home` but not `/f show`. `/koth` always works.
- Title times are in ticks. Titles can use `%arena%` and `%type%` (start) or `%player%` and `%faction%` (capture).
- Reward placeholders: `%player%`, `%arena%`, `%faction%`, `%type%`, plus PlaceholderAPI placeholders.
- The other sections (`discord`, `faction-wins`, `koth-arena`) are described below.

---

## 📅 Arenas & Schedules (arenas.yml)

`/koth create` writes arenas to `arenas.yml`. Schedules are added by hand, then applied with `/koth reload`.

```yaml
arenas:
  nether:
    type: "koth"
    enabled: true            # false = never starts from the schedule
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
      daily: "12:00"         # a single time works too
```

- Times use `settings.timezone`. Always quote them.
- Day keys are English day names in any case, or `daily`. Invalid days or times are skipped with a console warning.
- Only one KoTH runs at a time. A slot that comes up while another KoTH runs is skipped (see the console), as is one with fewer players online than the type's `minimum-players-online`.

---

## ⚔️ KOTH Arena (LMS-style event)

Types with `koth-arena: true` (the `arena` type by default) run as an event:

1. **Join** — `/koth start`, or the schedule `join-time` seconds before the slot, opens the join and announces it. `/koth join` teleports the player to the arena location with their own inventory. Joined players are invincible, can't hurt anyone and can only use `allowed-commands`. The countdown is broadcast at `countdown-announcements`.
2. **Fight** — When the countdown ends, invincibility is removed and the KoTH starts. Only joined players can capture, and players in the zone who didn't join are sent out. With fewer than `min-players` joined, the event is cancelled. Dying, logging out, `/koth leave`, being teleported away or going past `arena-radius` eliminates a player for good. Dead players respawn at the exit location.
3. **After the capture** — The winner is announced to everyone. Players stay `post-capture-time` seconds (with a countdown), then everyone still in the arena is teleported to the exit location.

```yaml
koth-arena:
  enabled: true
  join-time: 300                 # join phase length, before the start
  post-capture-time: 60          # time in the arena after the capture
  min-players: 2                 # cancelled if fewer joined
  countdown-announcements: [240, 180, 120, 60, 30, 10, 5, 4, 3, 2, 1]
  invincible-after-capture: true # nobody can be killed after the winner is decided
  block-outsider-pvp: true       # players in and out of the event can't hurt each other
  arena-radius: 0                # blocks from arena-location; 0 = no limit
  arena-location: { world: "", x: 0.0, y: 0.0, z: 0.0, yaw: 0.0, pitch: 0.0 }  # /koth setarena
  exit-location: { world: "", x: 0.0, y: 0.0, z: 0.0, yaw: 0.0, pitch: 0.0 }   # /koth setexit
  allowed-commands: ["msg", "r", "tell"]
```

Until `arena-location` points to a loaded world, arena KoTHs refuse to start. An empty or unknown exit world means the main world's spawn. `koth.bypass` / `koth.admin` ignore the command lock.

---

## 🏆 Faction KoTH Wins (/fkoth)

Every capture adds one win to the capturer's faction.

- `/fkoth top`, `/fkoth stats` and the `%fkoth_...%` placeholders.
- A top factions hologram with DecentHolograms, placed with `/fkoth sethologram`.
- Wins follow a faction when it changes its tag and are removed when it disbands (if the Factions version has those events; `/fkoth debug` shows it).
- Other plugins read the wins through the `FKothApi` service (see [Developer API](#-developer-api)).

```yaml
faction-wins:
  enabled: true
  storage-file: "faction-wins.yml"
  ignore-no-faction-winner: true      # false: factionless captures go to "Wilderness"
  delete-wins-on-faction-disband: true
  allow-offline-player-lookup: true   # /fkoth add works for offline players
  top-size: 10                        # entries in /fkoth top
  hologram:
    enabled: true
    id: "fkoth_top5"
    refresh-seconds: 15
    entries: 5
    title: "&6&lTop 5 KOTH Factions"
    line-format: "&e#%position%. &f%faction% &7- &6%wins%"
    empty-line-format: "&e#%position%. &7-"
  hologram-location: { world: "", x: 0.0, y: 0.0, z: 0.0, yaw: 0.0, pitch: 0.0 }  # /fkoth sethologram
```

---

## 💬 Messages (messages.yml)

Every text the plugin sends is in `messages.yml`, with `&` color codes and a global `prefix`. Sections: `general`, `admin`, `help`, `events`, `koth-arena`, `time-units`, `fkoth`, `wand`, `protection`, `placeholders`, `discord`.

- **Missing keys** fall back to the bundled text, so an older `messages.yml` keeps working after an update. A file with a YAML error is copied next to itself as `<name>.broken-<date>`, and the console says what to fix.
- **Help** — `help.header` plus one line per command (`help.wand`, `help.create`, ...). Only the lines for commands the sender may use are shown. `%types%` in `help.create` lists the types from `config.yml`.
- **Emoji** — The YAML parser in 1.8.8 can't read emoji such as 🏆, not even in comments. Write them as escapes in double quotes: `"\U0001F3C6"`.

---

## 🕵️ Staff & Vanish

- Vanished players (PremiumVanish, SuperVanish, Essentials), spectators and, with `ignore-creative`, creative players in a zone don't count for capture or contest.
- Vanished staff and players with `koth.bypass` / `koth.admin` can enter KOTH Arena zones and aren't sent out at the start of the fight.

---

## 🌐 Discord Webhooks

Embeds are posted asynchronously when a KoTH starts, is captured or ends without a winner.

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/your-webhook-url-here"
  server-ip: "play.yourserver.net"   # in the footer
  footer-text: "KoTH Network"        # next to the IP
  username: "KoTH Alerts"            # name the webhook posts as
  avatar-url: "https://mc-heads.net/avatar/MHF_Chest/100"
  player-heads: true                 # winner's head on capture embeds
  colors:
    started: "#00FF00"
    captured: "#FFA500"
    ended: "#FF0000"
```

Capture embeds show the winner's head (`https://mc-heads.net/avatar/<player>/100`) and Champion / Faction / Capture Time fields. The texts are in the `discord` section of `messages.yml`.

---

## 📊 PlaceholderAPI

`%koth_<placeholder>%`:

| Placeholder | Value | Example |
|---|---|---|
| `active`, `active_type` | Running arena (or the one used by a KOTH Arena event) and its type | `nether`, `koth` |
| `state` | `RUNNING`, `CONTESTED`, `JOIN` or `POST_CAPTURE` | `RUNNING` |
| `time_left`, `time_left_seconds` | Capture time left | `04:15`, `255` |
| `capper`, `capper_faction` | Player credited with the capture, and their faction | `Puk3p`, `Viper` |
| `players_in_zone`, `contested`, `in_zone` | Zone details (`in_zone` is for the viewing player) | `3`, `true`, `false` |
| `next`, `next_time`, `next_in` | Next scheduled arena, when, and how long until | `castle`, `Sun 18:00`, `2h 15m` |
| `join_open`, `join_time_left`, `participants`, `joined` | KOTH Arena join phase | `true`, `03:00`, `12`, `true` |
| `status`, `capture_time`, `capturer`, `capturer_faction` | Older names, kept for existing scoreboards | `WAITING`, `00:00`, `None` |

When nothing is running, the name, state and time placeholders return `placeholders.none` from `messages.yml`; counts return `0`.

`%fkoth_<placeholder>%`: `faction_name`, `faction_wins`, `faction_rank`, `top_size`, `top_<n>_name`, `top_<n>_wins`, `player_faction_wins_<player>` (same names as the FKothWins addon).

---

## 🧩 Developer API

Add KoTH from [JitPack](https://jitpack.io/#Puk3p/KoTH) as a `provided` dependency, and `softdepend: [KoTH]` (or `depend`) in your `plugin.yml`:

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.Puk3p</groupId>
    <artifactId>KoTH</artifactId>
    <version>v1.1.1</version>
    <scope>provided</scope>
</dependency>
```

**Faction wins** — `ro.puk3p.fkoth.api.FKothApi`, registered in the ServicesManager (same API as the FKothWins addon):

```java
RegisteredServiceProvider<FKothApi> rsp = Bukkit.getServicesManager().getRegistration(FKothApi.class);
if (rsp != null) {
    FKothApi api = rsp.getProvider();
    int wins = api.getWinsForFaction("Viper");
    List<FactionWinsSnapshot> top = api.top(10);
}
```

Methods: `getWinsForFaction`, `getWinsForPlayerFaction`, `getFactionForPlayer`, `addWinsForFaction`, `setWinsForFaction`, `removeWinsForFaction`, `top`.

**Events** (`com.puk3p.koth.event`):

| Event | When | Cancelling it |
|---|---|---|
| `KothStartEvent` | Right before a KoTH starts (`getArena()`) | The arena stays idle. |
| `KothCaptureEvent` | A capture timer reaches zero, before rewards (`getArena()`, `getCapturer()`) | Denies the win for now; it fires again next second if the player still holds the zone. |

---

## 🏗️ Building & Releases

```bash
git clone https://github.com/Puk3p/KoTH.git
cd KoTH
mvn clean package
```

The jar is `target/KoTH-<version>.jar`. `package` works on any JDK from 8 to 25.

`mvn verify` also runs **Spotless** (ktlint) and **SpotBugs**; use JDK 17 for it, like CI. Fix formatting with `mvn spotless:apply`. SpotBugs exclusions are in `spotbugs-exclude.xml`.

**CI** (`.github/workflows/build.yml`) — On every push to `main` / `develop` and every pull request to `main`: Spotless check, compile, SpotBugs, package. The jar is uploaded as a build artifact.

**Releases** (`.github/workflows/release.yml`) — Push a `vX.Y.Z` tag:

```bash
git tag -a v1.2.0 -m "v1.2.0"
git push origin v1.2.0
```

The workflow sets the version from the tag, runs the same checks, publishes a GitHub release with `KoTH-X.Y.Z.jar` and posts it to the Discord operator thread. It needs the repository secrets `URL_WEBHOOK_OPERATOR` (webhook URL) and `OPERATOR_ROLE_ID` (role to mention).
