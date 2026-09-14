# Tuning a Core Ultra 9 / 64 GB / RTX 5080 laptop for Minecraft

Ordered by how much each item is worth. The first three are worth more than every mod in this
repository combined, and none of them are things a mod can do for you.

## 1. Make sure the game is on the discrete GPU

On a laptop, Windows decides per-executable which GPU a process gets. Java launchers make this worse
than usual: the executable Windows sees is `javaw.exe` inside a runtime directory, not
`minecraft.exe`, and a launcher update can change that path, silently reverting the game to the
integrated GPU. Symptoms are 60–120 FPS where you expected 400+, and the F3 screen naming Intel
graphics.

Check the right-hand column of F3, or run `/spectune`. To fix it:

1. Find the exact runtime your launcher uses. In the vanilla launcher it is under
   `%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\...\bin\javaw.exe`;
   Prism and MultiMC show the path in their Java settings.
2. NVIDIA app → Settings → Graphics → Program Settings → add that `javaw.exe` → set to
   *High-performance NVIDIA processor*.
3. Windows Settings → System → Display → Graphics → add the same executable → *High performance*.

Do both. They are separate mechanisms and either one alone can be overridden.

If the laptop has a MUX switch or Advanced Optimus, setting the display to discrete-only in the
vendor utility removes the problem permanently, at the cost of battery life.

## 2. Fix the JVM arguments

See [JVM_ARGS.md](JVM_ARGS.md) for the line to paste and why each flag is there. Two things matter
more than the rest:

**Do not give Minecraft 32 GB because you have 64 GB.** Above 32 GB the JVM stops compressing object
pointers, so every reference in the heap widens from four bytes to eight. The heap gets bigger, more
bandwidth is needed to walk it, and collection takes longer — for a game that does not need the
space. 8–12 GB is right for a heavy modpack; vanilla is happy with 4.

**The RAM you do not allocate is doing useful work.** Everything left over becomes OS page cache,
which holds your world's region files in memory. That is what removes chunk-loading hitches, and it
is a better use of 50 GB than a heap the collector has to traverse.

## 3. Stop Windows from parking the game on efficiency cores

Arrow Lake and Meteor Lake laptops ship with power settings that actively move work to the E-cores.
For a game that is one hot render thread plus one hot tick thread, that is the wrong trade.

- Power mode: *Best performance*, plugged in. On battery the CPU will not sustain clocks and no
  amount of tuning changes that.
- Vendor control panel (Lenovo Vantage, Armoury Crate, MSI Center): set the performance profile to
  the highest one. These override the Windows setting.
- Leave the E-cores enabled. They are where SpecTune sends chunk generation; disabling them in the
  BIOS makes world loading slower, not faster.

SpecTune's thread priority policy is the software half of this. It cannot pin threads to cores —
Java has no portable affinity API — but priority is an input to the scheduler's placement decision
on both Windows and Linux, so widening the gap between the render thread and the chunk workers
measurably reduces frame-time variance on hybrid parts.

## 4. Install the mods that actually rewrite the engine

SpecTune tunes; it does not rewrite. These do, and they are worth more:

| Mod | What it fixes |
|---|---|
| [Sodium](https://modrinth.com/mod/sodium) | The renderer. The single biggest FPS gain available. |
| [Lithium](https://modrinth.com/mod/lithium) | Server-side tick logic; raises TPS headroom. |
| [FerriteCore](https://modrinth.com/mod/ferritecore) | Memory usage of block states and models. |
| [ModernFix](https://modrinth.com/mod/modernfix) | Start-up time and memory, especially with large packs. |
| [ImmediatelyFast](https://modrinth.com/mod/immediatelyfast) | Immediate-mode rendering: GUIs, text, particles. |
| [Entity Culling](https://modrinth.com/mod/entityculling) | Skips entities you cannot see. |

Install those first. SpecTune is complementary: it sets the knobs none of them look at.

### Sodium settings worth changing on this machine

- **Chunk update threads**: the count SpecTune suggests in its report (roughly P-cores plus half the
  E-cores). Sodium's default is conservative.
- **Persistent mapping**: on. Requires a modern driver, which you have.
- **GPU fence sync / render-ahead limit**: 2–3. Lower means less input latency, higher means smoother
  frame pacing. With a high-refresh laptop panel, 2 is usually right.

## 5. Video settings, and which ones are actually expensive

SpecTune applies these automatically on first launch. The reasoning:

**Render distance** is GPU and memory bandwidth. A laptop 5080 handles 24 chunks comfortably in
vanilla; with shaders, drop to 16.

**Simulation distance** is CPU, and it is the expensive one. It controls how far the server ticks
entities, redstone and block updates — work that lands on a single thread no matter how many cores
you have. 12 is the practical ceiling for smooth ticks; raising it is the most common cause of
"my FPS is fine but the game feels laggy".

**VSync** off, framerate unlimited, *if* the panel supports G-Sync or VRR. If it does not, cap the
framerate two or three frames below the refresh rate instead — an uncapped renderer on a fixed-rate
panel produces tearing and worse frame pacing, not a smoother picture.

**Biome blend** above 5×5 costs chunk rebuild time for a visual difference you will not see in
motion. SpecTune leaves it at the vanilla 5×5.

**Mipmap levels** at 4 costs almost nothing on this GPU and removes texture shimmer at distance.

**Entity shadows** and **fancy clouds** are cheap here; on a weaker GPU tier SpecTune turns both
down.

## 6. Verify, do not assume

Run `/spectune` after any change. The report writes to `config/spectune-report.txt` and includes the
detected topology, the applied thread counts, the collector actually in use, and every finding.

For frame-time work specifically, F3 gives you the frame graph — watch the *variance*, not the
average. A steady 200 FPS feels better than a 400 FPS average with spikes to 40, and every item on
this page targets variance rather than peak numbers.
