package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.PlayerPlayerSitEvent;
import dev.geco.gsit.api.event.PlayerStopPlayerSitEvent;
import dev.geco.gsit.api.event.PrePlayerPlayerSitEvent;
import dev.geco.gsit.api.event.PrePlayerStopPlayerSitEvent;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerSitService {

    public static final String PLAYERSIT_ENTITY_TAG = GSitMain.NAME + "_PlayerSitEntity";

    private final GSitMain gSitMain;
    private final int sitEntityStackCount;
    private final HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>> bottomToTopStacks = new HashMap<>();
    private final HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>> topToBottomStacks = new HashMap<>();
    private final Set<Player> preventDismountStackPlayers = Collections.synchronizedSet(new HashSet<>());
    private final HashMap<String, Long> spawnTimes = new HashMap<>();
    private int playerSitCount = 0;
    private long playerSitTime = 0;

    public PlayerSitService(GSitMain gSitMain) {
        this.gSitMain = gSitMain;
        sitEntityStackCount = gSitMain.getVersionManager().isNewerOrVersion(1, 20, 2) ? 1 : 2;
    }

    public int getSitEntityStackCount() { return sitEntityStackCount; }

    public Set<Player> getPreventDismountStackPlayers() { return preventDismountStackPlayers; }

    public boolean isPlayerInPlayerSitStack(Player player) {
        synchronized(bottomToTopStacks) {
            return bottomToTopStacks.containsKey(player.getUniqueId()) || topToBottomStacks.containsKey(player.getUniqueId());
        }
    }

    public boolean hasPlayerSitStacks() { synchronized(bottomToTopStacks) { return !bottomToTopStacks.isEmpty() || !topToBottomStacks.isEmpty(); } }

    public Map<UUID, List<UUID>> getBottomMarkerIdsSnapshot() {
        synchronized(bottomToTopStacks) {
            Map<UUID, List<UUID>> snapshot = new HashMap<>();
            bottomToTopStacks.forEach((bottomId, stack) -> snapshot.put(bottomId, List.copyOf(stack.getValue())));
            return snapshot;
        }
    }

    public void removeAllPlayerSitStacks() {
        List<UUID> topPlayerIds;
        synchronized(bottomToTopStacks) { topPlayerIds = new ArrayList<>(topToBottomStacks.keySet()); }
        for(UUID topPlayerId : topPlayerIds) {
            Player topPlayer = Bukkit.getPlayer(topPlayerId);
            if(topPlayer != null) stopPlayerSit(topPlayer, StopReason.PLUGIN, false, true, true);
        }
        synchronized(bottomToTopStacks) {
            bottomToTopStacks.clear();
            topToBottomStacks.clear();
        }
        preventDismountStackPlayers.clear();
    }

    public boolean sitOnPlayer(Player player, Player target) {
        if(!gSitMain.isAcceptingOperations()) return false;
        if(!gSitMain.getEntityUtil().isPlayerSitLocationValid(target.getLocation())) return false;

        PrePlayerPlayerSitEvent prePlayerPlayerSitEvent = new PrePlayerPlayerSitEvent(player, target);
        Bukkit.getPluginManager().callEvent(prePlayerPlayerSitEvent);
        if(prePlayerPlayerSitEvent.isCancelled()) return false;

        List<UUID> playerSitEntityIds = gSitMain.getEntityUtil().createPlayerSitEntities(player, target);
        if(playerSitEntityIds == null) return false;

        if(gSitMain.getConfigService().CUSTOM_MESSAGE) gSitMain.getMessageService().sendActionBarMessage(player, "Messages.action-playersit-info");

        synchronized(bottomToTopStacks) {
            bottomToTopStacks.put(target.getUniqueId(), new AbstractMap.SimpleImmutableEntry<>(player.getUniqueId(), playerSitEntityIds));
            topToBottomStacks.put(player.getUniqueId(), new AbstractMap.SimpleImmutableEntry<>(target.getUniqueId(), playerSitEntityIds));
            spawnTimes.put(target.getUniqueId().toString() + player.getUniqueId(), System.nanoTime());
            playerSitCount++;
        }
        Bukkit.getPluginManager().callEvent(new PlayerPlayerSitEvent(player, target));

        return true;
    }

    public boolean stopPlayerSit(Player source, StopReason stopReason) { return stopPlayerSit(source, stopReason, true, true, true); }

    public boolean stopPlayerSit(Player source, StopReason stopReason, boolean removePassengers, boolean removeVehicle, boolean callPreEvent) {
        return stopPlayerSit(source, stopReason, removePassengers, removeVehicle, callPreEvent, true);
    }

    boolean stopPlayerSitForHotUnload(Player source) { return stopPlayerSit(source, StopReason.PLUGIN, true, false, false, false); }

    private boolean stopPlayerSit(Player source, StopReason stopReason, boolean removePassengers, boolean removeVehicle, boolean callPreEvent, boolean removeEntities) {
        AbstractMap.SimpleImmutableEntry<UUID, List<UUID>> passengers;
        AbstractMap.SimpleImmutableEntry<UUID, List<UUID>> vehicles;
        synchronized(bottomToTopStacks) {
            passengers = removePassengers ? bottomToTopStacks.get(source.getUniqueId()) : null;
            vehicles = removeVehicle ? topToBottomStacks.get(source.getUniqueId()) : null;
        }
        if(passengers == null && vehicles == null) return true;

        if(callPreEvent) {
            PrePlayerStopPlayerSitEvent prePlayerStopPlayerSitEvent = new PrePlayerStopPlayerSitEvent(source, stopReason);
            Bukkit.getPluginManager().callEvent(prePlayerStopPlayerSitEvent);
            if(prePlayerStopPlayerSitEvent.isCancelled() && stopReason.isCancellable()) return false;
        }

        if(passengers != null) {
            source.eject();
            synchronized(bottomToTopStacks) {
                bottomToTopStacks.remove(source.getUniqueId(), passengers);
                topToBottomStacks.remove(passengers.getKey());
                String key = source.getUniqueId().toString() + passengers.getKey();
                Long spawnTime = spawnTimes.remove(key);
                if(spawnTime != null) playerSitTime += System.nanoTime() - spawnTime;
            }
            if(removeEntities) {
                for(UUID passenger : passengers.getValue()) {
                    Entity passengerEntity = Bukkit.getEntity(passenger);
                    if(passengerEntity == null) continue;
                    gSitMain.getTaskService().run(passengerEntity::remove, passengerEntity);
                }
            }
        }

        if(vehicles != null) {
            source.leaveVehicle();
            synchronized(bottomToTopStacks) {
                topToBottomStacks.remove(source.getUniqueId(), vehicles);
                bottomToTopStacks.remove(vehicles.getKey());
                String key = vehicles.getKey().toString() + source.getUniqueId();
                Long spawnTime = spawnTimes.remove(key);
                if(spawnTime != null) playerSitTime += System.nanoTime() - spawnTime;
            }
            if(removeEntities) {
                for(UUID vehicle : vehicles.getValue()) {
                    Entity vehicleEntity = Bukkit.getEntity(vehicle);
                    if(vehicleEntity == null) continue;
                    gSitMain.getTaskService().run(vehicleEntity::remove, vehicleEntity);
                }
            }
        }

        Bukkit.getPluginManager().callEvent(new PlayerStopPlayerSitEvent(source, stopReason));

        return true;
    }

    public int getPlayerSitCount() { synchronized(bottomToTopStacks) { return this.playerSitCount; } }

    public int getPlayerSitTime() { synchronized(bottomToTopStacks) { return Math.toIntExact(this.playerSitTime / 1_000_000_000); } }

    public void resetPlayerSitStats() {
        synchronized(bottomToTopStacks) {
            spawnTimes.clear();
            playerSitCount = 0;
            playerSitTime = 0;
        }
    }

}
