package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;

class SlottedPageTest {
    // memastikan beberapa baris bisa dipulihkan dari halaman
    @Test
    void restoresMultipleSerializedRows() {
        TableSchema schema = new TableSchema("people", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 20, false)),
                List.of(), List.of());
        BinaryRecordSerializer serializer = new BinaryRecordSerializer();
        SlottedPage page = new SlottedPage();
        byte[] alice = serializer.serialize(List.of(1, "Alice"), schema);
        byte[] bob = serializer.serialize(List.of(2, "Bob"), schema);
        int aliceSlot = page.reserveSlot(alice.length);
        int bobSlot = page.reserveSlot(bob.length);
        page.writeSlot(aliceSlot, alice);
        page.writeSlot(bobSlot, bob);

        SlottedPage restored = new SlottedPage(page.toBytes());
        assertNotEquals(aliceSlot, bobSlot);
        assertEquals(List.of(1, "Alice"), serializer.deserialize(restored.readRecord(aliceSlot), schema));
        assertEquals(List.of(2, "Bob"), serializer.deserialize(restored.readRecord(bobSlot), schema));
    }

    // memastikan banyak record dan slot tetap sama setelah dipulihkan
    @Test
    void storesMultipleRecordsAndRestoresSlots() {
        SlottedPage page = new SlottedPage();
        byte[] first = {1, 2, 3};
        int a = page.reserveSlot(first.length);
        int b = page.reserveSlot(2);
        page.writeSlot(a, first);
        page.writeSlot(b, new byte[] {4, 5});
        first[0] = 99;
        byte[] returned = page.readRecord(a);
        returned[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, page.readRecord(a));
        assertEquals(0, a);
        assertEquals(1, b);
        SlottedPage restored = new SlottedPage(page.toBytes());
        assertArrayEquals(new byte[] {1, 2, 3}, restored.readRecord(a));
        assertArrayEquals(new byte[] {4, 5}, restored.readRecord(b));
        assertEquals(page.slotCount(), restored.slotCount());
        assertTrue(restored.isOccupied(a));
        assertTrue(restored.isOccupied(b));
        assertArrayEquals(page.toBytes(), restored.toBytes());
    }

    // memastikan slot terhapus tidak dipakai ulang dan data lama tetap aman
    @Test
    void deletionDoesNotReuseSlotAndFailedWritePreservesRecord() {
        SlottedPage page = new SlottedPage();
        int a = page.reserveSlot(2);
        page.writeSlot(a, new byte[] {7, 8});
        assertEquals(StorageErrorCode.SLOT_UNAVAILABLE,
                assertThrows(StorageException.class,
                        () -> page.writeSlot(a, new byte[] {9})).getCode());
        assertArrayEquals(new byte[] {7, 8}, page.readRecord(a));
        page.deleteRecord(a);
        int b = page.reserveSlot(1);
        assertEquals(1, b);
        assertThrows(StorageException.class, () -> page.readRecord(a));
        assertThrows(StorageException.class, () -> page.readRecord(10));
    }

    // memastikan halaman penuh dan direktori rusak ditolak dengan aman
    @Test
    void fullPageAndCorruptDirectoryFailSafely() {
        SlottedPage page = new SlottedPage();
        int slot = page.reserveSlot(SlottedPage.PAGE_SIZE - 16 - 12);
        byte[] full = new byte[SlottedPage.PAGE_SIZE - 16 - 12];
        Arrays.fill(full, (byte) 5);
        page.writeSlot(slot, full);
        assertFalse(page.hasSpace(1));
        assertThrows(StorageException.class, () -> page.reserveSlot(1));
        assertArrayEquals(full, page.readRecord(slot));
        byte[] corrupt = page.toBytes();
        ByteBuffer.wrap(corrupt).putInt(16, 0);
        assertEquals(StorageErrorCode.INVALID_BLOCK,
                assertThrows(StorageException.class, () -> new SlottedPage(corrupt)).getCode());
        assertArrayEquals(full, page.readRecord(slot));
    }

    // memastikan slot pesanan tetap ada setelah halaman dipulihkan
    @Test
    void reservationSurvivesSnapshot() {
        SlottedPage page = new SlottedPage();
        int slot = page.reserveSlot(3);
        SlottedPage restored = new SlottedPage(page.toBytes());
        assertThrows(StorageException.class,
                () -> restored.writeSlot(slot, new byte[] {1, 2, 3, 4}));
        restored.writeSlot(slot, new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, restored.readRecord(slot));
    }

    // menghitung slot aktif pesanan dan terhapus tanpa kehilangan nomornya
    @Test
    void slotCountAndOccupancyIncludeEverySlotState() {
        SlottedPage page = new SlottedPage();
        int active = page.reserveSlot(2);
        int reserved = page.reserveSlot(2);
        int deleted = page.reserveSlot(2);
        page.writeSlot(active, new byte[] {1, 2});
        page.writeSlot(deleted, new byte[] {3, 4});
        page.deleteRecord(deleted);

        assertEquals(3, page.slotCount());
        assertTrue(page.isOccupied(active));
        assertFalse(page.isOccupied(reserved));
        assertFalse(page.isOccupied(deleted));
        assertThrows(StorageException.class, () -> page.isOccupied(-1));
        assertThrows(StorageException.class, () -> page.isOccupied(page.slotCount()));

        SlottedPage restored = new SlottedPage(page.toBytes());
        assertEquals(3, restored.slotCount());
        assertTrue(restored.isOccupied(active));
        assertFalse(restored.isOccupied(reserved));
        assertFalse(restored.isOccupied(deleted));
    }

    // memulihkan record ke slot lama tanpa menggeser nomor slot lain
    @Test
    void reserveSlotAtRestoresOnlyDeletedSlot() {
        SlottedPage page = new SlottedPage();
        int oldSlot = page.reserveSlot(3);
        int active = page.reserveSlot(2);
        int reserved = page.reserveSlot(1);
        page.writeSlot(oldSlot, new byte[] {1, 2, 3});
        page.writeSlot(active, new byte[] {4, 5});
        page.deleteRecord(oldSlot);
        byte[] before = page.toBytes();

        assertFalse(page.reserveSlotAt(active, 3));
        assertFalse(page.reserveSlotAt(reserved, 3));
        assertArrayEquals(before, page.toBytes());
        assertThrows(StorageException.class, () -> page.reserveSlotAt(-1, 2));
        assertThrows(StorageException.class, () -> page.reserveSlotAt(3, 2));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION,
                assertThrows(StorageException.class,
                        () -> page.reserveSlotAt(oldSlot, 0)).getCode());
        assertArrayEquals(before, page.toBytes());

        assertTrue(page.reserveSlotAt(oldSlot, 3));
        assertFalse(page.isOccupied(oldSlot));
        page.writeSlot(oldSlot, new byte[] {6, 7, 8});
        assertEquals(3, page.slotCount());
        SlottedPage restored = new SlottedPage(page.toBytes());
        assertArrayEquals(new byte[] {6, 7, 8}, restored.readRecord(oldSlot));
        assertArrayEquals(new byte[] {4, 5}, restored.readRecord(active));
        assertFalse(restored.isOccupied(reserved));
    }

    // memastikan reservasi gagal tidak mengubah halaman yang hampir penuh
    @Test
    void reserveSlotAtReturnsFalseWithoutChangingFullPage() {
        SlottedPage page = new SlottedPage();
        int deleted = page.reserveSlot(2);
        int active = page.reserveSlot(SlottedPage.PAGE_SIZE - 16 - 24 - 2);
        page.writeSlot(deleted, new byte[] {1, 2});
        page.writeSlot(active, new byte[SlottedPage.PAGE_SIZE - 16 - 24 - 2]);
        page.deleteRecord(deleted);
        byte[] before = page.toBytes();

        assertFalse(page.reserveSlotAt(deleted, 3));
        assertArrayEquals(before, page.toBytes());
        assertEquals(2, page.slotCount());
        assertTrue(page.reserveSlotAt(deleted, 2));
    }

    // mengganti panjang record sambil menjaga slot dan isi record lain
    @Test
    void replaceRecordHandlesGrowthShrinkAndRoundTrip() {
        SlottedPage page = new SlottedPage();
        int changed = page.reserveSlot(3);
        int other = page.reserveSlot(2);
        page.writeSlot(changed, new byte[] {1, 2, 3});
        page.writeSlot(other, new byte[] {4, 5});
        byte[] larger = new byte[20];
        Arrays.fill(larger, (byte) 7);

        assertTrue(page.replaceRecord(changed, larger));
        larger[0] = 99;
        assertEquals(2, page.slotCount());
        assertEquals(7, page.readRecord(changed)[0]);
        assertArrayEquals(new byte[] {4, 5}, page.readRecord(other));
        assertTrue(page.replaceRecord(changed, new byte[] {8}));
        assertTrue(page.replaceRecord(changed, new byte[] {9}));

        SlottedPage restored = new SlottedPage(page.toBytes());
        assertArrayEquals(new byte[] {9}, restored.readRecord(changed));
        assertArrayEquals(new byte[] {4, 5}, restored.readRecord(other));
        assertEquals(2, restored.slotCount());
    }

    // memastikan update yang gagal tidak merusak record lama
    @Test
    void replaceRecordRejectsInvalidInputAndPreservesFullPage() {
        SlottedPage page = new SlottedPage();
        int changed = page.reserveSlot(10);
        int other = page.reserveSlot(SlottedPage.PAGE_SIZE - 16 - 24 - 10);
        page.writeSlot(changed, new byte[10]);
        page.writeSlot(other, new byte[SlottedPage.PAGE_SIZE - 16 - 24 - 10]);
        byte[] before = page.toBytes();

        assertFalse(page.replaceRecord(changed, new byte[11]));
        assertThrows(StorageException.class, () -> page.replaceRecord(-1, new byte[] {1}));
        assertThrows(StorageException.class, () -> page.replaceRecord(changed, null));
        assertThrows(StorageException.class, () -> page.replaceRecord(changed, new byte[0]));
        assertArrayEquals(before, page.toBytes());
        assertArrayEquals(new byte[10], page.readRecord(changed));
        assertEquals(SlottedPage.PAGE_SIZE - 16 - 24 - 10, page.readRecord(other).length);

        page.deleteRecord(changed);
        assertThrows(StorageException.class,
                () -> page.replaceRecord(changed, new byte[] {1}));
        assertTrue(page.reserveSlotAt(changed, 1));
        assertThrows(StorageException.class,
                () -> page.replaceRecord(changed, new byte[] {1}));
    }
}
