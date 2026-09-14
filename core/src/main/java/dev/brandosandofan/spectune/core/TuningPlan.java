package dev.brandosandofan.spectune.core;

/**
 * The concrete numbers SpecTune will apply: how many worker threads to allow, what priority each
 * class of thread runs at, and the video settings the detected GPU can carry.
 */
public record TuningPlan(ThreadPlan threads, VideoPlan video) {

    /**
     * Thread scheduling policy.
     *
     * <p>{@code backgroundThreads} is fed to Minecraft's {@code max.bg.threads} property, which caps
     * the shared worker pool used for chunk generation, light updates and region I/O. Vanilla caps
     * it at 7 regardless of core count.
     *
     * <p>The priorities exist for hybrid CPUs. Java cannot set core affinity portably, but Windows
     * and Linux both feed thread priority into their scheduler's placement decision, so demoting
     * background pools is the portable way to keep the render and client threads on performance
     * cores instead of being migrated onto E-cores mid-frame.
     */
    public record ThreadPlan(
            int backgroundThreads,
            int recommendedChunkBuilderThreads,
            int foregroundPriority,
            int workerPriority,
            int backgroundIoPriority,
            boolean priorityTuningEnabled) {}

    /** Video settings, all in vanilla's own units. */
    public record VideoPlan(
            int renderDistance,
            int simulationDistance,
            int maxFps,
            boolean vsync,
            boolean fancyGraphics,
            int biomeBlendRadius,
            int mipmapLevels,
            boolean entityShadows,
            CloudMode clouds,
            ParticleMode particles) {

        public enum CloudMode { OFF, FAST, FANCY }

        public enum ParticleMode { ALL, DECREASED, MINIMAL }
    }
}
