package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.SpecTuneConfig;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;
import net.minecraft.text.Text;

/**
 * SpecTune's own advanced settings screen: every video setting it tunes, plus its own thread and
 * warning knobs, as plain sliders and cycle buttons, with one button that resets everything here
 * back to SpecTune's recommendation for the detected machine.
 *
 * <p>Reachable with {@code /spectune settings}. The video rows edit the same {@link GameOptions}
 * fields the vanilla Video Settings screen does - changes are visible immediately - and are
 * written to {@code options.txt} on close; the SpecTune-specific rows are written to {@code
 * spectune.properties} on close. The worker-thread row is the one exception: {@link
 * SpecTunePreLaunch} can only size that pool before the game window opens, so a change there
 * needs a relaunch, which the row's own label says.
 */
public final class SpecTuneOptionsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 200;
    private static final int TOP = 32;

    private final Screen parent;
    private boolean configDirty;
    private int leftY = TOP;
    private int rightY = TOP;

    public SpecTuneOptionsScreen(Screen parent) {
        super(Text.literal("SpecTune Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        GameOptions options = this.client.options;
        int leftX = this.width / 2 - 5 - ROW_WIDTH;
        int rightX = this.width / 2 + 5;

        addSlider(leftX, true, "Render Distance", 2, 32, options.getViewDistance().getValue(),
                v -> Integer.toString(v), v -> options.getViewDistance().setValue(v));
        addSlider(leftX, true, "Simulation Distance", 2, 32, options.getSimulationDistance().getValue(),
                v -> Integer.toString(v), v -> options.getSimulationDistance().setValue(v));
        addSlider(leftX, true, "Max FPS", 10, 260, options.getMaxFps().getValue(),
                v -> v >= 260 ? "Unlimited" : Integer.toString(v), v -> options.getMaxFps().setValue(v));
        addToggle(leftX, true, "VSync", options.getEnableVsync().getValue(),
                v -> options.getEnableVsync().setValue(v));
        addToggle(leftX, true, "Fancy Graphics", options.getGraphicsMode().getValue() == GraphicsMode.FANCY,
                v -> options.getGraphicsMode().setValue(v ? GraphicsMode.FANCY : GraphicsMode.FAST));
        addSlider(leftX, true, "Biome Blend", 0, 7, options.getBiomeBlendRadius().getValue(),
                v -> Integer.toString(v), v -> options.getBiomeBlendRadius().setValue(v));
        addSlider(leftX, true, "Mipmap Levels", 0, 4, options.getMipmapLevels().getValue(),
                v -> Integer.toString(v), v -> options.getMipmapLevels().setValue(v));

        addToggle(rightX, false, "Entity Shadows", options.getEntityShadows().getValue(),
                v -> options.getEntityShadows().setValue(v));
        addCycle(rightX, false, "Clouds", List.of("Off", "Fast", "Fancy"),
                cloudIndex(options.getCloudRenderMode().getValue()),
                i -> options.getCloudRenderMode().setValue(cloudMode(i)));
        addCycle(rightX, false, "Particles", List.of("All", "Decreased", "Minimal"),
                particleIndex(options.getParticles().getValue()),
                i -> options.getParticles().setValue(particleMode(i)));
        addSlider(rightX, false, "Worker Threads (relaunch)", 0, 32, SpecTune.config().backgroundThreadOverride(),
                v -> v == 0 ? "Auto" : Integer.toString(v), this::setBackgroundThreadOverride);
        addToggle(rightX, false, "Priority Tuning", SpecTune.config().priorityTuning(), this::setPriorityTuning);
        addToggle(rightX, false, "Warn on Integrated GPU", SpecTune.config().warnOnIntegratedGpu(),
                this::setWarnOnIntegratedGpu);
        addCycle(rightX, false, "Video Apply Mode",
                List.of("Off", "Once", "Always"), SpecTune.config().videoApplyMode().ordinal(),
                this::setVideoApplyMode);

        int buttonsY = Math.max(leftY, rightY) + 10;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset to SpecTune Defaults"),
                        button -> resetToDefaults())
                .dimensions(this.width / 2 - 155, buttonsY, 150, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                        .dimensions(this.width / 2 + 5, buttonsY, 150, 20)
                        .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
    }

    @Override
    public void close() {
        this.client.options.write();
        if (configDirty) {
            saveConfig();
        }
        this.client.setScreen(parent);
    }

    // ------------------------------------------------------------ SpecTune-config-backed rows

    private void setBackgroundThreadOverride(int value) {
        SpecTune.config().set("threads.backgroundOverride", Integer.toString(value));
        configDirty = true;
    }

    private void setPriorityTuning(boolean enabled) {
        SpecTune.config().set("threads.priorityTuning", Boolean.toString(enabled));
        configDirty = true;
        SpecTune.attachGpu(SpecTune.gpu());
        SpecTuneMod.restartTuner();
    }

    private void setWarnOnIntegratedGpu(boolean enabled) {
        SpecTune.config().set("gpu.warnOnIntegrated", Boolean.toString(enabled));
        configDirty = true;
    }

    private void setVideoApplyMode(int index) {
        SpecTuneConfig.ApplyMode mode = SpecTuneConfig.ApplyMode.values()[index];
        SpecTune.config().set("video.apply", mode.name().toLowerCase(java.util.Locale.ROOT));
        configDirty = true;
    }

    private void resetToDefaults() {
        SpecTuneConfig config = SpecTune.config();
        config.resetTunables();
        VideoTuner.clearAppliedMarker(config);
        configDirty = false;
        saveConfig();

        SpecTune.attachGpu(SpecTune.gpu());
        VideoTuner.apply(this.client, SpecTune.plan().video(), config);
        SpecTuneMod.restartTuner();

        this.client.setScreen(new SpecTuneOptionsScreen(parent));
    }

    private void saveConfig() {
        try {
            SpecTune.config().save(SpecTune.configFile());
        } catch (IOException e) {
            SpecTune.LOGGER.warn("Could not save {}: {}", SpecTune.configFile(), e.toString());
        }
    }

    // -------------------------------------------------------------------------- Enum <-> index

    private static int cloudIndex(CloudRenderMode mode) {
        return switch (mode) {
            case OFF -> 0;
            case FAST -> 1;
            case FANCY -> 2;
        };
    }

    private static CloudRenderMode cloudMode(int index) {
        return switch (index) {
            case 0 -> CloudRenderMode.OFF;
            case 1 -> CloudRenderMode.FAST;
            default -> CloudRenderMode.FANCY;
        };
    }

    private static int particleIndex(ParticlesMode mode) {
        return switch (mode) {
            case ALL -> 0;
            case DECREASED -> 1;
            case MINIMAL -> 2;
        };
    }

    private static ParticlesMode particleMode(int index) {
        return switch (index) {
            case 0 -> ParticlesMode.ALL;
            case 1 -> ParticlesMode.DECREASED;
            default -> ParticlesMode.MINIMAL;
        };
    }

    // -------------------------------------------------------------------------- Row builders

    private void addSlider(int x, boolean left, String label, int min, int max, int initial,
            Function<Integer, String> format, IntConsumer onChange) {
        int y = left ? leftY : rightY;
        this.addDrawableChild(new IntSliderWidget(x, y, ROW_WIDTH, 20, min, max, initial,
                v -> label + ": " + format.apply(v), onChange));
        advance(left);
    }

    private void addToggle(int x, boolean left, String label, boolean initial, Consumer<Boolean> onChange) {
        addCycle(x, left, label, List.of("Off", "On"), initial ? 1 : 0, i -> onChange.accept(i == 1));
    }

    private void addCycle(int x, boolean left, String label, List<String> options, int initialIndex,
            IntConsumer onChange) {
        int y = left ? leftY : rightY;
        int[] index = {Math.max(0, Math.min(options.size() - 1, initialIndex))};
        ButtonWidget button = ButtonWidget.builder(cycleText(label, options.get(index[0])), b -> {
                    index[0] = (index[0] + 1) % options.size();
                    b.setMessage(cycleText(label, options.get(index[0])));
                    onChange.accept(index[0]);
                })
                .dimensions(x, y, ROW_WIDTH, 20)
                .build();
        this.addDrawableChild(button);
        advance(left);
    }

    private static Text cycleText(String label, String value) {
        return Text.literal(label + ": " + value);
    }

    private void advance(boolean left) {
        if (left) {
            leftY += ROW_HEIGHT;
        } else {
            rightY += ROW_HEIGHT;
        }
    }

    // -------------------------------------------------------------------------- Slider widget

    /** A generic integer slider: normalises {@code [min, max]} onto the vanilla {@code [0, 1]} track. */
    private static final class IntSliderWidget extends SliderWidget {

        private final int min;
        private final int max;
        private final Function<Integer, String> label;
        private final IntConsumer onChange;

        IntSliderWidget(int x, int y, int width, int height, int min, int max, int initial,
                Function<Integer, String> label, IntConsumer onChange) {
            super(x, y, width, height, Text.empty(), normalise(min, max, initial));
            this.min = min;
            this.max = max;
            this.label = label;
            this.onChange = onChange;
            this.updateMessage();
        }

        private static double normalise(int min, int max, int value) {
            if (max <= min) return 0;
            int clamped = Math.max(min, Math.min(max, value));
            return (double) (clamped - min) / (max - min);
        }

        private int intValue() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            // SliderWidget's own constructor calls this before this subclass's fields are assigned;
            // the explicit call at the end of this constructor sets the real message afterwards.
            if (label == null) return;
            this.setMessage(Text.literal(label.apply(intValue())));
        }

        @Override
        protected void applyValue() {
            if (onChange == null) return;
            onChange.accept(intValue());
        }
    }
}
