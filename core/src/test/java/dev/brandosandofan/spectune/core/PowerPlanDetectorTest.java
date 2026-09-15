package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PowerPlanDetectorTest {

    @Test
    void parsesTheBuiltInHighPerformanceScheme() {
        PowerPlanInfo info = PowerPlanDetector.parseWindows(
                "Power Scheme GUID: 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c  (High performance)\r\n");
        assertEquals(PowerPlanInfo.Profile.HIGH_PERFORMANCE, info.profile());
        assertEquals("High performance", info.label());
    }

    @Test
    void parsesTheBuiltInUltimatePerformanceScheme() {
        PowerPlanInfo info = PowerPlanDetector.parseWindows(
                "Power Scheme GUID: e9a42b02-d5df-448d-aa00-03f14749eb61  (Ultimate Performance)\r\n");
        assertEquals(PowerPlanInfo.Profile.HIGH_PERFORMANCE, info.profile());
    }

    @Test
    void parsesTheBuiltInBalancedScheme() {
        PowerPlanInfo info = PowerPlanDetector.parseWindows(
                "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)\r\n");
        assertEquals(PowerPlanInfo.Profile.BALANCED, info.profile());
        assertTrue(info.throttling());
    }

    @Test
    void parsesTheBuiltInPowerSaverScheme() {
        PowerPlanInfo info = PowerPlanDetector.parseWindows(
                "Power Scheme GUID: a1841308-3541-4fab-bc81-f71556f20b4a  (Power saver)\r\n");
        assertEquals(PowerPlanInfo.Profile.POWER_SAVER, info.profile());
        assertTrue(info.throttling());
    }

    @Test
    void classifiesAnUnrecognisedGuidByItsName() {
        // Vendor tools (Lenovo Vantage, Armoury Crate, ...) clone a scheme under their own GUID.
        PowerPlanInfo info = PowerPlanDetector.parseWindows(
                "Power Scheme GUID: 11111111-1111-1111-1111-111111111111  (Lenovo Best Performance Mode)\r\n");
        assertEquals(PowerPlanInfo.Profile.HIGH_PERFORMANCE, info.profile());
    }

    @Test
    void rejectsUnusablePowercfgOutput() {
        assertEquals(PowerPlanInfo.Profile.UNKNOWN, PowerPlanDetector.parseWindows(null).profile());
        assertEquals(PowerPlanInfo.Profile.UNKNOWN, PowerPlanDetector.parseWindows("").profile());
        assertEquals(PowerPlanInfo.Profile.UNKNOWN, PowerPlanDetector.parseWindows("'powercfg' is not recognized")
                .profile());
    }

    @Test
    void classifiesLinuxGovernors() {
        assertEquals(PowerPlanInfo.Profile.HIGH_PERFORMANCE,
                PowerPlanDetector.parseLinuxGovernor("performance\n").profile());
        assertEquals(PowerPlanInfo.Profile.POWER_SAVER,
                PowerPlanDetector.parseLinuxGovernor("powersave\n").profile());
        assertEquals(PowerPlanInfo.Profile.BALANCED,
                PowerPlanDetector.parseLinuxGovernor("schedutil\n").profile());
        assertEquals(PowerPlanInfo.Profile.BALANCED,
                PowerPlanDetector.parseLinuxGovernor("ondemand\n").profile());
        assertEquals(PowerPlanInfo.Profile.UNKNOWN,
                PowerPlanDetector.parseLinuxGovernor("userspace\n").profile());
        assertEquals(PowerPlanInfo.Profile.UNKNOWN, PowerPlanDetector.parseLinuxGovernor(null).profile());
    }

    @Test
    void detectionNeverThrowsOnTheHostMachine() {
        PowerPlanInfo info = PowerPlanDetector.detect();
        assertTrue(info != null);
    }
}
