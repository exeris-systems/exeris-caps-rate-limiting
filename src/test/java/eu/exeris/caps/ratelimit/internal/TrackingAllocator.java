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
 * Wraps a real allocator and counts what it hands out: allocations, requested sizes, final releases,
 * and every {@code close()} call per buffer (the kernel's close is idempotent, so only a wrapper can see
 * a redundant one).
 */
public final class TrackingAllocator implements MemoryAllocator {

    private final MemoryAllocator delegate;
    private final int capacityShortfall;
    public final List<CountingBuffer> buffers = new ArrayList<>();
    public final List<Integer> requested = new ArrayList<>();
    public final AtomicInteger finalReleases = new AtomicInteger();

    public TrackingAllocator(MemoryAllocator delegate) {
        this(delegate, 0);
    }

    /** @param capacityShortfall bytes by which every returned buffer reports less capacity than requested */
    public TrackingAllocator(MemoryAllocator delegate, int capacityShortfall) {
        this.delegate = delegate;
        this.capacityShortfall = capacityShortfall;
    }

    public int allocations() {
        return buffers.size();
    }

    public int outstanding() {
        return buffers.size() - finalReleases.get();
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        requested.add(estimatedBytes);
        LoanedBuffer real = delegate.allocateNetwork(estimatedBytes);
        CountingBuffer counted = new CountingBuffer(real, capacityShortfall == 0 ? -1 : estimatedBytes - capacityShortfall);
        real.addCloseAction(finalReleases::incrementAndGet);
        buffers.add(counted);
        return counted;
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        throw new AssertionError("responses must be allocated with allocateNetwork");
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int bytes) {
        throw new AssertionError("responses must be allocated with allocateNetwork");
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long bytes) {
        throw new AssertionError("responses must be allocated with allocateNetwork");
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
