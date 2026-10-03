package dev.geco.gsit;

import dev.geco.gsit.event.PacketHandler;
import dev.geco.gsit.service.CrawlService;
import dev.geco.gsit.service.DataService;
import dev.geco.gsit.service.PlayerSitService;
import dev.geco.gsit.service.PoseService;
import dev.geco.gsit.service.SitService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GSitMainShutdownTest {

    @Test
    void fullServerShutdownSkipsWorldOwnedCleanup() throws Exception {
        Fixture fixture = new Fixture();

        fixture.unload(false);

        verify(fixture.dataService).close();
        verifyNoInteractions(fixture.sitService, fixture.playerSitService, fixture.poseService, fixture.crawlService);
        fixture.verifyNonWorldCleanup();
    }

    @Test
    void liveReloadStillCleansWorldOwnedState() throws Exception {
        Fixture fixture = new Fixture();

        fixture.unload(true);

        verify(fixture.dataService).close();
        verify(fixture.sitService).removeAllSeats();
        verify(fixture.playerSitService).removeAllPlayerSitStacks();
        verify(fixture.poseService).removeAllPoses();
        verify(fixture.crawlService).removeAllCrawls();
        fixture.verifyNonWorldCleanup();
    }

    private static final class Fixture {

        private final GSitMain plugin = mock(GSitMain.class, CALLS_REAL_METHODS);
        private final DataService dataService = mock(DataService.class);
        private final SitService sitService = mock(SitService.class);
        private final PlayerSitService playerSitService = mock(PlayerSitService.class);
        private final PoseService poseService = mock(PoseService.class);
        private final CrawlService crawlService = mock(CrawlService.class);
        private final PacketHandler packetHandler = mock(PacketHandler.class);

        private Fixture() throws Exception {
            setField("dataService", dataService);
            setField("sitService", sitService);
            setField("playerSitService", playerSitService);
            setField("poseService", poseService);
            setField("crawlService", crawlService);
            setField("packetHandler", packetHandler);
        }

        private void unload(boolean cleanupWorldState) throws Exception {
            Method unload = GSitMain.class.getDeclaredMethod("unload", boolean.class);
            unload.setAccessible(true);
            unload.invoke(plugin, cleanupWorldState);
        }

        private void verifyNonWorldCleanup() {
            verify(packetHandler).removePlayerPacketHandlers();
        }

        private void setField(String name, Object value) throws Exception {
            Field field = GSitMain.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(plugin, value);
        }
    }
}
