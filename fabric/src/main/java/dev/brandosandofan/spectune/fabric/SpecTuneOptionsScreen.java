package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.SpecTuneConfig;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
 * warning knobs, laid out across tabs the way Sodium's options screen is, with one button that
 * resets everything back to SpecTune's recommendation for the detected machine.
 *
 * <p>This replaces vanilla's own Video Settings screen: {@code SpecTuneClient} intercepts the
 * moment a {@code VideoOptionsScreen} would open and substitutes this one instead (toggle with
 * {@code gui.replaceVideoSettings} in {@code spectune.properties}). It is also reachable directly
 * with {@code /spectune settings}. The video rows edit the same {@link GameOptions} fields the
 * vanilla screen does - changes are visible immediately - and are written to {@code options.txt}
 * on close; the SpecTune-specific rows are written to {@code spectune.properties} on close. The
 * worker-thread row is the one exception: {@link SpecTunePreLaunch} can only size that pool
 * before the game window opens, so a change there needs a relaunch, which the row's own label
 * says.
 *
 * <p>Switching tabs rebuilds the screen from scratch ({@code client.setScreen(new
 * SpecTuneOptionsScreen(parent, tab))}) rather than clearing and re-running {@code init()} in
 * place. Slower, but it is the one screen-transition pattern already proven safe here: a plain
 * button press is never nested inside Minecraft's own screen-opening call stack the way the
 * original Video Settings redirect was, which is what caused the crash {@code
 * SpecTuneClient}'s comments describe.
 */
public final class SpecTuneOptionsScreen extends Screen {

    enum Tab {
        VIDEO("Video"),
        QUALITY("Quality"),
        SPECTUNE("SpecTune");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 240;
    private static final int TOP = 28;
    private static final int CONTENT_TOP = TOP + 26;

    private final Screen parent;
    private final Tab tab;
    private boolean configDirty;
    private int y = CONTENT_TOP;

    public SpecTuneOptionsScreen(Screen parent) {
        this(parent, Tab.VIDEO);
    }

    public SpecTuneOptionsScreen(Screen parent, Tab tab) {
        super(Text.literal("SpecTune Settings"));
        this.parent = parent;
        this.tab = tab;
    }

    @Override
    protected void init() {
        y = CONTENT_TOP;
        addTabStrip();

        GameOptions options = this.client.options;
        int x = this.width / 2 - ROW_WIDTH / 2;

        switch (tab) {
            case VIDEO -> buildVideoTab(options, x);
            case QUALITY -> buildQualityTab(options, x);
            case SPECTUNE -> buildSpecTuneTab(x);
        }

        int buttonsY = y + 10;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset to SpecTune Defaults"),
                        button -> resetToDefaults())
                .dimensions(this.width / 2 - 155, buttonsY, 150, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                        .dimensions(this.width / 2 + 5, buttonsY, 150, 20)
                        .build());
    }

    private void addTabStrip() {
        Tab[] tabs = Tab.values();
        int tabWidth = 100;
        int startX = this.width / 2 - (tabWidth * tabs.length) / 2;
        for (int i = 0; i < tabs.length; i++) {
            Tab candidate = tabs[i];
            boolean active = candidate == tab;
            ButtonWidget tabButton = ButtonWidget.builder(
                            Text.literal(active ? "[" + candidate.label + "]" : candidate.label),
                            pressed -> this.client.setScreen(new SpecTuneOptionsScreen(parent, candidate)))
                    .dimensions(startX + i * tabWidth, TOP, tabWidth, 20)
                    .build();
            tabButton.active = !active;
            this.addDrawableChild(tabButton);
        }
    }

    // -------------------------------------------------------------------------------- Tab content

    private void buildVideoTab(GameOptions options, int x) {
        addSlider(x, "Render Distance", 2, 32, options.getViewDistance().getValue(),
                v -> Integer.toString(v), v -> options.getViewDistance().setValue(v));
        addSlider(x, "Simulation Distance", 2, 32, options.getSimulationDistance().getValue(),
                v -> Integer.toString(v), v -> options.getSimulationDistance().setValue(v));
        addSlider(x, "Max FPS", 10, 260, options.getMaxFps().getValue(),
                v -> v >= 260 ? "Unlimited" : Integer.toString(v), v -> options.getMaxFps().setValue(v));
        addToggle(x, "VSync", options.getEnableVsync().getValue(), v -> options.getEnableVsync().setValue(v));
        addToggle(x, "Fullscreen", options.getFullscreen().getValue(), v -> options.getFullscreen().setValue(v));
        addSlider(x, "GUI Scale", 0, 4, options.getGuiScale().getValue(),
                v -> v == 0 ? "Auto" : Integer.toString(v), v -> options.getGuiScale().setValue(v));
        addSlider(x, "Brightness", 0, 100, percentOf(options.getGamma().getValue()),
                v -> v + "%", v -> options.getGamma().setValue(v / 100.0));
        addToggle(x, "Smooth Lighting", options.getAo().getValue(), v -> options.getAo().setValue(v));
        addToggle(x, "View Bobbing", options.getBobView().getValue(), v -> options.getBobView().setValue(v));
    }

    private void buildQualityTab(GameOptions options, int x) {
        addToggle(x, "Fancy Graphics", options.getGraphicsMode().getValue() == GraphicsMode.FANCY,
                v -> options.getGraphicsMode().setValue(v ? GraphicsMode.FANCY : GraphicsMode.FAST));
        addSlider(x, "Field of View", 30, 110, options.getFov().getValue(),
                v -> Integer.toString(v), v -> options.getFov().setValue(v));
        addSlider(x, "Biome Blend", 0, 7, options.getBiomeBlendRadius().getValue(),
                v -> Integer.toString(v), v -> options.getBiomeBlendRadius().setValue(v));
        addSlider(x, "Mipmap Levels", 0, 4, options.getMipmapLevels().getValue(),
                v -> Integer.toString(v), v -> options.getMipmapLevels().setValue(v));
        addToggle(x, "Entity Shadows", options.getEntityShadows().getValue(),
                v -> options.getEntityShadows().setValue(v));
        addSlider(x, "Entity Distance", 50, 500, percentOf(options.getEntityDistanceScaling().getValue()),
                v -> v + "%", v -> options.getEntityDistanceScaling().setValue(v / 100.0));
        addCycle(x, "Clouds", List.of("Off", "Fast", "Fancy"), cloudIndex(options.getCloudRenderMode().getValue()),
                i -> options.getCloudRenderMode().setValue(cloudMode(i)));
        addCycle(x, "Particles", List.of("All", "Decreased", "Minimal"),
                particleIndex(options.getParticles().getValue()),
                i -> options.getParticles().setValue(particleMode(i)));
    }

    /** Converts a vanilla {@code 0.0-N.0} fraction option to the whole-percent int this screen's sliders use. */
    private static int percentOf(double fraction) {
        return (int) Math.round(fraction * 100);
    }

    private void buildSpecTuneTab(int x) {
        addSlider(x, "Worker Threads (relaunch)", 0, 32, SpecTune.config().backgroundThreadOverride(),
                v -> v == 0 ? "Auto" : Integer.toString(v), this::setBackgroundThreadOverride);
        addToggle(x, "Priority Tuning", SpecTune.config().priorityTuning(), this::setPriorityTuning);
        addToggle(x, "Warn on Integrated GPU", SpecTune.config().warnOnIntegratedGpu(),
                this::setWarnOnIntegratedGpu);
        addCycle(x, "Video Apply Mode", List.of("Off", "Once", "Always"),
                SpecTune.config().videoApplyMode().ordinal(), this::setVideoApplyMode);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 6, 0xFFFFFF);
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
        SpecTune.config().set("video.apply", mode.name().toLowerCase(Locale.ROOT));
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

        this.client.setScreen(new SpecTuneOptionsScreen(parent, tab));
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

    private void addSlider(int x, String label, int min, int max, int initial,
            Function<Integer, String> format, IntConsumer onChange) {
        this.addDrawableChild(new IntSliderWidget(x, y, ROW_WIDTH, 20, min, max, initial,
                v -> label + ": " + format.apply(v), onChange));
        y += ROW_HEIGHT;
    }

    private void addToggle(int x, String label, boolean initial, Consumer<Boolean> onChange) {
        addCycle(x, label, List.of("Off", "On"), initial ? 1 : 0, i -> onChange.accept(i == 1));
    }

    private void addCycle(int x, String label, List<String> options, int initialIndex, IntConsumer onChange) {
        int[] index = {Math.max(0, Math.min(options.size() - 1, initialIndex))};
        ButtonWidget button = ButtonWidget.builder(cycleText(label, options.get(index[0])), b -> {
                    index[0] = (index[0] + 1) % options.size();
                    b.setMessage(cycleText(label, options.get(index[0])));
                    onChange.accept(index[0]);
                })
                .dimensions(x, y, ROW_WIDTH, 20)
                .build();
        this.addDrawableChild(button);
        y += ROW_HEIGHT;
    }

    private static Text cycleText(String label, String value) {
        return Text.literal(label + ": " + value);
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
