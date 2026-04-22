package io.vortexcore.integration.placeholder;

import io.vortexcore.adventure.AdventureBoardService;
import io.vortexcore.adventure.SeasonTrackService;
import io.vortexcore.economy.DynamicMarketManager;
import io.vortexcore.economy.MarketSnapshot;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.profession.ProfessionType;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.quest.QuestService;
import io.vortexcore.quest.QuestState;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.talent.TalentService;
import io.vortexcore.world.BreachSessionSnapshot;
import io.vortexcore.world.ExpeditionBoardManager;
import io.vortexcore.world.ExpeditionRunSnapshot;
import io.vortexcore.world.FractureRegionSnapshot;
import io.vortexcore.world.WorldThreatManager;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

@SuppressWarnings("deprecation")
public final class VortexPlaceholderExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final ProfessionService professionService;
    private final SeasonTrackService seasonTrackService;
    private final AdventureBoardService adventureBoardService;
    private final ExpeditionBoardManager expeditionBoardManager;
    private final WorldThreatManager worldThreatManager;
    private final io.vortexcore.world.MythicBreachManager mythicBreachManager;
    private final DynamicMarketManager dynamicMarketManager;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final QuestService questService;
    private final TalentService talentService;

    public VortexPlaceholderExpansion(
        Plugin plugin,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        ProfessionService professionService,
        SeasonTrackService seasonTrackService,
        AdventureBoardService adventureBoardService,
        ExpeditionBoardManager expeditionBoardManager,
        WorldThreatManager worldThreatManager,
        io.vortexcore.world.MythicBreachManager mythicBreachManager,
        DynamicMarketManager dynamicMarketManager,
        VaultEconomyBridge vaultEconomyBridge,
        QuestService questService,
        TalentService talentService
    ) {
        this.plugin = plugin;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.professionService = professionService;
        this.seasonTrackService = seasonTrackService;
        this.adventureBoardService = adventureBoardService;
        this.expeditionBoardManager = expeditionBoardManager;
        this.worldThreatManager = worldThreatManager;
        this.mythicBreachManager = mythicBreachManager;
        this.dynamicMarketManager = dynamicMarketManager;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.questService = questService;
        this.talentService = talentService;
    }

    @Override
    public String getIdentifier() {
        return "vortexrpg";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        String normalized = params == null ? "" : params.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        if (normalized.equals("vault_provider")) {
            return vaultEconomyBridge.statusSnapshot().providerName();
        }
        if (normalized.equals("vault_available")) {
            return Boolean.toString(vaultEconomyBridge.isAvailable());
        }
        if (normalized.equals("expedition_rotation")) {
            return expeditionBoardManager.currentRotationId();
        }
        if (normalized.startsWith("market_price_")) {
            return formatDouble(dynamicMarketManager.snapshot(normalized.substring("market_price_".length())).livePrice());
        }
        if (normalized.startsWith("market_stock_")) {
            MarketSnapshot snapshot = dynamicMarketManager.snapshot(normalized.substring("market_stock_".length()));
            return Long.toString(snapshot.currentStock());
        }

        UnifiedPlayer profile = resolveProfile(offlinePlayer);
        if (profile == null) {
            return "";
        }

        Player onlinePlayer = offlinePlayer == null ? null : offlinePlayer.getPlayer();

        return switch (normalized) {
            case "level" -> Integer.toString(profile.level());
            case "xp" -> Long.toString(profile.experience());
            case "xp_to_next" -> Long.toString(playerProfileService.experienceToNextLevel(profile));
            case "class" -> classSkillService.selectedClass(profile).map(VortexClass::displayName).orElse("Unchosen");
            case "class_id" -> classSkillService.selectedClass(profile).map(VortexClass::commandId).orElse("none");
            case "class_ascension" -> classSkillService.activeAscension(profile).map(ClassSkillService.ClassAscension::displayName).orElse("none");
            case "skill_points" -> Integer.toString(classSkillService.unspentSkillPoints(profile));
            case "active_spell" -> classSkillService.activeSpellId(profile).orElse("none");
            case "title" -> playerProfileService.activeTitle(profile).orElse("none");
            case "trail" -> playerProfileService.activeTrail(profile).orElse("none");
            case "cast_aura" -> playerProfileService.activeAura(profile).orElse("none");
            case "breach_banner" -> playerProfileService.activeBanner(profile).orElse("none");
            case "breach_tokens" -> Long.toString(playerProfileService.breachTokenBalance(profile));
            case "season_id" -> seasonTrackService.currentSeasonId();
            case "season_level" -> Integer.toString(seasonTrackService.currentLevel(profile));
            case "season_renown" -> Long.toString(seasonTrackService.renown(profile));
            case "season_to_next" -> Long.toString(seasonTrackService.renownToNextLevel(profile));
            case "vault_balance" -> formatDouble(vaultEconomyBridge.balance(offlinePlayer));
            case "vault_balance_formatted" -> vaultEconomyBridge.formatAmount(vaultEconomyBridge.balance(offlinePlayer));
            case "adventure_daily_rotation" -> onlinePlayer == null ? "" : adventureBoardService.dailyRotationId(onlinePlayer);
            case "adventure_weekly_rotation" -> onlinePlayer == null ? "" : adventureBoardService.weeklyRotationId(onlinePlayer);
            case "expedition_title" -> expeditionBoardManager.activeRun(profile.uniqueId()).map(ExpeditionRunSnapshot::title).orElse("none");
            case "expedition_progress" -> expeditionBoardManager.activeRun(profile.uniqueId())
                .map(run -> run.killCount() + "/" + run.targetKills())
                .orElse("0/0");
            case "expedition_score" -> expeditionBoardManager.activeRun(profile.uniqueId())
                .map(run -> formatDouble(run.score()))
                .orElse("0.0");
            case "expedition_party_size" -> expeditionBoardManager.activeRun(profile.uniqueId())
                .map(run -> Integer.toString(run.participantCount()))
                .orElse("0");
            case "breach_phase" -> mythicBreachManager.activeSession(profile.uniqueId())
                .map(session -> Integer.toString(session.phase()))
                .orElse("0");
            case "breach_active_ability" -> mythicBreachManager.activeSession(profile.uniqueId())
                .map(BreachSessionSnapshot::activeAbility)
                .orElse("none");
            case "breach_revives" -> mythicBreachManager.activeSession(profile.uniqueId())
                .map(session -> session.remainingRevives() + "/" + session.totalRevives())
                .orElse("0/0");
            case "fracture_state" -> currentFracture(onlinePlayer).map(snapshot -> snapshot.state().name().toLowerCase(Locale.ROOT)).orElse("purified");
            case "fracture_score" -> currentFracture(onlinePlayer).map(snapshot -> formatDouble(snapshot.threatScore())).orElse("0.0");
            case "fracture_modifiers" -> currentFracture(onlinePlayer)
                .map(snapshot -> snapshot.modifiers().stream().map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(",")))
                .orElse("none");
            case "quest_active_count" -> {
                if (questService == null) yield "0";
                try {
                    var data = questService.questData(profile.uniqueId()).join();
                    yield Long.toString(data.quests().values().stream().filter(q -> q.state() == QuestState.ACTIVE).count());
                } catch (Exception ignored) {
                    yield "0";
                }
            }
            case "quest_completed_count" -> {
                if (questService == null) yield "0";
                try {
                    var data = questService.questData(profile.uniqueId()).join();
                    yield Integer.toString(data.completedQuestLog().size());
                } catch (Exception ignored) {
                    yield "0";
                }
            }
            case "talent_points" -> {
                if (talentService == null) yield "0";
                try {
                    var data = talentService.talentData(profile.uniqueId()).join();
                    yield Integer.toString(talentService.talentPointsAvailable(data));
                } catch (Exception ignored) {
                    yield "0";
                }
            }
            case "talent_nodes_allocated" -> {
                if (talentService == null) yield "0";
                try {
                    var data = talentService.talentData(profile.uniqueId()).join();
                    Optional<VortexClass> cls = classSkillService.selectedClass(profile);
                    if (cls.isEmpty()) yield "0";
                    else yield Integer.toString(data.allocatedNodes().getOrDefault(cls.get().commandId(), java.util.Set.of()).size());
                } catch (Exception ignored) {
                    yield "0";
                }
            }
            default -> dynamicPlaceholder(normalized, profile);
        };
    }

    private UnifiedPlayer resolveProfile(OfflinePlayer offlinePlayer) {
        if (offlinePlayer == null) {
            return null;
        }
        Player player = offlinePlayer.getPlayer();
        if (player != null && player.isOnline()) {
            return playerProfileService.profile(player);
        }
        return playerProfileService.profile(offlinePlayer.getUniqueId()).orElse(null);
    }

    private Optional<FractureRegionSnapshot> currentFracture(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        return worldThreatManager.fractureAt(player.getLocation());
    }

    private String dynamicPlaceholder(String normalized, UnifiedPlayer profile) {
        if (normalized.startsWith("stat_")) {
            String attributeId = normalized.substring("stat_".length());
            try {
                VortexAttribute attribute = VortexAttribute.valueOf(attributeId.toUpperCase(Locale.ROOT));
                return formatDouble(profile.stat(attribute).current());
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }

        if (normalized.startsWith("profession_")) {
            String remainder = normalized.substring("profession_".length());
            ProfessionType profession;
            String field;
            if (remainder.endsWith("_mastery")) {
                profession = ProfessionType.parse(remainder.substring(0, remainder.length() - "_mastery".length()));
                field = "mastery";
            } else if (remainder.endsWith("_to_next")) {
                profession = ProfessionType.parse(remainder.substring(0, remainder.length() - "_to_next".length()));
                field = "to_next";
            } else if (remainder.endsWith("_level")) {
                profession = ProfessionType.parse(remainder.substring(0, remainder.length() - "_level".length()));
                field = "level";
            } else if (remainder.endsWith("_xp")) {
                profession = ProfessionType.parse(remainder.substring(0, remainder.length() - "_xp".length()));
                field = "xp";
            } else {
                return "";
            }
            if (profession == null) {
                return "";
            }
            ProfessionService.ProfessionProgress progress = professionService.progress(profile, profession);
            return switch (field) {
                case "level" -> Integer.toString(progress.level());
                case "xp" -> Long.toString(progress.experience());
                case "to_next" -> Long.toString(progress.experienceToNextLevel());
                case "mastery" -> Boolean.toString(professionService.hasMastery(profile, profession));
                default -> "";
            };
        }

        return "";
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
