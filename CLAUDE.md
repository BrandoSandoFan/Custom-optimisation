# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SpecTune, a Fabric mod for Minecraft that detects the host machine (CPU topology, RAM, GPU) and
tunes the worker-thread pool, thread priorities, and video settings to it, plus reviews the JVM
and reports what's misconfigured. See `README.md` for the full feature description and
`docs/TUNING.md` / `docs/JVM_ARGS.md` for the reasoning behind specific numbers.

## Repository layout and why it's split this way

```
core/     Plain Java 21, zero Minecraft/Fabric dependency. Every decision lives here:
          CPU/GPU detection, planning, JVM review, config. Unit-tested and buildable standalone.
fabric/   Fabric entrypoints only. Applies what core decides; contains no tuning logic itself.
docs/     Tuning playbook and JVM argument reference (numbers/reasoning, not code).
```

`fabric/build.gradle` compiles `core`'s sources directly into the mod jar via
`sourceSets.main.java.srcDir '../core/src/main/java'` rather than a project dependency or
jar-in-jar — there is one artifact, and `core` stays a fully independent Gradle project with its
own `settings.gradle`/`gradlew`. Keep all tunable logic in `core`; `fabric` should stay thin enough
that a NeoForge port only needs that adapter layer reimplemented.

**No mixins.** Thread classification works by matching thread names (`ThreadClassifier`) rather
than injecting into Minecraft's executor factories, which is what keeps the mod working across
Minecraft version bumps without a remap.

## Commands

### core (no Minecraft toolchain needed, works fully offline)

```bash
cd core
./gradlew test                    # run all unit tests
./gradlew test --tests "TuningPlannerTest"          # single test class
./gradlew test --tests "*.picksAHighRenderDistance*" # single test method (name substring)
./gradlew jar                     # build the standalone analyser CLI jar
java -jar build/libs/spectune-core-1.0.0.jar "NVIDIA GeForce RTX 5080 Laptop GPU"
                                   # run the analyser without Minecraft; GPU renderer string is optional
```

### fabric (requires network access to maven.fabricmc.net; CI builds this, sandboxed dev environments
often cannot)

```bash
cd fabric
./gradlew build                   # jar lands in fabric/build/libs/
```

If Fabric's Maven is unreachable in your environment (common in sandboxes), you cannot build
`fabric/` directly. In that situation, type-check changes to `fabric/src` by compiling against
hand-written stubs for the Fabric/Minecraft/LWJGL/Brigadier APIs actually used (a handful of
classes: `ModInitializer`, `ClientModInitializer`, `PreLaunchEntrypoint`, `FabricLoader`,
`ClientLifecycleEvents`, `GameOptions` and its `SimpleOption`/`GraphicsMode`/`CloudRenderMode`/
`ParticlesMode`, `MinecraftClient`, `GL11`, Brigadier's `LiteralArgumentBuilder`/`CommandDispatcher`)
in a scratch directory, then let CI (`.github/workflows/build.yml`, job `mod (jar)`) do the real
build against actual Minecraft/Fabric API as the final check before pushing.

### Retargeting to a different Minecraft version

Edit the five version properties in `fabric/gradle.properties`
(`minecraft_version`, `yarn_mappings`, `loader_version`, `fabric_api_version`, `loom_version`) —
current values for any version are at https://fabricmc.net/develop/. Nothing else should need to
change given the no-mixin design, though Minecraft's `GameOptions` accessor names in
`VideoTuner.java` do shift between versions and are the most likely thing to need adjustment.

## Architecture

### The core decision pipeline

`CpuDetector.detect()` → `MachineProfile.capture()` → `TuningPlanner.plan()` → `TuningPlan`
(`ThreadPlan` + `VideoPlan`), reviewed alongside by `JvmAdvisor.review()` → `List<Advice>`.
Everything is pure functions over immutable records (`CpuTopology`, `GpuInfo`, `MachineProfile`,
`TuningPlan`), which is what makes `TuningPlannerTest`/`JvmAdvisorTest` able to test against
synthetic machines (e.g. a fabricated 24-core hybrid CPU) without touching real hardware.

`SpecTuneConfig` overrides apply as the last step in this pipeline (`applyOverrides`,
`resolveTopology`), never inside detection itself — detection functions stay pure and testable,
and override logic stays in one place.

### CPU topology detection (`CpuDetector`)

The hybrid performance/efficiency (P/E) core split is the input that matters most for thread
planning, and detection quality differs sharply by OS:

- **Linux**: exact split via `/sys/devices/cpu_core/cpus` + `cpu_atom/cpus`, falling back to
  `/proc/cpuinfo` parsing (physical/core id pairs) if sysfs isn't present.
- **macOS**: exact split via `sysctl hw.perflevel0/1.physicalcpu`.
- **Windows**: no exact split available without native calls. `CpuDetector.infer()` derives it:
  when SMT is present, `P = logical - physical` is exact arithmetic (only P-cores carry SMT
  siblings on every Intel hybrid part shipped so far); when SMT is absent (Arrow Lake and later),
  a documented core-count ladder (`ladderPerformanceCores`) is used and the result is flagged via
  `CpuTopology.Source.splitIsExact() == false`. This inferred/exact distinction propagates all the
  way to `JvmAdvisor`'s report output — don't silently treat an inferred split as certain.

`SpecTuneConfig.resolveTopology()` caches the detected topology in `spectune.properties` and
reuses it across launches, invalidating only when the live logical-core count no longer matches
(different machine or firmware core toggle). This exists specifically because Windows detection
shells out to PowerShell/wmic, which costs real startup time — don't reintroduce an unconditional
probe on the pre-launch path.

### The pre-launch constraint

`SpecTunePreLaunch` (Fabric's `preLaunch` entrypoint) is the *only* point in the process lifecycle
where `max.bg.threads` can still change Minecraft's behavior — the shared worker pool it controls
is built in a static initializer the first time a related class loads, so anything after that is
too late. It also respects a `-Dmax.bg.threads=...` already set on the command line rather than
overriding a deliberate user choice — check `System.getProperty` before setting.

### Thread priority tuning (no core affinity available)

Java has no portable core-affinity API, so `ThreadTuner` (fabric) uses `ThreadClassifier.classify()`
(core, name-substring based) to assign OS thread *priority* instead — both Windows and Linux
schedulers weight priority when placing threads onto specific cores, which is the only portable
lever available for keeping render/server threads off E-cores. `ThreadTuner` re-scans live threads
periodically (`sweep()`, every 20s by default) because Minecraft and Sodium create worker pools
lazily well after startup, not just once at init.

### GPU-dependent replanning

`TuningPlanner.plan()` is called twice in the Fabric mod's lifecycle: once at `bootstrap()` with
`gpu = null` (before any GL context exists, for thread planning only), and again from
`SpecTuneClient.attachGpu()` once the client has started and `GL11.glGetString` can be read. Video
planning depends on GPU tier; thread planning doesn't. Don't assume `SpecTune.gpu()` is non-null
outside client-side, post-`CLIENT_STARTED` code paths.

### GPU tier classification is naming-convention-based, and vendors break it

`GpuInfo.classify()` infers `Tier` from patterns in the OpenGL renderer string (`RTX \d{4}`,
`RX \d{4}`, Arc's `[ab]\d{3}`), not from any capability database, so it silently misclassifies any
GPU whose model-number convention doesn't match what earlier entries in the same vendor line used.
This already happened once: AMD's RDNA4 RX 9000 series dropped the third suffix digit every prior
generation used (RX 7900/6800 vs. RX 9070), which made a straight `% 1000 >= 700` comparison
undertier real cards until `classify()` special-cased suffixes below 100. When a new GPU generation
ships, check its renderer string against `classify()` directly rather than assuming the existing
regexes cover it — a wrong tier changes render distance, FPS cap, and particle/cloud settings, not
just a cosmetic label.

### Config philosophy (`SpecTuneConfig`)

Every inferred/detected value is overridable via `spectune.properties`, and `0`/absent always means
"derive it" rather than a real override — see the per-key defaults table in `README.md`. Video
settings default to `ApplyMode.ONCE`: applied on first launch, then left alone so the mod never
fights the player's own later changes to `options.txt`. Don't change this default without strong
justification — it's a deliberate choice to avoid being an annoying mod that resets settings.
