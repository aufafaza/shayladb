package mdbms.api;

import java.util.Set;
import mdbms.models.access.BufferStats;

public interface IDurableStorage{
    void flushBuffer();

    BufferStats getBufferStats();

    // buang cache buffer file yang barusan diganti dari luar misalnya restore snapshot, lalu muat ulang katalog dan index dari disk
    // file nya ga dihapus, set null atau kosong berarti semua cache dibuang
    void invalidateAndReload(Set<String> affectedFiles);

    // muat ulang katalog lalu bangun ulang index dari isi heap setelah recovery, wajib selesai sebelum engine terima query
    // perubahan hasil redo undo yang masih di buffer tetap dipakai, ga dibuang
    void rebuildIndexesAndReloadCatalog();
}