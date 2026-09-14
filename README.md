# SpecTune

A Fabric mod that sizes Minecraft to the machine it is running on instead of to the machine Mojang
assumed. Written against a specific target: an Intel Core Ultra 9 laptop with 64 GB of DDR5 and a
laptop RTX 5080.

## What it actually does

**Unlocks the worker pool.** Minecraft sizes its shared background executor — chunk generation,
lighting, region I/O — as `min(cores - 1, 7)`. On a 24-core CPU that leaves two thirds of the
machine idle during world loading. SpecTune computes a size from the real core layout and sets
`max.bg.threads` from a pre-launch entrypoint, which is the only moment the pool can still be
influenced (Minecraft builds it in a static initialiser).

**Separates foreground from background threads.** On a hybrid CPU the expensive failure is the
render thread being migrated onto an efficiency core while sixteen chunk workers sit on the
performance cores. Java has no portable core affinity, but Windows and Linux both weigh thread
priority when placing work, so SpecTune promotes the render and server threads and demotes the
worker and I/O pools. Threads are re-scanned every 20 seconds because Minecraft and Sodium create
pools lazily.

**Checks which GPU you are on.** It reads the OpenGL renderer string at client start. If the game
came up on the integrated GPU — the single most common and most expensive laptop misconfiguration —
it says so in the log and in `/spectune`, in red.

**Reviews the JVM and tells you what is wrong with it.** Heap above the compressed-oops threshold,
mismatched `-Xms`/`-Xmx`, a stop-the-world collector, non-generational ZGC, a pre-21 runtime. It
cannot change any of these at runtime, so it reports them and generates the argument line you should
be using. `/spectune args` prints it in-game; `config/spectune-report.txt` holds the full report.

**Applies a video profile matched to the detected GPU tier and RAM.** Once, by default — it records
that it has done so and then leaves your settings alone.

## What it does not do

It does not rewrite the renderer, and it will not beat [Sodium](https://modrinth.com/mod/sodium).
Nothing here duplicates Sodium, Lithium, FerriteCore or ModernFix, and this mod is worth far less
than they are. Install those first; this fills the gap they leave, which is that none of them look
at your particular machine and adjust to it. See [docs/TUNING.md](docs/TUNING.md) for the full
companion list and the laptop-specific setup that matters more than any mod.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 (retarget via `fabric/gradle.properties`) |
| Loader | Fabric |
| Java | 21+ |
| Dependency | Fabric API |

## Building

```bash
cd fabric
./gradlew build          # jar lands in fabric/build/libs/
```

The `core` module builds and tests on its own, with no Minecraft toolchain:

```bash
cd core
gradle test
gradle jar && java -jar build/libs/spectune-core-1.0.0.jar "NVIDIA GeForce RTX 5080 Laptop GPU"
```

That second command is the whole analyser without the game: it profiles the machine, reviews the
JVM and prints the recommended arguments. Useful before you install anything.

## Commands

| Command | Effect |
|---|---|
| `/spectune` | Machine summary and findings |
| `/spectune args` | The JVM argument line for this machine |
| `/spectune apply` | Re-apply the video profile now |

## Configuration

`config/spectune.properties`, written on first launch. Every inferred value can be overridden.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `threads.autoSize` | `true` | Set `max.bg.threads` at pre-launch |
| `threads.backgroundOverride` | `0` | Explicit worker count; `0` means derive it |
| `threads.priorityTuning` | `true` | Run the thread priority sweeper |
| `video.apply` | `once` | `off`, `once`, or `always` |
| `video.renderDistanceOverride` | `0` | Explicit render distance; `0` means derive it |
| `gpu.warnOnIntegrated` | `true` | Shout when rendering on the iGPU |
| `cpu.performanceCores` | `0` | Override the detected P-core count |
| `cpu.efficiencyCores` | `0` | Override the detected E-core count |
| `report.write` | `true` | Write `config/spectune-report.txt` |

A value set on the command line always wins: if you pass `-Dmax.bg.threads=N` yourself, SpecTune
leaves it alone.

## Repository layout

```
core/     Plain Java 21, no Minecraft dependency. Detection, planning, JVM review. Unit-tested.
fabric/   Fabric entrypoints. Applies what core decides. Compiles core's sources into the mod jar.
docs/     Tuning playbook and JVM argument reference.
```

The split is deliberate: every decision lives in `core`, where it can be tested against synthetic
machines without a Minecraft toolchain, and where a NeoForge port would only need the thin adapter
layer reimplemented.

## Caveat on CPU detection

Linux and macOS report the performance/efficiency split exactly. Windows reports core counts but not
the split without native calls, so SpecTune derives it: when SMT is present the arithmetic is exact
(only P-cores are hyper-threaded on Intel hybrid parts, so `P = logical - physical`), and when it is
absent — Arrow Lake and later — a documented ladder is used and the result is flagged as inferred in
the report. If it guesses wrong for your part, set `cpu.performanceCores` and `cpu.efficiencyCores`.

## Licence

MIT.
