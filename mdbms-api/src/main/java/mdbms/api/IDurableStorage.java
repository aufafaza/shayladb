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

    // muat ulang katalog lalu bangun ulang index dari isi heap setelah recovery, wajib selesai sebelum engine terima query
    // default nya gagal biar recovery ga lanjut dengan index yang belum disamain
    default void rebuildIndexesAndReloadCatalog() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support rebuildIndexesAndReloadCatalog");
    }
}