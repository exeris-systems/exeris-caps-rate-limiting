package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a real allocator and counts what it hands out: allocations, requested hints, final releases,
 * and every {@code close()} call per buffer (the kernel's close is idempotent, so only a wrapper can see
 * a redundant one). Only {@code allocate(AllocationHint)} is served; every other entry point fails the
 * calling test, because a response body is allocated by hint. Optionally the whole capacity of every
 * buffer is pre-filled with a non-zero pattern, so a write that relies on zeroed memory is visible.
 */
public final class TrackingAllocator implements MemoryAllocator {

    private final MemoryAllocator delegate;
    private final long reportedCapacity;
    private final int prefill;
    public final List<CountingBuffer> buffers = new ArrayList<>();
    public final List<AllocationHint> hints = new ArrayList<>();
    public final AtomicInteger networkCalls = new AtomicInteger();
    public final AtomicInteger finalReleases = new AtomicInteger();

    public TrackingAllocator(MemoryAllocator delegate) {
        this(delegate, -1L, -1);
    }

    /**
     * @param reportedCapacity capacity every returned buffer reports, or a negative value for the real one
     * @param prefill byte written over the whole real capacity of every buffer, or a negative value for none
     */
    private TrackingAllocator(MemoryAllocator delegate, long reportedCapacity, int prefill) {
        this.delegate = delegate;
        this.reportedCapacity = reportedCapacity;
        this.prefill = prefill;
    }

    /** Every returned buffer reports {@code capacity} instead of its real capacity. */
    public static TrackingAllocator reportingCapacity(MemoryAllocator delegate, long capacity) {
        return new TrackingAllocator(delegate, capacity, -1);
    }

    /** Every returned buffer has its whole capacity filled with {@code pattern} before it is handed out. */
    public static TrackingAllocator prefilled(MemoryAllocator delegate, byte pattern) {
        return new TrackingAllocator(delegate, -1L, pattern & 0xFF);
    }

    public int allocations() {
        return buffers.size();
    }

    public int outstanding() {
        return buffers.size() - finalReleases.get();
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        networkCalls.incrementAndGet();
        throw new AssertionError("a response body must be allocated by hint, not with allocateNetwork("
                + estimatedBytes + ")");
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        hints.add(hint);
        LoanedBuffer real = delegate.allocate(hint);
        if (prefill >= 0) {
            real.segment().asSlice(0, real.capacity()).fill((byte) prefill);
        }
        CountingBuffer counted = new CountingBuffer(real, reportedCapacity);
        real.addCloseAction(finalReleases::incrementAndGet);
        buffers.add(counted);
        return counted;
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int bytes) {
        throw new AssertionError("responses must be allocated by hint");
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long bytes) {
        throw new AssertionError("responses must be allocated by hint");
    }

    @Override
    public MemoryStats stats() {
        return delegate.stats();
    }

    @Override
    public void close() {
        // the wrapped allocator is owned by whoever created it
    }

    /** A buffer that delegates to a real one and counts {@code close()} calls. */
    public static final class CountingBuffer implements LoanedBuffer {
        private final LoanedBuffer delegate;
        private final long reportedCapacity;
        public final AtomicInteger closeCalls = new AtomicInteger();

        CountingBuffer(LoanedBuffer delegate, long reportedCapacity) {
            this.delegate = delegate;
            this.reportedCapacity = reportedCapacity;
        }

        @Override
        public MemorySegment segment() {
            return delegate.segment();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public long capacity() {
            return reportedCapacity >= 0 ? reportedCapacity : delegate.capacity();
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return delegate.slice(offset, length);
        }

        @Override
        public LoanedBuffer view() {
            return delegate.view();
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return delegate.peek(offset, length);
        }

        @Override
        public void retain() {
            delegate.retain();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            delegate.close();
        }

        @Override
        public int refCount() {
            return delegate.refCount();
        }

        @Override
        public void setSize(long size) {
            delegate.setSize(size);
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public void addCloseAction(Runnable action) {
            delegate.addCloseAction(action);
        }
    }
}
