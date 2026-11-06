package mdbms.storage.block;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.BufferStats;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

/** Cache blok dengan kapasitas tetap, penggantian LRU, dan pemeriksaan WAL sebelum halaman dirty ditulis. */
public class BufferPool {
    private record BlockId(Path file, int number) {}

    private final BlockManager blockManager;
    private final int capacity;
    private final Map<BlockId, Block> frames = new LinkedHashMap<>();
    private IFailureRecoveryManager recoveryManager;
    private long clock;
    private long hits;
    private long misses;

    public BufferPool(BlockManager blockManager, int capacity) {
        if (blockManager == null || capacity <= 0) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK,
                    "Buffer pool requires a block manager and positive capacity");
        }
        this.blockManager = blockManager;
        this.capacity = capacity;
    }

    public synchronized void attachRecoveryManager(IFailureRecoveryManager frm) {
        if (frm == null) {
            throw new StorageException(StorageErrorCode.WAL_FLUSH_FAILED, "Recovery manager must not be null");
        }
        recoveryManager = frm;
    }

    /** Mengambil frame dari cache atau disk, lalu menahannya agar tidak dapat dikeluarkan sampai diunpin. */
    public synchronized Block pin(String fileName, int blockNumber) {
        BlockId id = idFor(fileName, blockNumber);
        Block block = frames.get(id);
        if (block == null) {
            // Cache miss: siapkan ruang
            misses++;
            makeRoom();
            block = new Block(fileName, blockNumber, blockManager.readBlock(fileName, blockNumber));
            frames.put(id, block);
        } else {
            // Cache hit: gunakan objek frame yang sama
            hits++;
        }
        block.pinCount++;
        block.lastUsed = ++clock; // Akses terbaru memiliki nilai terbesar
        return block;
    }

    /**
     * Melepas frame. Jika isinya berubah, pemanggil wajib memberikan LSN dari log perubahan.
     * Pelepasan tanpa perubahan tidak menghapus status dirty
     */
    public synchronized void unpin(Block block, boolean dirty, long pageLsn) {
        if (block == null || block.pinCount <= 0
                || frames.get(idFor(block.fileName, block.blockNumber)) != block) {
            throw new StorageException(StorageErrorCode.INVALID_PIN, "Frame is not pinned in this pool");
        }
        if (dirty) {
            validateLsn(pageLsn);
            block.pageLsn = block.dirty ? Math.max(block.pageLsn, pageLsn) : pageLsn;
            block.dirty = true;
        }
        block.pinCount--;
    }

    /** Alokasi blok nol di disk dan memasukkannya ke cache */
    public synchronized Block appendBlock(String fileName) {
        Path path = blockManager.pathFor(fileName);
        makeRoom();
        int number = blockManager.appendBlock(fileName);
        Block block = new Block(fileName, number, new byte[BlockManager.BLOCK_SIZE]);
        block.pinCount = 1;
        block.lastUsed = ++clock;
        frames.put(new BlockId(path, number), block);
        return block;
    }
    public synchronized int blockCount(String fileName) {
        return blockManager.blockCount(fileName);
    }

    /** Mengembalikan salinan halaman agar perubahan pada hasil baca tidak mengubah isi cache. */
    public synchronized byte[] readBuffer(String fileName, int blockNumber) {
        Block block = pin(fileName, blockNumber);
        try {
            return block.data.clone();
        } finally {
            // Pin internal tetap dilepas meski pembuatan salinan gagal.
            unpin(block, false, 0);
        }
    }

    /** Menyalin satu halaman ke cache dan menandainya dirty. write ke disk saat flush atau dikeluarkan. */
    public synchronized void writeBuffer(String fileName, int blockNumber, byte[] data, long pageLsn) {
        BlockManager.validateData(data);
        validateLsn(pageLsn);
        Block block = pin(fileName, blockNumber);
        try {
            System.arraycopy(data, 0, block.data, 0, BlockManager.BLOCK_SIZE);
        } finally {
            unpin(block, true, pageLsn);
        }
    }

    /** Mengeluarkan halaman dari cache setelah flush bila dirty. Blok dan file di disk tidak dihapus. */
    public synchronized void deleteBuffer(String fileName, int blockNumber) {
        BlockId id = idFor(fileName, blockNumber);
        Block block = frames.get(id);
        if (block == null) return;
        requireUnpinned(block);
        flush(block);
        frames.remove(id);
    }

    // buang semua frame file ini tanpa flush karena isinya emang mau dibuang, lalu hapus file nya di disk
    // ditolak kalau masih ada frame file ini yang lagi dipin
    public synchronized void discardFile(String fileName) {
        Path path = blockManager.pathFor(fileName);
        for (Map.Entry<BlockId, Block> entry : frames.entrySet()) {
            if (entry.getKey().file().equals(path)) requireUnpinned(entry.getValue());
        }
        frames.keySet().removeIf(id -> id.file().equals(path));
        blockManager.deleteFile(fileName);
    }

    /** Menulis semua frame dirty yang tidak sedang dipakai */
    public synchronized void flushBuffer() {
        for (Block block : frames.values()) requireUnpinned(block);
        for (Block block : frames.values()) flush(block);
    }

    /** Membuat statistik kapasitas, jumlah frame, jumlah dirty, serta hit/miss dari pencarian melalui pin. */
    public synchronized BufferStats getBufferStats() {
        int dirty = 0;
        for (Block block : frames.values()) if (block.dirty) dirty++;
        return new BufferStats(capacity, frames.size(), dirty, hits, misses);
    }

    /** Membentuk kunci cache dari path file yang divalidasi dan nomor blok nonnegatif. */
    private BlockId idFor(String fileName, int blockNumber) {
        BlockManager.validateNumber(blockNumber);
        return new BlockId(blockManager.pathFor(fileName), blockNumber);
    }

    /** Jika pool penuh, mengeluarkan frame dengan lru untuk unpinned file. */
    private void makeRoom() {
        if (frames.size() < capacity) return;
        Block victim = null;
        for (Block block : frames.values()) {
            // LRU: abaikan frame yang dipakai, lalu pilih lastUsed paling kecil.
            if (block.pinCount == 0 && (victim == null || block.lastUsed < victim.lastUsed)) victim = block;
        }
        if (victim == null) {
            throw new StorageException(StorageErrorCode.BUFFER_FULL, "All buffer frames are pinned");
        }
        flush(victim); // Korban baru dihapus setelah flush berhasil; kegagalan mempertahankan data dirty di cache.
        frames.remove(idFor(victim.fileName, victim.blockNumber));
    }

    /** flush eksplisit, LRU, dan deleteBuffer: log dahulu, halaman kemudian. */
    private void flush(Block block) {
        if (!block.dirty) return;
        if (recoveryManager == null) {
            throw new StorageException(StorageErrorCode.WAL_FLUSH_FAILED, "No recovery manager attached");
        }
        boolean flushed;
        try {
            //flushLogUpTo(pageLsn) wajib berhasil sebelum frame ditulis
            flushed = recoveryManager.flushLogUpTo(block.pageLsn);
        } catch (RuntimeException e) {
            throw new StorageException(StorageErrorCode.WAL_FLUSH_FAILED,
                    "Cannot flush WAL through LSN " + block.pageLsn, e);
        }
        if (!flushed) {
            throw new StorageException(StorageErrorCode.WAL_FLUSH_FAILED,
                    "WAL flush rejected LSN " + block.pageLsn);
        }
        // Tulis dan sinkronkan halaman melalui BlockManager.
        blockManager.writeBlock(block.fileName, block.blockNumber, block.data);
        // Jika WAL atau penulisan gagal, baris ini tidak dijalankan sehingga frame tetap dirty untuk percobaan ulang.
        block.dirty = false;
    }

    /** Melarang flush atau pengeluaran frame yang masih dipakai pemegang pin. */
    private static void requireUnpinned(Block block) {
        if (block.pinCount != 0) {
            throw new StorageException(StorageErrorCode.INVALID_PIN,
                    "Frame is still pinned: " + block.fileName + " block " + block.blockNumber);
        }
    }

    private static void validateLsn(long pageLsn) {
        if (pageLsn < 0) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Dirty changes require a nonnegative page LSN");
        }
    }
}
