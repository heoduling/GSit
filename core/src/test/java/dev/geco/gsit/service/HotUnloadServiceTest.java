package dev.geco.gsit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotUnloadServiceTest {

    @Test
    void parsesSupportedPlugManAliasesWithoutTheLeadingSlash() {
        HotUnloadService.Request request = HotUnloadService.Request.parse("///plm ReLoAd GSit");

        assertEquals("reload", request.action());
        assertEquals("GSit", request.target());
        assertEquals("plm ReLoAd GSit", request.command());
        assertTrue(request.targetsGSit());
        assertFalse(request.allPlugins());
    }

    @Test
    void recognizesNamespacedPlugManAndAllTarget() {
        HotUnloadService.Request request = HotUnloadService.Request.parse("plugmanx:plugman unload *");

        assertEquals("unload", request.action());
        assertTrue(request.allPlugins());
        assertFalse(request.targetsGSit());
    }

    @Test
    void leavesLoadAndUnrelatedCommandsToPlugMan() {
        assertNull(HotUnloadService.Request.parse("plugman load GSit"));
        assertNull(HotUnloadService.Request.parse("plugman reload"));
        assertNull(HotUnloadService.Request.parse("other reload GSit"));
    }
}
