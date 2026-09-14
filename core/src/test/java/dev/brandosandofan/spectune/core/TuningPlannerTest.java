package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.brandosandofan.spectune.core.TuningPlan.ThreadPlan;
import dev.brandosandofan.spectune.core.TuningPlan.VideoPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuningPlannerTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** The machine this mod was written for: Core Ultra 9 HX, 64 GiB, RTX 5080 laptop. */
    private static MachineProfile targetMachine() {
        CpuTopology cpu = new CpuTopology("Intel(R) Core(TM) Ultra 9 275HX", 24, 24, 8, 16, false,
                CpuTopology.Source.CONFIG_OVERRIDE);
        return new MachineProfile(cpu, 64 * GIB, 8 * GIB, 8 * GIB, "Generational ZGC", 21,
                "OpenJDK 64-Bit Server VM 21.0.4", "Windows 11", List.of("-Xmx8G", "-Xms8G"));
    }

    private static GpuInfo rtx5080() {
        return GpuInfo.of("NVIDIA Corporation", "NVIDIA GeForce RTX 5080 Laptop GPU/PCIe/SSE2", "4.6.0");
    }

    @Test
    void unlocksTheWorkerPoolWellBeyondVanilla() {
        ThreadPlan threads = TuningPlanner.planThreads(targetMachine(), SpecTuneConfig.defaults());
        assertEquals(16, threads.backgroundThreads());
        assertTrue(threads.backgroundThreads() > TuningPlanner.VANILLA_BACKGROUND_THREAD_CAP);
    }

    @Test
    void separatesForegroundAndWorkerPriorityMoreOnHybridCpus() {
        ThreadPlan hybrid = TuningPlanner.planThreads(targetMachine(), SpecTuneConfig.defaults());

        CpuTopology flat = new CpuTopology("AMD Ryzen 9 7950X", 32, 16, 16, 0, true,
                CpuTopology.Source.PROC_CPUINFO);
        MachineProfile flatMachine = new MachineProfile(flat, 64 * GIB, 8 * GIB, 8 * GIB,
                "Generational ZGC", 21, "vm", "Linux", List.of());
        ThreadPlan homogeneous = TuningPlanner.planThreads(flatMachine, SpecTuneConfig.defaults());

        int hybridGap = hybrid.foregroundPriority() - hybrid.workerPriority();
        int flatGap = homogeneous.foregroundPriority() - homogeneous.workerPriority();
        assertTrue(hybridGap > flatGap, "hybrid CPUs need a wider priority gap, got " + hybridGap);
        assertTrue(hybrid.foregroundPriority() <= Thread.MAX_PRIORITY);
        assertTrue(hybrid.backgroundIoPriority() >= Thread.MIN_PRIORITY);
    }

    @Test
    void keepsWorkerPoolsSaneOnSmallMachines() {
        CpuTopology dualCore = new CpuTopology("Old laptop", 4, 2, 2, 0, true, CpuTopology.Source.WMI);
        MachineProfile machine = new MachineProfile(dualCore, 8 * GIB, 2 * GIB, 2 * GIB, "G1", 21,
                "vm", "Windows 10", List.of());
        ThreadPlan threads = TuningPlanner.planThreads(machine, SpecTuneConfig.defaults());
        assertTrue(threads.backgroundThreads() >= 2);
        assertTrue(threads.backgroundThreads() <= 4);
    }

    @Test
    void honoursAnExplicitThreadOverride() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("threads.backgroundOverride", "6");
        assertEquals(6, TuningPlanner.planThreads(targetMachine(), config).backgroundThreads());
    }

    @Test
    void picksAHighRenderDistanceForAHighTierGpuWithPlentyOfRam() {
        VideoPlan video = TuningPlanner.planVideo(targetMachine(), SpecTuneConfig.defaults(), rtx5080());
        assertEquals(24, video.renderDistance());
        assertEquals(12, video.simulationDistance());
        assertEquals(TuningPlanner.UNLIMITED_FPS, video.maxFps());
        assertFalse(video.vsync());
        assertTrue(video.fancyGraphics());
    }

    @Test
    void backsOffHardOnAnIntegratedGpu() {
        GpuInfo igpu = GpuInfo.of("Intel", "Intel(R) Arc(TM) Graphics", "4.6.0");
        VideoPlan video = TuningPlanner.planVideo(targetMachine(), SpecTuneConfig.defaults(), igpu);
        assertEquals(8, video.renderDistance());
        assertTrue(video.simulationDistance() <= 8);
        assertFalse(video.fancyGraphics());
    }

    @Test
    void neverSimulatesFurtherThanItRenders() {
        GpuInfo weak = GpuInfo.of("Intel", "Intel(R) UHD Graphics 620", "4.5.0");
        CpuTopology manyCores = new CpuTopology("Threadripper", 64, 32, 32, 0, true,
                CpuTopology.Source.PROC_CPUINFO);
        MachineProfile machine = new MachineProfile(manyCores, 128 * GIB, 8 * GIB, 8 * GIB,
                "Generational ZGC", 21, "vm", "Linux", List.of());
        VideoPlan video = TuningPlanner.planVideo(machine, SpecTuneConfig.defaults(), weak);
        assertTrue(video.simulationDistance() <= video.renderDistance());
    }

    @Test
    void renderDistanceOverrideWins() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("video.renderDistanceOverride", "32");
        assertEquals(32, TuningPlanner.planVideo(targetMachine(), config, rtx5080()).renderDistance());
    }

    @Test
    void clampsAbsurdOverrides() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("video.renderDistanceOverride", "999");
        assertEquals(32, TuningPlanner.planVideo(targetMachine(), config, rtx5080()).renderDistance());
    }
}
