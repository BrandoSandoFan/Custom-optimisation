package dev.brandosandofan.spectune.fabric;

import dev.brandosandofan.spectune.core.ThreadClassifier;
import dev.brandosandofan.spectune.core.TuningPlan;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps latency-sensitive threads above throughput threads in the OS scheduler's eyes.
 *
 * <p>On a hybrid CPU the expensive failure mode is the render thread being migrated onto an
 * efficiency core for a few frames while sixteen chunk workers occupy the performance cores. Java
 * offers no portable core affinity, but the Windows and Linux schedulers both take thread priority
 * into account when placing work, so demoting the worker pools and promoting the render and server
 * threads is the portable lever that exists.
 *
 * <p>Threads are re-scanned periodically because Minecraft and Sodium create pools lazily, long
 * after start-up.
 */
public final class ThreadTuner {

    private static final long INITIAL_DELAY_SECONDS = 5;
    private static final long INTERVAL_SECONDS = 20;

    private final TuningPlan.ThreadPlan plan;
    /** Weakly held so finished threads do not pin their stacks alive. */
    private final Set<Thread> adjusted = Collections.newSetFromMap(new WeakHashMap<>());
    private ScheduledExecutorService scheduler;

    public ThreadTuner(TuningPlan.ThreadPlan plan) {
        this.plan = plan;
    }

    public synchronized void start() {
        if (!plan.priorityTuningEnabled() || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpecTune Thread Tuner");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        SpecTune.LOGGER.info("Thread priority tuning active (foreground {}, worker {}, I/O {}).",
                plan.foregroundPriority(), plan.workerPriority(), plan.backgroundIoPriority());
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void sweepQuietly() {
        try {
            int changed = sweep();
            if (changed > 0) {
                SpecTune.LOGGER.debug("Re-prioritised {} thread(s).", changed);
            }
        } catch (RuntimeException e) {
            SpecTune.LOGGER.debug("Thread sweep failed", e);
        }
    }

    /** Returns how many threads were re-prioritised in this pass. */
    public int sweep() {
        int changed = 0;
        for (Thread thread : liveThreads()) {
            if (thread == null) continue;
            synchronized (adjusted) {
                if (!adjusted.add(thread)) continue;
            }
            int priority = ThreadClassifier.priorityFor(thread.getName(), plan);
            if (priority < 0 || thread.getPriority() == priority) continue;
            try {
                thread.setPriority(priority);
                changed++;
            } catch (IllegalArgumentException | SecurityException e) {
                // A thread group can cap priority below what we asked for; not worth failing over.
            }
        }
        return changed;
    }

    /** Snapshot of every live thread, walked from the root thread group. */
    static Thread[] liveThreads() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        for (ThreadGroup parent = group.getParent(); parent != null; parent = parent.getParent()) {
            group = parent;
        }
        Thread[] threads = new Thread[Math.max(16, group.activeCount() * 2)];
        int count = group.enumerate(threads, true);
        // enumerate() silently truncates when the array is too small; grow until it does not.
        while (count == threads.length) {
            threads = new Thread[threads.length * 2];
            count = group.enumerate(threads, true);
        }
        Thread[] result = new Thread[count];
        System.arraycopy(threads, 0, result, 0, count);
        return result;
    }

    public int adjustedCount() {
        synchronized (adjusted) {
            return adjusted.size();
        }
    }
}
