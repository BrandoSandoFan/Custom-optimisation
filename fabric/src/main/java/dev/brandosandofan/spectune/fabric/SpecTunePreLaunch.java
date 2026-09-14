package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.TuningPlanner;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before any Minecraft class is loaded.
 *
 * <p>This is the only window in which the shared worker pool can be resized. Minecraft builds that
 * pool in a static initialiser and caps it at
 * {@value dev.brandosandofan.spectune.core.TuningPlanner#VANILLA_BACKGROUND_THREAD_CAP} threads
 * unless the {@code max.bg.threads} system property says otherwise. Once the class initialises the
 * pool is fixed for the process, so setting the property here is the whole mechanism.
 */
public final class SpecTunePreLaunch implements PreLaunchEntrypoint {

    static final String BACKGROUND_THREADS_PROPERTY = "max.bg.threads";

    @Override
    public void onPreLaunch() {
        SpecTune.bootstrap();
        if (!SpecTune.config().enabled() || !SpecTune.config().autoSizeThreads()) {
            SpecTune.LOGGER.info("Thread auto-sizing disabled by config.");
            return;
        }

        String existing = System.getProperty(BACKGROUND_THREADS_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            // A value on the command line is a deliberate choice; never override it.
            SpecTune.LOGGER.info("{} already set to {} on the command line, leaving it alone.",
                    BACKGROUND_THREADS_PROPERTY, existing);
            return;
        }

        int threads = SpecTune.plan().threads().backgroundThreads();
        System.setProperty(BACKGROUND_THREADS_PROPERTY, Integer.toString(threads));
        SpecTune.LOGGER.info("{} = {} (vanilla would cap at {}) for {}",
                BACKGROUND_THREADS_PROPERTY, threads, TuningPlanner.VANILLA_BACKGROUND_THREAD_CAP,
                SpecTune.profile().cpu().describe());
    }
}
