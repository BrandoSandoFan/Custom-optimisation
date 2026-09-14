package dev.brandosandofan.spectune.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A snapshot of the machine and the JVM the game is currently running in: CPU topology, installed
 * RAM, heap sizing, collector in use and the command line that produced all of it.
 */
public record MachineProfile(
        CpuTopology cpu,
        long installedMemoryBytes,
        long maxHeapBytes,
        long initialHeapBytes,
        String collector,
        int javaMajorVersion,
        String javaVendorVersion,
        String osName,
        List<String> jvmArguments) {

    private static final long GIB = 1024L * 1024L * 1024L;

    public MachineProfile {
        jvmArguments = List.copyOf(jvmArguments == null ? List.of() : jvmArguments);
    }

    public static MachineProfile capture() {
        return capture(CpuDetector.detect());
    }

    public static MachineProfile capture(CpuTopology cpu) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return new MachineProfile(
                cpu,
                installedMemory(),
                memory.getHeapMemoryUsage().getMax(),
                memory.getHeapMemoryUsage().getInit(),
                detectCollector(),
                javaMajor(System.getProperty("java.version", "")),
                System.getProperty("java.vm.name", "") + " " + System.getProperty("java.version", ""),
                System.getProperty("os.name", "unknown"),
                safeArguments(runtime));
    }

    private static List<String> safeArguments(RuntimeMXBean runtime) {
        try {
            return runtime.getInputArguments();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Installed physical RAM, or {@code -1} if the platform MXBean is unavailable. */
    private static long installedMemory() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError e) {
            // Non-HotSpot runtime; fall through.
        }
        return -1L;
    }

    /**
     * Names the collector in play. HotSpot exposes one MXBean per generation, so the pair of names
     * is folded back into a single collector name.
     */
    static String detectCollector() {
        List<String> names = new ArrayList<>();
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                names.add(gc.getName());
            }
        } catch (RuntimeException e) {
            return "Unknown";
        }
        return collectorFromBeanNames(names);
    }

    static String collectorFromBeanNames(List<String> names) {
        String joined = String.join(",", names).toLowerCase(Locale.ROOT);
        if (joined.contains("zgc") || joined.contains("z ")) {
            return joined.contains("minor") || joined.contains("young") ? "Generational ZGC" : "ZGC";
        }
        if (joined.contains("shenandoah")) return "Shenandoah";
        if (joined.contains("g1")) return "G1";
        if (joined.contains("ps ") || joined.contains("parallel")) return "Parallel";
        if (joined.contains("concurrentmarksweep") || joined.contains("concurrent mark")) return "CMS";
        if (joined.contains("copy") || joined.contains("marksweepcompact")) return "Serial";
        return names.isEmpty() ? "Unknown" : names.get(0);
    }

    /** Extracts the feature version from a {@code java.version} string ("21.0.4" → 21). */
    static int javaMajor(String version) {
        if (version == null || version.isBlank()) return -1;
        String v = version.strip();
        if (v.startsWith("1.")) v = v.substring(2); // 1.8.0_402 → 8
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        try {
            return end == 0 ? -1 : Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public double installedMemoryGiB() {
        return installedMemoryBytes <= 0 ? -1 : installedMemoryBytes / (double) GIB;
    }

    public double maxHeapGiB() {
        return maxHeapBytes <= 0 ? -1 : maxHeapBytes / (double) GIB;
    }

    public boolean windows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** True if any JVM argument starts with the given flag prefix. */
    public boolean hasFlag(String prefix) {
        for (String arg : jvmArguments) {
            if (arg.startsWith(prefix)) return true;
        }
        return false;
    }
}
