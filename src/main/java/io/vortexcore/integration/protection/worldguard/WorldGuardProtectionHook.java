package io.vortexcore.integration.protection.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHook;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class WorldGuardProtectionHook implements ProtectionHook {

    @Override
    public String providerName() {
        return "WorldGuard";
    }

    @Override
    public ProtectionCheckResult check(Player player, Location location, ProtectionAction action) {
        if (location.getWorld() == null) {
            return ProtectionCheckResult.pass();
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        if (regionContainer == null) {
            return ProtectionCheckResult.pass();
        }

        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()))) {
            return ProtectionCheckResult.pass();
        }

        RegionQuery query = regionContainer.createQuery();
        boolean allowed = query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.BUILD);
        if (allowed) {
            return ProtectionCheckResult.pass();
        }

        String coordinates = location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
        return new ProtectionCheckResult(
            false,
            providerName(),
            "WorldGuard blocked " + action.displayName() + " at "
                + location.getWorld().getName()
                + " [" + coordinates + "]"
        );
    }
}
