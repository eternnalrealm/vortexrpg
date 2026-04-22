package io.vortexcore.integration.citizens;

import io.vortexcore.adventure.AdventureBoardService;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.npc.NeuralNPCManager;
import io.vortexcore.npc.NeuralNpcProfile;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.world.BreachQuartermasterService;
import io.vortexcore.world.ExpeditionBoardManager;
import io.vortexcore.world.FractureGatewayService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class CitizensIntegrationService implements Listener {

    private static final String META_ROLE = "vortex.role";
    private static final String META_CONTEXT = "vortex.context";

    private final FoliaExecutionFacade scheduler;
    private final ProtectionHookService protectionHookService;
    private final NeuralNPCManager neuralNPCManager;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final ProfessionService professionService;
    private final AdventureBoardService adventureBoardService;
    private final ExpeditionBoardManager expeditionBoardManager;
    private final BreachQuartermasterService breachQuartermasterService;
    private final FractureGatewayService fractureGatewayService;
    private final MessageService messageService;
    private final String greetingPrompt;

    public CitizensIntegrationService(
        FoliaExecutionFacade scheduler,
        ProtectionHookService protectionHookService,
        NeuralNPCManager neuralNPCManager,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        ProfessionService professionService,
        AdventureBoardService adventureBoardService,
        ExpeditionBoardManager expeditionBoardManager,
        BreachQuartermasterService breachQuartermasterService,
        FractureGatewayService fractureGatewayService,
        MessageService messageService,
        String greetingPrompt
    ) {
        this.scheduler = scheduler;
        this.protectionHookService = protectionHookService;
        this.neuralNPCManager = neuralNPCManager;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.professionService = professionService;
        this.adventureBoardService = adventureBoardService;
        this.expeditionBoardManager = expeditionBoardManager;
        this.breachQuartermasterService = breachQuartermasterService;
        this.fractureGatewayService = fractureGatewayService;
        this.messageService = messageService;
        this.greetingPrompt = greetingPrompt == null || greetingPrompt.isBlank()
            ? "Greet the player in one short line and explain how you can help."
            : greetingPrompt;
    }

    public String bindSelectedNpc(CommandSender sender, CitizensNpcRole role, String context) {
        NPC npc = selectedNpc(sender);
        if (npc == null) {
            return "Select a Citizens NPC first.";
        }

        npc.data().setPersistent(META_ROLE, role.id());
        if (context == null || context.isBlank()) {
            npc.data().remove(META_CONTEXT);
        } else {
            npc.data().setPersistent(META_CONTEXT, context);
        }
        return "Bound Citizens NPC #" + npc.getId() + " (" + npc.getName() + ") to " + role.displayName() + ".";
    }

    public String clearSelectedNpc(CommandSender sender) {
        NPC npc = selectedNpc(sender);
        if (npc == null) {
            return "Select a Citizens NPC first.";
        }

        npc.data().remove(META_ROLE);
        npc.data().remove(META_CONTEXT);
        return "Cleared Vortex binding from Citizens NPC #" + npc.getId() + " (" + npc.getName() + ").";
    }

    public List<String> selectedNpcInfo(CommandSender sender) {
        NPC npc = selectedNpc(sender);
        if (npc == null) {
            return List.of("Select a Citizens NPC first.");
        }

        ArrayList<String> lines = new ArrayList<>();
        lines.add("Citizens NPC #" + npc.getId() + " | " + npc.getName());
        lines.add("Role: " + roleOf(npc).map(CitizensNpcRole::displayName).orElse("Unbound"));
        String context = npc.data().get(META_CONTEXT, "");
        lines.add("Context: " + (context == null || context.isBlank() ? "None" : context));
        return List.copyOf(lines);
    }

    public StatusSnapshot statusSnapshot() {
        return new StatusSnapshot(CitizensAPI.hasImplementation(), countBindings());
    }

    public List<String> roleIds() {
        return java.util.Arrays.stream(CitizensNpcRole.values()).map(CitizensNpcRole::id).toList();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcRightClick(NPCRightClickEvent event) {
        Optional<CitizensNpcRole> role = roleOf(event.getNPC());
        if (role.isEmpty()) {
            return;
        }

        Player player = event.getClicker();
        ProtectionCheckResult protection = protectionHookService.check(player, npcLocation(event.getNPC(), player.getLocation()), ProtectionAction.NPC_INTERACT);
        if (!protection.allowed()) {
            event.setCancelled(true);
            messageService.send(player, ProtectionHookService.denyMessage(protection, ProtectionAction.NPC_INTERACT));
            return;
        }

        event.setCancelled(true);
        switch (role.get()) {
            case CLASS_TRAINER -> classSkillService.openClassMenu(player, playerProfileService.profile(player));
            case SKILL_MASTER -> classSkillService.openSkillMenu(player, playerProfileService.profile(player));
            case EXPEDITION_BOARD -> expeditionBoardManager.openBoard(player);
            case QUARTERMASTER -> breachQuartermasterService.openQuartermaster(player);
            case PROFESSION_MASTER -> professionService.openLedger(player);
            case ADVENTURE_BOARD -> adventureBoardService.openBoard(player);
            case FRACTURE_GATEWAY -> fractureGatewayService.openGatewayMenu(player);
            case NEURAL_GUIDE -> handleNeuralGuide(player, event.getNPC());
            case QUEST_GIVER -> player.performCommand("quest open");
        }
    }

    private void handleNeuralGuide(Player player, NPC npc) {
        UUID npcId = UUID.nameUUIDFromBytes(("citizens:" + npc.getId()).getBytes(StandardCharsets.UTF_8));
        String context = npc.data().get(META_CONTEXT, "");

        LinkedHashMap<String, Double> stats = new LinkedHashMap<>();
        stats.put("presence", 1.0D);
        stats.put("lore_depth", context == null || context.isBlank() ? 0.8D : 1.2D);

        String personaPrompt = context == null || context.isBlank()
            ? "You are a VortexRPG guide who explains nearby systems clearly and briefly."
            : context;

        neuralNPCManager.registerNpc(new NeuralNpcProfile(npcId, npc.getName(), personaPrompt, Map.copyOf(stats)));
        messageService.send(player, npc.getName() + " studies your aura...");
        neuralNPCManager.requestReply(npcId, player.getUniqueId(), greetingPrompt)
            .whenComplete((reply, error) -> {
                if (error != null) {
                    scheduler.runEntity(player, "citizens-neural-error-" + player.getUniqueId(), () ->
                        messageService.send(player, npc.getName() + " falls silent: " + error.getMessage())
                    );
                    return;
                }

                scheduler.runEntity(player, "citizens-neural-reply-" + player.getUniqueId(), () ->
                    messageService.send(player, "[" + npc.getName() + "] " + reply)
                );
            });
    }

    private Optional<CitizensNpcRole> roleOf(NPC npc) {
        if (npc == null || !npc.data().has(META_ROLE)) {
            return Optional.empty();
        }
        return Optional.ofNullable(CitizensNpcRole.parse(npc.data().get(META_ROLE, "")));
    }

    private NPC selectedNpc(CommandSender sender) {
        if (!CitizensAPI.hasImplementation()) {
            return null;
        }
        return CitizensAPI.getDefaultNPCSelector().getSelected(sender);
    }

    private int countBindings() {
        if (!CitizensAPI.hasImplementation()) {
            return 0;
        }

        int count = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.data().has(META_ROLE)) {
                count++;
            }
        }
        return count;
    }

    private Location npcLocation(NPC npc, Location fallback) {
        Entity entity = npc.getEntity();
        return entity == null ? fallback : entity.getLocation();
    }

    public record StatusSnapshot(boolean installed, int boundNpcCount) {

        public String describeLine() {
            return "Citizens -> installed: " + installed + " | bound NPCs: " + boundNpcCount;
        }
    }
}
