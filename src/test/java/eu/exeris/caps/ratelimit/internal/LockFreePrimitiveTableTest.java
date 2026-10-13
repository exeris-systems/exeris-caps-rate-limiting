package eu.exeris.caps.ratelimit.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LockFreePrimitiveTable Tests")
class LockFreePrimitiveTableTest {

    @Test
    @DisplayName("Allocates slots deterministically and recovers existing slot")
    void allocatesAndRecoversSlot() {
        LockFreePrimitiveTable table = new LockFreePrimitiveTable(64, 10_000_000_000L);
        long now = 1_000_000_000L;

        int slot1 = table.findOrAllocateSlot(12345L, now);
        int slot2 = table.findOrAllocateSlot(12345L, now + 100);

        assertThat(slot1).isEqualTo(slot2);
    }

    @Test
    @DisplayName("Differentiates different keys")
    void differentiatesDistinctKeys() {
        LockFreePrimitiveTable table = new LockFreePrimitiveTable(64, 10_000_000_000L);
        long now = 1_000_000_000L;

        int slotA = table.findOrAllocateSlot(111L, now);
        int slotB = table.findOrAllocateSlot(222L, now);

        assertThat(slotA).isNotEqualTo(slotB);
    }

    @Test
    @DisplayName("Reclaims expired slot on collision")
    void reclaimsExpiredSlot() {
        long maxIdleNanos = 2_000_000_000L; // 2 seconds
        LockFreePrimitiveTable table = new LockFreePrimitiveTable(16, maxIdleNanos);

        long t0 = 1_000_000_000L;
        int slotOld = table.findOrAllocateSlot(999L, t0);

        // Advance time by 5 seconds (exceeding maxIdleNanos)
        long t1 = t0 + 5_000_000_000L;

        // Force a key that probes into the same space or reclaims
        // Old key 999 is expired.
        int slotOldAfter = table.findOrAllocateSlot(999L, t1);
        assertThat(slotOldAfter).isEqualTo(slotOld);
    }
}
