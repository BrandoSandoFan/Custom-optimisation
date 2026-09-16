package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JvmAdvisorTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    private static CpuTopology ultra9() {
        return new CpuTopology("Intel(R) Core(TM) Ultra 9 275HX", 24, 24, 8, 16, false,
                CpuTopology.Source.CONFIG_OVERRIDE);
    }

    private static MachineProfile profile(long heapGiB, String collector, int java, List<String> args) {
        return new MachineProfile(ultra9(), 64 * GIB, heapGiB * GIB, heapGiB * GIB, collector, java,
                "vm", "Windows 11", args);
    }

    private static boolean mentions(List<Advice> advice, String fragment) {
        return advice.stream().anyMatch(a -> a.format().toLowerCase().contains(fragment.toLowerCase()));
    }

    @Test
    void sizesHeapAgainstInstalledRam() {
        assertEquals(12, JvmAdvisor.recommendedHeapGiB(64 * GIB));
        assertEquals(8, JvmAdvisor.recommendedHeapGiB(32 * GIB));
        assertEquals(6, JvmAdvisor.recommendedHeapGiB(16 * GIB));
        assertEquals(4, JvmAdvisor.recommendedHeapGiB(8 * GIB));
        assertEquals(4, JvmAdvisor.recommendedHeapGiB(-1));
    }

    @Test
    void warnsAboutAnOversizedHeap() {
        List<Advice> advice = JvmAdvisor.review(profile(40, "G1", 21, List.of("-Xmx40G")), null, null);
        assertTrue(mentions(advice, "compressed-oops"));
        assertTrue(advice.stream().anyMatch(a -> a.severity() == Advice.Severity.WARN));
    }

    @Test
    void leavesASensibleHeapAlone() {
        List<Advice> advice = JvmAdvisor.review(profile(12, "Generational ZGC", 21, List.of()), null, null);
        assertFalse(mentions(advice, "compressed-oops"));
        assertFalse(mentions(advice, "heap is small"));
    }

    @Test
    void warnsAboutStopTheWorldCollectors() {
        List<Advice> advice = JvmAdvisor.review(profile(8, "Parallel", 21, List.of()), null, null);
        assertTrue(mentions(advice, "throughput collector"));
        assertTrue(mentions(advice, "Generational ZGC"));
    }

    @Test
    void tellsZgcUsersToTurnOnGenerationalMode() {
        List<Advice> advice = JvmAdvisor.review(profile(8, "ZGC", 21, List.of()), null, null);
        assertTrue(mentions(advice, "ZGenerational"));
    }

    @Test
    void doesNotSuggestARemovedFlagOnNewerJava() {
        List<Advice> advice = JvmAdvisor.review(profile(8, "ZGC", 25, List.of()), null, null);
        assertTrue(mentions(advice, "default on Java 25"));
    }

    @Test
    void escalatesWhenTheGameIsOnTheIntegratedGpu() {
        GpuInfo igpu = GpuInfo.of("Intel", "Intel(R) Arc(TM) Graphics", "4.6");
        List<Advice> advice = JvmAdvisor.review(profile(8, "Generational ZGC", 21, List.of()), igpu, null);
        assertTrue(advice.stream().anyMatch(a -> a.severity() == Advice.Severity.CRITICAL));
        assertTrue(mentions(advice, "integrated"));
    }

    @Test
    void staysQuietOnAWellConfiguredMachine() {
        GpuInfo gpu = GpuInfo.of("NVIDIA", "NVIDIA GeForce RTX 5080 Laptop GPU", "4.6");
        List<Advice> advice = JvmAdvisor.review(
                profile(12, "Generational ZGC", 21, List.of("-Xmx12G", "-Xms12G", "-XX:+AlwaysPreTouch")), gpu,
                null);
        assertTrue(advice.stream().noneMatch(a -> a.severity() != Advice.Severity.INFO),
                () -> "unexpected warning: " + advice);
    }

    @Test
    void criticalWhenOsPowerPlanIsPowerSaver() {
        PowerPlanInfo saver = new PowerPlanInfo(PowerPlanInfo.Profile.POWER_SAVER, "Power saver");
        List<Advice> advice = JvmAdvisor.review(profile(8, "Generational ZGC", 21, List.of()), null, saver);
        assertTrue(advice.stream().anyMatch(a -> a.severity() == Advice.Severity.CRITICAL));
        assertTrue(mentions(advice, "capping cpu clocks"));
    }

    @Test
    void warnsWhenOsPowerPlanIsBalanced() {
        PowerPlanInfo balanced = new PowerPlanInfo(PowerPlanInfo.Profile.BALANCED, "Balanced");
        List<Advice> advice = JvmAdvisor.review(profile(8, "Generational ZGC", 21, List.of()), null, balanced);
        assertTrue(advice.stream().anyMatch(a -> a.severity() == Advice.Severity.WARN));
        assertTrue(mentions(advice, "throttles under light load"));
    }

    @Test
    void staysQuietWhenOsPowerPlanIsHighPerformanceOrUnknown() {
        PowerPlanInfo high = new PowerPlanInfo(PowerPlanInfo.Profile.HIGH_PERFORMANCE, "High performance");
        List<Advice> advice = JvmAdvisor.review(profile(8, "Generational ZGC", 21, List.of()), null, high);
        assertFalse(mentions(advice, "power plan"));

        List<Advice> unknown = JvmAdvisor.review(
                profile(8, "Generational ZGC", 21, List.of()), null, PowerPlanInfo.unknown());
        assertFalse(mentions(unknown, "power plan"));
    }

    @Test
    void generatedArgumentsAreInternallyConsistent() {
        MachineProfile machine = profile(12, "Generational ZGC", 21, List.of());
        TuningPlan plan = TuningPlanner.plan(machine, SpecTuneConfig.defaults(), null);
        List<String> args = JvmAdvisor.recommendedArguments(machine, plan);

        assertTrue(args.contains("-Xms12G"));
        assertTrue(args.contains("-Xmx12G"));
        assertTrue(args.contains("-XX:+UseZGC"));
        assertTrue(args.contains("-XX:+ZGenerational"), "generational mode is opt-in on Java 21");
        assertTrue(args.contains("-Dmax.bg.threads=" + plan.threads().backgroundThreads()));
        assertTrue(args.stream().noneMatch(a -> a.startsWith("-XX:+UseG1GC")));
    }

    @Test
    void dropsTheGenerationalFlagOnJavaVersionsWhereItWasRemoved() {
        MachineProfile machine = profile(12, "Generational ZGC", 25, List.of());
        TuningPlan plan = TuningPlanner.plan(machine, SpecTuneConfig.defaults(), null);
        assertFalse(JvmAdvisor.recommendedArguments(machine, plan).contains("-XX:+ZGenerational"));
    }

    @Test
    void foldsCollectorBeanNamesBackIntoOneName() {
        assertEquals("Generational ZGC",
                MachineProfile.collectorFromBeanNames(List.of("ZGC Minor Cycles", "ZGC Major Cycles")));
        assertEquals("ZGC", MachineProfile.collectorFromBeanNames(List.of("ZGC Cycles", "ZGC Pauses")));
        assertEquals("G1", MachineProfile.collectorFromBeanNames(List.of("G1 Young Generation", "G1 Old Generation")));
        assertEquals("Parallel", MachineProfile.collectorFromBeanNames(List.of("PS Scavenge", "PS MarkSweep")));
        assertEquals("Unknown", MachineProfile.collectorFromBeanNames(List.of()));
    }

    @Test
    void parsesJavaVersionStrings() {
        assertEquals(21, MachineProfile.javaMajor("21.0.4"));
        assertEquals(25, MachineProfile.javaMajor("25"));
        assertEquals(8, MachineProfile.javaMajor("1.8.0_402"));
        assertEquals(-1, MachineProfile.javaMajor(""));
        assertEquals(-1, MachineProfile.javaMajor(null));
    }
}
