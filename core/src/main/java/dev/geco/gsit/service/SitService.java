package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.EntitySitEvent;
import dev.geco.gsit.api.event.EntityStopSitEvent;
import dev.geco.gsit.api.event.PreEntitySitEvent;
import dev.geco.gsit.api.event.PreEntityStopSitEvent;
import dev.geco.gsit.model.Seat;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class SitService {

    public static final double STAIR_XZ_OFFSET = 0.123d;
    public static final double STAIR_Y_OFFSET = 0.5d;
    public static final double DIRECTIONAL_XZ_OFFSET = 0.2d;
    public static final String SIT_TAG = GSitMain.NAME + "_sit";

    private final GSitMain gSitMain;
    private final double baseOffset;
    private final HashMap<UUID, Seat> seats = new HashMap<>();
    private final HashMap<Block, Set<Seat>> blockSeats = new HashMap<>();
    private int sitCount = 0;
    private long sitTime = 0;

    public SitService(GSitMain gSitMain) {
        this.gSitMain = gSitMain;
        baseOffset = gSitMain.getVersionManager().isNewerOrVersion(1, 20, 2) ? -0.025d : 0.1975d;
    }

    public double getBaseOffset() { return baseOffset; }

    public HashMap<UUID, Seat> getAllSeats() { return seats; }

    public boolean isEntitySitting(LivingEntity entity) { synchronized(seats) { return seats.containsKey(entity.getUniqueId()); } }

    public Seat getSeatByEntity(LivingEntity entity) { synchronized(seats) { return seats.get(entity.getUniqueId()); } }

    public List<Seat> getSeatsSnapshot() { synchronized(seats) { return new ArrayList<>(seats.values()); } }

    public boolean hasSeats() { synchronized(seats) { return !seats.isEmpty(); } }

    public void removeAllSeats() { for(Seat seat : getSeatsSnapshot()) removeSeat(seat, StopReason.PLUGIN); }

    public boolean isBlockWithSeat(Block block) { synchronized(blockSeats) { return blockSeats.containsKey(block); } }

    public Set<Seat> getSeatsByBlock(Block block) { synchronized(blockSeats) { return new HashSet<>(blockSeats.getOrDefault(block, Collections.emptySet())); } }

    public boolean kickSeatEntitiesFromBlock(Block block, LivingEntity entity) {
        if(!isBlockWithSeat(block)) return true;
        if(!gSitMain.getPermissionService().hasPermission(entity, "Kick.Sit", "Kick.*")) return false;
        for(Seat seat : getSeatsByBlock(block)) if(!removeSeat(seat, StopReason.KICKED)) return false;
        return true;
    }

    public boolean isValidSitBlockData(BlockData blockData) {
        for(BlockData sitBlockData : gSitMain.getConfigService().S_SITBLOCKDATA.keySet()) if(sitBlockData.matches(blockData)) return true;
        return gSitMain.getConfigService().S_SITMATERIALS.containsKey(blockData.getMaterial());
    }

    public double getSitBlockDataHeightOffset(BlockData blockData) {
        for(Map.Entry<BlockData, Double> sitBlockData : gSitMain.getConfigService().S_SITBLOCKDATA.entrySet()) if(sitBlockData.getKey().matches(blockData)) return sitBlockData.getValue();
        return gSitMain.getConfigService().S_SITMATERIALS.getOrDefault(blockData.getMaterial(), 0d);
    }

    public boolean isBlacklistedSitBlockData(BlockData blockData) {
        for(BlockData sitBlockData : gSitMain.getConfigService().BLOCKDATABLACKLIST) if(sitBlockData.matches(blockData)) return true;
        return gSitMain.getConfigService().MATERIALBLACKLIST.contains(blockData.getMaterial());
    }

    public Seat createSeat(Block block, LivingEntity entity) { return createSeat(block, entity, true, 0d, 0d, 0d, entity.getLocation().getYaw(), gSitMain.getConfigService().CENTER_BLOCK); }

    public Seat createSeat(Block block, LivingEntity entity, boolean canRotate, double xOffset, double yOffset, double zOffset, float seatRotation, boolean sitInBlockCenter) {
        if(!gSitMain.isAcceptingOperations()) return null;
        Location returnLocation = entity.getLocation();
        Location seatLocation = getSeatLocation(block, returnLocation, xOffset, yOffset, zOffset, sitInBlockCenter);
        if(!gSitMain.getEntityUtil().isSitLocationValid(seatLocation)) return null;

        PreEntitySitEvent preEntitySitEvent = new PreEntitySitEvent(entity, block);
        Bukkit.getPluginManager().callEvent(preEntitySitEvent);
        if(preEntitySitEvent.isCancelled()) return null;

        seatLocation.setYaw(seatRotation);
        Entity seatEntity = gSitMain.getEntityUtil().createSeatEntity(seatLocation, entity, canRotate);
        if(seatEntity == null) return null;

        if(gSitMain.getConfigService().CUSTOM_MESSAGE && entity instanceof Player) gSitMain.getMessageService().sendActionBarMessage((Player) entity, "Messages.action-sit-info");

        Seat seat = new Seat(block, seatLocation, entity, seatEntity, returnLocation);
        synchronized(seats) { seats.put(entity.getUniqueId(), seat); }
        synchronized(blockSeats) { blockSeats.computeIfAbsent(block, b -> new HashSet<>()).add(seat); }
        synchronized(seats) { sitCount++; }
        Bukkit.getPluginManager().callEvent(new EntitySitEvent(seat));

        return seat;
    }

    public Location getSeatLocation(Block block, Location location, double xOffset, double yOffset, double zOffset, boolean sitInBlockCenter) {
        double additionalOffset = sitInBlockCenter ? block.getBoundingBox().getMinY() + block.getBoundingBox().getHeight() : 0d;
        double sitBlockDataHeightOffset = getSitBlockDataHeightOffset(block.getBlockData());
        additionalOffset = (sitInBlockCenter ? additionalOffset == 0d ? 1d : additionalOffset - block.getY() : additionalOffset) + sitBlockDataHeightOffset;
        if(sitInBlockCenter) return block.getLocation().add(0.5d + xOffset, yOffset - baseOffset + additionalOffset, 0.5d + zOffset);
        return location.add(xOffset, yOffset - baseOffset + sitBlockDataHeightOffset, zOffset);
    }

    public void moveSeat(Seat seat, BlockFace blockDirection) {
        if(seat.getEntity() instanceof Player player) {
            PlayerMoveEvent playerMoveEvent = new PlayerMoveEvent(player, player.getLocation(), player.getLocation().add(blockDirection.getModX(), blockDirection.getModY(), blockDirection.getModZ()));
            Bukkit.getPluginManager().callEvent(playerMoveEvent);
            if(playerMoveEvent.isCancelled()) return;
        }

        synchronized(blockSeats) {
            Set<Seat> blockSeatList = blockSeats.get(seat.getBlock());
            if(blockSeatList != null) blockSeatList.remove(seat);
            seat.setBlock(seat.getBlock().getRelative(blockDirection));
            blockSeats.computeIfAbsent(seat.getBlock(), b -> new HashSet<>()).add(seat);
        }
        seat.setLocation(seat.getLocation().add(blockDirection.getModX(), blockDirection.getModY(), blockDirection.getModZ()));
        gSitMain.getEntityUtil().setEntityLocation(seat.getSeatEntity(), seat.getLocation());
    }

    public boolean removeSeat(Seat seat, StopReason stopReason) { return removeSeat(seat, stopReason, true); }

    public boolean removeSeat(Seat seat, StopReason stopReason, boolean useSafeDismount) { return removeSeat(seat, stopReason, useSafeDismount, true); }

    boolean removeSeatForHotUnload(Seat seat) { return removeSeat(seat, StopReason.PLUGIN, true, false); }

    private boolean removeSeat(Seat seat, StopReason stopReason, boolean useSafeDismount, boolean removeSeatEntity) {
        PreEntityStopSitEvent preEntityStopSitEvent = new PreEntityStopSitEvent(seat, stopReason);
        Bukkit.getPluginManager().callEvent(preEntityStopSitEvent);
        if(preEntityStopSitEvent.isCancelled() && stopReason.isCancellable()) return false;

        Entity entity = seat.getEntity();
        synchronized(seats) { seats.remove(entity.getUniqueId(), seat); }
        if(useSafeDismount) handleSafeSeatDismount(seat);

        synchronized(blockSeats) {
            Set<Seat> blockSeatList = blockSeats.get(seat.getBlock());
            if(blockSeatList != null) {
                blockSeatList.remove(seat);
                if(blockSeatList.isEmpty()) blockSeats.remove(seat.getBlock(), blockSeatList);
            }
        }
        if(removeSeatEntity) {
            if(!stopReason.isUsingEntityTask()) seat.getSeatEntity().remove();
            else gSitMain.getTaskService().run(() -> seat.getSeatEntity().remove(), seat.getSeatEntity());
        }
        Bukkit.getPluginManager().callEvent(new EntityStopSitEvent(seat, stopReason));
        synchronized(seats) { sitTime += seat.getLifetimeInNanoSeconds(); }

        return true;
    }

    public void handleSafeSeatDismount(Seat seat) {
        Location returnLocation;
        if(gSitMain.getConfigService().GET_UP_RETURN) returnLocation = seat.getReturnLocation();
        else {
            BlockData blockData = seat.getBlock().getBlockData();
            double sitBlockDataHeightOffset = getSitBlockDataHeightOffset(blockData);
            returnLocation = seat.getLocation().add(0d, baseOffset + (blockData instanceof Stairs ? STAIR_Y_OFFSET : 0d) - sitBlockDataHeightOffset, 0d);
        }

        Entity entity = seat.getEntity();
        Location entityLocation = entity.getLocation();

        returnLocation.setYaw(entityLocation.getYaw());
        returnLocation.setPitch(entityLocation.getPitch());

        try {
            if(entity.isValid()) gSitMain.getEntityUtil().setEntityLocation(entity, returnLocation);
            if(seat.getSeatEntity().isValid() && !gSitMain.getVersionManager().isNewerOrVersion(1, 17)) gSitMain.getEntityUtil().setEntityLocation(seat.getSeatEntity(), returnLocation);
        } catch(IllegalStateException e) {
            if(!gSitMain.isFoliaServer()) gSitMain.getLogger().log(Level.WARNING, "Failed to set entity location", e);
        }
    }

    public Seat createCustomSeat(Block block, LivingEntity entity, boolean force) { return createCustomSeat(block, entity, force, true, 0d, 0d, 0d, entity.getLocation().getYaw(), gSitMain.getConfigService().CENTER_BLOCK); }

    public Seat createCustomSeat(Block block, LivingEntity entity, boolean force, boolean canRotate, double xOffset, double yOffset, double zOffset, float seatRotation, boolean sitInBlockCenter) {
        BlockData blockData = block.getBlockData();

        if(!force && blockData instanceof Slab slab && (slab.getType() != Slab.Type.BOTTOM && gSitMain.getConfigService().S_BOTTOM_PART_ONLY)) return null;

        if(blockData instanceof Stairs stair) {
            if(stair.getHalf() == Bisected.Half.BOTTOM) {
                return gSitMain.getSitService().createStairSeat(block, stair, entity);
            } else if(!force && gSitMain.getConfigService().S_BOTTOM_PART_ONLY) return null;
        }

        if(block.getType().name().equals("SHELF_MUSHROOM") && blockData instanceof Directional directional) {
            return createDirectionalSeat(block, directional, entity);
        }

        return createSeat(block, entity, canRotate, xOffset, yOffset, zOffset, seatRotation, sitInBlockCenter);
    }

    private Seat createStairSeat(Block block, Stairs blockData, LivingEntity entity) {
        if(blockData.getHalf() != Bisected.Half.BOTTOM) return createSeat(block, entity);

        BlockFace blockFace = blockData.getFacing().getOppositeFace();
        if(blockData.getShape() == Stairs.Shape.STRAIGHT) {
            return switch(blockFace) {
                case EAST -> createSeat(block, entity, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, 0d, -90f, true);
                case SOUTH -> createSeat(block, entity, false, 0d, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, 0f, true);
                case WEST -> createSeat(block, entity, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, 0d, 90f, true);
                case NORTH -> createSeat(block, entity, false, 0d, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, 180f, true);
                default -> null;
            };
        }

        Stairs.Shape stairShape = blockData.getShape();
        if(blockFace == BlockFace.NORTH && stairShape == Stairs.Shape.OUTER_RIGHT || blockFace == BlockFace.EAST && stairShape == Stairs.Shape.OUTER_LEFT || blockFace == BlockFace.NORTH && stairShape == Stairs.Shape.INNER_RIGHT || blockFace == BlockFace.EAST && stairShape == Stairs.Shape.INNER_LEFT) {
            return createSeat(block, entity, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, -135f, true);
        } else if(blockFace == BlockFace.NORTH && stairShape == Stairs.Shape.OUTER_LEFT || blockFace == BlockFace.WEST && stairShape == Stairs.Shape.OUTER_RIGHT || blockFace == BlockFace.NORTH && stairShape == Stairs.Shape.INNER_LEFT || blockFace == BlockFace.WEST && stairShape == Stairs.Shape.INNER_RIGHT) {
            return createSeat(block, entity, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, 135f, true);
        } else if(blockFace == BlockFace.SOUTH && stairShape == Stairs.Shape.OUTER_RIGHT || blockFace == BlockFace.WEST && stairShape == Stairs.Shape.OUTER_LEFT || blockFace == BlockFace.SOUTH && stairShape == Stairs.Shape.INNER_RIGHT || blockFace == BlockFace.WEST && stairShape == Stairs.Shape.INNER_LEFT) {
            return createSeat(block, entity, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, 45f, true);
        } else if(blockFace == BlockFace.SOUTH && stairShape == Stairs.Shape.OUTER_LEFT || blockFace == BlockFace.EAST && stairShape == Stairs.Shape.OUTER_RIGHT || blockFace == BlockFace.SOUTH && stairShape == Stairs.Shape.INNER_LEFT || blockFace == BlockFace.EAST && stairShape == Stairs.Shape.INNER_RIGHT) {
            return createSeat(block, entity, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, -45f, true);
        }

        return null;
    }

    private Seat createDirectionalSeat(Block block, Directional blockData, LivingEntity entity) {
        BlockFace blockFace = blockData.getFacing().getOppositeFace();

        return switch(blockFace) {
            case EAST -> createSeat(block, entity, false, DIRECTIONAL_XZ_OFFSET, 0d, 0d, 90f, true);
            case SOUTH -> createSeat(block, entity, false, 0d, 0d, DIRECTIONAL_XZ_OFFSET, 180f, true);
            case WEST -> createSeat(block, entity, false, -DIRECTIONAL_XZ_OFFSET, 0d, 0d, -90f, true);
            case NORTH -> createSeat(block, entity, false, 0d, 0d, -DIRECTIONAL_XZ_OFFSET, 0f, true);
            default -> null;
        };
    }

    public int getSitCount() { synchronized(seats) { return this.sitCount; } }

    public int getSitTime() { synchronized(seats) { return Math.toIntExact(this.sitTime / 1_000_000_000); } }

    public void resetSitStats() {
        synchronized(seats) {
            sitCount = 0;
            sitTime = 0;
        }
    }

}
