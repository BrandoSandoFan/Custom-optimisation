package dev.brandosandofan.spectune.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.brandosandofan.spectune.core.Advice;
import dev.brandosandofan.spectune.core.GpuInfo;
import dev.brandosandofan.spectune.core.JvmAdvisor;
import dev.brandosandofan.spectune.core.SpecTuneConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.opengl.GL11;

/**
 * Client entrypoint: probes the GPU once a GL context exists, applies the video profile, replaces
 * vanilla's Video Settings screen with SpecTune's own, and registers {@code /spectune}.
 */
public final class SpecTuneClient implements ClientModInitializer {

    /**
     * Set by the {@code BEFORE_INIT} hook, consumed by the next {@code END_CLIENT_TICK}. Both run
     * on the render thread only, so this needs no synchronization.
     */
    private static Screen pendingReplacementParent;

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

        // Swap out vanilla's Video Settings screen for SpecTune's own. No mixin: Screen keeps its
        // "return to this when done" parent in a private field regardless of which vanilla class
        // declares it, so it is read generically rather than guessed at by name.
        //
        // The swap itself waits for the next END_CLIENT_TICK rather than happening directly in
        // BEFORE_INIT, or via client.execute() from inside it (both tried and both crashed the
        // same way - see below). MinecraftClient.setScreen() assigns currentScreen to the incoming
        // screen *before* calling its init(), so calling setScreen() again while still inside that
        // call is reentrant: the inner call's first move is currentScreen.removed() on a screen
        // that has been assigned but not yet initialised, which is exactly what crashed - Screen's
        // own removed() read a list-widget field that only init() populates. client.execute() does
        // not avoid this: MinecraftClient is a ReentrantThreadExecutor, so calling execute() from
        // its own thread (the render thread, which is where BEFORE_INIT fires) runs the task
        // immediately rather than queuing it - so it hit the identical crash, just caught silently
        // by the try/catch that was added at the same time, which is why the redirect quietly did
        // nothing instead of opening SpecTune's screen. A tick event is a genuinely later,
        // unnested call: it fires from Minecraft's own game loop, never from inside a screen's own
        // init/setScreen call chain, so by the time it runs the outer setScreen(videoOptionsScreen)
        // call - and everything on its stack - has long since returned.
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof VideoOptionsScreen)) return;
            if (!SpecTune.config().enabled() || !SpecTune.config().replaceVideoSettingsScreen()) return;

            Screen parent = findParentScreen(screen);
            if (parent == null) {
                SpecTune.LOGGER.warn("Could not find VideoOptionsScreen's parent field; "
                        + "leaving the vanilla screen in place.");
                return;
            }
            pendingReplacementParent = parent;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingReplacementParent == null) return;
            Screen parent = pendingReplacementParent;
            pendingReplacementParent = null;

            // Only replace it if it is still what is open: something else (Escape, a different
            // click) may have already changed the screen in the meantime.
            if (!(client.currentScreen instanceof VideoOptionsScreen)) return;
            try {
                client.setScreen(new SpecTuneOptionsScreen(parent));
            } catch (RuntimeException e) {
                SpecTune.LOGGER.warn("Could not open the SpecTune settings screen; "
                        + "leaving the vanilla one open.", e);
            }
        });
    }

    /**
     * Finds the {@code Screen}-typed field vanilla's options screens use to remember what to
     * return to on close. Reflection instead of a mixin: the field's owning class and exact name
     * have moved between versions, but every version has exactly one such field.
     */
    private static Screen findParentScreen(Screen screen) {
        Field fallback = null;
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!Screen.class.isAssignableFrom(field.getType())) continue;
                if (field.getName().toLowerCase(Locale.ROOT).contains("parent")) {
                    Screen parent = readScreenField(field, screen);
                    if (parent != null) return parent;
                }
                if (fallback == null) fallback = field;
            }
        }
        return fallback == null ? null : readScreenField(fallback, screen);
    }

    private static Screen readScreenField(Field field, Screen screen) {
        try {
            field.setAccessible(true);
            Object value = field.get(screen);
            return value instanceof Screen parent ? parent : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the renderer strings straight from LWJGL rather than through Minecraft's own debug
     * helper, whose class has moved between versions. Runs on the render thread, where the context
     * is current.
     */
    private static GpuInfo probeGpu() {
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
                }))
                .then(ClientCommandManager.literal("settings").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.setScreen(new SpecTuneOptionsScreen(client.currentScreen));
                    return 1;
                }));
    }

    /** An explicit {@code /spectune apply} overrides the once-only guard, but not {@code off}. */
    private static SpecTuneConfig forceApply() {
        SpecTuneConfig config = SpecTune.config();
        if (config.videoApplyMode() != SpecTuneConfig.ApplyMode.OFF) {
            VideoTuner.clearAppliedMarker(config);
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

        for (Advice advice : JvmAdvisor.review(SpecTune.profile(), gpu, SpecTune.powerPlan())) {
            Formatting colour = switch (advice.severity()) {
                case CRITICAL -> Formatting.RED;
                case WARN -> Formatting.YELLOW;
                case INFO -> Formatting.GRAY;
            };
            source.sendFeedback(Text.literal(advice.format()).formatted(colour));
        }
        source.sendFeedback(Text.literal("Full report: config/spectune-report.txt")
                .formatted(Formatting.DARK_GRAY));
        source.sendFeedback(Text.literal("Run /spectune settings for the advanced settings screen.")
                .formatted(Formatting.DARK_GRAY));
    }
}
