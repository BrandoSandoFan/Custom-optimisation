package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.CpuDetector;
import dev.brandosandofan.spectune.core.CpuTopology;
import dev.brandosandofan.spectune.core.GpuInfo;
import dev.brandosandofan.spectune.core.MachineProfile;
import dev.brandosandofan.spectune.core.SpecTuneConfig;
import dev.brandosandofan.spectune.core.TuningPlan;
import dev.brandosandofan.spectune.core.TuningPlanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide state: the config, the detected machine and the plan derived from them. */
public final class SpecTune {

    public static final String MOD_ID = "spectune";
    public static final Logger LOGGER = LoggerFactory.getLogger("SpecTune");

    private static SpecTuneConfig config;
    private static MachineProfile profile;
    private static TuningPlan plan;
    private static GpuInfo gpu;

    private SpecTune() {}

    /**
     * Loads config and profiles the machine. Called from the pre-launch entrypoint, before any
     * Minecraft class is initialised, because the worker pool size can only be influenced then.
     */
    public static synchronized void bootstrap() {
        if (config != null) return;
        config = SpecTuneConfig.load(configFile());
        CpuTopology cpu = config.applyOverrides(CpuDetector.detect());
        profile = MachineProfile.capture(cpu);
        plan = TuningPlanner.plan(profile, config, null);
        try {
            config.save(configFile());
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", configFile(), e.toString());
        }
    }

    /**
     * Re-plans once the GPU is known. The video half of the plan depends on the renderer string,
     * which is only readable after a GL context exists.
     */
    public static synchronized void attachGpu(GpuInfo detected) {
        gpu = detected;
        plan = TuningPlanner.plan(profile, config, detected);
    }

    public static synchronized SpecTuneConfig config() {
        return config;
    }

    public static synchronized MachineProfile profile() {
        return profile;
    }

    public static synchronized TuningPlan plan() {
        return plan;
    }

    public static synchronized GpuInfo gpu() {
        return gpu;
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path configFile() {
        return configDirectory().resolve(MOD_ID + ".properties");
    }

    static void writeReport(String report) {
        Path file = configDirectory().resolve(MOD_ID + "-report.txt");
        try {
            Files.writeString(file, report, StandardCharsets.UTF_8);
            LOGGER.info("Wrote {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", file, e.toString());
        }
    }
}
