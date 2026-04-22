package io.vortexcore.command;

import io.vortexcore.config.ResourceMigrationService;
import io.vortexcore.diagnostics.DiagnosticsTracker;
import io.vortexcore.integration.citizens.CitizensIntegrationService;
import io.vortexcore.integration.citizens.CitizensNpcRole;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.instances.InstanceController;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.item.ItemForgeService;
import io.vortexcore.persistence.PlayerDataRepository;
import io.vortexcore.resourcepack.VortexResourcePackService;
import io.vortexcore.rpg.ClassForgeService;
import io.vortexcore.rpg.SpellForgeService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.rpg.effects.EffectForgeService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.world.FractureRealmService;
import io.vortexcore.world.ExpeditionBoardManager;
import io.vortexcore.world.FractureRegionSnapshot;
import io.vortexcore.world.FractureState;
import io.vortexcore.world.FractureGatewayService;
import io.vortexcore.world.MythicBreachManager;
import io.vortexcore.world.WorldThreatManager;
import io.vortexcore.support.SupportDumpService;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.ScoreboardService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.EnumMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class VortexCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_CLASSES = "vortex.admin.classes";
    private static final String PERM_SPELLS = "vortex.admin.spells";
    private static final String PERM_EFFECTS = "vortex.admin.effects";
    private static final String PERM_RELOAD_SPELLS = "vortex.admin.reloadspells";
    private static final String PERM_LIST_SPELLS = "vortex.admin.listspells";
    private static final String PERM_RELOAD_EFFECTS = "vortex.admin.reloadeffects";
    private static final String PERM_LIST_EFFECTS = "vortex.admin.listeffects";
    private static final String PERM_ITEMS = "vortex.admin.items";
    private static final String PERM_RELOAD_ITEMS = "vortex.admin.reloaditems";
    private static final String PERM_LIST_ITEMS = "vortex.admin.listitems";
    private static final String PERM_DIAGNOSTICS = "vortex.admin.diagnostics";
    private static final String PERM_PERSISTENCE = "vortex.admin.persistence";
    private static final String PERM_VALIDATE = "vortex.admin.validate";
    private static final String PERM_CITIZENS = "vortex.admin.citizens";
    private static final String PERM_DUMP = "vortex.admin.dump";
    private static final String PERM_SCOREBOARD = "vortex.admin.scoreboard";
    private static final String PERM_SPELL_NEW = "vortex.admin.spell.new";

    private final SpellbookService spellbookService;
    private final ClassForgeService classForgeService;
    private final SpellForgeService spellForgeService;
    private final EffectForgeService effectForgeService;
    private final ItemForgeService itemForgeService;
    private final CustomItemService customItemService;
    private final EffectService effectService;
    private final PlayerDataRepository playerDataRepository;
    private final DiagnosticsTracker diagnosticsTracker;
    private final WorldThreatManager worldThreatManager;
    private final ExpeditionBoardManager expeditionBoardManager;
    private final MythicBreachManager mythicBreachManager;
    private final InstanceController instanceController;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final ProtectionHookService protectionHookService;
    private final CitizensIntegrationService citizensIntegrationService;
    private final VortexResourcePackService resourcePackService;
    private final FractureRealmService fractureRealmService;
    private final FractureGatewayService fractureGatewayService;
    private final SupportDumpService supportDumpService;
    private final ResourceMigrationService resourceMigrationService;
    private final ScoreboardService scoreboardService;
    private final MessageService messageService;

    public VortexCommand(
        SpellbookService spellbookService,
        ClassForgeService classForgeService,
        SpellForgeService spellForgeService,
        EffectForgeService effectForgeService,
        ItemForgeService itemForgeService,
        CustomItemService customItemService,
        EffectService effectService,
        PlayerDataRepository playerDataRepository,
        DiagnosticsTracker diagnosticsTracker,
        WorldThreatManager worldThreatManager,
        ExpeditionBoardManager expeditionBoardManager,
        MythicBreachManager mythicBreachManager,
        InstanceController instanceController,
        VaultEconomyBridge vaultEconomyBridge,
        ProtectionHookService protectionHookService,
        CitizensIntegrationService citizensIntegrationService,
        VortexResourcePackService resourcePackService,
        FractureRealmService fractureRealmService,
        FractureGatewayService fractureGatewayService,
        SupportDumpService supportDumpService,
        ResourceMigrationService resourceMigrationService,
        ScoreboardService scoreboardService,
        MessageService messageService
    ) {
        this.spellbookService = spellbookService;
        this.classForgeService = classForgeService;
        this.spellForgeService = spellForgeService;
        this.effectForgeService = effectForgeService;
        this.itemForgeService = itemForgeService;
        this.customItemService = customItemService;
        this.effectService = effectService;
        this.playerDataRepository = playerDataRepository;
        this.diagnosticsTracker = diagnosticsTracker;
        this.worldThreatManager = worldThreatManager;
        this.expeditionBoardManager = expeditionBoardManager;
        this.mythicBreachManager = mythicBreachManager;
        this.instanceController = instanceController;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.protectionHookService = protectionHookService;
        this.citizensIntegrationService = citizensIntegrationService;
        this.resourcePackService = resourcePackService;
        this.fractureRealmService = fractureRealmService;
        this.fractureGatewayService = fractureGatewayService;
        this.supportDumpService = supportDumpService;
        this.resourceMigrationService = resourceMigrationService;
        this.scoreboardService = scoreboardService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelpMenu(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("reloadspells")) {
            if (deny(sender, PERM_RELOAD_SPELLS)) {
                return true;
            }
            SpellbookService.ReloadResult result = spellbookService.reloadCustomSpells();
            messageService.send(sender, "\u00a7e\u00a7lSpell Registry \u00a7areloaded \u00a78\u2014 \u00a7f" + result.loaded() + " loaded\u00a77, \u00a7f" + result.skipped() + " skipped");
            result.messages().stream().limit(5).forEach(message -> messageService.send(sender, "- " + message));
            return true;
        }

        if (subcommand.equals("listspells")) {
            if (deny(sender, PERM_LIST_SPELLS)) {
                return true;
            }
            String joined = spellbookService.registeredSpellIds().stream().reduce((left, right) -> left + "\u00a77, \u00a7f" + right).orElse("\u00a77None");
            messageService.send(sender, "\u00a7e\u00a7lSpell Registry \u00a78\u2014 \u00a7f" + joined);
            return true;
        }

        if (subcommand.equals("reloadeffects")) {
            if (deny(sender, PERM_RELOAD_EFFECTS)) {
                return true;
            }
            EffectService.ReloadResult result = effectService.reloadCustomEffects();
            messageService.send(sender, "\u00a7e\u00a7lEffect Registry \u00a7areloaded \u00a78\u2014 \u00a7f" + result.loaded() + " loaded\u00a77, \u00a7f" + result.skipped() + " skipped");
            result.messages().stream().limit(5).forEach(message -> messageService.send(sender, "- " + message));
            return true;
        }

        if (subcommand.equals("listeffects")) {
            if (deny(sender, PERM_LIST_EFFECTS)) {
                return true;
            }
            String joined = effectService.registeredEffectIds().stream().reduce((left, right) -> left + "\u00a77, \u00a7f" + right).orElse("\u00a77None");
            messageService.send(sender, "\u00a7e\u00a7lEffect Registry \u00a78\u2014 \u00a7f" + joined);
            return true;
        }

        if (subcommand.equals("reloaditems")) {
            if (deny(sender, PERM_RELOAD_ITEMS)) {
                return true;
            }
            CustomItemService.ReloadResult result = customItemService.reload();
            messageService.send(sender, "\u00a7e\u00a7lItem Registry \u00a7areloaded \u00a78\u2014 \u00a7f" + result.loaded() + " file override(s) applied");
            result.notes().stream().limit(5).forEach(message -> messageService.send(sender, "- " + message));
            return true;
        }

        if (subcommand.equals("listitems")) {
            if (deny(sender, PERM_LIST_ITEMS)) {
                return true;
            }
            String joined = customItemService.registeredItemIds().stream().reduce((left, right) -> left + "\u00a77, \u00a7f" + right).orElse("\u00a77None");
            messageService.send(sender, "\u00a7e\u00a7lItem Registry \u00a78\u2014 \u00a7f" + joined);
            return true;
        }

        if (subcommand.equals("diagnostics") || subcommand.equals("diag")) {
            if (deny(sender, PERM_DIAGNOSTICS)) {
                return true;
            }
            if (diagnosticsTracker == null) {
                messageService.send(sender, "\u00a7cDiagnostics tracker is offline.");
                return true;
            }
            messageService.send(sender, "\u00a7e\u00a7lRuntime Diagnostics");
            diagnosticsTracker.snapshot().describeLines(5).forEach(line -> messageService.send(sender, "\u00a77 " + line));
            return true;
        }

        if (subcommand.equals("persistence")) {
            if (deny(sender, PERM_PERSISTENCE)) {
                return true;
            }
            if (playerDataRepository == null) {
                messageService.send(sender, "\u00a7cPersistence layer offline \u00a78\u2014 \u00a77running in local fallback mode (Mongo/Redis not connected).");
                return true;
            }
            messageService.send(sender, "\u00a7e\u00a7lPersistence Status");
            playerDataRepository.statusSnapshot().describeLines().forEach(line -> messageService.send(sender, "\u00a77 " + line));
            return true;
        }

        if (subcommand.equals("validate") || subcommand.equals("health")) {
            if (deny(sender, PERM_VALIDATE)) {
                return true;
            }
            messageService.send(sender, "\u00a7e\u00a7lHealth Validation");
            validationLines().forEach(line -> messageService.send(sender, "\u00a77 " + line));
            return true;
        }

        if (subcommand.equals("dump") || subcommand.equals("support")) {
            if (deny(sender, PERM_DUMP)) {
                return true;
            }
            if (supportDumpService == null) {
                messageService.send(sender, "\u00a7cSupport dump service is offline.");
                return true;
            }
            messageService.send(sender, "\u00a7eGenerating support bundle...");
            supportDumpService.writeDump(sender.getName(), validationLines())
                .thenAccept(path -> messageService.send(sender, "\u00a7a\u00a7lDump saved \u00a78\u2192 \u00a7f" + path))
                .exceptionally(error -> {
                    messageService.send(sender, "\u00a7cDump failed: " + error.getMessage());
                    return null;
                });
            return true;
        }

        if (subcommand.equals("citizens")) {
            if (deny(sender, PERM_CITIZENS)) {
                return true;
            }
            if (citizensIntegrationService == null) {
                messageService.send(sender, "\u00a7cCitizens integration is not active \u00a78\u2014 \u00a77is the Citizens plugin installed?");
                return true;
            }

            if (args.length == 1 || args[1].equalsIgnoreCase("info")) {
                citizensIntegrationService.selectedNpcInfo(sender).forEach(line -> messageService.send(sender, line));
                return true;
            }

            if (args[1].equalsIgnoreCase("bind")) {
                if (args.length < 3) {
                    messageService.send(sender, "\u00a7cMissing role. \u00a77Usage: \u00a7f/" + label + " citizens bind <role> [context...]");
                    return true;
                }
                CitizensNpcRole role = CitizensNpcRole.parse(args[2]);
                if (role == null) {
                    messageService.send(sender, "\u00a7cUnknown role. \u00a77Available: \u00a7f" + String.join("\u00a77, \u00a7f", citizensIntegrationService.roleIds()));
                    return true;
                }
                String context = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
                messageService.send(sender, citizensIntegrationService.bindSelectedNpc(sender, role, context));
                return true;
            }

            if (args[1].equalsIgnoreCase("clear")) {
                messageService.send(sender, citizensIntegrationService.clearSelectedNpc(sender));
                return true;
            }

            messageService.send(sender, "\u00a7cUnknown sub-action. \u00a77Usage: \u00a7f/" + label + " citizens [info|bind|clear]");
            return true;
        }

        if (subcommand.equals("classes")) {
            if (deny(sender, PERM_CLASSES)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "Only players can open the class forge GUI.");
                return true;
            }
            classForgeService.openBrowser(player);
            return true;
        }

        if (subcommand.equals("spells")) {
            if (deny(sender, PERM_SPELLS)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "Only players can open the spell forge GUI.");
                return true;
            }
            spellForgeService.openBrowser(player);
            return true;
        }

        if (subcommand.equals("effects")) {
            if (deny(sender, PERM_EFFECTS)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "Only players can open the effect forge GUI.");
                return true;
            }
            effectForgeService.openBrowser(player);
            return true;
        }

        if (subcommand.equals("items")) {
            if (deny(sender, PERM_ITEMS)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "Only players can open the item forge GUI.");
                return true;
            }
            itemForgeService.openBrowser(player);
            return true;
        }

        if (subcommand.equals("scoreboard")) {
            if (deny(sender, PERM_SCOREBOARD)) {
                return true;
            }
            if (scoreboardService == null) {
                messageService.send(sender, "\u00a7cScoreboard service is not available.");
                return true;
            }
            if (args.length < 2) {
                messageService.send(sender, "\u00a7eScoreboard is " + (scoreboardService.isEnabled() ? "\u00a7aON" : "\u00a7cOFF") + "\u00a7e. Usage: /" + label + " scoreboard [on|off]");
                return true;
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("on")) {
                scoreboardService.setEnabled(true);
                messageService.send(sender, "\u00a7aScoreboard enabled for all players.");
            } else if (action.equals("off")) {
                scoreboardService.setEnabled(false);
                messageService.send(sender, "\u00a7cScoreboard disabled for all players.");
            } else {
                messageService.send(sender, "\u00a7cUsage: /" + label + " scoreboard [on|off]");
            }
            return true;
        }

        if (subcommand.equals("spell") || subcommand.equals("spells")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("new")) {
                return handleSpellNew(sender, label, args);
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the spell forge.");
                return true;
            }
            if (deny(sender, PERM_SPELLS)) {
                return true;
            }
            spellForgeService.openBrowser(player);
            return true;
        }

        sendHelpMenu(sender, label);
        return true;
    }

    /** Writes a starter spell template to spells.yml and hot-reloads the registry. */
    private boolean handleSpellNew(CommandSender sender, String label, String[] args) {
        if (deny(sender, PERM_SPELL_NEW)) {
            return true;
        }
        if (args.length < 3) {
            messageService.send(sender, "<red>Usage: /" + label + " spell new <id> [Display Name...]</red>");
            return true;
        }
        String id = args[2].toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        String displayName = args.length > 3
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
            : io.vortexcore.support.NameFormat.friendly(id);

        java.io.File spellsFile = new java.io.File(spellbookService.dataFolder(), "spells.yml");
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(spellsFile);
            String path = "spells." + id;
            if (cfg.isConfigurationSection(path)) {
                messageService.send(sender, "<yellow>Spell <white>" + id + "</white> already exists in spells.yml. Edit it directly or reload.</yellow>");
                return true;
            }
            cfg.set(path + ".enabled", true);
            cfg.set(path + ".display-name", displayName);
            cfg.set(path + ".cooldown-millis", 3000);
            cfg.set(path + ".min-level", 1);
            cfg.set(path + ".required-classes", java.util.List.of());
            cfg.set(path + ".targeters", java.util.List.of(java.util.Map.of("type", "nearby_monster", "range", 16, "limit", 1)));
            cfg.set(path + ".conditions", java.util.List.of(java.util.Map.of("type", "has_target")));
            cfg.set(path + ".mechanics", java.util.List.of(
                java.util.Map.of("type", "damage", "base", 5.0, "scaling-attribute", "intelligence", "scale", 0.8),
                java.util.Map.of("type", "send_message", "message", displayName + " strikes the target.")
            ));
            cfg.save(spellsFile);
        } catch (java.io.IOException e) {
            messageService.send(sender, "<red>Failed to write spells.yml: " + e.getMessage() + "</red>");
            return true;
        }

        SpellbookService.ReloadResult reload = spellbookService.reloadCustomSpells();
        messageService.send(sender, "<green>Spell <white>" + id + "</white> created and registry reloaded (" + reload.loaded() + " loaded).</green>");
        messageService.send(sender, "<gray>Edit <white>spells.yml</white> to customise its stats, then run <white>/vrp admin reloadspells</white>.</gray>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            ArrayList<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_CLASSES)) {
                suggestions.add("classes");
            }
            if (sender.hasPermission(PERM_SPELLS)) {
                suggestions.add("spells");
            }
            if (sender.hasPermission(PERM_SPELL_NEW)) {
                suggestions.add("spell");
            }
            if (sender.hasPermission(PERM_EFFECTS)) {
                suggestions.add("effects");
            }
            if (sender.hasPermission(PERM_ITEMS)) {
                suggestions.add("items");
            }
            if (sender.hasPermission(PERM_RELOAD_SPELLS)) {
                suggestions.add("reloadspells");
            }
            if (sender.hasPermission(PERM_LIST_SPELLS)) {
                suggestions.add("listspells");
            }
            if (sender.hasPermission(PERM_RELOAD_EFFECTS)) {
                suggestions.add("reloadeffects");
            }
            if (sender.hasPermission(PERM_LIST_EFFECTS)) {
                suggestions.add("listeffects");
            }
            if (sender.hasPermission(PERM_RELOAD_ITEMS)) {
                suggestions.add("reloaditems");
            }
            if (sender.hasPermission(PERM_LIST_ITEMS)) {
                suggestions.add("listitems");
            }
            if (sender.hasPermission(PERM_DIAGNOSTICS)) {
                suggestions.add("diagnostics");
            }
            if (sender.hasPermission(PERM_PERSISTENCE)) {
                suggestions.add("persistence");
            }
            if (sender.hasPermission(PERM_VALIDATE)) {
                suggestions.add("validate");
            }
            if (sender.hasPermission(PERM_CITIZENS)) {
                suggestions.add("citizens");
            }
            if (sender.hasPermission(PERM_DUMP)) {
                suggestions.add("dump");
            }
            if (sender.hasPermission(PERM_SCOREBOARD)) {
                suggestions.add("scoreboard");
            }
            return suggestions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spell") && sender.hasPermission(PERM_SPELL_NEW)) {
            return List.of("new");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("citizens") && sender.hasPermission(PERM_CITIZENS)) {
            return List.of("info", "bind", "clear");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("citizens") && args[1].equalsIgnoreCase("bind") && sender.hasPermission(PERM_CITIZENS)) {
            return citizensIntegrationService == null ? List.of() : citizensIntegrationService.roleIds();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scoreboard") && sender.hasPermission(PERM_SCOREBOARD)) {
            return List.of("on", "off");
        }
        return List.of();
    }

    private void sendHelpMenu(CommandSender sender, String label) {
        messageService.send(sender, "");
        messageService.send(sender, "\u00a76\u2550\u2550\u2550 \u00a7e\u00a7lVortexRPG \u00a76\u00b7 \u00a77Admin Toolkit \u00a76\u2550\u2550\u2550");
        messageService.send(sender, "");
        if (sender.hasPermission(PERM_CLASSES) || sender.hasPermission(PERM_SPELLS) || sender.hasPermission(PERM_EFFECTS) || sender.hasPermission(PERM_ITEMS)) {
            messageService.send(sender, "\u00a76\u25b8 \u00a7eForge Editors");
            if (sender.hasPermission(PERM_CLASSES)) {
                messageService.send(sender, "  \u00a77/" + label + " classes \u00a78\u2014 \u00a7fOpen the class forge GUI");
            }
            if (sender.hasPermission(PERM_SPELLS)) {
                messageService.send(sender, "  \u00a77/" + label + " spells \u00a78\u2014 \u00a7fOpen the spell forge GUI");
            }
            if (sender.hasPermission(PERM_EFFECTS)) {
                messageService.send(sender, "  \u00a77/" + label + " effects \u00a78\u2014 \u00a7fOpen the effect forge GUI");
            }
            if (sender.hasPermission(PERM_ITEMS)) {
                messageService.send(sender, "  \u00a77/" + label + " items \u00a78\u2014 \u00a7fOpen the item forge GUI");
            }
        }
        if (sender.hasPermission(PERM_RELOAD_SPELLS) || sender.hasPermission(PERM_RELOAD_EFFECTS) || sender.hasPermission(PERM_RELOAD_ITEMS)) {
            messageService.send(sender, "\u00a76\u25b8 \u00a7eHot Reload");
            if (sender.hasPermission(PERM_RELOAD_SPELLS)) {
                messageService.send(sender, "  \u00a77/" + label + " reloadspells \u00a78\u2014 \u00a7fReload spells.yml without restart");
            }
            if (sender.hasPermission(PERM_RELOAD_EFFECTS)) {
                messageService.send(sender, "  \u00a77/" + label + " reloadeffects \u00a78\u2014 \u00a7fReload effects.yml without restart");
            }
            if (sender.hasPermission(PERM_RELOAD_ITEMS)) {
                messageService.send(sender, "  \u00a77/" + label + " reloaditems \u00a78\u2014 \u00a7fReload items.yml without restart");
            }
        }
        if (sender.hasPermission(PERM_LIST_SPELLS) || sender.hasPermission(PERM_LIST_EFFECTS) || sender.hasPermission(PERM_LIST_ITEMS)) {
            messageService.send(sender, "\u00a76\u25b8 \u00a7eRegistry Inspection");
            if (sender.hasPermission(PERM_LIST_SPELLS)) {
                messageService.send(sender, "  \u00a77/" + label + " listspells \u00a78\u2014 \u00a7fDump all registered spell ids");
            }
            if (sender.hasPermission(PERM_LIST_EFFECTS)) {
                messageService.send(sender, "  \u00a77/" + label + " listeffects \u00a78\u2014 \u00a7fDump all registered effect ids");
            }
            if (sender.hasPermission(PERM_LIST_ITEMS)) {
                messageService.send(sender, "  \u00a77/" + label + " listitems \u00a78\u2014 \u00a7fDump all registered item ids");
            }
        }
        if (sender.hasPermission(PERM_DIAGNOSTICS) || sender.hasPermission(PERM_PERSISTENCE) || sender.hasPermission(PERM_VALIDATE) || sender.hasPermission(PERM_DUMP)) {
            messageService.send(sender, "\u00a76\u25b8 \u00a7eServer Health");
            if (sender.hasPermission(PERM_DIAGNOSTICS)) {
                messageService.send(sender, "  \u00a77/" + label + " diagnostics \u00a78\u2014 \u00a7fLive tick, TPS & region metrics");
            }
            if (sender.hasPermission(PERM_PERSISTENCE)) {
                messageService.send(sender, "  \u00a77/" + label + " persistence \u00a78\u2014 \u00a7fMongo/Redis connection & degradation");
            }
            if (sender.hasPermission(PERM_VALIDATE)) {
                messageService.send(sender, "  \u00a77/" + label + " validate \u00a78\u2014 \u00a7fFull-stack health snapshot");
            }
            if (sender.hasPermission(PERM_DUMP)) {
                messageService.send(sender, "  \u00a77/" + label + " dump \u00a78\u2014 \u00a7fExport sanitized support bundle");
            }
        }
        if (sender.hasPermission(PERM_CITIZENS)) {
            messageService.send(sender, "\u00a76\u25b8 \u00a7eCitizens Integration");
            messageService.send(sender, "  \u00a77/" + label + " citizens info \u00a78\u2014 \u00a7fInspect selected NPC binding");
            messageService.send(sender, "  \u00a77/" + label + " citizens bind <role> \u00a78\u2014 \u00a7fBind NPC to a Vortex role");
            messageService.send(sender, "  \u00a77/" + label + " citizens clear \u00a78\u2014 \u00a7fClear NPC role binding");
        }
        messageService.send(sender, "");
        messageService.send(sender, "\u00a78Use \u00a77/" + label + " <command> \u00a78for detailed output.");
    }

    private List<String> validationLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Validation snapshot");
        lines.add(
            "Registries -> spells: " + spellbookService.registeredSpellIds().size()
                + ", effects: " + effectService.registeredEffectIds().size()
                + ", items: " + customItemService.registeredItemCount()
        );

        if (diagnosticsTracker != null) {
            diagnosticsTracker.snapshot().describeLines(3).forEach(line -> lines.add("Runtime -> " + line));
        } else {
            lines.add("Runtime -> diagnostics tracker unavailable");
        }

        if (playerDataRepository == null) {
            lines.add("Persistence -> local test mode or startup fallback (Mongo/Redis not connected)");
        } else {
            playerDataRepository.statusSnapshot().describeLines().forEach(line -> lines.add("Persistence -> " + line));
        }

        if (vaultEconomyBridge != null) {
            lines.add(vaultEconomyBridge.statusSnapshot().describeLine());
        }
        if (protectionHookService != null) {
            lines.add(protectionHookService.statusSnapshot().describeLine());
        }
        if (resourceMigrationService != null) {
            resourceMigrationService.statusLines().forEach(line -> lines.add("Resources -> " + line));
        }
        if (resourcePackService != null) {
            lines.add(resourcePackService.statusSnapshot().describeLine());
        }
        if (fractureRealmService != null) {
            lines.add(fractureRealmService.statusSnapshot().describeLine());
        }
        if (fractureGatewayService != null) {
            lines.add(fractureGatewayService.statusSnapshot().describeLine());
        }
        if (citizensIntegrationService != null) {
            lines.add(citizensIntegrationService.statusSnapshot().describeLine());
        } else {
            lines.add("Citizens -> not active");
        }

        List<FractureRegionSnapshot> fractures = worldThreatManager == null ? List.of() : worldThreatManager.activeFractures();
        Map<FractureState, Integer> byState = new EnumMap<>(FractureState.class);
        for (FractureState state : FractureState.values()) {
            byState.put(state, 0);
        }
        FractureRegionSnapshot hottest = null;
        for (FractureRegionSnapshot fracture : fractures) {
            byState.compute(fracture.state(), (ignored, count) -> count == null ? 1 : count + 1);
            if (hottest == null || fracture.threatScore() > hottest.threatScore()) {
                hottest = fracture;
            }
        }

        lines.add(
            "Fractures -> total: " + fractures.size()
                + ", unstable: " + byState.getOrDefault(FractureState.UNSTABLE, 0)
                + ", corrupted: " + byState.getOrDefault(FractureState.CORRUPTED, 0)
                + ", overrun: " + byState.getOrDefault(FractureState.OVERRUN, 0)
                + ", breach: " + byState.getOrDefault(FractureState.BOSS_BREACH, 0)
        );
        if (hottest != null) {
            lines.add(
                "Fractures -> hottest region: "
                    + hottest.key().chunkX()
                    + ","
                    + hottest.key().chunkZ()
                    + " in "
                    + hottest.key().worldId()
                    + " | state "
                    + hottest.state()
                    + " | score "
                    + String.format(Locale.US, "%.1f", hottest.threatScore())
            );
        }

        if (expeditionBoardManager != null) {
            lines.add(
                "Expeditions -> active runs: " + expeditionBoardManager.activeRunCount()
                    + ", assigned players: " + expeditionBoardManager.assignedPlayerCount()
                    + ", rotation: " + expeditionBoardManager.currentRotationId()
            );
        }

        if (mythicBreachManager != null || instanceController != null) {
            lines.add(
                "Instances -> live instances: " + (instanceController == null ? 0 : instanceController.activeInstanceCount())
                    + ", breach sessions: " + (mythicBreachManager == null ? 0 : mythicBreachManager.activeSessionCount())
                    + ", pending reward caches: " + (mythicBreachManager == null ? 0 : mythicBreachManager.pendingRewardCacheCount())
            );
        }

        lines.add("Drill target -> run /vortex persistence before and after restart/crash simulation, then compare /vortex diagnostics.");
        return List.copyOf(lines);
    }

    private boolean deny(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        messageService.send(sender, "\u00a7c\u2716 Access denied \u00a78\u2014 \u00a77requires \u00a7f" + permission);
        return true;
    }
}
