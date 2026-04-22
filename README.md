# VortexRPG

Free, open-source Minecraft RPG plugin for Paper, Spigot, and Folia.

![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Minecraft 1.21+](https://img.shields.io/badge/Minecraft-1.21%2B-3C8527?style=flat-square)
![Paper](https://img.shields.io/badge/Platform-Paper-6DB33F?style=flat-square)
![Spigot](https://img.shields.io/badge/Platform-Spigot-F47A20?style=flat-square)
![Folia](https://img.shields.io/badge/Platform-Folia-00A3FF?style=flat-square)

VortexRPG is a data-driven RPG framework for Minecraft servers that combines classes, spellcasting, progression, professions, quests, world events, dungeons, bosses, custom items, economy systems, and in-game admin tooling in one plugin.

## Overview

VortexRPG is built for servers that want more than a basic skill plugin. It ships with player-facing progression systems, world content loops, and admin tools for editing RPG content directly in-game.

Out of the box, the plugin includes:

- Playable classes with starter items, class-specific abilities, and ascension paths
- Spellcasting, active spell loadouts, spellbook menus, and spell editing tools
- Character stats, talents, progression, and scoreboard/HUD support
- Professions, crafting stations, gathering hooks, and dynamic economy systems
- Quests, adventure boards, seasonal reward tracks, expeditions, fractures, and gateways
- Custom items, effects, bosses, dungeons, party systems, and an auction house
- Admin forges, diagnostics, validation tools, hot reload commands, and support dump generation

## Feature Set

| System | What it includes |
| --- | --- |
| Classes | Class selection, class inspection, ascension paths, class kits, starter relics, and class-specific combat flow |
| Spells | Spellbook GUI, active spell equip/cast flow, spell registry, built-in spell editing, spell forge, and effect bindings |
| Progression | Stats, XP curve, talent trees, skill unlocking, point allocation, and live attribute updates |
| Content Loops | Quests, adventure board objectives, season tracks, expeditions, fractures, breaches, gateways, bosses, and dungeons |
| Items | Custom items, custom effects, click-based class weapons, item forge tooling, locked relic handling, and resource-pack-ready visuals |
| Economy | Vault support, dynamic market pricing, auction house, breach rewards, and profession/crafting hooks |
| Social | Parties, party chat, ready checks, summons, and shared group flows for expeditions and encounters |
| Integrations | Vault, PlaceholderAPI, WorldGuard, Citizens, and optional resource pack delivery |
| Admin Tools | In-game forges, hot reload, diagnostics, persistence snapshots, validation checks, scoreboard controls, and support dump export |

## Included Gameplay Areas

### Core player systems

- `/class` for class selection, info, specs, and ascension
- `/skills`, `/spell`, and `/cast` for spell and skill management
- `/stats` and `/talent` for progression and passive growth
- `/professions` for gathering and station access
- `/quest` and `/adventure` for repeatable and seasonal progression

### World and encounter systems

- `/fracture` for rift tracking, gateway travel, and realm administration
- `/expeditions` for contracts, breach finales, leaderboards, and party content
- `/vboss` for world boss browsing, summoning, and editing
- `/vdungeon` for instanced dungeon browsing, entry, and administration

### Economy and social systems

- `/auction` for player listings, browsing, and selling
- `/party` and `/pc` for party management and party chat

### Admin and content creation

- `/vortex` for admin toolkit access
- `/vrp` as a unified command hub for the full plugin surface
- In-game forge editors for classes, spells, effects, items, bosses, and other RPG content
- Hot reload commands for spell, effect, and item registries without restarting the server

## Supported Platforms

- Paper 1.21+
- Folia
- Spigot 1.21+ via the dedicated Spigot build
- Java 21

## Installation

1. Install Java 21 on your server host.
2. Choose the correct jar for your server:
   - Paper/Folia: the standard shadow jar from `build/libs/`
   - Spigot: the `-spigot.jar` build from `build/libs/`
3. Drop the jar into your server's `plugins/` folder.
4. Start the server once to generate configuration files.
5. Edit the YAML files in `plugins/VortexRPG/` to fit your server.
6. Use the in-game admin tooling and hot reload commands to iterate on content.

## Configuration Files

VortexRPG ships with editable YAML resources for its major systems:

| File | Purpose |
| --- | --- |
| `config.yml` | Core settings, integrations, scoreboard, resource-pack delivery, economy tuning, and system toggles |
| `spells.yml` | Spell definitions, mechanics, targeters, scaling, and gating |
| `effects.yml` | Status effects and reusable combat modifiers |
| `items.yml` | Custom items, class relics, click bindings, and item overrides |
| `quests.yml` | Quest content and progression rules |
| `talents.yml` | Talent tree and passive progression definitions |
| `fractures.yml` | Fracture pressure, breach pacing, rewards, and world-state tuning |
| `gateways.yml` | Gateway destinations and travel configuration |
| `bosses.yml` | Boss encounter definitions and admin-editable boss data |
| `dungeons.yml` | Dungeon instances, entry rules, and dungeon metadata |

## Optional Integrations

| Plugin | Usage |
| --- | --- |
| Vault | Economy rewards, balance hooks, and reward payouts |
| PlaceholderAPI | Placeholder expansion for external displays and menus |
| WorldGuard | Protection-aware hooks and safer world interactions |
| Citizens | NPC role bindings and neural guide interactions |

## Building From Source

### Windows

```powershell
.\gradlew.bat test shadowJar spigotJar
```

### macOS / Linux

```bash
./gradlew test shadowJar spigotJar
```

Build outputs are written to `build/libs/`.

## Development Notes

- Gradle Kotlin DSL build
- Java 21 toolchain
- JUnit 5 test suite
- Paper/Folia main build plus a dedicated Spigot jar task
- Shaded runtime libraries include bStats, Jackson, and Caffeine

## Getting Started In-Game

For a clean first boot flow:

1. Join the server and choose a class with `/class`.
2. Open the spellbook with `/spell` and equip an active spell.
3. Review stats with `/stats` and talents with `/talent`.
4. Explore quests, professions, adventure boards, fractures, and expeditions.
5. Use `/vrp` to discover the unified command hub.

## Project Goal

VortexRPG is intended to be a complete foundation for Minecraft RPG servers: open-source, configurable, and broad enough to support custom classes, combat, economy, world content, and ongoing live operations from one codebase.