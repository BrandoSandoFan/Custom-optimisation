package dev.brandosandofan.spectune.core;

import java.util.List;

/** Renders the detected machine, the chosen plan and the findings as plain text. */
public final class TuningReport {

    private TuningReport() {}

    public static String render(MachineProfile profile, TuningPlan plan, GpuInfo gpu, List<Advice> advice) {
        StringBuilder sb = new StringBuilder();
        sb.append("SpecTune report\n");
        sb.append("===============\n\n");

        sb.append("Machine\n");
        sb.append("  CPU          ").append(profile.cpu().describe()).append('\n');
        sb.append("  RAM          ").append(format(profile.installedMemoryGiB())).append(" GiB installed\n");
        sb.append("  GPU          ").append(gpu == null ? "not probed yet" : gpu.describe()).append('\n');
        sb.append("  Java         ").append(profile.javaVendorVersion()).append('\n');
        sb.append("  Collector    ").append(profile.collector()).append('\n');
        sb.append("  Heap         ").append(format(profile.maxHeapGiB())).append(" GiB max\n\n");

        TuningPlan.ThreadPlan threads = plan.threads();
        sb.append("Threading\n");
        sb.append("  Worker pool  ").append(threads.backgroundThreads())
                .append(" threads (vanilla caps at ").append(TuningPlanner.VANILLA_BACKGROUND_THREAD_CAP)
                .append(")\n");
        sb.append("  Priorities   foreground ").append(threads.foregroundPriority())
                .append(", worker ").append(threads.workerPriority())
                .append(", I/O ").append(threads.backgroundIoPriority())
                .append(threads.priorityTuningEnabled() ? "\n" : " (disabled)\n");
        sb.append("  Sodium       ").append(threads.recommendedChunkBuilderThreads())
                .append(" chunk-builder threads suggested\n\n");

        TuningPlan.VideoPlan video = plan.video();
        sb.append("Video plan\n");
        sb.append("  Render/sim   ").append(video.renderDistance()).append(" / ")
                .append(video.simulationDistance()).append(" chunks\n");
        sb.append("  Max FPS      ")
                .append(video.maxFps() >= TuningPlanner.UNLIMITED_FPS ? "unlimited" : video.maxFps())
                .append(", vsync ").append(video.vsync() ? "on" : "off").append('\n');
        sb.append("  Detail       ").append(video.fancyGraphics() ? "fancy" : "fast")
                .append(", mipmap ").append(video.mipmapLevels())
                .append(", biome blend ").append(video.biomeBlendRadius())
                .append(", clouds ").append(video.clouds())
                .append(", particles ").append(video.particles()).append('\n');

        if (!advice.isEmpty()) {
            sb.append("\nFindings\n");
            for (Advice item : advice) {
                sb.append("  ").append(item.format()).append('\n');
            }
        }

        sb.append("\nRecommended JVM arguments\n  ");
        sb.append(String.join(" ", JvmAdvisor.recommendedArguments(profile, plan))).append('\n');
        return sb.toString();
    }

    private static String format(double value) {
        return value < 0 ? "unknown" : String.format("%.1f", value);
    }
}
