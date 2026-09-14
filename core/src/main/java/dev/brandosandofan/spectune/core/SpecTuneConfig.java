package dev.brandosandofan.spectune.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * File-backed configuration. Deliberately {@link Properties} rather than JSON so the core module
 * stays dependency-free and the file stays hand-editable.
 *
 * <p>Anything SpecTune infers can be overridden here; an override always wins over detection.
 */
public final class SpecTuneConfig {

    /** How aggressively the video profile is allowed to touch {@code options.txt}. */
    public enum ApplyMode {
        /** Never write video options. */
        OFF,
        /** Write them once, then leave the player's later edits alone. */
        ONCE,
        /** Re-apply on every launch. */
        ALWAYS;

        static ApplyMode parse(String raw, ApplyMode fallback) {
            if (raw == null) return fallback;
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    private final Properties properties = new Properties();

    public static SpecTuneConfig defaults() {
        return new SpecTuneConfig();
    }

    public static SpecTuneConfig load(Path file) {
        SpecTuneConfig config = new SpecTuneConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config.properties.load(reader);
            } catch (IOException e) {
                // Keep defaults; the caller logs.
            }
        }
        return config;
    }

    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties out = new Properties();
        out.putAll(properties);
        // Materialise defaults so the file documents every knob after first run.
        out.putIfAbsent("enabled", String.valueOf(enabled()));
        out.putIfAbsent("threads.autoSize", String.valueOf(autoSizeThreads()));
        out.putIfAbsent("threads.backgroundOverride", String.valueOf(backgroundThreadOverride()));
        out.putIfAbsent("threads.priorityTuning", String.valueOf(priorityTuning()));
        out.putIfAbsent("video.apply", videoApplyMode().name().toLowerCase(Locale.ROOT));
        out.putIfAbsent("video.renderDistanceOverride", String.valueOf(renderDistanceOverride()));
        out.putIfAbsent("gpu.warnOnIntegrated", String.valueOf(warnOnIntegratedGpu()));
        out.putIfAbsent("cpu.performanceCores", String.valueOf(performanceCoreOverride()));
        out.putIfAbsent("cpu.efficiencyCores", String.valueOf(efficiencyCoreOverride()));
        out.putIfAbsent("report.write", String.valueOf(writeReport()));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.store(writer, "SpecTune - 0 means \"work it out yourself\". See docs/TUNING.md.");
        }
    }

    public boolean enabled() {
        return bool("enabled", true);
    }

    public boolean autoSizeThreads() {
        return bool("threads.autoSize", true);
    }

    /** Explicit {@code max.bg.threads} value, or {@code 0} to size it from the CPU. */
    public int backgroundThreadOverride() {
        return integer("threads.backgroundOverride", 0);
    }

    public boolean priorityTuning() {
        return bool("threads.priorityTuning", true);
    }

    public ApplyMode videoApplyMode() {
        return ApplyMode.parse(properties.getProperty("video.apply"), ApplyMode.ONCE);
    }

    /** Explicit render distance, or {@code 0} to derive it from the GPU tier. */
    public int renderDistanceOverride() {
        return integer("video.renderDistanceOverride", 0);
    }

    public boolean warnOnIntegratedGpu() {
        return bool("gpu.warnOnIntegrated", true);
    }

    public int performanceCoreOverride() {
        return integer("cpu.performanceCores", 0);
    }

    public int efficiencyCoreOverride() {
        return integer("cpu.efficiencyCores", 0);
    }

    public boolean writeReport() {
        return bool("report.write", true);
    }

    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    /**
     * Applies {@code cpu.*} overrides to a detected topology. Supplying only one of the two counts
     * is enough: the other is taken from the detected physical core count.
     */
    public CpuTopology applyOverrides(CpuTopology detected) {
        int p = performanceCoreOverride();
        int e = efficiencyCoreOverride();
        if (p <= 0 && e <= 0) return detected;
        if (p <= 0) p = Math.max(1, detected.physicalCores() - e);
        if (e <= 0 && p < detected.physicalCores()) e = detected.physicalCores() - p;
        e = Math.max(0, e);
        int physical = p + e;
        // Only P-cores carry SMT siblings on every hybrid part shipped so far.
        int logical = detected.smt() ? physical + p : physical;
        return new CpuTopology(detected.brand(), logical, physical, p, e, detected.smt(),
                CpuTopology.Source.CONFIG_OVERRIDE);
    }

    private boolean bool(String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) return fallback;
        raw = raw.strip();
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private int integer(String key, int fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
