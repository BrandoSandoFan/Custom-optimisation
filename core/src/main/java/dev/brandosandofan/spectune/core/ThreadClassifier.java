package dev.brandosandofan.spectune.core;

import java.util.Locale;

/**
 * Maps a thread name to the priority class it belongs in.
 *
 * <p>Minecraft names its threads consistently enough to classify them this way, which avoids having
 * to mixin into every executor factory in the game and keeps the mod working across versions.
 */
public final class ThreadClassifier {

    public enum Kind {
        /** Render and client threads: a stall here is a dropped frame. */
        FOREGROUND,
        /** The integrated server tick loop. */
        SERVER,
        /** Chunk meshing, world generation, light, networking: throughput work. */
        WORKER,
        /** Disk I/O, downloads, housekeeping: latency here is invisible. */
        BACKGROUND,
        /** Not ours to touch. */
        UNMANAGED
    }

    private static final String[] WORKER = {
        "worker-main", "worker-bootstrap", "chunk builder", "chunkbuilder", "sodium",
        "netty", "light thread", "datafixer", "chunk render"
    };

    private static final String[] BACKGROUND = {
        "io-worker", "file io", "downloader", "realms", "yggdrasil", "cleaner",
        "texture downloader", "region file", "profiler"
    };

    private static final String[] SERVER = {"server thread", "integrated server"};

    private static final String[] FOREGROUND = {"render thread", "client thread"};

    private ThreadClassifier() {}

    /**
     * Classifies by name. Order matters: {@code Worker-Main-3} must be caught by the worker rules
     * before anything looking for "main" gets to it.
     */
    public static Kind classify(String rawName) {
        if (rawName == null || rawName.isBlank()) return Kind.UNMANAGED;
        String name = rawName.toLowerCase(Locale.ROOT);
        if (contains(name, WORKER)) return Kind.WORKER;
        if (contains(name, BACKGROUND)) return Kind.BACKGROUND;
        if (contains(name, SERVER)) return Kind.SERVER;
        if (contains(name, FOREGROUND)) return Kind.FOREGROUND;
        return Kind.UNMANAGED;
    }

    /** The priority a thread should run at, or {@code -1} to leave it alone. */
    public static int priorityFor(String threadName, TuningPlan.ThreadPlan plan) {
        return switch (classify(threadName)) {
            case FOREGROUND -> plan.foregroundPriority();
            // The tick loop matters, but never at the expense of the frame being drawn.
            case SERVER -> Math.max(Thread.MIN_PRIORITY, plan.foregroundPriority() - 1);
            case WORKER -> plan.workerPriority();
            case BACKGROUND -> plan.backgroundIoPriority();
            case UNMANAGED -> -1;
        };
    }

    private static boolean contains(String name, String[] needles) {
        for (String needle : needles) {
            if (name.contains(needle)) return true;
        }
        return false;
    }
}
