package mdbms.models.access;

// pinnedBlocks itu jumlah frame yang lagi di pin, harus 0 kalau ga ada operasi storage yang lagi jalan
public record BufferStats(int capacity, int usedBlocks, int dirtyBlocks, long hits, long misses, int pinnedBlocks){
    // bentuk lama tanpa pinnedBlocks biar pemakai yang belum butuh ga perlu diubah
    public BufferStats(int capacity, int usedBlocks, int dirtyBlocks, long hits, long misses){
        this(capacity, usedBlocks, dirtyBlocks, hits, misses, 0);
    }
}
