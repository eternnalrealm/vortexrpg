package io.vortexcore.ui;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.quest.QuestService;
import io.vortexcore.quest.QuestState;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.talent.TalentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.attribute.Attribute;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class ScoreboardService implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String OBJECTIVE_NAME = "vortexrpg";
    /** Invisible colour codes used as unique entries per line. */
    private static final String[] ENTRY_KEYS = new String[]{
        "\u00a70\u00a7r", "\u00a71\u00a7r", "\u00a72\u00a7r", "\u00a73\u00a7r",
        "\u00a74\u00a7r", "\u00a75\u00a7r", "\u00a76\u00a7r", "\u00a77\u00a7r",
        "\u00a78\u00a7r", "\u00a79\u00a7r", "\u00a7a\u00a7r", "\u00a7b\u00a7r",
        "\u00a7c\u00a7r", "\u00a7d\u00a7r", "\u00a7e\u00a7r"
    };

    private final FoliaExecutionFacade scheduler;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final QuestService questService;
    private final TalentService talentService;
    private final int maxActiveQuests;

    private volatile boolean enabled;
    private volatile Component title;
    private volatile List<String> lineTemplates;
    private final Map<UUID, Scoreboard> playerBoards = new ConcurrentHashMap<>();
    private VortexTask updateTask;

    public ScoreboardService(
        FoliaExecutionFacade scheduler,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        VaultEconomyBridge vaultEconomyBridge,
        QuestService questService,
        TalentService talentService,
        int maxActiveQuests,
        boolean enabled,
        String titleTemplate,
        List<String> lineTemplates,
        long updateTicks
    ) {
        this.scheduler = scheduler;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.questService = questService;
        this.talentService = talentService;
        this.maxActiveQuests = maxActiveQuests;
        this.enabled = enabled;
        this.title = MINI.deserialize(titleTemplate);
        this.lineTemplates = List.copyOf(lineTemplates);

        if (enabled) {
            this.updateTask = scheduler.runGlobalTimer(
                "scoreboard-update",
                updateTicks,
                updateTicks,
                task -> refreshAll()
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean state) {
        this.enabled = state;
        if (!state) {
            if (updateTask != null) {
                updateTask.cancel();
                updateTask = null;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                removeBoard(player);
            }
            playerBoards.clear();
        }
    }

    public void reload(boolean enabledFlag, String titleTemplate, List<String> newLines, long updateTicks) {
        setEnabled(false);
        this.title = MINI.deserialize(titleTemplate);
        this.lineTemplates = List.copyOf(newLines);
        if (enabledFlag) {
            this.enabled = true;
            this.updateTask = scheduler.runGlobalTimer(
                "scoreboard-update",
                updateTicks,
                updateTicks,
                task -> refreshAll()
            );
            for (Player player : Bukkit.getOnlinePlayers()) {
                createBoard(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        scheduler.runEntity(player, "scoreboard-join-" + player.getUniqueId(), 5L, () -> createBoard(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerBoards.remove(event.getPlayer().getUniqueId());
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        playerBoards.clear();
    }

    private void createBoard(Player player) {
        if (!enabled || !player.isOnline()) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int lineCount = Math.min(lineTemplates.size(), ENTRY_KEYS.length);
        for (int i = 0; i < lineCount; i++) {
            String entry = ENTRY_KEYS[i];
            Team team = board.registerNewTeam("line_" + i);
            team.addEntry(entry);
            objective.getScore(entry).setScore(lineCount - i);
        }

        playerBoards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateBoard(player, board);
    }

    private void removeBoard(Player player) {
        if (player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = playerBoards.get(player.getUniqueId());
            if (board == null) {
                if (enabled) {
                    scheduler.runEntity(player, "scoreboard-late-create-" + player.getUniqueId(), () -> createBoard(player));
                }
                continue;
            }
            scheduler.runEntity(player, "scoreboard-refresh-" + player.getUniqueId(), () -> updateBoard(player, board));
        }
    }

    private void updateBoard(Player player, Scoreboard board) {
        if (!player.isOnline()) return;

        UnifiedPlayer profile = playerProfileService.profile(player);
        String className = classSkillService.selectedClass(profile).map(VortexClass::displayName).orElse("Unchosen");
        String classId = classSkillService.selectedClass(profile).map(VortexClass::commandId).orElse("none");
        int level = profile.level();
        long xp = profile.experience();
        long xpNext = playerProfileService.experienceToNextLevel(profile);
        long breachTokens = playerProfileService.breachTokenBalance(profile);
        String balance = vaultEconomyBridge.isAvailable()
            ? vaultEconomyBridge.formatAmount(vaultEconomyBridge.balance(player))
            : "N/A";
        int skillPoints = classSkillService.unspentSkillPoints(profile);

        // Quest count (sync from cache, don't block)
        int questsActive = 0;
        try {
            if (questService != null) {
                var data = questService.questData(profile.uniqueId()).getNow(null);
                if (data != null) {
                    questsActive = (int) data.quests().values().stream()
                        .filter(q -> q.state() == QuestState.ACTIVE).count();
                }
            }
        } catch (Exception ignored) { }

        int talentPoints = 0;
        try {
            if (talentService != null) {
                var data = talentService.talentData(profile.uniqueId()).getNow(null);
                if (data != null) {
                    talentPoints = talentService.talentPointsAvailable(data);
                }
            }
        } catch (Exception ignored) { }

        int lineCount = Math.min(lineTemplates.size(), ENTRY_KEYS.length);
        for (int i = 0; i < lineCount; i++) {
            String template = lineTemplates.get(i);
            String resolved = template
                .replace("{class}", className)
                .replace("{class_id}", classId)
                .replace("{level}", Integer.toString(level))
                .replace("{xp}", Long.toString(xp))
                .replace("{xp_next}", Long.toString(xpNext))
                .replace("{currency}", balance)
                .replace("{breach_tokens}", Long.toString(breachTokens))
                .replace("{skill_points}", Integer.toString(skillPoints))
                .replace("{quests_active}", Integer.toString(questsActive))
                .replace("{quests_max}", Integer.toString(maxActiveQuests))
                .replace("{talent_points}", Integer.toString(talentPoints))
                .replace("{player}", player.getName())
                .replace("{health}", Integer.toString((int) player.getHealth()))
                .replace("{max_health}", Integer.toString((int) player.getAttribute(Attribute.MAX_HEALTH).getBaseValue()));

            Component lineComponent = MINI.deserialize(resolved);
            Team team = board.getTeam("line_" + i);
            if (team != null) {
                team.prefix(lineComponent);
            }
        }

        // Update title in case it changed
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective != null) {
            objective.displayName(title);
        }
    }
}
