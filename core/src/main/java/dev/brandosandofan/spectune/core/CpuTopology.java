package dev.brandosandofan.spectune.core;

/**
 * Describes the CPU the game is running on, with the hybrid (performance / efficiency core)
 * split broken out because that split is what drives every scheduling decision SpecTune makes.
 *
 * <p>On a homogeneous CPU {@code performanceCores == physicalCores} and {@code efficiencyCores == 0}.
 */
public record CpuTopology(
        String brand,
        int logicalCores,
        int physicalCores,
        int performanceCores,
        int efficiencyCores,
        boolean smt,
        Source source) {

    /** Where the numbers came from, so the report can say how much to trust them. */
    public enum Source {
        /** Linux {@code /sys/devices/cpu_core/cpus} + {@code cpu_atom/cpus}: exact. */
        SYSFS,
        /** Linux {@code /proc/cpuinfo}: exact core counts, hybrid split inferred. */
        PROC_CPUINFO,
        /** Windows WMI/CIM: exact core counts, hybrid split inferred. */
        WMI,
        /** macOS {@code sysctl hw.perflevelN.*}: exact. */
        SYSCTL,
        /** Nothing but {@link Runtime#availableProcessors()} was available. */
        FALLBACK,
        /** Supplied by the user in {@code config/spectune.properties}. */
        CONFIG_OVERRIDE;

        public boolean splitIsExact() {
            return this == SYSFS || this == SYSCTL || this == CONFIG_OVERRIDE;
        }
    }

    public CpuTopology {
        if (logicalCores < 1) throw new IllegalArgumentException("logicalCores < 1");
        if (physicalCores < 1) throw new IllegalArgumentException("physicalCores < 1");
        if (performanceCores < 1) throw new IllegalArgumentException("performanceCores < 1");
        if (efficiencyCores < 0) throw new IllegalArgumentException("efficiencyCores < 0");
        brand = brand == null || brand.isBlank() ? "Unknown CPU" : brand.trim();
    }

    public boolean hybrid() {
        return efficiencyCores > 0;
    }

    /**
     * Cores worth putting latency-sensitive work on. On a hybrid part that is the P-core count;
     * elsewhere it is simply the physical core count.
     */
    public int foregroundCores() {
        return hybrid() ? performanceCores : physicalCores;
    }

    public static CpuTopology fallback() {
        int logical = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new CpuTopology("Unknown CPU", logical, logical, logical, 0, false, Source.FALLBACK);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(brand);
        sb.append(" - ").append(physicalCores).append('C');
        if (smt) sb.append('/').append(logicalCores).append('T');
        if (hybrid()) sb.append(" (").append(performanceCores).append("P + ").append(efficiencyCores).append("E)");
        sb.append(" [").append(source).append(source.splitIsExact() ? "" : ", split inferred").append(']');
        return sb.toString();
    }
}
