package dev.brandosandofan.spectune.core;

import dev.brandosandofan.spectune.core.TuningPlan.ThreadPlan;
import dev.brandosandofan.spectune.core.TuningPlan.VideoPlan;

/**
 * Turns a {@link MachineProfile} into a {@link TuningPlan}. Pure functions, no side effects, so the
 * decisions can be tested against synthetic machines.
 */
public final class TuningPlanner {

    /** Vanilla's own ceiling on the shared worker pool. Everything above this is what we unlock. */
    public static final int VANILLA_BACKGROUND_THREAD_CAP = 7;

    /**
     * Beyond roughly this many workers, chunk generation stops scaling and simply adds contention
     * with the render thread and the GC.
     */
    static final int BACKGROUND_THREAD_CEILING = 16;

    /** Minecraft treats this value as "unlimited" in the max framerate slider. */
    public static final int UNLIMITED_FPS = 260;

    private static final long GIB = 1024L * 1024L * 1024L;

    private TuningPlanner() {}

    public static TuningPlan plan(MachineProfile profile, SpecTuneConfig config, GpuInfo gpu) {
        return new TuningPlan(planThreads(profile, config), planVideo(profile, config, gpu));
    }

    // -------------------------------------------------------------- Threads

    public static ThreadPlan planThreads(MachineProfile profile, SpecTuneConfig config) {
        CpuTopology cpu = profile.cpu();
        int background = config.backgroundThreadOverride() > 0
                ? config.backgroundThreadOverride()
                : backgroundThreadsFor(cpu);

        boolean tuning = config.priorityTuning();
        // On hybrid parts the gap between foreground and background priority is what the OS
        // scheduler uses to decide which threads deserve a P-core, so widen it there.
        int foreground = Thread.NORM_PRIORITY + (cpu.hybrid() ? 3 : 2);
        int worker = Thread.NORM_PRIORITY - (cpu.hybrid() ? 2 : 1);
        int io = Thread.MIN_PRIORITY + 1;

        return new ThreadPlan(
                background,
                chunkBuilderThreadsFor(cpu),
                clampPriority(foreground),
                clampPriority(worker),
                clampPriority(io),
                tuning);
    }

    /**
     * Sizes the shared worker pool. Reserves the render thread, the client thread and the
     * integrated server thread, then keeps a little headroom so workers never starve them.
     */
    static int backgroundThreadsFor(CpuTopology cpu) {
        int usable = cpu.physicalCores() - 3;
        if (cpu.hybrid()) {
            // Chunk work is throughput-bound and happily runs on E-cores; P-cores stay for the
            // latency-sensitive threads, minus one P-core kept free for the render thread's spikes.
            usable = cpu.efficiencyCores() + Math.max(1, cpu.performanceCores() - 3);
        }
        return Math.max(2, Math.min(BACKGROUND_THREAD_CEILING, usable));
    }

    /**
     * Suggested Sodium chunk-builder thread count. Sodium's builders are pure throughput work, but
     * oversubscribing them shows up directly as frame-time variance, so this stays below the worker
     * pool size.
     */
    static int chunkBuilderThreadsFor(CpuTopology cpu) {
        int base = cpu.hybrid() ? cpu.performanceCores() + cpu.efficiencyCores() / 2 : cpu.physicalCores() / 2;
        return Math.max(2, Math.min(12, base));
    }

    static int clampPriority(int priority) {
        return Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority));
    }

    // ---------------------------------------------------------------- Video

    public static VideoPlan planVideo(MachineProfile profile, SpecTuneConfig config, GpuInfo gpu) {
        GpuInfo.Tier tier = gpu == null ? GpuInfo.Tier.UNKNOWN : gpu.tier();
        long ram = profile.installedMemoryBytes();
        int cores = profile.cpu().physicalCores();

        int renderDistance = switch (tier) {
            case HIGH -> ram >= 24 * GIB ? 24 : 16;
            case MID -> 16;
            case LOW -> 12;
            case INTEGRATED -> 8;
            case UNKNOWN -> 12;
        };
        if (config.renderDistanceOverride() > 0) {
            renderDistance = Math.max(2, Math.min(32, config.renderDistanceOverride()));
        }

        // Simulation distance is integrated-server work, so it tracks core count, not the GPU, and
        // it is the single most expensive video setting for tick time.
        int simulation = cores >= 12 ? 12 : cores >= 8 ? 10 : 8;
        simulation = Math.min(simulation, renderDistance);

        boolean strong = tier == GpuInfo.Tier.HIGH || tier == GpuInfo.Tier.MID;
        return new VideoPlan(
                renderDistance,
                simulation,
                tier == GpuInfo.Tier.HIGH ? UNLIMITED_FPS : 120,
                false,
                strong,
                strong ? 2 : 1,
                strong ? 4 : 2,
                strong,
                tier == GpuInfo.Tier.HIGH ? VideoPlan.CloudMode.FANCY : VideoPlan.CloudMode.FAST,
                strong ? VideoPlan.ParticleMode.ALL : VideoPlan.ParticleMode.DECREASED);
    }
}
