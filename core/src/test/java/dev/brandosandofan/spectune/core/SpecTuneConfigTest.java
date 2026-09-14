package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecTuneConfigTest {

    @Test
    void defaultsAreTheSafeOnes() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        assertTrue(config.enabled());
        assertTrue(config.autoSizeThreads());
        assertTrue(config.priorityTuning());
        assertEquals(SpecTuneConfig.ApplyMode.ONCE, config.videoApplyMode(),
                "video settings must not be rewritten behind the player's back on every launch");
        assertEquals(0, config.backgroundThreadOverride());
    }

    @Test
    void roundTripsThroughAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spectune.properties");
        SpecTuneConfig written = SpecTuneConfig.defaults();
        written.set("threads.backgroundOverride", "11");
        written.set("video.apply", "always");
        written.save(file);

        SpecTuneConfig read = SpecTuneConfig.load(file);
        assertEquals(11, read.backgroundThreadOverride());
        assertEquals(SpecTuneConfig.ApplyMode.ALWAYS, read.videoApplyMode());
        assertTrue(Files.readString(file).contains("gpu.warnOnIntegrated"),
                "saving must materialise every knob so the file documents itself");
    }

    @Test
    void missingFileYieldsDefaults(@TempDir Path dir) {
        SpecTuneConfig config = SpecTuneConfig.load(dir.resolve("absent.properties"));
        assertTrue(config.enabled());
    }

    @Test
    void malformedValuesFallBackInsteadOfThrowing() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("threads.backgroundOverride", "lots");
        config.set("enabled", "yes please");
        config.set("video.apply", "sometimes");
        assertEquals(0, config.backgroundThreadOverride());
        assertTrue(config.enabled());
        assertEquals(SpecTuneConfig.ApplyMode.ONCE, config.videoApplyMode());
    }

    @Test
    void coreOverrideReplacesInference() {
        CpuTopology detected = new CpuTopology("Intel Core Ultra 9 285HX", 24, 24, 8, 16, false,
                CpuTopology.Source.WMI);
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("cpu.performanceCores", "6");
        config.set("cpu.efficiencyCores", "8");

        CpuTopology overridden = config.applyOverrides(detected);
        assertEquals(6, overridden.performanceCores());
        assertEquals(8, overridden.efficiencyCores());
        assertEquals(14, overridden.physicalCores());
        assertEquals(CpuTopology.Source.CONFIG_OVERRIDE, overridden.source());
        assertTrue(overridden.source().splitIsExact());
    }

    @Test
    void halfAnOverrideIsCompletedFromTheDetectedCoreCount() {
        CpuTopology detected = new CpuTopology("Intel Core Ultra 9 285HX", 24, 24, 8, 16, false,
                CpuTopology.Source.WMI);
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("cpu.performanceCores", "8");

        CpuTopology overridden = config.applyOverrides(detected);
        assertEquals(8, overridden.performanceCores());
        assertEquals(16, overridden.efficiencyCores());
    }

    @Test
    void noOverrideLeavesDetectionUntouched() {
        CpuTopology detected = new CpuTopology("AMD Ryzen 7 7800X3D", 16, 8, 8, 0, true,
                CpuTopology.Source.PROC_CPUINFO);
        assertEquals(detected, SpecTuneConfig.defaults().applyOverrides(detected));
    }
}
