package dev.brandosandofan.spectune.core;

import java.util.List;

/**
 * Standalone entry point: {@code java -jar spectune-core.jar}.
 *
 * <p>Runs the same detection and planning the mod runs, but without Minecraft, so the machine can be
 * profiled and the JVM arguments generated before anything is installed. The GPU cannot be probed
 * from here (there is no GL context), so pass the renderer string from the game's F3 screen as an
 * argument to get video recommendations too.
 */
public final class SpecTuneCli {

    private SpecTuneCli() {}

    public static void main(String[] args) {
        SpecTuneConfig config = SpecTuneConfig.defaults();
        MachineProfile profile = MachineProfile.capture(config.resolveTopology());

        GpuInfo gpu = args.length > 0 ? GpuInfo.of("command line", String.join(" ", args), "unknown") : null;
        PowerPlanInfo power = PowerPlanDetector.detect();
        TuningPlan plan = TuningPlanner.plan(profile, config, gpu);
        List<Advice> advice = JvmAdvisor.review(profile, gpu, power);

        System.out.println(TuningReport.render(profile, plan, gpu, power, advice));
        if (gpu == null) {
            System.out.println("Tip: pass your GPU's renderer string (F3 screen, right-hand column) as an "
                    + "argument to include video settings tuned for it, e.g.");
            System.out.println("  java -jar spectune-core.jar \"NVIDIA GeForce RTX 5080 Laptop GPU\"");
        }
    }
}
