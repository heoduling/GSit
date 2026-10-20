package dev.geco.gsit.event;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.model.Pose;
import dev.geco.gsit.model.Seat;
import dev.geco.gsit.model.StopReason;
import dev.geco.gsit.service.ConfigService;
import dev.geco.gsit.service.PoseService;
import dev.geco.gsit.service.SitService;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;

class BlockEventHandlerTest {
    private final GSitMain plugin = mock(GSitMain.class);
    private final SitService sits = mock(SitService.class);
    private final PoseService poses = mock(PoseService.class);
    private final ConfigService config = mock(ConfigService.class);
    private final BlockEventHandler handler = new BlockEventHandler(plugin);
    private final Block block = mock(Block.class);

    BlockEventHandlerTest() {
        when(plugin.getSitService()).thenReturn(sits);
        when(plugin.getPoseService()).thenReturn(poses);
        when(plugin.getConfigService()).thenReturn(config);
        config.GET_UP_BREAK = true;
    }

    @Test void unoccupiedBlocksDoNotMaterializeSnapshots() {
        handler.blockPistonExtendEvent(new BlockPistonExtendEvent(block, List.of(block), BlockFace.EAST));
        handler.blockPistonRetractEvent(new BlockPistonRetractEvent(block, List.of(block), BlockFace.WEST));
        verify(sits, never()).getSeatsByBlock(any());
        verify(poses, never()).getPosesByBlock(any());
        verify(sits, never()).moveSeat(any(), any());
        verify(poses, never()).removePose(any(), any());
    }

    @Test void populatedBlocksStillMoveAndStopOnceEvenForDuplicateInputs() {
        Seat seat = mock(Seat.class);
        Pose pose = mock(Pose.class);
        when(sits.isBlockWithSeat(block)).thenReturn(true);
        when(poses.isBlockWithPose(block)).thenReturn(true);
        when(sits.getSeatsByBlock(block)).thenReturn(Set.of(seat));
        when(poses.getPosesByBlock(block)).thenReturn(Set.of(pose));
        handler.blockPistonExtendEvent(new BlockPistonExtendEvent(block, List.of(block, block), BlockFace.EAST));
        verify(sits).moveSeat(seat, BlockFace.EAST);
        verify(poses).removePose(pose, StopReason.BLOCK_BREAK);
    }

    @Test void disabledGetUpBreakPreservesPosesWhileMovingSeats() {
        config.GET_UP_BREAK = false;
        Seat seat = mock(Seat.class);
        when(sits.isBlockWithSeat(block)).thenReturn(true);
        when(sits.getSeatsByBlock(block)).thenReturn(Set.of(seat));
        handler.blockPistonRetractEvent(new BlockPistonRetractEvent(block, List.of(block), BlockFace.WEST));
        verify(sits).moveSeat(seat, BlockFace.WEST);
        verifyNoInteractions(poses);
    }
}
