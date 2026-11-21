package mdbms.storage.index;

import java.util.List;
import mdbms.models.data.RowId;
import mdbms.models.schema.IndexInfo;

// kontrak satu index di satu kolom tabel, implementasinya sekarang cuma BPlusTreeIndex
// key yang masuk boleh mentah, implementasi yang wajib normalisasi pakai KeyNormalizer
public interface Index {

    // bikin file index baru yang masih kosong, dipanggil sekali pas CREATE INDEX
    void create();

    // daftarin satu RowId di bawah key, key yang sama boleh punya banyak RowId
    void insert(Object key, RowId rowId);

    // sama kayak insert tapi halaman index yang berubah ditandai LSN log perubahan baris nya (#54)
    default void insert(Object key, RowId rowId, long pageLsn) {
        insert(key, rowId);
    }

    // hapus pasangan key dan RowId, kalau pasangannya ga ada diabaikan aja
    void delete(Object key, RowId rowId);

    // sama kayak delete tapi halaman index yang berubah ditandai LSN log perubahan baris nya (#54)
    default void delete(Object key, RowId rowId, long pageLsn) {
        delete(key, rowId);
    }

    // ambil semua RowId yang key nya sama persis, list kosong kalau ga ketemu
    List<RowId> search(Object key);

    // ambil RowId dengan key di antara low dan high urut naik, low atau high null artinya ga ada batas di sisi itu
    List<RowId> searchRange(Object low, Object high, boolean lowInclusive, boolean highInclusive);

    // kosongin semua isi index tapi file nya tetap ada, dipakai pas tabel di truncate
    void clear();

    // hapus index beserta file nya, dipakai pas DROP INDEX atau DROP TABLE
    void destroy();

    // info index ini: nama, tabel, kolom, dan tipe index
    IndexInfo getInfo();
}
