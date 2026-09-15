package dev.brandosandofan.spectune.core;

/**
 * What the OS's power-management policy is currently doing to CPU clocks: Windows' active power
 * scheme, or the Linux cpufreq governor. Neither the thread pool sizing nor the JVM flags matter if
 * the OS itself is capping clock speed underneath them.
 */
public record PowerPlanInfo(Profile profile, String label) {

    public enum Profile {
        /** Windows "High performance"/"Ultimate performance", or the Linux "performance" governor. */
        HIGH_PERFORMANCE,
        /** Windows "Balanced", or "schedutil"/"ondemand"/"conservative": ramps clocks under load spikes. */
        BALANCED,
        /** Windows "Power saver", or the "powersave" governor: clocks are capped low regardless of load. */
        POWER_SAVER,
        /** Not probed, or the platform has no equivalent single-setting concept (macOS). */
        UNKNOWN
    }

    public PowerPlanInfo {
        label = label == null || label.isBlank() ? "unknown" : label.strip();
    }

    public static PowerPlanInfo unknown() {
        return new PowerPlanInfo(Profile.UNKNOWN, "not detected");
    }

    /** True if the OS is actively holding clocks below what the hardware can sustain. */
    public boolean throttling() {
        return profile == Profile.BALANCED || profile == Profile.POWER_SAVER;
    }

    public String describe() {
        return label + " [" + profile + "]";
    }
}
