package mdbms.api;

import java.util.Set;
import mdbms.models.access.BufferStats;

public interface IDurableStorage{
    void flushBuffer();

    BufferStats getBufferStats();

    // buang cache buffer file yang barusan diganti dari luar misalnya restore snapshot, lalu muat ulang katalog dan index dari disk
    // default nya gagal biar implementasi yang belum ngisi ga keliatan sukses padahal cache nya basi
    default void invalidateAndReload(Set<String> affectedFiles) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support invalidateAndReload");
    }

    // dipanggil sekali setelah recovery sebelum engine terima query, muat ulang katalog, lepas slot dipesan yang nyangkut, lalu bangun ulang index dari heap
    // index diperlakuin sebagai data turunan jadi ga dicatat di WAL, default nya gagal biar recovery ga lanjut dengan index basi
    default void rebuildIndexesAndReloadCatalog() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support rebuildIndexesAndReloadCatalog");
    }
}