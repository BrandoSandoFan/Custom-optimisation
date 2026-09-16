package dev.brandosandofan.spectune.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort detection of the OS-level power policy governing CPU clocks.
 *
 * <p>This sits below everything else SpecTune tunes: a laptop on Windows' "Balanced" plan, or a
 * Linux box whose cpufreq governor is "powersave", will not sustain boost clocks no matter how the
 * worker pool or JVM is configured. {@code docs/TUNING.md} has told players to check this by hand
 * since the mod's first version ("Stop Windows from parking the game on efficiency cores"); this is
 * the code half of that advice, surfaced through {@link JvmAdvisor} like every other finding.
 *
 * <p>Both probes are cheap (a native binary with no shell, or a single sysfs read), unlike the
 * PowerShell/WMI CPU probe on Windows, so unlike {@link CpuDetector} this does not need caching.
 */
public final class PowerPlanDetector {

    // Windows' built-in power scheme GUIDs. Stable across Windows versions.
    private static final String GUID_HIGH_PERFORMANCE = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
    private static final String GUID_ULTIMATE_PERFORMANCE = "e9a42b02-d5df-448d-aa00-03f14749eb61";
    private static final String GUID_BALANCED = "381b4222-f694-41f0-9685-ff5bb260df2e";
    private static final String GUID_POWER_SAVER = "a1841308-3541-4fab-bc81-f71556f20b4a";

    private static final Pattern SCHEME_LINE = Pattern.compile("([0-9a-fA-F-]{36})\\s*(?:\\(([^)]*)\\))?");

    private PowerPlanDetector() {}

    public static PowerPlanInfo detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return detectWindows();
            if (os.contains("linux")) return detectLinux();
        } catch (RuntimeException e) {
            // Detection is advisory; never let it break game start-up.
        }
        return PowerPlanInfo.unknown();
    }

    // -------------------------------------------------------------- Windows

    static PowerPlanInfo detectWindows() {
        return parseWindows(run(3, "powercfg", "/getactivescheme"));
    }

    /**
     * Parses {@code powercfg /getactivescheme}, e.g.
     * {@code "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)"}.
     */
    static PowerPlanInfo parseWindows(String out) {
        if (out == null || out.isBlank()) return PowerPlanInfo.unknown();
        Matcher m = SCHEME_LINE.matcher(out);
        if (!m.find()) return PowerPlanInfo.unknown();

        String guid = m.group(1).toLowerCase(Locale.ROOT);
        String name = m.group(2) == null ? "" : m.group(2).trim();

        if (guid.equals(GUID_HIGH_PERFORMANCE) || guid.equals(GUID_ULTIMATE_PERFORMANCE)) {
            return new PowerPlanInfo(PowerPlanInfo.Profile.HIGH_PERFORMANCE, name.isBlank() ? "High performance" : name);
        }
        if (guid.equals(GUID_BALANCED)) {
            return new PowerPlanInfo(PowerPlanInfo.Profile.BALANCED, name.isBlank() ? "Balanced" : name);
        }
        if (guid.equals(GUID_POWER_SAVER)) {
            return new PowerPlanInfo(PowerPlanInfo.Profile.POWER_SAVER, name.isBlank() ? "Power saver" : name);
        }
        // A custom or vendor scheme (Lenovo Vantage, Armoury Crate, MSI Center, ...): those regularly
        // clone one of the built-in schemes under their own GUID, so fall back to matching the name.
        return new PowerPlanInfo(classifyByName(name), name.isBlank() ? "Unrecognised power plan" : name);
    }

    static PowerPlanInfo.Profile classifyByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("ultimate") || n.contains("high performance") || n.contains("best performance")
                || n.contains("performance mode")) {
            return PowerPlanInfo.Profile.HIGH_PERFORMANCE;
        }
        if (n.contains("power saver") || n.contains("battery saver") || n.contains("silent")
                || n.contains("quiet")) {
            return PowerPlanInfo.Profile.POWER_SAVER;
        }
        if (n.contains("balanced")) return PowerPlanInfo.Profile.BALANCED;
        return PowerPlanInfo.Profile.UNKNOWN;
    }

    // ---------------------------------------------------------------- Linux

    static PowerPlanInfo detectLinux() {
        return parseLinuxGovernor(readFile(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
    }

    static PowerPlanInfo parseLinuxGovernor(String governor) {
        if (governor == null || governor.isBlank()) return PowerPlanInfo.unknown();
        String g = governor.strip().toLowerCase(Locale.ROOT);
        return switch (g) {
            case "performance" -> new PowerPlanInfo(PowerPlanInfo.Profile.HIGH_PERFORMANCE, "performance governor");
            case "powersave" -> new PowerPlanInfo(PowerPlanInfo.Profile.POWER_SAVER, "powersave governor");
            case "schedutil", "ondemand", "conservative" ->
                    new PowerPlanInfo(PowerPlanInfo.Profile.BALANCED, g + " governor");
            default -> new PowerPlanInfo(PowerPlanInfo.Profile.UNKNOWN, g + " governor");
        };
    }

    // ---------------------------------------------------------------- Utils

    private static String readFile(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Runs a probe command with a hard timeout; returns {@code null} on any failure. */
    private static String run(int timeoutSeconds, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null;
            return process.exitValue() == 0 ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
