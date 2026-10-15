package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A heap-backed {@link LoanedBuffer} for the small response bodies this capability writes itself.
 *
 * @since 0.1.0
 */
public final class HeapLoanedBuffer implements LoanedBuffer {

    private final MemorySegment segment;
    private final long capacity;
    private final AtomicInteger refCount = new AtomicInteger(1);
    private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();
    private volatile long size;

    public HeapLoanedBuffer(byte[] bytes) {
        this(MemorySegment.ofArray(Objects.requireNonNull(bytes, "bytes").clone()));
    }

    private HeapLoanedBuffer(MemorySegment segment) {
        this.segment = segment;
        this.capacity = segment.byteSize();
        this.size = segment.byteSize();
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public LoanedBuffer slice(long offset, long length) {
        return new HeapLoanedBuffer(segment.asSlice(offset, length));
    }

    @Override
    public LoanedBuffer view() {
        return slice(0, size);
    }

    @Override
    public LoanedBuffer peek(long offset, long length) {
        return slice(offset, length);
    }

    @Override
    public void retain() {
        refCount.incrementAndGet();
    }

    @Override
    public void close() {
        if (refCount.decrementAndGet() == 0) {
            for (Runnable action : closeActions) {
                action.run();
            }
        }
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public void setSize(long newSize) {
        if (newSize < 0 || newSize > capacity) {
            throw new IllegalArgumentException("size out of range");
        }
        this.size = newSize;
    }

    @Override
    public boolean isAlive() {
        return refCount.get() > 0;
    }

    @Override
    public void addCloseAction(Runnable action) {
        closeActions.add(Objects.requireNonNull(action, "action"));
    }
}
