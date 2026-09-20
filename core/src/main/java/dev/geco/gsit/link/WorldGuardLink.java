package dev.geco.gsit.link;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.geco.gsit.GSitMain;
import dev.geco.gsit.link.worldguard.RegionFlagHandler;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class WorldGuardLink {

    public static StateFlag SIT_FLAG = new StateFlag("sit", true);
    public static StateFlag PLAYERSIT_FLAG = new StateFlag("playersit", true);
    public static StateFlag POSE_FLAG = new StateFlag("pose", true);
    public static StateFlag CRAWL_FLAG = new StateFlag("crawl", true);
    public static final String NPC_TAG = "NPC";

    public void registerFlags() {
        FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
        SIT_FLAG = registerOrGet(flagRegistry, SIT_FLAG);
        PLAYERSIT_FLAG = registerOrGet(flagRegistry, PLAYERSIT_FLAG);
        POSE_FLAG = registerOrGet(flagRegistry, POSE_FLAG);
        CRAWL_FLAG = registerOrGet(flagRegistry, CRAWL_FLAG);
    }

    private StateFlag registerOrGet(FlagRegistry registry, StateFlag flag) {
        try {
            registry.register(flag);
            return flag;
        } catch(Throwable ignored) {
            Flag<?> registered = registry.get(flag.getName());
            return registered instanceof StateFlag stateFlag ? stateFlag : flag;
        }
    }

    public void registerFlagHandlers() {
        WorldGuard.getInstance().getPlatform().getSessionManager().registerHandler(RegionFlagHandler.FACTORY, null);
    }

    public void unregisterFlagHandlers() {
        WorldGuard.getInstance().getPlatform().getSessionManager().unregisterHandler(RegionFlagHandler.FACTORY);
    }

    public boolean canUseInLocation(Location location, Player player, String flag) {
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            if(container.get(BukkitAdapter.adapt(location.getWorld())) == null) return true;
            RegionQuery regionQuery = container.createQuery();
            com.sk89q.worldedit.util.Location regionLocation = BukkitAdapter.adapt(location);
            FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
            if(!(flagRegistry.get(flag) instanceof StateFlag stateFlag)) return true;
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            return regionQuery.testState(regionLocation, localPlayer, stateFlag);
        } catch(Throwable e) { GSitMain.getInstance().getLogger().log(Level.SEVERE, "Could not check WorldGuard location!", e); }
        return true;
    }

}
