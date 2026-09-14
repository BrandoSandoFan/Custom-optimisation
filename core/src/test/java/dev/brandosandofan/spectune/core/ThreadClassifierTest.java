package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.brandosandofan.spectune.core.ThreadClassifier.Kind;
import dev.brandosandofan.spectune.core.TuningPlan.ThreadPlan;
import org.junit.jupiter.api.Test;

class ThreadClassifierTest {

    private static final ThreadPlan PLAN = new ThreadPlan(16, 12, 8, 3, 2, true);

    @Test
    void classifiesTheThreadsMinecraftActuallyCreates() {
        assertEquals(Kind.FOREGROUND, ThreadClassifier.classify("Render thread"));
        assertEquals(Kind.SERVER, ThreadClassifier.classify("Server thread"));
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Worker-Main-3"));
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Worker-bootstrap-1"));
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Netty Client IO #0"));
        assertEquals(Kind.BACKGROUND, ThreadClassifier.classify("IO-Worker-2"));
        assertEquals(Kind.BACKGROUND, ThreadClassifier.classify("Yggdrasil Key Fetcher"));
    }

    @Test
    void classifiesSodiumsChunkBuilders() {
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Chunk Builder"));
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Sodium Chunk Builder 4"));
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Chunk Render Task Executor"));
    }

    @Test
    void workerRulesWinOverTheForegroundOnes() {
        // The trap: a naive "main" match would promote all sixteen chunk workers.
        assertEquals(Kind.WORKER, ThreadClassifier.classify("Worker-Main-12"));
        assertEquals(PLAN.workerPriority(), ThreadClassifier.priorityFor("Worker-Main-12", PLAN));
    }

    @Test
    void leavesUnknownThreadsAlone() {
        assertEquals(Kind.UNMANAGED, ThreadClassifier.classify("Some Other Mod's Thread"));
        assertEquals(-1, ThreadClassifier.priorityFor("Some Other Mod's Thread", PLAN));
        assertEquals(-1, ThreadClassifier.priorityFor(null, PLAN));
        assertEquals(-1, ThreadClassifier.priorityFor("  ", PLAN));
    }

    @Test
    void ordersPrioritiesTheWayFrameTimeNeeds() {
        int render = ThreadClassifier.priorityFor("Render thread", PLAN);
        int server = ThreadClassifier.priorityFor("Server thread", PLAN);
        int worker = ThreadClassifier.priorityFor("Worker-Main-1", PLAN);
        int io = ThreadClassifier.priorityFor("IO-Worker-1", PLAN);

        assertTrue(render > server, "the frame being drawn outranks the tick loop");
        assertTrue(server > worker, "the tick loop outranks chunk work");
        assertTrue(worker > io, "chunk work outranks disk I/O");
        assertTrue(io >= Thread.MIN_PRIORITY && render <= Thread.MAX_PRIORITY);
    }

    @Test
    void staysWithinLegalPriorityRangeEvenWithAnExtremePlan() {
        ThreadPlan extreme = new ThreadPlan(4, 4, Thread.MIN_PRIORITY, Thread.MIN_PRIORITY,
                Thread.MIN_PRIORITY, true);
        int server = ThreadClassifier.priorityFor("Server thread", extreme);
        assertTrue(server >= Thread.MIN_PRIORITY, "priority must never fall below the legal minimum");
    }
}
