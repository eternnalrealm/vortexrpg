package io.vortexcore.integration.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface ProtectionHook {

    String providerName();

    ProtectionCheckResult check(Player player, Location location, ProtectionAction action);
}
