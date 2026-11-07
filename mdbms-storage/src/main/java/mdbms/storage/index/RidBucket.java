package mdbms.storage.index;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import mdbms.models.data.RowId;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

// satu block isinya daftar RowId buat satu key, kalau penuh disambung ke block berikutnya lewat next
// layoutnya count 4 byte, next 4 byte, lalu tiap entri blockNumber 4 byte dan slot 4 byte
public final class RidBucket {
    public static final int HEADER_SIZE = 8;
    public static final int NO_NEXT = -1;

    private static final int ENTRY_SIZE = 8;
    private static final int CAPACITY = (BlockManager.BLOCK_SIZE - HEADER_SIZE) / ENTRY_SIZE;

    private final Block block;
    private final String tableName;

    // bungkus block yang udah ada, count yang ga masuk akal dianggap block rusak
    public RidBucket(Block block, String tableName) {
        this.block = Objects.requireNonNull(block, "block");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        if (block.data.length != BlockManager.BLOCK_SIZE) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Bucket block must be " + BlockManager.BLOCK_SIZE + " bytes");
        }
        int count = count();
        if (count < 0 || count > CAPACITY) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Corrupt bucket count " + count + " in block " + block.blockNumber);
        }
    }

    // semua RowId di block ini aja, ga ikut block sambungan
    public List<RowId> rowIds() {
        int count = count();
        List<RowId> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(entryAt(i));
        }
        return result;
    }

    // tambah RowId, false kalau udah ada, block penuh harus dicek dulu pakai isFull
    public boolean add(RowId rowId) {
        checkTable(rowId);
        if (indexOf(rowId) >= 0) return false;
        int count = count();
        if (count == CAPACITY) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Bucket block " + block.blockNumber + " is full");
        }
        int offset = HEADER_SIZE + count * ENTRY_SIZE;
        buffer().putInt(offset, rowId.blockNumber()).putInt(offset + 4, rowId.slot());
        setCount(count + 1);
        return true;
    }

    // hapus RowId, entri terakhir dipindah ke posisi yang kosong biar tetap rapat
    public boolean remove(RowId rowId) {
        checkTable(rowId);
        int index = indexOf(rowId);
        if (index < 0) return false;
        int last = count() - 1;
        if (index != last) {
            RowId moved = entryAt(last);
            int offset = HEADER_SIZE + index * ENTRY_SIZE;
            buffer().putInt(offset, moved.blockNumber()).putInt(offset + 4, moved.slot());
        }
        setCount(last);
        return true;
    }

    // penuh kalau udah 511 entri
    public boolean isFull() {
        return count() == CAPACITY;
    }

    // kosong kalau belum ada entri sama sekali
    public boolean isEmpty() {
        return count() == 0;
    }

    // nomor block sambungan, kosong kalau ini block terakhir di rantai
    public OptionalInt getNext() {
        int next = buffer().getInt(4);
        return next == NO_NEXT ? OptionalInt.empty() : OptionalInt.of(next);
    }

    // atur block sambungan, pakai NO_NEXT buat mutus rantai
    public void setNext(int blockNumber) {
        if (blockNumber < 0 && blockNumber != NO_NEXT) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Invalid next bucket block " + blockNumber);
        }
        buffer().putInt(4, blockNumber);
    }

    // jumlah entri di block ini
    public int count() {
        return buffer().getInt(0);
    }

    // ganti jumlah entri di header
    private void setCount(int count) {
        buffer().putInt(0, count);
    }

    // baca entri ke i jadi RowId
    private RowId entryAt(int i) {
        int offset = HEADER_SIZE + i * ENTRY_SIZE;
        ByteBuffer buffer = buffer();
        return new RowId(tableName, buffer.getInt(offset), buffer.getInt(offset + 4));
    }

    // cari posisi RowId, minus satu kalau ga ketemu
    private int indexOf(RowId rowId) {
        int count = count();
        for (int i = 0; i < count; i++) {
            if (entryAt(i).equals(rowId)) return i;
        }
        return -1;
    }

    // RowId dari tabel lain ditolak karena nama tabel ga disimpan di block
    private void checkTable(RowId rowId) {
        Objects.requireNonNull(rowId, "rowId");
        if (!tableName.equals(rowId.tableName())) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "RowId of table " + rowId.tableName() + " does not belong to index of table " + tableName);
        }
    }

    // akses data block langsung tanpa salin
    private ByteBuffer buffer() {
        return ByteBuffer.wrap(block.data);
    }
}
