package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.PlayerPoseEvent;
import dev.geco.gsit.api.event.PlayerStopPoseEvent;
import dev.geco.gsit.api.event.PrePlayerPoseEvent;
import dev.geco.gsit.api.event.PrePlayerStopPoseEvent;
import dev.geco.gsit.model.Pose;
import dev.geco.gsit.model.PoseType;
import dev.geco.gsit.model.Seat;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PoseService {

    public static final String POSE_TAG = GSitMain.NAME + "_POSE";

    private final GSitMain gSitMain;
    private final boolean available;
    private final HashMap<UUID, Pose> poses = new HashMap<>();
    private final HashMap<Block, Set<Pose>> blockPoses = new HashMap<>();
    private final HashMap<PoseType, Integer> poseCount = new HashMap<>();
    private final HashMap<PoseType, Long> poseTime = new HashMap<>();

    public PoseService(GSitMain gSitMain) {
        this.gSitMain = gSitMain;
        available = gSitMain.getVersionManager().isNewerOrVersion(1, 18);
    }

    public boolean isAvailable() { return available; }

    public HashMap<UUID, Pose> getAllPoses() { return poses; }

    public boolean isPlayerPosing(Player player) { synchronized(poses) { return poses.containsKey(player.getUniqueId()); } }

    public Pose getPoseByPlayer(Player player) { synchronized(poses) { return poses.get(player.getUniqueId()); } }

    public java.util.List<Pose> getPosesSnapshot() { synchronized(poses) { return new ArrayList<>(poses.values()); } }

    public boolean hasPoses() { synchronized(poses) { return !poses.isEmpty(); } }

    public void removeAllPoses() { for(Pose pose : getPosesSnapshot()) removePose(pose, StopReason.PLUGIN); }

    public boolean isBlockWithPose(Block block) { synchronized(blockPoses) { return blockPoses.containsKey(block); } }

    public Set<Pose> getPosesByBlock(Block block) { synchronized(blockPoses) { return new HashSet<>(blockPoses.getOrDefault(block, Collections.emptySet())); } }

    public boolean kickPoseEntitiesFromBlock(Block block, Player player) {
        if(!isBlockWithPose(block)) return true;
        if(!gSitMain.getPermissionService().hasPermission(player, "Kick.Pose", "Kick.*")) return false;
        for(Pose pose : getPosesByBlock(block)) if(!removePose(pose, StopReason.KICKED)) return false;
        return true;
    }

    public Pose createPose(Block block, Player player, PoseType poseType) { return createPose(block, player, poseType, 0d, 0d, 0d, player.getLocation().getYaw(), gSitMain.getConfigService().CENTER_BLOCK); }

    public Pose createPose(Block block, Player player, PoseType poseType, double xOffset, double yOffset, double zOffset, float seatRotation, boolean sitInBlockCenter) {
        if(!gSitMain.isAcceptingOperations()) return null;
        Location returnLocation = player.getLocation();
        Location seatLocation = gSitMain.getSitService().getSeatLocation(block, returnLocation, xOffset, yOffset, zOffset, sitInBlockCenter);
        if(!gSitMain.getEntityUtil().isSitLocationValid(seatLocation)) return null;

        PrePlayerPoseEvent prePlayerPoseEvent = new PrePlayerPoseEvent(player, block);
        Bukkit.getPluginManager().callEvent(prePlayerPoseEvent);
        if(prePlayerPoseEvent.isCancelled()) return null;

        seatLocation.setYaw(seatRotation);
        Entity seatEntity = gSitMain.getEntityUtil().createSeatEntity(seatLocation, player, true);
        if(seatEntity == null) return null;

        if(gSitMain.getConfigService().CUSTOM_MESSAGE) gSitMain.getMessageService().sendActionBarMessage(player, "Messages.action-pose-info");

        Pose pose = gSitMain.getEntityUtil().createPose(new Seat(block, seatLocation, player, seatEntity, returnLocation), poseType);
        if(pose == null) return null;

        pose.spawn();
        synchronized(poses) { poses.put(player.getUniqueId(), pose); }
        synchronized(blockPoses) { blockPoses.computeIfAbsent(block, b -> new HashSet<>()).add(pose); }
        synchronized(poseCount) { poseCount.merge(poseType, 1, Integer::sum); }
        Bukkit.getPluginManager().callEvent(new PlayerPoseEvent(pose));

        return pose;
    }

    public boolean removePose(Pose pose, StopReason stopReason) { return removePose(pose, stopReason, true); }

    public boolean removePose(Pose pose, StopReason stopReason, boolean useSafeDismount) { return removePose(pose, stopReason, useSafeDismount, true); }

    boolean removePoseForHotUnload(Pose pose) { return removePose(pose, StopReason.PLUGIN, true, false); }

    private boolean removePose(Pose pose, StopReason stopReason, boolean useSafeDismount, boolean removeSeatEntity) {
        PrePlayerStopPoseEvent prePlayerStopPoseEvent = new PrePlayerStopPoseEvent(pose, stopReason);
        Bukkit.getPluginManager().callEvent(prePlayerStopPoseEvent);
        if(prePlayerStopPoseEvent.isCancelled() && stopReason.isCancellable()) return false;

        Seat seat = pose.getSeat();
        Player player = pose.getPlayer();
        synchronized(blockPoses) {
            Set<Pose> blockPoseList = blockPoses.get(seat.getBlock());
            if(blockPoseList != null) {
                blockPoseList.remove(pose);
                if(blockPoseList.isEmpty()) blockPoses.remove(seat.getBlock(), blockPoseList);
            }
        }
        synchronized(poses) { poses.remove(player.getUniqueId(), pose); }
        if(useSafeDismount) gSitMain.getSitService().handleSafeSeatDismount(seat);

        pose.remove();
        if(removeSeatEntity) {
            if(!stopReason.isUsingEntityTask()) seat.getSeatEntity().remove();
            else gSitMain.getTaskService().run(() -> seat.getSeatEntity().remove(), seat.getSeatEntity());
        }
        Bukkit.getPluginManager().callEvent(new PlayerStopPoseEvent(pose, stopReason));
        synchronized(poseCount) { poseTime.merge(pose.getPoseType(), seat.getLifetimeInNanoSeconds(), Long::sum); }

        return true;
    }

    public Map<PoseType, Integer> getPoseCount() { synchronized(poseCount) { return new HashMap<>(poseCount); } }

    public Map<PoseType, Integer> getPoseTime() { synchronized(poseCount) { return poseTime.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> Math.toIntExact(e.getValue() / 1_000_000_000))); } }

    public void resetPoseStats() {
        synchronized(poseCount) {
            poseCount.clear();
            poseTime.clear();
        }
    }

}
