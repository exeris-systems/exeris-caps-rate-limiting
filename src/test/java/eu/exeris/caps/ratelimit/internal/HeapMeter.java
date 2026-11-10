package eu.exeris.caps.ratelimit.internal;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-thread heap allocation measurement shared by the allocation tests.
 *
 * <p>Invariant of every figure returned here: it is the MINIMUM over several windows. What the JVM
 * allocates on a path is a fixed cost plus noise, and the noise (a GC safepoint, a deoptimisation, a
 * profiling or sampling allocation, a tier change) only ever adds bytes, so the smallest window is the
 * stable estimator of the fixed cost and a mean or median is not. When two paths are compared their
 * windows are interleaved, so a slow drift in the JVM's state affects both alike instead of one.
 *
 * <p>The figure counts the calling thread only, so allocation by other threads (JFR itself, the
 * compiler, the GC) never enters it.
 */
final class HeapMeter {

    private HeapMeter() {
    }

    /**
     * Smallest bytes-per-operation of each path over {@code rounds} windows of {@code ops} operations,
     * after {@code warmup} operations of every path. The windows of the paths alternate within a round.
     */
    static double[] interleavedMinima(int warmup, int ops, int rounds, Runnable... paths) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        long thread = Thread.currentThread().threadId();
        for (Runnable path : paths) {
            for (int i = 0; i < warmup; i++) {
                path.run();
            }
        }
        double[] best = new double[paths.length];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int round = 0; round < rounds; round++) {
            for (int p = 0; p < paths.length; p++) {
                long before = bean.getThreadAllocatedBytes(thread);
                for (int i = 0; i < ops; i++) {
                    paths[p].run();
                }
                best[p] = Math.min(best[p], (bean.getThreadAllocatedBytes(thread) - before) / (double) ops);
            }
        }
        return best;
    }
}
