package dev.quicksilver.render;

import dev.quicksilver.Quicksilver;
import dev.quicksilver.mixin.render.TessellatorAccessor;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.render.Tessellator;

/** Pool of mesh building threads, each owning a private tessellator. */
public final class MeshWorkers {
    private MeshWorkers() {}

    public static final PriorityBlockingQueue<BuildTask> QUEUE = new PriorityBlockingQueue<>();
    public static final Queue<BuildResult> RESULTS = new ConcurrentLinkedQueue<>();
    public static final AtomicInteger BUSY = new AtomicInteger();
    private static volatile int generation;
    private static Worker[] workers;

    public static final class Worker extends Thread {
        final Tessellator tessellator = TessellatorAccessor.qs$create(262144);
        final MeshCapture capture = new MeshCapture();

        Worker(int i) {
            super("Quicksilver Mesh Worker " + i);
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            while (true) {
                BuildTask task;
                try {
                    task = QUEUE.take();
                } catch (InterruptedException e) {
                    return;
                }
                if (task.generation != generation) continue;
                BUSY.incrementAndGet();
                try {
                    BuildResult r;
                    try {
                        r = SectionMesher.build(task, tessellator, capture);
                    } catch (Throwable t) {
                        r = new BuildResult(task);
                        r.error = t;
                        resetTessellator();
                    }
                    RESULTS.add(r);
                } finally {
                    BUSY.decrementAndGet();
                }
            }
        }

        private void resetTessellator() {
            QsTessellator q = (QsTessellator) tessellator;
            q.qs$setCapture(capture);
            try {
                tessellator.draw();
            } catch (Throwable ignored) {
            }
            q.qs$setCapture(null);
            tessellator.setOffset(0, 0, 0);
        }
    }

    public static synchronized void ensureStarted() {
        if (workers != null) return;
        int n = Quicksilver.workerThreads();
        workers = new Worker[n];
        for (int i = 0; i < n; i++) {
            workers[i] = new Worker(i);
            workers[i].start();
        }
        Quicksilver.LOGGER.info("Started {} chunk mesh worker threads", n);
    }

    public static int threadCount() {
        return workers == null ? 0 : workers.length;
    }

    /** Invalidate all queued work (world change / renderer reload). */
    public static void newGeneration() {
        generation++;
        QUEUE.clear();
        RESULTS.clear();
    }

    public static int generation() {
        return generation;
    }

    /** The tessellator block rendering should use on the current thread. */
    public static Tessellator tessellator() {
        Thread t = Thread.currentThread();
        if (t instanceof Worker w) return w.tessellator;
        return Tessellator.INSTANCE;
    }
}
