package io.vortexcore;

import io.vortexcore.adventure.AdventureBoardService;
import io.vortexcore.auction.AuctionCommand;
import io.vortexcore.auction.AuctionRepository;
import io.vortexcore.auction.AuctionService;
import io.vortexcore.boss.BossCommand;
import io.vortexcore.boss.BossForgeService;
import io.vortexcore.boss.BossRegistry;
import io.vortexcore.boss.BossService;
import io.vortexcore.adventure.SeasonTrackService;
import io.vortexcore.command.AdventureCommand;
import io.vortexcore.command.ExpeditionsCommand;
import io.vortexcore.command.FractureCommand;
import io.vortexcore.command.ClassCommand;
import io.vortexcore.command.ProfessionsCommand;
import io.vortexcore.command.QuestCommand;
import io.vortexcore.command.SkillsCommand;
import io.vortexcore.command.SpellCommand;
import io.vortexcore.command.StatsCommand;
import io.vortexcore.command.CastCommand;
import io.vortexcore.command.TalentCommand;
import io.vortexcore.command.VortexCommand;
import io.vortexcore.command.VrpCommand;
import io.vortexcore.config.ResourceMigrationService;
import io.vortexcore.cosmetic.PrestigeCosmeticService;
import io.vortexcore.dungeon.DungeonCommand;
import io.vortexcore.dungeon.DungeonRegistry;
import io.vortexcore.dungeon.DungeonService;
import io.vortexcore.party.PartyCommand;
import io.vortexcore.party.PartyService;
import io.vortexcore.combat.CombatEngine;
import io.vortexcore.combat.ManaManager;
import io.vortexcore.combat.PlayerHUD;
import io.vortexcore.ecology.ChunkKarmaManager;
import io.vortexcore.diagnostics.DiagnosticsTracker;
import io.vortexcore.economy.DynamicMarketManager;
import io.vortexcore.integration.citizens.CitizensIntegrationService;
import io.vortexcore.integration.placeholder.VortexPlaceholderExpansion;
import io.vortexcore.forge.ForgeInputGuard;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.instances.InstanceController;
import io.vortexcore.item.CustomItemDefinition;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.item.ItemForgeService;
import io.vortexcore.item.CustomItemRuntimeService;
import io.vortexcore.loot.GaussianLootGenerator;
import io.vortexcore.math.ExperienceCurve;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.npc.LLMEndpoint;
import io.vortexcore.npc.LLMProviderType;
import io.vortexcore.npc.NeuralNPCManager;
import io.vortexcore.persistence.PlayerDataRepository;
import org.bstats.bukkit.Metrics;
import io.vortexcore.physics.PhysicsFier;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.quest.QuestBoardMenu;
import io.vortexcore.quest.QuestRegistry;
import io.vortexcore.quest.QuestRepository;
import io.vortexcore.quest.QuestService;
import io.vortexcore.quest.listener.QuestEventListener;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.ClassForgeService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellForgeService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.rpg.effects.EffectForgeService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.resourcepack.VortexResourcePackService;
import io.vortexcore.scheduling.BukkitExecutionFacade;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.support.SupportDumpService;
import io.vortexcore.talent.TalentRegistry;
import io.vortexcore.talent.TalentRepository;
import io.vortexcore.talent.TalentService;
import io.vortexcore.talent.TalentTreeMenu;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.ProfileMenuService;
import io.vortexcore.ui.ScoreboardService;
import io.vortexcore.ui.VortexMenuBuilder;
import io.vortexcore.world.ExpeditionBoardManager;
import io.vortexcore.world.ExpeditionPartyManager;
import io.vortexcore.world.BreachQuartermasterService;
import io.vortexcore.world.FractureGatewayService;
import io.vortexcore.world.FractureNavigatorService;
import io.vortexcore.world.FractureProfileService;
import io.vortexcore.world.FractureRealmService;
import io.vortexcore.world.FractureLootService;
import io.vortexcore.world.MythicBreachManager;
import io.vortexcore.world.RiftSpawnController;
import io.vortexcore.world.WorldThreatManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Difficulty;
import org.bukkit.plugin.java.JavaPlugin;

public final class VortexCorePlugin extends JavaPlugin {

    private FoliaExecutionFacade scheduler;
    private NexusBus nexusBus;
    private PlayerDataRepository playerDataRepository;
    private DynamicMarketManager dynamicMarketManager;
    private CustomItemService customItemService;
    private ItemForgeService itemForgeService;
    private CustomItemRuntimeService customItemRuntimeService;
    private NeuralNPCManager neuralNPCManager;
    private InstanceController instanceController;
    private PhysicsFier physicsFier;
    private GaussianLootGenerator gaussianLootGenerator;
    private ChunkKarmaManager chunkKarmaManager;
    private WorldThreatManager worldThreatManager;
    private FractureProfileService fractureProfileService;
    private FractureNavigatorService fractureNavigatorService;
    private FractureRealmService fractureRealmService;
    private FractureGatewayService fractureGatewayService;
    private FractureLootService fractureLootService;
    private RiftSpawnController riftSpawnController;
    private ExpeditionPartyManager expeditionPartyManager;
    private MythicBreachManager mythicBreachManager;
    private BreachQuartermasterService breachQuartermasterService;
    private VortexMenuBuilder menuBuilder;
    private ClassSkillService classSkillService;
    private ClassForgeService classForgeService;
    private SpellbookService spellbookService;
    private SpellForgeService spellForgeService;
    private EffectForgeService effectForgeService;
    private EffectService effectService;
    private PlayerProfileService playerProfileService;
    private ProfessionService professionService;
    private PrestigeCosmeticService prestigeCosmeticService;
    private SeasonTrackService seasonTrackService;
    private AdventureBoardService adventureBoardService;
    private ProfileMenuService profileMenuService;
    private ExpeditionBoardManager expeditionBoardManager;
    private DiagnosticsTracker diagnosticsTracker;
    private VaultEconomyBridge vaultEconomyBridge;
    private VortexPlaceholderExpansion placeholderExpansion;
    private ResourceMigrationService resourceMigrationService;
    private ProtectionHookService protectionHookService;
    private CitizensIntegrationService citizensIntegrationService;
    private SupportDumpService supportDumpService;
    private VortexResourcePackService resourcePackService;
    private QuestRegistry questRegistry;
    private QuestRepository questRepository;
    private QuestService questService;
    private QuestBoardMenu questBoardMenu;
    private TalentRegistry talentRegistry;
    private TalentRepository talentRepository;
    private TalentService talentService;
    private TalentTreeMenu talentTreeMenu;
    private MessageService messageService;
    private ForgeInputGuard forgeInputGuard;
    private ScoreboardService scoreboardService;
    private BossRegistry bossRegistry;
    private BossService bossService;
    private BossForgeService bossForgeService;
    private DungeonRegistry dungeonRegistry;
    private DungeonService dungeonService;
    private AuctionRepository auctionRepository;
    private AuctionService auctionService;
    private PartyService partyService;
    private CombatEngine combatEngine;
    private ManaManager manaManager;
    private PlayerHUD playerHUD;

    @Override
    public void onEnable() {
        this.resourceMigrationService = new ResourceMigrationService(this, List.of("config.yml", "spells.yml", "effects.yml", "fractures.yml", "items.yml", "gateways.yml", "quests.yml", "talents.yml", "bosses.yml", "dungeons.yml"));
        resourceMigrationService.migrateAll().describeLines().forEach(getLogger()::info);
        reloadConfig();

        new Metrics(this, 30633);
        this.scheduler = createScheduler();
        this.nexusBus = new NexusBus(this, scheduler);
        this.messageService = new MessageService(getConfig().getString("messages.prefix",
            "<gradient:#8B5CF6:#3B82F6>VortexRPG</gradient> <dark_gray>\u00bb</dark_gray> "));
        this.forgeInputGuard = new ForgeInputGuard();
        bootstrapPersistence();
        this.protectionHookService = new ProtectionHookService(
            getLogger(),
            this,
            getConfig().getBoolean("integrations.protection.enabled", true),
            getConfig().getBoolean("integrations.protection.worldguard.enabled", true)
        );
        protectionHookService.statusSnapshot().describeLines().forEach(getLogger()::info);
        this.vaultEconomyBridge = new VaultEconomyBridge(
            getLogger(),
            this,
            scheduler,
            getConfig().getBoolean("integrations.vault.enabled", true),
            new VaultEconomyBridge.RewardPolicy(
                getConfig().getDouble("integrations.vault.expedition-base-reward", 120.0D),
                getConfig().getDouble("integrations.vault.expedition-score-multiplier", 0.06D),
                getConfig().getDouble("integrations.vault.breach-cache-base-reward", 220.0D),
                getConfig().getDouble("integrations.vault.breach-token-multiplier", 2.5D),
                getConfig().getDouble("integrations.vault.breach-experience-multiplier", 0.08D)
            )
        );
        vaultEconomyBridge.refreshHook();

        this.dynamicMarketManager = new DynamicMarketManager(
            getLogger(),
            scheduler,
            getConfig().getLong("economy.baseline-stock", 128L),
            getConfig().getDouble("economy.price-floor-multiplier", 0.35D),
            getConfig().getDouble("economy.price-cap-multiplier", 3.75D),
            getConfig().getDouble("economy.demand-elasticity", 0.65D),
            getConfig().getDouble("economy.stock-elasticity", 0.45D),
            getConfig().getDouble("economy.stock-decay-rate", 0.08D),
            Duration.ofSeconds(getConfig().getLong("economy.decay-period-seconds", 300L))
        );
        dynamicMarketManager.start();

        this.neuralNPCManager = new NeuralNPCManager(
            getLogger(),
            scheduler,
            new LLMEndpoint(
                LLMProviderType.valueOf(getConfig().getString("npc.provider", "OLLAMA")),
                getConfig().getString("npc.base-url", "http://127.0.0.1:11434/api/chat"),
                getConfig().getString("npc.api-key", ""),
                getConfig().getString("npc.model", "vortexcore-npc"),
                Duration.ofSeconds(getConfig().getLong("npc.timeout-seconds", 15L))
            ),
            getConfig().getInt("npc.max-memory-turns", 16)
        );

        this.instanceController = new InstanceController(
            this,
            scheduler,
            getDataFolder().toPath().resolve(getConfig().getString("instances.template-root", "templates")),
            getServer().getWorldContainer().toPath()
        );

        this.physicsFier = new PhysicsFier(
            getLogger(),
            scheduler,
            Duration.ofMillis(getConfig().getLong("physics.tick-period-millis", 50L))
        );
        physicsFier.start();
        this.gaussianLootGenerator = new GaussianLootGenerator();

        this.chunkKarmaManager = new ChunkKarmaManager(
            getLogger(),
            scheduler,
            nexusBus,
            getConfig().getLong("ecology.tree-cut-threshold", 128L),
            getConfig().getLong("ecology.mob-kill-threshold", 256L),
            getConfig().getLong("ecology.ore-mine-threshold", 96L)
        );
        this.fractureProfileService = new FractureProfileService(this, getLogger());
        FractureProfileService.ReloadResult fractureProfileReload = fractureProfileService.reload();
        getLogger().info("Fracture profile registry ready. Loaded " + fractureProfileReload.loaded() + " profile(s).");

        this.worldThreatManager = new WorldThreatManager(
            getLogger(),
            scheduler,
            nexusBus,
            dynamicMarketManager,
            instanceController,
            fractureProfileService,
            Duration.ofSeconds(getConfig().getLong("fractures.tick-period-seconds", 30L)),
            getConfig().getDouble("fractures.base-intensity", 35.0D),
            getConfig().getDouble("fractures.ecology-intensity-multiplier", 1.0D),
            getConfig().getDouble("fractures.passive-escalation", 6.0D),
            getConfig().getDouble("fractures.corrupted-threshold", 75.0D),
            getConfig().getDouble("fractures.overrun-threshold", 150.0D),
            getConfig().getDouble("fractures.boss-threshold", 250.0D),
            getConfig().getString("fractures.boss-template", "fracture_breach"),
            getConfig().getStringList("fractures.scarcity-items"),
            getConfig().getStringList("fractures.recovery-items"),
            getConfig().getLong("fractures.market-pressure-units", 8L)
        );
        worldThreatManager.start();
        this.fractureNavigatorService = new FractureNavigatorService(this, scheduler, worldThreatManager);
        this.fractureRealmService = new FractureRealmService(
            this,
            getLogger(),
            scheduler,
            worldThreatManager,
            fractureProfileService,
            getDataFolder().toPath().resolve(getConfig().getString("instances.template-root", "templates")),
            getServer().getWorldContainer().toPath(),
            getConfig().getBoolean("fracture-realm.enabled", true),
            getConfig().getString("fracture-realm.world-name", "vortex_fractured"),
            getConfig().getString("fracture-realm.template-name", "fractured_realm"),
            parseEnvironment(getConfig().getString("fracture-realm.environment", "NORMAL")),
            getConfig().getLong("fracture-realm.seed", 847261105931L),
            getConfig().getBoolean("fracture-realm.generate-structures", false),
            parseDifficulty(getConfig().getString("fracture-realm.difficulty", "HARD")),
            getConfig().getLong("fracture-realm.fixed-time", 18000L),
            getConfig().getBoolean("fracture-realm.storm", true),
            getConfig().getBoolean("fracture-realm.thundering", true),
            getConfig().getBoolean("fracture-realm.auto-save", true),
            getConfig().getBoolean("fracture-realm.spawn-monsters", true),
            getConfig().getBoolean("fracture-realm.spawn-animals", false),
            getConfig().getBoolean("fracture-realm.pvp", true),
            getConfig().getDouble("fracture-realm.world-border-radius", 1536.0D),
            getConfig().getBoolean("fracture-realm.auto-seed.enabled", true),
            getConfig().getDouble("fracture-realm.auto-seed.intensity", 120.0D),
            getConfig().getInt("fracture-realm.auto-seed.anchor-count", 8),
            getConfig().getInt("fracture-realm.auto-seed.ring-radius-blocks", 384),
            getConfig().getStringList("fracture-realm.auto-seed.profile-cycle")
        );
        fractureRealmService.ensureRealm().whenComplete((world, error) -> {
            if (error != null) {
                getLogger().warning("Unable to boot the fracture realm: " + friendlyError(error));
            } else if (world != null) {
                getLogger().info("Fracture realm ready in world " + world.getName() + ".");
            }
        });

        this.menuBuilder = new VortexMenuBuilder(
            this,
            scheduler,
            Duration.ofMillis(getConfig().getLong("menus.click-cooldown-millis", 150L))
        );
        getServer().getPluginManager().registerEvents(menuBuilder, this);
        this.customItemService = new CustomItemService(this, getLogger());
        registerBuiltinGuiItems();
        CustomItemService.ReloadResult itemReload = customItemService.reload();
        getLogger().info("Custom item registry ready. Loaded " + itemReload.loaded() + " external definition(s).");
        itemReload.notes().forEach(note -> getLogger().info("Custom items -> " + note));
        this.resourcePackService = new VortexResourcePackService(
            getLogger(),
            scheduler,
            getConfig().getBoolean("resource-pack.enabled", false),
            getConfig().getBoolean("resource-pack.send-on-join", true),
            getConfig().getLong("resource-pack.send-delay-ticks", 20L),
            getConfig().getString("resource-pack.url", ""),
            getConfig().getString("resource-pack.sha1", ""),
            getConfig().getBoolean("resource-pack.required", false),
            getConfig().getString("resource-pack.prompt", ""),
            getConfig().getString("resource-pack.pack-id", "")
        );
        getServer().getPluginManager().registerEvents(resourcePackService, this);
        getLogger().info(resourcePackService.statusSnapshot().describeLine());

        this.effectService = new EffectService(
            this,
            getLogger(),
            scheduler,
            messageService,
            getConfig().getLong("effects.tick-period-ticks", 1L)
        );
        getServer().getPluginManager().registerEvents(effectService, this);
        effectService.start();
        EffectService.ReloadResult effectReload = effectService.reloadCustomEffects();
        getLogger().info("Effect registry ready. Loaded " + effectReload.loaded() + " custom effects.");

        this.classSkillService = new ClassSkillService(
            this,
            scheduler,
            menuBuilder,
            customItemService,
            messageService,
            getConfig().getInt("progression.ascension-level-threshold", 20)
        );
        this.spellbookService = new SpellbookService(
            this,
            getLogger(),
            scheduler,
            menuBuilder,
            nexusBus,
            classSkillService,
            effectService,
            protectionHookService,
            messageService
        );
        this.classForgeService = new ClassForgeService(this, scheduler, menuBuilder, classSkillService, spellbookService, messageService, forgeInputGuard);
        this.spellForgeService = new SpellForgeService(this, scheduler, menuBuilder, spellbookService, messageService, forgeInputGuard);
        this.effectForgeService = new EffectForgeService(this, scheduler, menuBuilder, effectService, messageService, forgeInputGuard);
        spellbookService.reloadCustomSpells();
        classSkillService.reloadCustomClasses();
        getServer().getPluginManager().registerEvents(spellbookService, this);
        getServer().getPluginManager().registerEvents(classForgeService, this);
        getServer().getPluginManager().registerEvents(spellForgeService, this);
        getServer().getPluginManager().registerEvents(effectForgeService, this);
        this.playerProfileService = new PlayerProfileService(
            this,
            getLogger(),
            scheduler,
            playerDataRepository,
            classSkillService,
            nexusBus,
            worldThreatManager,
            messageService,
            ExperienceCurve.cubic(
                getConfig().getDouble("progression.curve-a", 45.0D),
                getConfig().getDouble("progression.curve-c", 120.0D)
            )
        );
        classSkillService.setPlayerProfileService(playerProfileService);
        getServer().getPluginManager().registerEvents(playerProfileService, this);
        this.fractureGatewayService = new FractureGatewayService(
            this,
            getLogger(),
            scheduler,
            menuBuilder,
            customItemService,
            playerProfileService,
            fractureNavigatorService,
            fractureRealmService,
            protectionHookService,
            messageService,
            getConfig().getBoolean("fracture-gateway.enabled", true),
            getConfig().getBoolean("fracture-gateway.auto-create-primary", true),
            getConfig().getBoolean("fracture-gateway.auto-create-return", true),
            getConfig().getBoolean("fracture-gateway.build-structures", true),
            getConfig().getBoolean("fracture-gateway.ambient-particles", true),
            getConfig().getLong("fracture-gateway.monument-pulse-ticks", 40L),
            getConfig().getString("fracture-gateway.primary-world", ""),
            getConfig().getInt("fracture-gateway.primary-offset-x", 10),
            getConfig().getInt("fracture-gateway.primary-offset-z", 0),
            getConfig().getString("fracture-gateway.primary-gateway-id", "worldspawn"),
            getConfig().getString("fracture-gateway.return-gateway-id", "realm_return")
        );
        getServer().getPluginManager().registerEvents(fractureGatewayService, this);
        fractureGatewayService.bootstrap();
        getLogger().info(fractureGatewayService.statusSnapshot().describeLine());
        this.customItemRuntimeService = new CustomItemRuntimeService(
            this,
            scheduler,
            customItemService,
            playerProfileService,
            classSkillService,
            spellbookService,
            effectService,
            messageService
        );
        classSkillService.addLiveAttributeAugmenter(customItemRuntimeService);
        getServer().getPluginManager().registerEvents(customItemRuntimeService, this);
        this.itemForgeService = new ItemForgeService(this, scheduler, menuBuilder, customItemService, messageService, forgeInputGuard);
        getServer().getPluginManager().registerEvents(itemForgeService, this);

        this.professionService = new ProfessionService(
            this,
            scheduler,
            menuBuilder,
            playerProfileService,
            dynamicMarketManager,
            protectionHookService,
            effectService,
            customItemService,
            messageService,
            ExperienceCurve.cubic(
                getConfig().getDouble("professions.curve-a", 18.0D),
                getConfig().getDouble("professions.curve-c", 40.0D)
            ),
            getConfig().getBoolean("professions.station-open-requires-sneak", true),
            getConfig().getInt("professions.mastery-level-threshold", 20),
            getConfig().getLong("professions.gathering.mining-ore-xp", 18L),
            getConfig().getLong("professions.gathering.mining-stone-xp", 6L),
            getConfig().getLong("professions.gathering.foraging-log-xp", 10L),
            getConfig().getLong("professions.gathering.farming-harvest-xp", 8L),
            getConfig().getLong("professions.gathering.fishing-catch-xp", 16L)
        );
        getServer().getPluginManager().registerEvents(professionService, this);

        this.prestigeCosmeticService = new PrestigeCosmeticService(
            this,
            scheduler,
            playerProfileService,
            getConfig().getLong("cosmetics.trail-period-ticks", 10L)
        );
        prestigeCosmeticService.start();

        this.fractureLootService = new FractureLootService(
            this,
            customItemService,
            gaussianLootGenerator,
            dynamicMarketManager,
            getConfig().getDouble("fractures.loot-base-drop-chance", 0.38D),
            getConfig().getDouble("fractures.loot-boss-drop-chance", 1.0D)
        );

        this.expeditionPartyManager = new ExpeditionPartyManager(Duration.ofMinutes(
            getConfig().getLong("fractures.party-invite-ttl-minutes", 2L)
        ));

        this.expeditionBoardManager = new ExpeditionBoardManager(
            menuBuilder,
            worldThreatManager,
            fractureLootService,
            expeditionPartyManager,
            getConfig().getInt("fractures.board-max-contracts", 21)
        );

        this.breachQuartermasterService = new BreachQuartermasterService(
            this,
            scheduler,
            menuBuilder,
            playerProfileService,
            fractureLootService,
            messageService
        );

        this.mythicBreachManager = new MythicBreachManager(
            this,
            getLogger(),
            scheduler,
            worldThreatManager,
            instanceController,
            expeditionBoardManager,
            expeditionPartyManager,
            fractureLootService,
            playerProfileService,
            vaultEconomyBridge,
            effectService,
            menuBuilder,
            messageService,
            getConfig().getString("fractures.breach-boss-type", "wither_skeleton"),
            getConfig().getDouble("fractures.breach-boss-health", 450.0D),
            getConfig().getDouble("fractures.breach-boss-damage-multiplier", 2.4D),
            getConfig().getDouble("fractures.breach-boss-score-bonus", 450.0D),
            getConfig().getDouble("fractures.breach-phase-two-threshold", 0.75D),
            getConfig().getDouble("fractures.breach-phase-three-threshold", 0.45D),
            getConfig().getDouble("fractures.breach-phase-four-threshold", 0.20D),
            getConfig().getLong("fractures.breach-ability-cooldown-seconds", 14L),
            getConfig().getInt("fractures.breach-shared-revives", 3),
            getConfig().getLong("fractures.breach-disconnect-grace-seconds", 30L),
            getConfig().getLong("fractures.breach-revive-respawn-delay-seconds", 3L),
            getConfig().getLong("fractures.breach-token-base-reward", 18L),
            getConfig().getLong("fractures.breach-token-severity-bonus", 6L)
        );
        getServer().getPluginManager().registerEvents(mythicBreachManager, this);
        mythicBreachManager.start();

        this.riftSpawnController = new RiftSpawnController(
            this,
            getLogger(),
            scheduler,
            worldThreatManager,
            fractureLootService,
            expeditionBoardManager,
            playerProfileService,
            vaultEconomyBridge,
            effectService,
            getConfig().getDouble("fractures.suppression-per-kill", 12.0D),
            getConfig().getLong("fractures.visual-pulse-ticks", 20L)
        );
        getServer().getPluginManager().registerEvents(riftSpawnController, this);
        riftSpawnController.start();

        this.seasonTrackService = new SeasonTrackService(
            menuBuilder,
            playerProfileService,
            prestigeCosmeticService,
            messageService,
            getConfig().getInt("season.renown-per-level", 100),
            getConfig().getInt("season.adventure-daily-renown", 25),
            getConfig().getInt("season.adventure-weekly-renown", 40),
            getConfig().getInt("season.expeditions.s-renown", 42),
            getConfig().getInt("season.expeditions.a-renown", 34),
            getConfig().getInt("season.expeditions.b-renown", 28),
            getConfig().getInt("season.expeditions.c-renown", 22),
            getConfig().getInt("season.breaches.s-renown", 60),
            getConfig().getInt("season.breaches.a-renown", 48),
            getConfig().getInt("season.breaches.b-renown", 40),
            getConfig().getInt("season.breaches.c-renown", 32)
        );
        this.profileMenuService = new ProfileMenuService(
            menuBuilder,
            customItemService,
            playerProfileService,
            classSkillService,
            professionService,
            seasonTrackService,
            prestigeCosmeticService,
            spellbookService
        );

        this.adventureBoardService = new AdventureBoardService(
            menuBuilder,
            playerProfileService,
            worldThreatManager,
            seasonTrackService,
            classSkillService,
            messageService,
            protectionHookService
        );
        professionService.setActivityListener(adventureBoardService);
        spellbookService.addCastListener(adventureBoardService);
        spellbookService.addCastListener(prestigeCosmeticService);
        spellbookService.addCastListener(professionService::recordSpellCast);
        riftSpawnController.addCompletionListener(adventureBoardService);
        riftSpawnController.addCompletionListener(seasonTrackService);
        mythicBreachManager.addCompletionListener(adventureBoardService);
        mythicBreachManager.addCompletionListener(seasonTrackService);
        getServer().getPluginManager().registerEvents(adventureBoardService, this);

        bootstrapQuests();

        bootstrapTalents();

        bootstrapBosses();

        bootstrapDungeons();

        bootstrapAuctions();

        bootstrapPartyService();

        bootstrapCombatSystems();

        this.scoreboardService = new ScoreboardService(
            scheduler,
            playerProfileService,
            classSkillService,
            vaultEconomyBridge,
            questService,
            talentService,
            getConfig().getInt("quests.max-active", 5),
            getConfig().getBoolean("scoreboard.enabled", true),
            getConfig().getString("scoreboard.title", "<gradient:#8B5CF6:#3B82F6><bold>VortexRPG</bold></gradient>"),
            getConfig().getStringList("scoreboard.lines"),
            getConfig().getLong("scoreboard.update-ticks", 20L)
        );
        getServer().getPluginManager().registerEvents(scoreboardService, this);

        registerOptionalCitizensIntegration();

        this.diagnosticsTracker = new DiagnosticsTracker(
            this,
            scheduler,
            Duration.ofMillis(getConfig().getLong("diagnostics.sample-period-millis", 1000L))
        );
        registerPaperDiagnosticsListener();
        diagnosticsTracker.start();

        getServer().getPluginManager().registerEvents(neuralNPCManager, this);

        this.supportDumpService = new SupportDumpService(
            this,
            scheduler,
            resourceMigrationService,
            playerDataRepository,
            diagnosticsTracker,
            vaultEconomyBridge,
            protectionHookService,
            citizensIntegrationService,
            getConfig().getString("support.dump-directory", "support"),
            getConfig().getStringList("support.redact-paths")
        );

        registerOptionalPlaceholderExpansion();

        ExpeditionsCommand expeditionsCommand = new ExpeditionsCommand(expeditionBoardManager, expeditionPartyManager, mythicBreachManager, breachQuartermasterService, messageService);
        FractureCommand fractureCommand = new FractureCommand(scheduler, worldThreatManager, protectionHookService, fractureNavigatorService, fractureProfileService, fractureGatewayService, messageService);
        ClassCommand classCommand = new ClassCommand(playerProfileService, classSkillService, classForgeService, messageService);
        SkillsCommand skillsCommand = new SkillsCommand(playerProfileService, classSkillService, spellbookService, messageService);
        StatsCommand statsCommand = new StatsCommand(playerProfileService, classSkillService, professionService, seasonTrackService, prestigeCosmeticService, profileMenuService, messageService);
        AdventureCommand adventureCommand = new AdventureCommand(adventureBoardService, messageService);
        ProfessionsCommand professionsCommand = new ProfessionsCommand(playerProfileService, professionService, messageService);
        CastCommand castCommand = new CastCommand(playerProfileService, spellbookService, messageService);
        SpellCommand spellCommand = new SpellCommand(playerProfileService, spellbookService, spellForgeService, messageService);
        QuestCommand questCommand = new QuestCommand(questService, questBoardMenu, messageService);
        TalentCommand talentCommand = new TalentCommand(talentService, talentTreeMenu, messageService);
        BossCommand bossCommand = new BossCommand(bossService, bossForgeService, messageService);
        DungeonCommand dungeonCommand = new DungeonCommand(dungeonService, menuBuilder, messageService);
        AuctionCommand auctionCommand = new AuctionCommand(auctionService, menuBuilder, messageService);
        PartyCommand partyCommand = new PartyCommand(expeditionPartyManager, partyService, menuBuilder, messageService);
        VortexCommand vortexCommand = new VortexCommand(
            spellbookService,
            classForgeService,
            spellForgeService,
            effectForgeService,
            itemForgeService,
            customItemService,
            effectService,
            playerDataRepository,
            diagnosticsTracker,
            worldThreatManager,
            expeditionBoardManager,
            mythicBreachManager,
            instanceController,
            vaultEconomyBridge,
            protectionHookService,
            citizensIntegrationService,
            resourcePackService,
            fractureRealmService,
            fractureGatewayService,
            supportDumpService,
            resourceMigrationService,
            scoreboardService,
            messageService
        );

        registerCommand("expeditions", expeditionsCommand);
        registerCommand("fracture", fractureCommand);
        registerCommand("class", classCommand);
        registerCommand("skills", skillsCommand);
        registerCommand("stats", statsCommand);
        registerCommand("adventure", adventureCommand);
        registerCommand("professions", professionsCommand);
        registerCommand("cast", castCommand);
        registerCommand("spell", spellCommand);
        registerCommand("quest", questCommand);
        registerCommand("talent", talentCommand);
        registerCommand("vboss", bossCommand);
        registerCommand("vdungeon", dungeonCommand);
        registerCommand("auction", auctionCommand);
        registerCommand("party", partyCommand);
        registerCommand("pc", partyCommand);
        registerCommand("vortex", vortexCommand);
        registerCommand("vrp", new VrpCommand(
            messageService,
            classCommand,
            skillsCommand,
            statsCommand,
            castCommand,
            questCommand,
            talentCommand,
            professionsCommand,
            adventureCommand,
            fractureCommand,
            expeditionsCommand,
            bossCommand,
            dungeonCommand,
            auctionCommand,
            partyCommand,
            vortexCommand
        ));

        String ver = getPluginMeta().getVersion();
        getLogger().info("");
        getLogger().info(" ╔═══════════════════════════════════════════════╗");
        getLogger().info(" ║                                               ║");
        getLogger().info(" ║   VortexRPG — EternalRealm™                   ║");
        getLogger().info(" ║                                               ║");
        getLogger().info(String.format(" ║   Version:   %-33s║", ver));
        getLogger().info(" ║   Scheduler: Folia-native                     ║");
        getLogger().info(" ║   Author:    EternalRealm™                    ║");
        getLogger().info(" ║   Website:   eternalrealm.uk                  ║");
        getLogger().info(" ║   Discord:   discord.gg/Tya84XrgSF            ║");
        getLogger().info(" ║                                               ║");
        getLogger().info(" ╚═══════════════════════════════════════════════╝");
        getLogger().info("");

        net.kyori.adventure.text.minimessage.MiniMessage mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        net.kyori.adventure.text.Component banner = mini.deserialize(String.join("\n",
            "",
            " <gradient:#8B5CF6:#3B82F6><bold>VortexRPG</bold></gradient> <dark_gray>—</dark_gray> <gold>EternalRealm™</gold>",
            "",
            " <gray>Version:</gray>   <white>" + ver + "</white>",
            " <gray>Scheduler:</gray> <green>Folia-native</green>",
            " <gray>Author:</gray>   <gold>EternalRealm™</gold>",
            " <gray>Website:</gray>  <click:open_url:'http://eternalrealm.uk'><hover:show_text:'<gray>Click to visit</gray> <white>eternalrealm.uk</white>'><aqua><underlined>eternalrealm.uk</underlined></aqua></hover></click>",
            " <gray>Discord:</gray>  <click:open_url:'https://discord.gg/Tya84XrgSF'><hover:show_text:'<gray>Click to join</gray> <white>Discord</white>'><blue><underlined>discord.gg/Tya84XrgSF</underlined></blue></hover></click>",
            ""
        ));
        getServer().getConsoleSender().sendMessage(banner);
        for (org.bukkit.entity.Player op : getServer().getOnlinePlayers()) {
            if (op.isOp()) {
                op.sendMessage(banner);
            }
        }
    }

    @Override
    public void onDisable() {
        if (physicsFier != null) {
            physicsFier.stop();
        }

        if (effectService != null) {
            effectService.stop();
        }

        if (diagnosticsTracker != null) {
            diagnosticsTracker.stop();
        }

        if (dynamicMarketManager != null) {
            dynamicMarketManager.stop();
        }

        if (worldThreatManager != null) {
            worldThreatManager.stop();
        }

        if (riftSpawnController != null) {
            riftSpawnController.stop();
        }

        if (mythicBreachManager != null) {
            mythicBreachManager.stop();
        }

        if (bossService != null) {
            bossService.stop();
        }

        if (dungeonService != null) {
            dungeonService.stop();
        }

        if (auctionService != null) {
            auctionService.stop();
        }

        if (partyService != null) {
            partyService.stop();
        }

        if (manaManager != null) {
            manaManager.stop();
        }

        if (playerHUD != null) {
            playerHUD.stop();
        }

        if (scoreboardService != null) {
            scoreboardService.stop();
        }

        if (fractureGatewayService != null) {
            fractureGatewayService.stop();
        }

        if (prestigeCosmeticService != null) {
            prestigeCosmeticService.close();
        }

        if (playerProfileService != null) {
            PlayerProfileService.SaveDrainResult saveDrainResult = playerProfileService.flushAndClose(
                Duration.ofSeconds(getConfig().getLong("persistence.shutdown-drain-seconds", 5L))
            );
            if (saveDrainResult.timedOut() || saveDrainResult.pendingSaves() > 0) {
                getLogger().warning(
                    "Profile save drain finished with "
                        + saveDrainResult.pendingSaves()
                        + " pending saves after attempting "
                        + saveDrainResult.attemptedSaves()
                        + " writes."
                );
            } else if (saveDrainResult.attemptedSaves() > 0) {
                getLogger().info("Drained " + saveDrainResult.completedSaves() + " player profile saves before shutdown.");
            }
        }

        if (neuralNPCManager != null) {
            neuralNPCManager.close();
        }

        if (instanceController != null) {
            instanceController.shutdown();
        }

        if (questRepository != null) {
            questRepository.close();
        }

        if (talentService != null) {
            talentService.close();
        }

        if (talentRepository != null) {
            talentRepository.close();
        }

        if (playerDataRepository != null) {
            playerDataRepository.close();
        }

        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
    }

    public FoliaExecutionFacade scheduler() {
        return scheduler;
    }

    public NexusBus nexusBus() {
        return nexusBus;
    }

    public PlayerDataRepository playerDataRepository() {
        return playerDataRepository;
    }

    public DynamicMarketManager dynamicMarketManager() {
        return dynamicMarketManager;
    }

    public NeuralNPCManager neuralNPCManager() {
        return neuralNPCManager;
    }

    public InstanceController instanceController() {
        return instanceController;
    }

    public PhysicsFier physicsFier() {
        return physicsFier;
    }

    public GaussianLootGenerator gaussianLootGenerator() {
        return gaussianLootGenerator;
    }

    public ChunkKarmaManager chunkKarmaManager() {
        return chunkKarmaManager;
    }

    public WorldThreatManager worldThreatManager() {
        return worldThreatManager;
    }

    public FractureLootService fractureLootService() {
        return fractureLootService;
    }

    public ClassSkillService classSkillService() {
        return classSkillService;
    }

    public SpellbookService spellbookService() {
        return spellbookService;
    }

    public SpellForgeService spellForgeService() {
        return spellForgeService;
    }

    public EffectService effectService() {
        return effectService;
    }

    public EffectForgeService effectForgeService() {
        return effectForgeService;
    }

    public PlayerProfileService playerProfileService() {
        return playerProfileService;
    }

    public ProfessionService professionService() {
        return professionService;
    }

    public AdventureBoardService adventureBoardService() {
        return adventureBoardService;
    }

    public VortexMenuBuilder menuBuilder() {
        return menuBuilder;
    }

    public ExpeditionBoardManager expeditionBoardManager() {
        return expeditionBoardManager;
    }

    public ExpeditionPartyManager expeditionPartyManager() {
        return expeditionPartyManager;
    }

    public MythicBreachManager mythicBreachManager() {
        return mythicBreachManager;
    }

    public DiagnosticsTracker diagnosticsTracker() {
        return diagnosticsTracker;
    }

    public VaultEconomyBridge vaultEconomyBridge() {
        return vaultEconomyBridge;
    }

    public ResourceMigrationService resourceMigrationService() {
        return resourceMigrationService;
    }

    public ProtectionHookService protectionHookService() {
        return protectionHookService;
    }

    public CitizensIntegrationService citizensIntegrationService() {
        return citizensIntegrationService;
    }

    public SupportDumpService supportDumpService() {
        return supportDumpService;
    }

    public VortexResourcePackService resourcePackService() {
        return resourcePackService;
    }

    public FractureRealmService fractureRealmService() {
        return fractureRealmService;
    }

    public MessageService messageService() {
        return messageService;
    }

    public ScoreboardService scoreboardService() {
        return scoreboardService;
    }

    private void registerBuiltinGuiItems() {
        registerGuiItem("gui_knight_crest", Material.NETHER_STAR, 32001, List.of("VortexRPG GUI class crest for Knight paths."));
        registerGuiItem("gui_mage_prism", Material.AMETHYST_SHARD, 32002, List.of("VortexRPG GUI class crest for Mage paths."));
        registerGuiItem("gui_ranger_mark", Material.EMERALD, 32003, List.of("VortexRPG GUI class crest for Ranger paths."));
        registerGuiItem("gui_cleric_seal", Material.GHAST_TEAR, 32004, List.of("VortexRPG GUI class crest for Cleric paths."));
        registerGuiItem("gui_mining_compass", Material.HEART_OF_THE_SEA, 32005, List.of("VortexRPG GUI profession icon for Mining."));
        registerGuiItem("gui_alchemy_flask", Material.GLASS_BOTTLE, 32006, List.of("VortexRPG GUI profession icon for Alchemy."));
        registerGuiItem("gui_engineering_core", Material.REDSTONE, 32007, List.of("VortexRPG GUI profession icon for Engineering."));
        registerGuiItem("gui_beast_totem", Material.LEAD, 32008, List.of("VortexRPG GUI profession icon for Beast Mastery."));
        registerGuiItem("gui_breach_token", Material.ECHO_SHARD, 32009, List.of("VortexRPG GUI reward icon for breach currencies and caches."));
        registerGuiItem("gui_adventure_scroll", Material.PAPER, 32010, List.of("VortexRPG GUI progression icon for season and adventure loops."));
        registerGuiItem("gui_spell_relic", Material.ENCHANTED_BOOK, 32011, List.of("VortexRPG GUI spell and relic icon."));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "mage_apprentice_staff",
            Material.BLAZE_ROD,
            "Apprentice Staff",
            List.of(
                "§7» §bLeft-Click: §fFire an arcane bolt (basic attack).",
                "§7» §dRight-Click: §fCast your equipped spell.",
                "",
                "§8Starter focus issued to fresh Mage initiates."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.INTELLIGENCE, 2.0D, io.vortexcore.progression.VortexAttribute.WISDOM, 1.0D),
            Map.of(), "mage",
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            new CustomItemDefinition.AbilityBinding("mage_staff_bolt", false, null, 1, 0, 1.0D, true, 650L),
            true
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "knight_iron_resolve",
            Material.IRON_SWORD,
            "Iron Resolve",
            List.of(
                "§7» §cLeft-Click: §fNormal sword strike (melee).",
                "§7» §dRight-Click: §fCast your equipped spell.",
                "",
                "§8Starter blade issued to Knight initiates."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.STRENGTH, 3.0D, io.vortexcore.progression.VortexAttribute.VITALITY, 2.0D),
            Map.of(), "knight",
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            CustomItemDefinition.AbilityBinding.none(),
            true
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "ranger_hunting_bow",
            Material.BOW,
            "Hunting Bow",
            List.of(
                "§7» §aLeft-Click: §fCast your equipped spell.",
                "§7» §eRight-Click: §fDraw & loose an arrow (basic attack).",
                "",
                "§8Starter shortbow issued to Ranger scouts."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.DEXTERITY, 3.0D, io.vortexcore.progression.VortexAttribute.VITALITY, 1.0D),
            Map.of(), "ranger",
            CustomItemDefinition.AbilityBinding.none(),
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            true
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "cleric_devotion_tome",
            Material.BOOK,
            "Devotion Tome",
            List.of(
                "§7» §fLeft-Click: §fChannel §bMend §fto heal (basic attack).",
                "§7» §dRight-Click: §fCast your equipped spell.",
                "",
                "§8Sacred text carried by Cleric initiates."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.SPIRIT, 3.0D, io.vortexcore.progression.VortexAttribute.WISDOM, 2.0D),
            Map.of(), "cleric",
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            new CustomItemDefinition.AbilityBinding("mend", false, null, 1, 0, 1.0D, true, 4500L),
            true
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "beastmaster_fang_totem",
            Material.BONE,
            "Fang Totem",
            List.of(
                "§7» §6Left-Click: §fUnleash §eBeast Call §f(basic attack).",
                "§7» §dRight-Click: §fCast your equipped spell.",
                "",
                "§8Carved totem bonded to wild companions."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.STRENGTH, 2.0D, io.vortexcore.progression.VortexAttribute.VITALITY, 3.0D),
            Map.of(), "beastmaster",
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            new CustomItemDefinition.AbilityBinding("beast_call", false, null, 1, 0, 1.0D, true, 5000L),
            true
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "engineer_field_kit",
            Material.CLOCK,
            "Field Kit",
            List.of(
                "§7» §bLeft-Click: §fDeploy targeting turret (basic attack).",
                "§7» §dRight-Click: §fCast your equipped spell.",
                "",
                "§8Standard-issue kit for Engineer recruits."
            ),
            null, true, null, Map.of(),
            Map.of(io.vortexcore.progression.VortexAttribute.INTELLIGENCE, 2.0D, io.vortexcore.progression.VortexAttribute.DEXTERITY, 2.0D),
            Map.of(), "engineer",
            new CustomItemDefinition.AbilityBinding(null, true, null, 1, 0, 1.0D, true, 0L),
            new CustomItemDefinition.AbilityBinding("deploy_turret", false, null, 1, 0, 1.0D, true, 6000L),
            true
        ));
    }

    private void registerGuiItem(String id, Material material, int customModelData, List<String> lore) {
        customItemService.registerIfAbsent(new CustomItemDefinition(
            id,
            material,
            friendlyItemName(id),
            lore,
            customModelData,
            false,
            null,
            Map.of()
        ));
    }

    private String friendlyItemName(String raw) {
        String[] tokens = raw.toLowerCase().split("_");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank() || token.equals("gui")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return builder.toString();
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        org.bukkit.command.PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command " + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }

    private void bootstrapPersistence() {
        try {
            java.nio.file.Path dataFolder = getDataFolder().toPath().resolve("playerdata");
            Duration cacheTtl = Duration.ofSeconds(getConfig().getLong("cache.ttl-seconds", 900L));
            this.playerDataRepository = new PlayerDataRepository(getLogger(), dataFolder, cacheTtl);
            getLogger().info("File-based persistence ready. Player data folder: " + dataFolder);
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize persistence: " + exception.getMessage());
            playerDataRepository = null;
        }
    }

    private void bootstrapQuests() {
        try {
            java.nio.file.Path questDataFolder = getDataFolder().toPath().resolve("questdata");
            Duration questCacheTtl = Duration.ofSeconds(getConfig().getLong("quests.cache-ttl-seconds", 900L));
            this.questRepository = new QuestRepository(getLogger(), questDataFolder, questCacheTtl);

            this.questRegistry = new QuestRegistry(this, getLogger());
            QuestRegistry.ReloadResult questReload = questRegistry.reload();
            getLogger().info("Quest registry ready. Loaded " + questReload.loaded() + " quest definition(s).");
            questReload.notes().forEach(note -> getLogger().info("Quests -> " + note));

            this.questService = new QuestService(
                getLogger(),
                scheduler,
                nexusBus,
                questRegistry,
                questRepository,
                playerProfileService,
                vaultEconomyBridge,
                messageService,
                getConfig().getInt("quests.max-active", 5)
            );

            this.questBoardMenu = new QuestBoardMenu(menuBuilder, questService, scheduler);

            getServer().getPluginManager().registerEvents(new QuestEventListener(questService, protectionHookService), this);

            getLogger().info("Quest system bootstrapped with " + questReload.loaded() + " quest(s) available.");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize quest system: " + exception.getMessage());
            questRepository = null;
            questRegistry = null;
            questService = null;
            questBoardMenu = null;
        }
    }

    private void bootstrapTalents() {
        try {
            java.nio.file.Path talentDataFolder = getDataFolder().toPath().resolve("talentdata");
            Duration cacheTtl = Duration.ofSeconds(getConfig().getLong("talents.cache-ttl-seconds", 900L));
            this.talentRepository = new TalentRepository(getLogger(), talentDataFolder, cacheTtl);
            this.talentRegistry = new TalentRegistry(this, getLogger());
            TalentRegistry.ReloadResult result = talentRegistry.reload();
            getLogger().info("Talent registry ready. Loaded " + result.loaded() + " tree(s).");
            result.notes().forEach(note -> getLogger().info("Talents -> " + note));
            this.talentService = new TalentService(
                getLogger(), scheduler, nexusBus, talentRegistry, talentRepository,
                playerProfileService, classSkillService, vaultEconomyBridge, messageService,
                getConfig().getInt("talents.talent-points-per-levels", 2),
                getConfig().getDouble("talents.reset-cost", 500.0)
            );
            this.talentTreeMenu = new TalentTreeMenu(menuBuilder, talentService, playerProfileService, classSkillService, scheduler, messageService);
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize talent system: " + exception.getMessage());
        }
    }

    private void bootstrapBosses() {
        try {
            this.bossRegistry = new BossRegistry(this, getLogger());
            BossRegistry.ReloadResult result = bossRegistry.reload();
            getLogger().info("Boss registry ready. Loaded " + result.loaded() + " boss definition(s).");
            result.notes().forEach(note -> getLogger().info("Bosses -> " + note));
            this.bossService = new BossService(
                this,
                getLogger(),
                scheduler,
                menuBuilder,
                messageService,
                bossRegistry,
                gaussianLootGenerator,
                questService,
                playerProfileService,
                protectionHookService,
                getConfig().getLong("bosses.tick-period-ticks", 20L),
                getConfig().getDouble("bosses.participant-range", 50.0)
            );
            getServer().getPluginManager().registerEvents(bossService, this);
            bossService.start();
            this.bossForgeService = new BossForgeService(
                scheduler, menuBuilder, messageService, forgeInputGuard, bossRegistry
            );
            getServer().getPluginManager().registerEvents(bossForgeService, this);
            getLogger().info("Boss system bootstrapped with " + result.loaded() + " boss(es) available.");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize boss system: " + exception.getMessage());
            bossRegistry = null;
            bossService = null;
            bossForgeService = null;
        }
    }

    private void bootstrapDungeons() {
        try {
            this.dungeonRegistry = new DungeonRegistry(this, getLogger());
            DungeonRegistry.ReloadResult result = dungeonRegistry.reload();
            getLogger().info("Dungeon registry ready. Loaded " + result.loaded() + " dungeon definition(s).");
            result.notes().forEach(note -> getLogger().info("Dungeons -> " + note));
            this.dungeonService = new DungeonService(
                this, getLogger(), scheduler, messageService, dungeonRegistry,
                instanceController, bossRegistry, vaultEconomyBridge
            );
            getServer().getPluginManager().registerEvents(dungeonService, this);
            dungeonService.start();
            getLogger().info("Dungeon system bootstrapped with " + result.loaded() + " dungeon(s) available.");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize dungeon system: " + exception.getMessage());
            dungeonRegistry = null;
            dungeonService = null;
        }
    }

    private void bootstrapAuctions() {
        try {
            this.auctionRepository = new AuctionRepository(getLogger(), getDataFolder().toPath());
            this.auctionService = new AuctionService(
                getLogger(), scheduler, messageService, auctionRepository, vaultEconomyBridge
            );
            auctionService.start();
            getLogger().info("Auction house bootstrapped.");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize auction house: " + exception.getMessage());
            auctionRepository = null;
            auctionService = null;
        }
    }

    private void bootstrapCombatSystems() {
        try {
            this.combatEngine = new CombatEngine(playerProfileService);
            getServer().getPluginManager().registerEvents(combatEngine, this);
            this.manaManager = new ManaManager(scheduler, playerProfileService);
            getServer().getPluginManager().registerEvents(manaManager, this);
            manaManager.start();
            this.playerHUD = new PlayerHUD(scheduler, playerProfileService, manaManager, expeditionPartyManager);
            playerHUD.start();
            getLogger().info("Combat systems bootstrapped (CRIT/DODGE, mana, HUD).");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize combat systems: " + exception.getMessage());
        }
    }

    private void bootstrapPartyService() {
        try {
            this.partyService = new PartyService(
                this, getLogger(), scheduler, messageService, expeditionPartyManager
            );
            getServer().getPluginManager().registerEvents(partyService, this);
            partyService.start();
            getLogger().info("Party service bootstrapped (HUD, chat, ready checks, summon).");
        } catch (Exception exception) {
            getLogger().warning("Unable to initialize party service: " + exception.getMessage());
            partyService = null;
        }
    }

    private void registerOptionalPlaceholderExpansion() {
        if (!getConfig().getBoolean("integrations.placeholderapi.enabled", true)) {
            getLogger().info("PlaceholderAPI integration is disabled in config.");
            return;
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI is not installed. Skipping placeholder registration.");
            return;
        }

        this.placeholderExpansion = new VortexPlaceholderExpansion(
            this,
            playerProfileService,
            classSkillService,
            professionService,
            seasonTrackService,
            adventureBoardService,
            expeditionBoardManager,
            worldThreatManager,
            mythicBreachManager,
            dynamicMarketManager,
            vaultEconomyBridge,
            questService,
            talentService
        );
        if (placeholderExpansion.register()) {
            getLogger().info("Registered internal PlaceholderAPI expansion with identifier %vortexrpg_*%.");
        } else {
            getLogger().warning("Unable to register the internal PlaceholderAPI expansion.");
            placeholderExpansion = null;
        }
    }

    private void registerOptionalCitizensIntegration() {
        if (!getConfig().getBoolean("integrations.citizens.enabled", true)) {
            getLogger().info("Citizens integration is disabled in config.");
            return;
        }

        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().info("Citizens is not installed. Skipping NPC integration.");
            return;
        }

        try {
            this.citizensIntegrationService = new CitizensIntegrationService(
                scheduler,
                protectionHookService,
                neuralNPCManager,
                playerProfileService,
                classSkillService,
                professionService,
                adventureBoardService,
                expeditionBoardManager,
                breachQuartermasterService,
                fractureGatewayService,
                messageService,
                getConfig().getString(
                    "integrations.citizens.neural-greeting-template",
                    "Greet the player in one short line and explain how you can help."
                )
            );
            getServer().getPluginManager().registerEvents(citizensIntegrationService, this);
            getLogger().info(citizensIntegrationService.statusSnapshot().describeLine());
        } catch (Throwable throwable) {
            getLogger().warning("Unable to initialize Citizens integration: " + throwable.getMessage());
            this.citizensIntegrationService = null;
        }
    }

    private World.Environment parseEnvironment(String raw) {
        try {
            return World.Environment.valueOf(raw == null ? "NORMAL" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Unknown fracture-realm.environment value '" + raw + "'. Falling back to NORMAL.");
            return World.Environment.NORMAL;
        }
    }

    private Difficulty parseDifficulty(String raw) {
        try {
            return Difficulty.valueOf(raw == null ? "HARD" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Unknown fracture-realm.difficulty value '" + raw + "'. Falling back to HARD.");
            return Difficulty.HARD;
        }
    }

    private String friendlyError(Throwable error) {
        Throwable resolved = error;
        while (resolved.getCause() != null) {
            resolved = resolved.getCause();
        }
        return resolved.getMessage() == null || resolved.getMessage().isBlank()
            ? resolved.getClass().getSimpleName()
            : resolved.getMessage();
    }



    /**
     * Returns {@code true} if the server is running Folia (region-threaded scheduler present).
     * The check uses reflection so it compiles and runs cleanly on plain Spigot.
     */
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Returns {@code true} if the server is running Paper or Folia (Paper API present).
     * Used to guard optional Paper-only integrations.
     */
    private static boolean isPaper() {
        try {
            Class.forName("com.destroystokyo.paper.event.server.ServerTickEndEvent");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private FoliaExecutionFacade createScheduler() {
        if (!isFolia()) {
            return new BukkitExecutionFacade(this);
        }

        try {
            Class<?> schedulerType = Class.forName("io.vortexcore.scheduling.FoliaOrchestrator");
            return (FoliaExecutionFacade) schedulerType.getConstructor(org.bukkit.plugin.Plugin.class).newInstance(this);
        } catch (ReflectiveOperationException | LinkageError exception) {
            getLogger().warning("Folia detected but this build does not include the Folia scheduler bridge. Falling back to Bukkit scheduling.");
            return new BukkitExecutionFacade(this);
        }
    }

    private void registerPaperDiagnosticsListener() {
        if (!isPaper()) {
            return;
        }

        try {
            Class<?> listenerType = Class.forName("io.vortexcore.diagnostics.PaperTickListener");
            Object listener = listenerType.getConstructor(DiagnosticsTracker.class).newInstance(diagnosticsTracker);
            getServer().getPluginManager().registerEvents((org.bukkit.event.Listener) listener, this);
        } catch (ReflectiveOperationException | LinkageError exception) {
            getLogger().fine("Paper diagnostics listener is unavailable in this build; continuing without tick-end samples.");
        }
    }
}
