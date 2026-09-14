# JVM arguments

## The line to paste

For 64 GB of RAM, Java 21, a hybrid CPU with 20+ cores:

```
-Xms12G -Xmx12G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+ZGenerational -XX:SoftMaxHeapSize=10G -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseStringDeduplication -XX:+PerfDisableSharedMem -Dmax.bg.threads=16
```

On Java 24 or newer, drop `-XX:+ZGenerational` — generational mode became the default and the flag
was removed.

For vanilla or a light modpack, `-Xms8G -Xmx8G -XX:SoftMaxHeapSize=6G` is enough. Run
`/spectune args` in-game, or the core jar from a terminal, to get the line computed for your actual
machine rather than the assumed one.

### Where to put it

| Launcher | Location |
|---|---|
| Vanilla launcher | Installations → Edit → More Options → JVM Arguments |
| Prism / MultiMC | Edit Instance → Settings → Java → JVM arguments |
| Modrinth App | Settings → Java & Memory, or per-profile |

Replace the whole existing line. The default `-Xmx2G ...` is not worth keeping any of.

## What each flag is for

**`-Xms12G -Xmx12G`** — heap floor and ceiling, set equal. If they differ the JVM grows and shrinks
the heap during play, and each resize is a stall. Twelve is chosen deliberately: it is well under the
32 GB threshold where HotSpot drops compressed object pointers and every reference in the heap widens
from four to eight bytes. Giving Minecraft more memory past that point makes collection slower, not
the game faster.

**`-XX:+UseZGC -XX:+ZGenerational`** — the collector. Minecraft's allocation profile is a flood of
very short-lived objects, which is exactly what a generational collector handles well. ZGC does its
marking and relocation concurrently with the application, so its pauses stay in the tens of
microseconds regardless of heap size, where G1's are tens of milliseconds — long enough to be a
visible frame drop. This needs Java 21 or newer, and it needs spare cores to run concurrently on,
which a 20-core laptop has.

**`-XX:SoftMaxHeapSize=10G`** — tells ZGC to aim to stay under this, collecting a little more eagerly
rather than letting the heap drift up to the hard ceiling. The gap to `-Xmx` is headroom for
allocation spikes when a world loads.

**`-XX:+AlwaysPreTouch`** — commits and touches every page of the heap at start-up instead of
faulting them in during play. Costs a few seconds of launch time and removes page-fault hitches in
the first minutes of a session. Only worth it when you have RAM to spare; with 64 GB you do.

**`-XX:+DisableExplicitGC`** — makes `System.gc()` a no-op. Some mods and libraries call it, and each
call is a full collection nobody asked for.

**`-XX:+UseStringDeduplication`** — Minecraft holds an enormous number of duplicate strings
(identifiers, NBT keys, translation keys). The collector merges their backing arrays during marking,
which is close to free on ZGC and saves real memory in a large pack.

**`-XX:+PerfDisableSharedMem`** — stops the JVM writing its performance counters to a memory-mapped
file in `/tmp` (or `%TEMP%`). That file's writes can block on disk I/O, which occasionally shows up
as a stall for no visible reason. Nothing you use reads those counters.

**`-Dmax.bg.threads=16`** — the shared worker pool cap. SpecTune sets this itself at pre-launch, so
passing it here is belt and braces; it is useful if you want to run a different number from the one
SpecTune computes, because a value on the command line always wins.

## Flags people recommend that you should not use

**Aikar's flags.** Written for G1 on Minecraft *servers*, in the Java 8 era, and tuned for a very
different allocation pattern. `-XX:+UseG1GC` with `MaxGCPauseMillis=200`, aggressive region sizing
and `G1NewSizePercent=30` on a client heap is worse than either modern default. They are not wrong,
they are old.

**`-XX:+UseLargePages`.** Genuine gains on Linux with hugepages configured; on Windows it needs the
"Lock pages in memory" privilege and silently does nothing without it, which is how most people run
it.

**Anything setting `-Xmx` above 16 GB for a client.** See above — this is the most common and most
counterproductive piece of advice in Minecraft performance threads.

**`-XX:+AggressiveOpts`.** Removed in Java 12. If a guide still lists it, the guide predates
everything else on this page.
