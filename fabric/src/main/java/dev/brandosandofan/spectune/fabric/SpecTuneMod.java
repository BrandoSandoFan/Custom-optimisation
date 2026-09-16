package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.Advice;
import dev.brandosandofan.spectune.core.JvmAdvisor;
import dev.brandosandofan.spectune.core.TuningReport;
import java.util.List;
import net.fabricmc.api.ModInitializer;

/**
 * Common entrypoint. Everything here applies to a dedicated server too, where the worker pool size
 * and the collector matter just as much as they do on the client.
 */
public final class SpecTuneMod implements ModInitializer {

    private static ThreadTuner tuner;

    @Override
    public void onInitialize() {
        SpecTune.bootstrap();
        if (!SpecTune.config().enabled()) {
            SpecTune.LOGGER.info("SpecTune is disabled in {}.", SpecTune.configFile());
            return;
        }

        tuner = new ThreadTuner(SpecTune.plan().threads());
        tuner.start();

        List<Advice> advice = JvmAdvisor.review(SpecTune.profile(), SpecTune.gpu(), SpecTune.powerPlan());
        for (Advice item : advice) {
            switch (item.severity()) {
                case CRITICAL -> SpecTune.LOGGER.error(item.format());
                case WARN -> SpecTune.LOGGER.warn(item.format());
                case INFO -> SpecTune.LOGGER.info(item.format());
            }
        }
    }

    static ThreadTuner tuner() {
        return tuner;
    }

    /**
     * Replaces the running tuner with one built from the current plan. Needed because {@link
     * ThreadTuner} is built around an immutable {@link TuningPlan.ThreadPlan}, so a live change to
     * priority tuning (on/off) or the priorities themselves needs a new instance rather than a
     * mutation.
     */
    static synchronized void restartTuner() {
        if (tuner != null) tuner.stop();
        tuner = new ThreadTuner(SpecTune.plan().threads());
        tuner.start();
    }

    /** Renders the current state of everything SpecTune knows. */
    static String report() {
        return TuningReport.render(
                SpecTune.profile(),
                SpecTune.plan(),
                SpecTune.gpu(),
                SpecTune.powerPlan(),
                JvmAdvisor.review(SpecTune.profile(), SpecTune.gpu(), SpecTune.powerPlan()));
    }
}
