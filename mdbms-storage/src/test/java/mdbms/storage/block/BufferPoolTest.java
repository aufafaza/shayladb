package mdbms.storage.block;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.BufferStats;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {
    @TempDir Path directory;
    private RecordingDisk disk;
    private List<String> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        disk = new RecordingDisk(directory, events);
        for (int i = 0; i < 3; i++) disk.appendBlock("users.tbl");
        disk.appendBlock("users.idx");
    }

    @Test
    void pinsShareFramesAndStatsAreSnapshots() {
        BufferPool pool = new BufferPool(disk, 3);
        Block first = pool.pin("users.tbl", 0);
        Block again = pool.pin("users.tbl", 0);
        assertSame(first, again);
        Block index = pool.pin("users.idx", 0);
        assertNotSame(first, index);
        assertEquals(2, disk.reads);
        BufferStats snapshot = pool.getBufferStats();
        assertEquals(new BufferStats(3, 2, 0, 1, 2), snapshot);
        pool.unpin(first, true, 7);
        pool.unpin(again, false, -1);
        pool.unpin(index, false, -1);
        assertEquals(1, pool.getBufferStats().dirtyBlocks());
        assertEquals(0, snapshot.dirtyBlocks());
    }

    @Test
    void lruEvictsTheOldestUnpinnedFrame() {
        BufferPool pool = new BufferPool(disk, 2);
        Block first = pool.pin("users.tbl", 0);
        pool.unpin(first, false, 0);
        Block second = pool.pin("users.tbl", 1);
        pool.unpin(second, false, 0);
        assertSame(first, pool.pin("users.tbl", 0));
        pool.unpin(first, false, 0);
        Block third = pool.pin("users.tbl", 2);
        pool.unpin(third, false, 0);
        assertSame(first, pool.pin("users.tbl", 0));
        assertNotSame(second, pool.pin("users.tbl", 1));
        assertEquals(4, disk.reads);
        assertTrue(events.isEmpty(), "Clean eviction must not write");
    }

    @Test
    void pinnedFramesAreNeverVictims() {
        BufferPool pool = new BufferPool(disk, 2);
        Block first = pool.pin("users.tbl", 0);
        Block second = pool.pin("users.tbl", 1);
        assertCode(StorageErrorCode.BUFFER_FULL, () -> pool.pin("users.tbl", 2));
        pool.unpin(second, false, 0);
        Block third = pool.pin("users.tbl", 2);
        assertSame(first, pool.pin("users.tbl", 0));
        assertCode(StorageErrorCode.INVALID_PIN, () -> pool.unpin(second, false, 0));
        pool.unpin(first, false, 0);
        pool.unpin(first, false, 0);
        pool.unpin(third, false, 0);
    }

    @Test
    void appendChecksCapacityBeforeExtendingFile() {
        BufferPool pool = new BufferPool(disk, 1);
        Block block = pool.appendBlock("new.tbl");
        assertNotNull(block);
        assertEquals(0, block.blockNumber);
        assertArrayEquals(new byte[4096], block.data);
        assertEquals(1, pool.blockCount("new.tbl"));
        assertCode(StorageErrorCode.BUFFER_FULL, () -> pool.appendBlock("new.tbl"));
        assertEquals(1, disk.blockCount("new.tbl"));
        assertEquals(new BufferStats(1, 1, 0, 0, 0), pool.getBufferStats());
        pool.unpin(block, false, 0);
        assertDoesNotThrow(pool::flushBuffer);
    }

    @Test
    void bufferCopiesProtectBothDirectionsAndFlushUsesMaximumLsn() {
        BufferPool pool = new BufferPool(disk, 2);
        pool.attachRecoveryManager(recovery(lsn -> true));
        byte[] input = page(7);
        pool.writeBuffer("users.tbl", 0, input, 12);
        input[0] = 99;
        byte[] copy = pool.readBuffer("users.tbl", 0);
        assertArrayEquals(page(7), copy);
        copy[0] = 66;
        assertArrayEquals(page(7), pool.readBuffer("users.tbl", 0));
        pool.writeBuffer("users.tbl", 0, page(8), 4);
        pool.flushBuffer();
        assertEquals(List.of("wal:12", "write:users.tbl:0"), events);
        assertArrayEquals(page(8), disk.readBlock("users.tbl", 0));
        assertEquals(0, pool.getBufferStats().dirtyBlocks());
        pool.flushBuffer();
        assertEquals(2, events.size());
    }

    @Test
    void flushRejectsPinsBeforeWritingAnyPage() {
        BufferPool pool = new BufferPool(disk, 2);
        pool.attachRecoveryManager(recovery(lsn -> true));
        pool.writeBuffer("users.tbl", 0, page(8), 4);
        Block pinned = pool.pin("users.tbl", 1);
        assertCode(StorageErrorCode.INVALID_PIN, pool::flushBuffer);
        assertTrue(events.isEmpty());
        pool.unpin(pinned, false, 0);
        pool.flushBuffer();
        assertEquals(List.of("wal:4", "write:users.tbl:0"), events);
    }

    @Test
    void evictionAndDeleteFlushWalForTableAndIndexPages() {
        BufferPool pool = new BufferPool(disk, 1);
        pool.attachRecoveryManager(recovery(lsn -> true));
        pool.writeBuffer("users.tbl", 0, page(3), 5);
        pool.writeBuffer("users.idx", 0, page(4), 9);
        assertEquals(List.of("wal:5", "write:users.tbl:0"), events);
        pool.deleteBuffer("users.idx", 0);
        assertEquals(List.of("wal:5", "write:users.tbl:0", "wal:9", "write:users.idx:0"), events);
        assertEquals(0, pool.getBufferStats().usedBlocks());
        assertArrayEquals(page(3), disk.readBlock("users.tbl", 0));
        assertArrayEquals(page(4), disk.readBlock("users.idx", 0));
        assertEquals(1, disk.blockCount("users.idx"));
        pool.deleteBuffer("users.idx", 0);
        assertEquals(4, events.size());
    }

    @Test
    void pinnedDeleteAndInvalidUnpinsPreserveTheFrame() {
        BufferPool pool = new BufferPool(disk, 1);
        Block block = pool.pin("users.tbl", 0);
        assertCode(StorageErrorCode.INVALID_PIN, () -> pool.deleteBuffer("users.tbl", 0));
        assertCode(StorageErrorCode.INVALID_PIN, () -> pool.unpin(null, false, 0));
        assertCode(StorageErrorCode.INVALID_PIN,
                () -> pool.unpin(new Block("users.tbl", 0, new byte[4096]), false, 0));
        assertCode(StorageErrorCode.INVALID_BLOCK, () -> pool.unpin(block, true, -1));
        pool.unpin(block, false, 0);
        assertCode(StorageErrorCode.INVALID_PIN, () -> pool.unpin(block, false, 0));
        assertSame(block, pool.pin("users.tbl", 0));
    }

    @Test
    void walFailurePreservesDirtyBytesUntilRetry() {
        BufferPool pool = new BufferPool(disk, 1);
        pool.writeBuffer("users.tbl", 0, page(3), 17);
        assertCode(StorageErrorCode.WAL_FLUSH_FAILED, pool::flushBuffer);
        pool.attachRecoveryManager(recovery(lsn -> false));
        assertCode(StorageErrorCode.WAL_FLUSH_FAILED, () -> pool.pin("users.tbl", 1));
        RuntimeException cause = new IllegalStateException("WAL unavailable");
        pool.attachRecoveryManager(recovery(lsn -> { throw cause; }));
        StorageException error = assertCode(StorageErrorCode.WAL_FLUSH_FAILED,
                () -> pool.deleteBuffer("users.tbl", 0));
        assertSame(cause, error.getCause());
        assertEquals(List.of("wal:17", "wal:17"), events);
        assertEquals(1, pool.getBufferStats().dirtyBlocks());
        assertArrayEquals(page(3), pool.readBuffer("users.tbl", 0));
        assertArrayEquals(new byte[4096], disk.readBlock("users.tbl", 0));
        pool.attachRecoveryManager(recovery(lsn -> true));
        pool.flushBuffer();
        assertArrayEquals(page(3), disk.readBlock("users.tbl", 0));
        assertEquals(0, pool.getBufferStats().dirtyBlocks());
    }

    @Test
    void failedPageWriteRetainsDirtyFrameForRetry() {
        BufferPool pool = new BufferPool(disk, 1);
        pool.attachRecoveryManager(recovery(lsn -> true));
        pool.writeBuffer("users.tbl", 0, page(3), 7);
        disk.failWrite = true;
        StorageException error = assertCode(StorageErrorCode.IO_FAILURE, () -> pool.pin("users.tbl", 1));
        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(1, pool.getBufferStats().dirtyBlocks());
        assertArrayEquals(page(3), pool.readBuffer("users.tbl", 0));
        disk.failWrite = false;
        pool.deleteBuffer("users.tbl", 0);
        assertArrayEquals(page(3), disk.readBlock("users.tbl", 0));
        assertEquals(List.of("wal:7", "write:users.tbl:0", "wal:7", "write:users.tbl:0"), events);
    }

    @Test
    void partialFlushLeavesFailedAndUnprocessedPagesDirty() {
        BufferPool pool = new BufferPool(disk, 3);
        pool.attachRecoveryManager(recovery(lsn -> lsn != 2));
        for (int i = 0; i < 3; i++) pool.writeBuffer("users.tbl", i, page(i + 1), i + 1);
        assertCode(StorageErrorCode.WAL_FLUSH_FAILED, pool::flushBuffer);
        assertEquals(List.of("wal:1", "write:users.tbl:0", "wal:2"), events);
        assertEquals(2, pool.getBufferStats().dirtyBlocks());
        assertArrayEquals(page(1), disk.readBlock("users.tbl", 0));
        assertArrayEquals(page(0), disk.readBlock("users.tbl", 1));
        assertArrayEquals(page(0), disk.readBlock("users.tbl", 2));
        pool.attachRecoveryManager(recovery(lsn -> true));
        pool.flushBuffer();
        assertEquals(List.of("wal:1", "write:users.tbl:0", "wal:2",
                "wal:2", "write:users.tbl:1", "wal:3", "write:users.tbl:2"), events);
        assertEquals(0, pool.getBufferStats().dirtyBlocks());
        for (int i = 0; i < 3; i++) assertArrayEquals(page(i + 1), disk.readBlock("users.tbl", i));
    }

    @Test
    void failedDirtyEvictionDoesNotAllocateAndExplicitZeroLsnStillFlushesWal() {
        BufferPool pool = new BufferPool(disk, 1);
        pool.writeBuffer("users.tbl", 0, page(3), 0);
        pool.attachRecoveryManager(recovery(lsn -> false));
        assertCode(StorageErrorCode.WAL_FLUSH_FAILED, () -> pool.appendBlock("users.tbl"));
        assertEquals(3, disk.blockCount("users.tbl"));
        assertEquals(1, pool.getBufferStats().dirtyBlocks());
        pool.attachRecoveryManager(recovery(lsn -> true));
        Block appended = pool.appendBlock("users.tbl");
        assertEquals(3, appended.blockNumber);
        pool.unpin(appended, false, 0);
        assertEquals(List.of("wal:0", "wal:0", "write:users.tbl:0"), events);
        assertArrayEquals(page(3), disk.readBlock("users.tbl", 0));
    }

    @Test
    void failedReadsLeaveNoResidentOrPinnedFrame() {
        BufferPool pool = new BufferPool(disk, 1);
        assertCode(StorageErrorCode.IO_FAILURE, () -> pool.readBuffer("missing.tbl", 0));
        assertCode(StorageErrorCode.INVALID_BLOCK, () -> pool.readBuffer("users.tbl", 3));
        assertEquals(new BufferStats(1, 0, 0, 0, 2), pool.getBufferStats());
        assertArrayEquals(page(0), pool.readBuffer("users.tbl", 0));
        assertDoesNotThrow(pool::flushBuffer);
    }

    @Test
    void invalidWritesCannotMutateOrPinPages() {
        assertThrows(StorageException.class, () -> new BufferPool(disk, 0));
        assertThrows(StorageException.class, () -> new BufferPool(disk, -1));
        assertThrows(StorageException.class, () -> new BufferPool(null, 1));
        BufferPool pool = new BufferPool(disk, 1);
        assertThrows(StorageException.class, () -> pool.attachRecoveryManager(null));
        assertThrows(StorageException.class, () -> pool.writeBuffer("users.tbl", 0, page(1), -1));
        assertThrows(StorageException.class, () -> pool.writeBuffer("users.tbl", 0, new byte[3], 1));
        assertThrows(StorageException.class, () -> pool.writeBuffer("users.tbl", 0, null, 1));
        assertThrows(StorageException.class, () -> pool.pin("../users.tbl", 0));
        assertThrows(StorageException.class, () -> pool.pin("users.tbl", -1));
        assertEquals(new BufferStats(1, 0, 0, 0, 0), pool.getBufferStats());
        assertDoesNotThrow(pool::flushBuffer);
    }

    @Test
    void concurrentPinsKeepOneFrameAndBalancedCounts() throws Exception {
        BufferPool pool = new BufferPool(disk, 1);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int j = 0; j < 100; j++) {
                        Block block = pool.pin("users.tbl", 0);
                        pool.unpin(block, false, 0);
                    }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(new BufferStats(1, 1, 0, 399, 1), pool.getBufferStats());
            assertEquals(1, disk.reads);
            assertDoesNotThrow(pool::flushBuffer);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void flushExcludesNewPinsUntilTheWriteCompletes() throws Exception {
        BufferPool pool = new BufferPool(disk, 1);
        CountDownLatch inWal = new CountDownLatch(1);
        CountDownLatch finishWal = new CountDownLatch(1);
        CountDownLatch pinStarted = new CountDownLatch(1);
        pool.attachRecoveryManager(recovery(lsn -> {
            inWal.countDown();
            await(finishWal);
            return true;
        }));
        pool.writeBuffer("users.tbl", 0, page(6), 8);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var flush = executor.submit(pool::flushBuffer);
            assertTrue(inWal.await(5, TimeUnit.SECONDS));
            var pin = executor.submit(() -> {
                pinStarted.countDown();
                Block block = pool.pin("users.tbl", 0);
                assertEquals(List.of("wal:8", "write:users.tbl:0"), events);
                pool.unpin(block, false, 0);
            });
            assertTrue(pinStarted.await(5, TimeUnit.SECONDS));
            finishWal.countDown();
            flush.get(5, TimeUnit.SECONDS);
            pin.get(5, TimeUnit.SECONDS);
        } finally {
            finishWal.countDown();
            executor.shutdownNow();
        }
    }

    private IFailureRecoveryManager recovery(LongPredicate succeeds) {
        return (IFailureRecoveryManager) Proxy.newProxyInstance(IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("flushLogUpTo")) throw new AssertionError(method.getName());
                    long lsn = (long) args[0];
                    events.add("wal:" + lsn);
                    return succeeds.test(lsn);
                });
    }

    private static byte[] page(int value) {
        byte[] data = new byte[4096];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static StorageException assertCode(StorageErrorCode code, org.junit.jupiter.api.function.Executable action) {
        StorageException error = assertThrows(StorageException.class, action);
        assertEquals(code, error.getCode());
        return error;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class RecordingDisk extends BlockManager {
        private final List<String> events;
        private int reads;
        private boolean failWrite;

        RecordingDisk(Path directory, List<String> events) {
            super(directory.toString());
            this.events = events;
        }

        @Override
        public byte[] readBlock(String fileName, int blockNumber) {
            reads++;
            return super.readBlock(fileName, blockNumber);
        }

        @Override
        public void writeBlock(String fileName, int blockNumber, byte[] data) {
            events.add("write:" + fileName + ":" + blockNumber);
            if (failWrite) {
                throw new StorageException(StorageErrorCode.IO_FAILURE, "Injected write failure", new IOException("Disk failed"));
            }
            super.writeBlock(fileName, blockNumber, data);
        }
    }
}
