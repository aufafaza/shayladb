package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;
import mdbms.models.data.RowId;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;

// test RidBucket langsung di atas byte block tanpa BufferPool
class RidBucketTest {

    // bikin bucket baru di block kosong dengan rantai yang udah diputus
    private static RidBucket emptyBucket(Block block) {
        RidBucket bucket = new RidBucket(block, "users");
        bucket.setNext(RidBucket.NO_NEXT);
        return bucket;
    }

    // block kosong ukuran standar
    private static Block newBlock() {
        return new Block("idx.idx", 3, new byte[BlockManager.BLOCK_SIZE]);
    }

    // bucket baru belum punya isi dan sambungan
    @Test
    void newBucketIsEmpty() {
        RidBucket bucket = emptyBucket(newBlock());

        assertTrue(bucket.isEmpty());
        assertFalse(bucket.isFull());
        assertEquals(0, bucket.count());
        assertEquals(OptionalInt.empty(), bucket.getNext());
        assertTrue(bucket.rowIds().isEmpty());
    }

    // RowId yang sama cuma masuk sekali
    @Test
    void addKeepsRowIdsAndIgnoresDuplicates() {
        RidBucket bucket = emptyBucket(newBlock());

        assertTrue(bucket.add(new RowId("users", 0, 1)));
        assertTrue(bucket.add(new RowId("users", 2, 5)));
        assertFalse(bucket.add(new RowId("users", 0, 1)));

        assertEquals(List.of(new RowId("users", 0, 1), new RowId("users", 2, 5)), bucket.rowIds());
    }

    // hapus di tengah mindahin entri terakhir ke situ
    @Test
    void removeFillsGapWithLastEntry() {
        RidBucket bucket = emptyBucket(newBlock());
        bucket.add(new RowId("users", 0, 0));
        bucket.add(new RowId("users", 0, 1));
        bucket.add(new RowId("users", 0, 2));

        assertTrue(bucket.remove(new RowId("users", 0, 0)));
        assertFalse(bucket.remove(new RowId("users", 9, 9)));

        assertEquals(List.of(new RowId("users", 0, 2), new RowId("users", 0, 1)), bucket.rowIds());
    }

    // isi bucket tetap kebaca dari byte yang sama
    @Test
    void contentSurvivesRewrapping() {
        Block block = newBlock();
        RidBucket bucket = emptyBucket(block);
        bucket.add(new RowId("users", 7, 3));
        bucket.setNext(12);

        RidBucket reopened = new RidBucket(block, "users");

        assertEquals(List.of(new RowId("users", 7, 3)), reopened.rowIds());
        assertEquals(OptionalInt.of(12), reopened.getNext());
    }

    // penuh di 511 entri dan nambah lagi ditolak
    @Test
    void fullBucketRejectsMore() {
        RidBucket bucket = emptyBucket(newBlock());
        for (int i = 0; i < 511; i++) {
            bucket.add(new RowId("users", i, 0));
        }

        assertTrue(bucket.isFull());
        StorageException e = assertThrows(StorageException.class, () -> bucket.add(new RowId("users", 999, 0)));
        assertEquals(StorageErrorCode.INVALID_BLOCK, e.getCode());
    }

    // RowId tabel lain ditolak
    @Test
    void rejectsRowIdOfOtherTable() {
        RidBucket bucket = emptyBucket(newBlock());

        StorageException e = assertThrows(StorageException.class, () -> bucket.add(new RowId("orders", 0, 0)));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
    }

    // count rusak dan next negatif ditolak
    @Test
    void rejectsCorruptBlock() {
        Block block = newBlock();
        ByteBuffer.wrap(block.data).putInt(0, 600);

        assertThrows(StorageException.class, () -> new RidBucket(block, "users"));
        assertThrows(StorageException.class, () -> emptyBucket(newBlock()).setNext(-5));
    }
}
