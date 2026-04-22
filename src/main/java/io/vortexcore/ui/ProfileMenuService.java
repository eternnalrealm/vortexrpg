package io.vortexcore.ui;

import io.vortexcore.adventure.SeasonTrackService;
import io.vortexcore.cosmetic.PrestigeCosmeticService;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.rpg.VortexClass;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ProfileMenuService {

    private final VortexMenuBuilder menuBuilder;
    private final CustomItemService customItemService;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final ProfessionService professionService;
    private final SeasonTrackService seasonTrackService;
    private final PrestigeCosmeticService prestigeCosmeticService;
    private final SpellbookService spellbookService;

    public ProfileMenuService(
        VortexMenuBuilder menuBuilder,
        CustomItemService customItemService,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        ProfessionService professionService,
        SeasonTrackService seasonTrackService,
        PrestigeCosmeticService prestigeCosmeticService,
        SpellbookService spellbookService
    ) {
        this.menuBuilder = menuBuilder;
        this.customItemService = customItemService;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.professionService = professionService;
        this.seasonTrackService = seasonTrackService;
        this.prestigeCosmeticService = prestigeCosmeticService;
        this.spellbookService = spellbookService;
    }

    public void open(Player player) {
        open(player, Page.OVERVIEW);
    }

    public void open(Player player, Page page) {
        menuBuilder.open(player, buildMenu(player, page));
    }

    private VortexMenuBuilder.MenuDefinition buildMenu(Player player, Page page) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Vortex Profile"), 6);
        switch (page) {
            case OVERVIEW -> buildOverviewPage(builder, profile);
            case ATTRIBUTES -> buildAttributesPage(builder, profile);
            case CAREER -> buildCareerPage(builder, profile);
            case REWARDS -> buildRewardsPage(builder, profile);
        }
        addNavigation(builder, page);
        return builder.build();
    }

    private void buildOverviewPage(VortexMenuBuilder.MenuDefinition.Builder builder, UnifiedPlayer profile) {
        builder.button(10, classIcon(profile, "Class Profile", List.of(
            classSkillService.summaryLine(profile)
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(12, customIcon("gui_adventure_scroll", Material.EXPERIENCE_BOTTLE, "Progression", List.of(
            "Level: " + profile.level(),
            "XP: " + profile.experience(),
            "To Next: " + playerProfileService.experienceToNextLevel(profile),
            "Skill Points: " + classSkillService.unspentSkillPoints(profile)
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(14, customIcon("gui_spell_relic", Material.ENCHANTED_BOOK, "Spell Loadout", List.of(
            "Active Spell: " + classSkillService.activeSpellId(profile).map(spellbookService::spellLabel).orElse("None"),
            "Unlocked Spells: " + spellbookService.availableSpells(profile).size(),
            "Ascension: " + classSkillService.activeAscension(profile).map(ClassSkillService.ClassAscension::displayName).orElse("None")
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(30, customIcon("gui_breach_token", Material.NAME_TAG, "Prestige", List.of(
            prestigeCosmeticService.cosmeticSummary(profile),
            "Breach Tokens: " + playerProfileService.breachTokenBalance(profile)
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(32, icon(Material.BOOK, "Profession Summary", List.of(
            professionService.summaryLine(profile)
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private void buildAttributesPage(VortexMenuBuilder.MenuDefinition.Builder builder, UnifiedPlayer profile) {
        VortexAttribute[] attributes = VortexAttribute.values();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int index = 0; index < Math.min(attributes.length, slots.length); index++) {
            VortexAttribute attribute = attributes[index];
            builder.button(slots[index], icon(attributeIcon(attribute), NameFormat.friendly(attribute.name()), List.of(
                "Current: " + format(profile.stat(attribute).current()),
                "Base: " + format(profile.stat(attribute).snapshot().baseValue()),
                "Flat Bonus: " + format(profile.stat(attribute).snapshot().flatBonus()),
                "Multiplier: " + format(profile.stat(attribute).snapshot().multiplier())
            )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        }
        builder.button(31, icon(Material.COMPASS, "Attribute Notes", List.of(
            "Strength feeds melee damage.",
            "Vitality feeds health and armor.",
            "Dexterity feeds mobility.",
            "Wisdom, intelligence, and spirit feed spell scaling."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private void buildCareerPage(VortexMenuBuilder.MenuDefinition.Builder builder, UnifiedPlayer profile) {
        builder.button(10, classIcon(profile, "Class Ascension", classSkillService.ascensionStatusLines(profile)), context ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        );
        builder.button(12, icon(Material.BOOKSHELF, "Skills", List.of(
            "Unlocked Skills: " + classSkillService.unlockedSkillIds(profile).size(),
            "Skill Points: " + classSkillService.unspentSkillPoints(profile),
            "Use /skills for the full atlas."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(14, icon(Material.SMITHING_TABLE, "Professions", professionService.detailLines(profile)), context ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        );
        builder.button(16, icon(Material.BEACON, "Masteries", professionService.masteryLines(profile)), context ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        );
        builder.button(31, icon(Material.MAP, "Career Notes", List.of(
            "Ascend your class at the configured threshold.",
            "Profession masteries grant permanent bonuses.",
            "Season rewards and expeditions round out endgame progression."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private void buildRewardsPage(VortexMenuBuilder.MenuDefinition.Builder builder, UnifiedPlayer profile) {
        builder.button(10, customIcon("gui_adventure_scroll", Material.GLOW_ITEM_FRAME, "Season Track", List.of(
            "Season: " + seasonTrackService.currentSeasonId(),
            "Renown: " + seasonTrackService.renown(profile),
            "Level: " + seasonTrackService.currentLevel(profile),
            "To Next: " + seasonTrackService.renownToNextLevel(profile),
            "Use /adventure season for the reward track."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(12, icon(Material.NAME_TAG, "Titles", List.of(
            "Active: " + playerProfileService.activeTitle(profile).map(NameFormat::friendly).orElse("None"),
            "Unlocked: " + playerProfileService.unlockedTitles(profile).size()
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(14, icon(Material.FEATHER, "Trails + Auras", List.of(
            "Trail: " + playerProfileService.activeTrail(profile).map(NameFormat::friendly).orElse("None"),
            "Aura: " + playerProfileService.activeAura(profile).map(NameFormat::friendly).orElse("None"),
            "Unlocked Trails: " + playerProfileService.unlockedTrails(profile).size(),
            "Unlocked Auras: " + playerProfileService.unlockedAuras(profile).size()
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(16, customIcon("gui_breach_token", Material.RED_BANNER, "Breach Banners", List.of(
            "Active Banner: " + playerProfileService.activeBanner(profile).map(NameFormat::friendly).orElse("None"),
            "Unlocked Banners: " + playerProfileService.unlockedBanners(profile).size()
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(31, icon(Material.CHEST, "Reward Notes", List.of(
            "Adventure Board and expeditions feed season renown.",
            "Season levels unlock cosmetics and currency.",
            "Breach tokens remain your core RPG vendor currency."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private void addNavigation(VortexMenuBuilder.MenuDefinition.Builder builder, Page page) {
        builder.button(45, navIcon(Material.NETHER_STAR, "Overview", page == Page.OVERVIEW), context -> {
            context.open(buildMenu(context.player(), Page.OVERVIEW));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(46, navIcon(Material.DIAMOND_SWORD, "Attributes", page == Page.ATTRIBUTES), context -> {
            context.open(buildMenu(context.player(), Page.ATTRIBUTES));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(52, navIcon(Material.BOOK, "Career", page == Page.CAREER), context -> {
            context.open(buildMenu(context.player(), Page.CAREER));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(53, navIcon(Material.GLOW_ITEM_FRAME, "Rewards", page == Page.REWARDS), context -> {
            context.open(buildMenu(context.player(), Page.REWARDS));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    }

    private ItemStack navIcon(Material material, String title, boolean active) {
        return icon(material, active ? title + " (Open)" : title, List.of(active ? "Current page." : "Click to open."));
    }

    private ItemStack classIcon(UnifiedPlayer profile, String title, List<String> loreLines) {
        return classSkillService.selectedClass(profile)
            .map(this::classIconId)
            .map(iconId -> customItemService.createIcon(iconId, title, loreLines))
            .orElseGet(() -> icon(Material.NETHER_STAR, title, loreLines));
    }

    private ItemStack customIcon(String iconId, Material fallback, String title, List<String> loreLines) {
        return iconId == null ? icon(fallback, title, loreLines) : customItemService.createIcon(iconId, title, loreLines);
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Material attributeIcon(VortexAttribute attribute) {
        return switch (attribute) {
            case STRENGTH -> Material.IRON_SWORD;
            case VITALITY -> Material.SHIELD;
            case WISDOM -> Material.BOOK;
            case DEXTERITY -> Material.FEATHER;
            case INTELLIGENCE -> Material.ENCHANTING_TABLE;
            case SPIRIT -> Material.BEACON;
        };
    }

    private String classIconId(VortexClass vortexClass) {
        return switch (vortexClass) {
            case KNIGHT -> "gui_knight_crest";
            case MAGE -> "gui_mage_prism";
            case RANGER -> "gui_ranger_mark";
            case CLERIC -> "gui_cleric_seal";
            case BEASTMASTER -> "gui_beastmaster_fang";
            case ENGINEER -> "gui_engineer_gear";
        };
    }

    private String format(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    public enum Page {
        OVERVIEW,
        ATTRIBUTES,
        CAREER,
        REWARDS
    }
}
