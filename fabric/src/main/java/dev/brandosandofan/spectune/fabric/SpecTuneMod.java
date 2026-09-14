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

        List<Advice> advice = JvmAdvisor.review(SpecTune.profile(), SpecTune.gpu());
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

    /** Renders the current state of everything SpecTune knows. */
    static String report() {
        return TuningReport.render(
                SpecTune.profile(),
                SpecTune.plan(),
                SpecTune.gpu(),
                JvmAdvisor.review(SpecTune.profile(), SpecTune.gpu()));
    }
}
