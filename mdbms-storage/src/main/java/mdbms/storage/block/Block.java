package mdbms.storage.block;

/**
 * Frame di RAM yang menyimpan isi satu blok file beserta statusnya.
 * Byte hanya boleh diakses selama frame dipin */
public final class Block {
    public final String fileName;
    public final int blockNumber;
    public final byte[] data;
    // Jumlah pemakaian aktif, frame boleh dikeluarkan ketika nilai ini 0
    int pinCount;
    // Menandai perubahan di RAM yang belum berhasil ditulis ke disk (1 = belum, 0 = sudah)
    boolean dirty;
    // Nomor urut akses terakhir buat milih victim LRU
    long lastUsed;
    // LSN tertinggi dari perubahan yang masih dirty, jadi target flush log sebelum penulisan halaman
    long pageLsn;

    /** Konstruktor. Pin, dirty, LRU, lsn diatur BufferPool. */
    public Block(String fileName, int blockNumber, byte[] data) {
        this.fileName = fileName;
        this.blockNumber = blockNumber;
        this.data = data;
    }
}
