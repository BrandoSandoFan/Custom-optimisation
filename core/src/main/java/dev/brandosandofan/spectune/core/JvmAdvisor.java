package dev.brandosandofan.spectune.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspects the JVM the game is actually running in and reports what is costing frames, plus a
 * generated argument line to fix it.
 *
 * <p>Most of what limits Minecraft on a well-specified machine is decided before a single mod class
 * loads: heap size, collector choice and whether the launcher handed the process the discrete GPU.
 * A mod cannot change those at runtime, so it reports them instead.
 */
public final class JvmAdvisor {

    private static final long GIB = 1024L * 1024L * 1024L;

    /**
     * Above this heap size HotSpot drops compressed object pointers, so every reference widens from
     * 4 to 8 bytes and the heap needs more bandwidth to do the same work.
     */
    static final long COMPRESSED_OOPS_LIMIT = 32L * GIB;

    private JvmAdvisor() {}

    public static List<Advice> review(MachineProfile profile, GpuInfo gpu) {
        List<Advice> advice = new ArrayList<>();
        reviewGpu(gpu, advice);
        reviewHeap(profile, advice);
        reviewCollector(profile, advice);
        reviewFlags(profile, advice);
        reviewCpu(profile, advice);
        return advice;
    }

    private static void reviewGpu(GpuInfo gpu, List<Advice> advice) {
        if (gpu == null) return;
        if (gpu.integrated()) {
            advice.add(Advice.critical(
                    "Running on the integrated GPU",
                    "OpenGL reports \"" + gpu.renderer() + "\", which is not a discrete card. "
                            + "Every other setting is irrelevant until this is fixed.",
                    "In the NVIDIA app, add javaw.exe (the one your launcher uses) under "
                            + "Settings > Graphics and force \"High-performance NVIDIA processor\"; "
                            + "or set Windows Graphics settings for that executable to High performance."));
        } else if (gpu.tier() == GpuInfo.Tier.UNKNOWN) {
            advice.add(Advice.info("Unrecognised GPU",
                    "OpenGL reports \"" + gpu.renderer() + "\". Video settings fell back to conservative values; "
                            + "set video.renderDistanceOverride in spectune.properties if that is too cautious."));
        }
    }

    private static void reviewHeap(MachineProfile profile, List<Advice> advice) {
        long max = profile.maxHeapBytes();
        long installed = profile.installedMemoryBytes();
        if (max <= 0) return;

        if (max >= COMPRESSED_OOPS_LIMIT) {
            advice.add(Advice.warn(
                    "Heap is above the compressed-oops limit",
                    String.format("-Xmx is %.0f GiB. Past 32 GiB the JVM stops compressing object pointers, "
                            + "which makes the heap bigger and slower for the same workload.", max / (double) GIB),
                    "Drop -Xmx to " + recommendedHeapGiB(installed) + "G."));
        } else if (max < 4 * GIB && installed >= 16 * GIB) {
            advice.add(Advice.warn(
                    "Heap is small for this machine",
                    String.format("-Xmx is %.1f GiB on a machine with %.0f GiB installed.",
                            max / (double) GIB, installed / (double) GIB),
                    "Raise -Xmx to " + recommendedHeapGiB(installed) + "G."));
        }

        long init = profile.initialHeapBytes();
        if (init > 0 && max > 0 && Math.abs(init - max) > GIB / 2) {
            advice.add(Advice.warn(
                    "-Xms and -Xmx differ",
                    "The heap will be grown and shrunk during play, and each resize is a stall.",
                    "Set -Xms equal to -Xmx."));
        }

        if (installed >= 48L * GIB && max > 0 && max <= 16 * GIB) {
            advice.add(Advice.info(
                    "Spare RAM is not wasted",
                    String.format("%.0f GiB installed with a %.0f GiB heap is correct: the rest becomes OS page "
                                    + "cache for region files, which is what actually removes chunk-load hitches.",
                            installed / (double) GIB, max / (double) GIB)));
        }
    }

    private static void reviewCollector(MachineProfile profile, List<Advice> advice) {
        String gc = profile.collector();
        int java = profile.javaMajorVersion();
        boolean manyCores = profile.cpu().logicalCores() >= 12;

        switch (gc) {
            case "Serial", "Parallel", "CMS" -> advice.add(Advice.warn(
                    "Throughput collector in use",
                    gc + " stops the world for its whole collection, which lands as a visible frame drop.",
                    "Switch to " + (java >= 21 ? "Generational ZGC" : "G1") + " - see docs/JVM_ARGS.md."));
            case "G1" -> {
                if (java >= 21 && manyCores) {
                    advice.add(Advice.info(
                            "G1 is fine, ZGC is better here",
                            "With " + profile.cpu().logicalCores() + " hardware threads there is ample room to run "
                                    + "Generational ZGC's concurrent collection, whose pauses stay under a "
                                    + "millisecond instead of G1's tens of milliseconds."));
                }
            }
            case "ZGC" -> advice.add(Advice.warn(
                    "ZGC is running non-generational",
                    "Minecraft allocates overwhelmingly short-lived objects, which is exactly what the "
                            + "generational mode is for.",
                    java >= 24
                            ? "Remove -XX:-ZGenerational; generational mode is the default on Java " + java + "."
                            : "Add -XX:+ZGenerational."));
            default -> { /* Generational ZGC or Shenandoah: nothing to say. */ }
        }

        if (java > 0 && java < 21) {
            advice.add(Advice.warn(
                    "Old Java runtime",
                    "Running on Java " + java + ". Modern Minecraft targets 21, and the collectors worth using "
                            + "are only there from 21 onward.",
                    "Point the launcher's installation at a Java 21+ runtime."));
        }
    }

    private static void reviewFlags(MachineProfile profile, List<Advice> advice) {
        if (profile.installedMemoryBytes() >= 32L * GIB && !profile.hasFlag("-XX:+AlwaysPreTouch")) {
            advice.add(Advice.info(
                    "AlwaysPreTouch is off",
                    "With this much RAM, committing the heap up front trades a few seconds of start-up for the "
                            + "removal of page-fault hitches during the first minutes of play. Add "
                            + "-XX:+AlwaysPreTouch."));
        }
        if (profile.hasFlag("-XX:+UseSerialGC")) {
            advice.add(Advice.warn("-XX:+UseSerialGC is set explicitly",
                    "Single-threaded collection on a many-core machine.", "Remove the flag."));
        }
    }

    private static void reviewCpu(MachineProfile profile, List<Advice> advice) {
        CpuTopology cpu = profile.cpu();
        if (cpu.source() == CpuTopology.Source.FALLBACK) {
            advice.add(Advice.info(
                    "CPU topology not detected",
                    "Only the logical core count was available, so thread sizing used conservative defaults. "
                            + "Set cpu.performanceCores and cpu.efficiencyCores in spectune.properties."));
        } else if (cpu.hybrid() && !cpu.source().splitIsExact()) {
            advice.add(Advice.info(
                    "Hybrid core split inferred",
                    "Detected " + cpu.performanceCores() + "P + " + cpu.efficiencyCores() + "E from core counts. "
                            + "If that is wrong, set cpu.performanceCores/cpu.efficiencyCores in "
                            + "spectune.properties."));
        }
    }

    /** Heap size worth giving Minecraft on a machine with the given installed RAM. */
    public static int recommendedHeapGiB(long installedBytes) {
        if (installedBytes <= 0) return 4;
        double gib = installedBytes / (double) GIB;
        if (gib >= 48) return 12;
        if (gib >= 24) return 8;
        if (gib >= 12) return 6;
        if (gib >= 8) return 4;
        return 3;
    }

    /**
     * Builds the JVM argument line for this machine. The heap is sized well under the
     * compressed-oops limit on purpose: a bigger heap does not make Minecraft faster, it makes each
     * collection cover more ground.
     */
    public static List<String> recommendedArguments(MachineProfile profile, TuningPlan plan) {
        int heap = recommendedHeapGiB(profile.installedMemoryBytes());
        int java = profile.javaMajorVersion();
        List<String> args = new ArrayList<>();
        args.add("-Xms" + heap + "G");
        args.add("-Xmx" + heap + "G");
        args.add("-XX:+UnlockExperimentalVMOptions");
        args.add("-XX:+UseZGC");
        if (java > 0 && java < 24) {
            // Generational mode is opt-in on 21-23 and the default (flag removed) from 24.
            args.add("-XX:+ZGenerational");
        }
        args.add("-XX:SoftMaxHeapSize=" + Math.max(2, heap - 2) + "G");
        args.add("-XX:+AlwaysPreTouch");
        args.add("-XX:+DisableExplicitGC");
        args.add("-XX:+UseStringDeduplication");
        args.add("-XX:+PerfDisableSharedMem");
        args.add("-Dmax.bg.threads=" + plan.threads().backgroundThreads());
        args.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism="
                + Math.max(2, profile.cpu().foregroundCores() - 1));
        return args;
    }
}
