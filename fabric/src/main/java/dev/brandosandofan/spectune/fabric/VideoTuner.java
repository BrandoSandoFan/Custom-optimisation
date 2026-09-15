package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.SpecTuneConfig;
import dev.brandosandofan.spectune.core.TuningPlan;
import java.io.IOException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;

/**
 * Writes the planned video settings into {@code options.txt}.
 *
 * <p>Deliberately conservative about when it runs: by default it applies once and then records that
 * it has, so a player who later turns something down does not find it turned back up on the next
 * launch. Set {@code video.apply=always} to re-apply every launch, or {@code off} to never touch
 * the options.
 */
public final class VideoTuner {

    /** Bumped when the planner's output changes shape enough to be worth re-applying. */
    private static final int PROFILE_GENERATION = 1;

    private static final String MARKER_KEY = "video.appliedGeneration";

    private VideoTuner() {}

    /** Returns true if the options were changed. */
    public static boolean apply(MinecraftClient client, TuningPlan.VideoPlan video, SpecTuneConfig config) {
        SpecTuneConfig.ApplyMode mode = config.videoApplyMode();
        if (mode == SpecTuneConfig.ApplyMode.OFF) return false;
        if (mode == SpecTuneConfig.ApplyMode.ONCE && alreadyApplied(config)) {
            SpecTune.LOGGER.info("Video profile already applied once; leaving options.txt alone.");
            return false;
        }

        GameOptions options = client.options;
        options.getViewDistance().setValue(video.renderDistance());
        options.getSimulationDistance().setValue(video.simulationDistance());
        options.getMaxFps().setValue(video.maxFps());
        options.getEnableVsync().setValue(video.vsync());
        options.getGraphicsMode().setValue(video.fancyGraphics() ? GraphicsMode.FANCY : GraphicsMode.FAST);
        options.getBiomeBlendRadius().setValue(video.biomeBlendRadius());
        options.getEntityShadows().setValue(video.entityShadows());
        options.getCloudRenderMode().setValue(cloudMode(video.clouds()));
        options.getParticles().setValue(particles(video.particles()));
        // Mipmap changes only take effect on the next resource reload; setting it here means the
        // player gets it without a forced reload at start-up.
        options.getMipmapLevels().setValue(video.mipmapLevels());
        options.write();

        config.set(MARKER_KEY, Integer.toString(PROFILE_GENERATION));
        try {
            config.save(SpecTune.configFile());
        } catch (IOException e) {
            SpecTune.LOGGER.warn("Applied the video profile but could not record it: {}", e.toString());
        }

        SpecTune.LOGGER.info("Applied video profile: render {} / sim {} chunks, max FPS {}, vsync {}.",
                video.renderDistance(), video.simulationDistance(), video.maxFps(),
                video.vsync() ? "on" : "off");
        return true;
    }

    /** Clears the "already applied" marker so the next {@link #apply} call re-applies under {@code ONCE}. */
    public static void clearAppliedMarker(SpecTuneConfig config) {
        config.set(MARKER_KEY, "0");
    }

    private static boolean alreadyApplied(SpecTuneConfig config) {
        String marker = config.get(MARKER_KEY);
        if (marker == null) return false;
        try {
            return Integer.parseInt(marker.strip()) >= PROFILE_GENERATION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static CloudRenderMode cloudMode(TuningPlan.VideoPlan.CloudMode mode) {
        return switch (mode) {
            case OFF -> CloudRenderMode.OFF;
            case FAST -> CloudRenderMode.FAST;
            case FANCY -> CloudRenderMode.FANCY;
        };
    }

    private static ParticlesMode particles(TuningPlan.VideoPlan.ParticleMode mode) {
        return switch (mode) {
            case ALL -> ParticlesMode.ALL;
            case DECREASED -> ParticlesMode.DECREASED;
            case MINIMAL -> ParticlesMode.MINIMAL;
        };
    }
}
