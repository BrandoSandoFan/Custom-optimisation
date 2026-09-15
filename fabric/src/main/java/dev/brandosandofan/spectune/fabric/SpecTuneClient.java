package dev.brandosandofan.spectune.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.brandosandofan.spectune.core.Advice;
import dev.brandosandofan.spectune.core.GpuInfo;
import dev.brandosandofan.spectune.core.JvmAdvisor;
import dev.brandosandofan.spectune.core.SpecTuneConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.opengl.GL11;

/**
 * Client entrypoint: probes the GPU once a GL context exists, applies the video profile, and
 * registers {@code /spectune}.
 */
public final class SpecTuneClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (!SpecTune.config().enabled()) return;

            GpuInfo gpu = probeGpu();
            SpecTune.attachGpu(gpu);
            SpecTune.LOGGER.info("GPU: {}", gpu.describe());

            if (gpu.integrated() && SpecTune.config().warnOnIntegratedGpu()) {
                // Worth shouting about: on a laptop this means the discrete card is idle.
                SpecTune.LOGGER.error("=======================================================");
                SpecTune.LOGGER.error("Minecraft is rendering on the INTEGRATED GPU: {}", gpu.renderer());
                SpecTune.LOGGER.error("Force javaw.exe onto the discrete GPU - see docs/TUNING.md.");
                SpecTune.LOGGER.error("=======================================================");
            }

            VideoTuner.apply(client, SpecTune.plan().video(), SpecTune.config());

            if (SpecTune.config().writeReport()) {
                SpecTune.writeReport(SpecTuneMod.report());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(buildCommand()));
    }

    /** Fabric mod id of VulkanMod, the Vulkan-backed renderer replacement. */
    private static final String VULKANMOD_ID = "vulkanmod";

    /**
     * Reads the renderer strings straight from LWJGL rather than through Minecraft's own debug
     * helper, whose class has moved between versions. Runs on the render thread, where the context
     * is current.
     *
     * <p>Renderer-replacement mods that never create a real OpenGL context (VulkanMod chief among
     * them) are a hard exception: they intercept most OpenGL entry points through their own mixins,
     * but calling a raw LWJGL function like this one is documented to crash the game rather than
     * fail into a catchable exception. Detect them by mod id and skip the probe entirely rather than
     * relying on the {@code catch} below to save us. Set {@code gpu.tierOverride} in
     * {@code spectune.properties} to restore tier-based video tuning under those renderers.
     */
    private static GpuInfo probeGpu() {
        if (FabricLoader.getInstance().isModLoaded(VULKANMOD_ID)) {
            SpecTune.LOGGER.info("VulkanMod detected; skipping the OpenGL renderer probe to avoid "
                    + "crashing on a call it cannot intercept. Set gpu.tierOverride in {} to restore "
                    + "tier-based video tuning.", SpecTune.configFile());
            return GpuInfo.of("unknown", "unavailable (VulkanMod active)", "unknown");
        }
        try {
            return GpuInfo.of(
                    GL11.glGetString(GL11.GL_VENDOR),
                    GL11.glGetString(GL11.GL_RENDERER),
                    GL11.glGetString(GL11.GL_VERSION));
        } catch (RuntimeException | LinkageError e) {
            SpecTune.LOGGER.warn("Could not query the GL renderer: {}", e.toString());
            return GpuInfo.of("unknown", "unknown", "unknown");
        }
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
        return ClientCommandManager.literal("spectune")
                .executes(context -> {
                    sendReport(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.literal("report").executes(context -> {
                    sendReport(context.getSource());
                    return 1;
                }))
                .then(ClientCommandManager.literal("args").executes(context -> {
                    String args = String.join(" ", JvmAdvisor.recommendedArguments(
                            SpecTune.profile(), SpecTune.plan()));
                    context.getSource().sendFeedback(Text.literal("Recommended JVM arguments:")
                            .formatted(Formatting.GOLD));
                    context.getSource().sendFeedback(Text.literal(args).formatted(Formatting.GRAY));
                    return 1;
                }))
                .then(ClientCommandManager.literal("apply").executes(context -> {
                    boolean changed = VideoTuner.apply(
                            MinecraftClient.getInstance(),
                            SpecTune.plan().video(),
                            forceApply());
                    context.getSource().sendFeedback(Text.literal(changed
                                    ? "Applied the video profile for this machine."
                                    : "Video profile is disabled in spectune.properties.")
                            .formatted(changed ? Formatting.GREEN : Formatting.YELLOW));
                    return 1;
                }));
    }

    /** An explicit {@code /spectune apply} overrides the once-only guard, but not {@code off}. */
    private static SpecTuneConfig forceApply() {
        SpecTuneConfig config = SpecTune.config();
        if (config.videoApplyMode() != SpecTuneConfig.ApplyMode.OFF) {
            config.set("video.appliedGeneration", "0");
        }
        return config;
    }

    private static void sendReport(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(SpecTune.profile().cpu().describe()).formatted(Formatting.AQUA));
        GpuInfo gpu = SpecTune.gpu();
        if (gpu != null) {
            source.sendFeedback(Text.literal(gpu.describe())
                    .formatted(gpu.integrated() ? Formatting.RED : Formatting.AQUA));
        }
        source.sendFeedback(Text.literal(String.format(
                        "Heap %.1f GiB, %s, %d worker threads",
                        SpecTune.profile().maxHeapGiB(),
                        SpecTune.profile().collector(),
                        SpecTune.plan().threads().backgroundThreads()))
                .formatted(Formatting.AQUA));

        for (Advice advice : JvmAdvisor.review(SpecTune.profile(), gpu)) {
            Formatting colour = switch (advice.severity()) {
                case CRITICAL -> Formatting.RED;
                case WARN -> Formatting.YELLOW;
                case INFO -> Formatting.GRAY;
            };
            source.sendFeedback(Text.literal(advice.format()).formatted(colour));
        }
        source.sendFeedback(Text.literal("Full report: config/spectune-report.txt")
                .formatted(Formatting.DARK_GRAY));
    }
}
