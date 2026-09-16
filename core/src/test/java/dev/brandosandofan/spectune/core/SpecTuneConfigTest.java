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
        assertTrue(config.replaceVideoSettingsScreen());
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
    void cachesTheDetectedTopologySoStartUpDoesNotReprobe() {
        int liveCores = Runtime.getRuntime().availableProcessors();
        CpuTopology detected = new CpuTopology("Intel Core Ultra 9 275HX", liveCores, liveCores,
                Math.max(1, liveCores / 3), liveCores - Math.max(1, liveCores / 3), false,
                CpuTopology.Source.WMI);

        SpecTuneConfig config = SpecTuneConfig.defaults();
        assertTrue(config.cachedTopology().isEmpty(), "nothing cached before the first probe");
        config.cacheTopology(detected);
        assertEquals(detected, config.cachedTopology().orElseThrow());
    }

    @Test
    void discardsACacheFromADifferentMachine() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.cacheTopology(new CpuTopology("Some other CPU",
                Runtime.getRuntime().availableProcessors() + 8,
                Runtime.getRuntime().availableProcessors() + 8, 8, 16, false, CpuTopology.Source.WMI));
        assertTrue(config.cachedTopology().isEmpty(), "a core-count mismatch must force a re-probe");
    }

    @Test
    void discardsAHandEditedCache() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("cpu.cache.brand", "Broken");
        config.set("cpu.cache.logical", Integer.toString(Runtime.getRuntime().availableProcessors()));
        config.set("cpu.cache.physical", "not a number");
        config.set("cpu.cache.performance", "4");
        config.set("cpu.cache.efficiency", "0");
        assertTrue(config.cachedTopology().isEmpty());
    }

    @Test
    void resolvingSurvivesARoundTripThroughTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spectune.properties");
        SpecTuneConfig first = SpecTuneConfig.load(file);
        CpuTopology resolved = first.resolveTopology();
        first.save(file);

        SpecTuneConfig second = SpecTuneConfig.load(file);
        assertEquals(resolved, second.cachedTopology().orElseThrow(),
                "the second launch must reuse the first launch's detection");
    }

    @Test
    void anOverrideStillWinsOverTheCache(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spectune.properties");
        SpecTuneConfig config = SpecTuneConfig.load(file);
        config.resolveTopology();
        config.set("cpu.performanceCores", "8");
        config.set("cpu.efficiencyCores", "16");

        CpuTopology resolved = config.resolveTopology();
        assertEquals(8, resolved.performanceCores());
        assertEquals(16, resolved.efficiencyCores());
        assertEquals(CpuTopology.Source.CONFIG_OVERRIDE, resolved.source());
    }

    @Test
    void resetTunablesClearsTheOverridesTheSettingsScreenExposes() {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        config.set("threads.backgroundOverride", "16");
        config.set("threads.priorityTuning", "false");
        config.set("gpu.warnOnIntegrated", "false");
        config.set("video.apply", "always");
        config.set("cpu.performanceCores", "8"); // must survive the reset

        config.resetTunables();

        assertEquals(0, config.backgroundThreadOverride());
        assertTrue(config.priorityTuning());
        assertTrue(config.warnOnIntegratedGpu());
        assertEquals(SpecTuneConfig.ApplyMode.ONCE, config.videoApplyMode());
        assertEquals(8, config.performanceCoreOverride(), "CPU topology overrides are a separate concern");
    }

    @Test
    void noOverrideLeavesDetectionUntouched() {
        CpuTopology detected = new CpuTopology("AMD Ryzen 7 7800X3D", 16, 8, 8, 0, true,
                CpuTopology.Source.PROC_CPUINFO);
        assertEquals(detected, SpecTuneConfig.defaults().applyOverrides(detected));
    }
}
