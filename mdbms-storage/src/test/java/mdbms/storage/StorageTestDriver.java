package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.models.access.BufferStats;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// driver SM-15, nguji operasi utama SM pakai database contoh dan komunikasi SM dengan QP, QO, dan FRM lewat interface
// tiap pengujian dicetak BERHASIL atau GAGAL, jalanin pakai mvn -pl mdbms-storage -am test -Dtest=StorageTestDriver
class StorageTestDriver {

    private static final String INDEX_NIM = "idx_takes_nim";
    private static final String INDEX_GPA = "idx_students_gpa";

    private final List<String> failures = new ArrayList<>();
    private int passed;

    @TempDir Path directory;

    // FRM tiruan yang nyatet tiap flushLogUpTo dari SM, method FRM lain dilarang dipanggil SM
    // accept false bikin FRM nolak flush buat ngecek SM ga nulis halaman sebelum log aman
    private static final class RecordingFrm implements IFailureRecoveryManager {
        int flushCalls;
        boolean accept = true;

        @Override public boolean flushLogUpTo(long lsn) {
            flushCalls++;
            return accept && lsn >= 0;
        }
        @Override public RecoveryResult writeTransactionLog(int txId, LogType eventType) { throw unexpected(); }
        @Override public RecoveryResult writeDataLog(int txId, LogType opType, String table, RowId rid,
                RecordImage beforeImg, RecordImage afterImg) { throw unexpected(); }
        @Override public RecoveryResult rollbackTransaction(int txId) { throw unexpected(); }
        @Override public RecoveryResult createCheckpoint() { throw unexpected(); }
        @Override public RecoveryResult recoverOnStartup() { throw unexpected(); }
        @Override public RecoveryResult recoverToPointInTime(Instant targetTime) { throw unexpected(); }
        @Override public RecoveryResult recoverToTransaction(int targetTxId) { throw unexpected(); }
        @Override public List<LogRecord> readLogs(Integer filterTxId) { throw unexpected(); }

        // SM cuma boleh manggil flushLogUpTo, panggilan lain berarti kontrak SM ke FRM berubah
        private static UnsupportedOperationException unexpected() {
            return new UnsupportedOperationException("SM tidak boleh memanggil method FRM ini");
        }
    }

    // buka StorageManager di direktori driver dengan FRM tiruan terpasang
    private StorageManager open(Path dir, int bufferCapacity, RecordingFrm frm) {
        StorageManager storage = new StorageManager(dir.toString(), bufferCapacity, 0);
        storage.attachRecoveryManager(frm);
        return storage;
    }

    // jalanin satu pengujian lalu cetak BERHASIL atau GAGAL, error di dalamnya dihitung gagal tanpa ngehentiin driver
    private void check(String name, BooleanSupplier test) {
        boolean ok;
        String reason = "";
        try {
            ok = test.getAsBoolean();
        } catch (RuntimeException e) {
            ok = false;
            reason = " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
        System.out.println((ok ? "[BERHASIL] " : "[GAGAL]    ") + name + reason);
        if (ok) passed++; else failures.add(name);
    }

    // cetak judul kelompok pengujian
    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    // ambil baris tabel yang cocok sama semua kondisi lewat IDataAccess
    private static List<Row> select(IDataAccess data, String table, Condition... conditions) {
        return data.readBlock(new DataRetrieval(table, List.of(), List.of(conditions))).rows();
    }

    // ambil RowId baris tabel yang cocok sama kondisi, diurutin biar gampang dibandingin
    private static List<RowId> rowIds(IDataAccess data, String table, Condition... conditions) {
        return sorted(select(data, table, conditions).stream().map(Row::rowId).toList());
    }

    // urutin RowId per block lalu slot
    private static List<RowId> sorted(List<RowId> ids) {
        return ids.stream().sorted(Comparator.comparingInt(RowId::blockNumber).thenComparingInt(RowId::slot)).toList();
    }

    // nilai kolom baris lewat nama kolom di schema
    private static Object value(TableSchema schema, Row row, String column) {
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).name().equals(column)) return row.values().get(i);
        }
        throw new IllegalArgumentException("Kolom tidak ada: " + column);
    }

    // salin semua file biasa dari satu folder ke folder lain, dipakai buat snapshot dan restore
    private static void copyFiles(Path from, Path to) {
        try {
            Files.createDirectories(to);
            try (var files = Files.list(from)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Gagal menyalin file " + from + " ke " + to, e);
        }
    }

    // susun map kolom ke nilai buat satu mahasiswa baru di tabel students
    private static Map<String, Object> student(int nim, String name, String gender, String major, float gpa) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nim", nim);
        values.put("name", name);
        values.put("gender", gender);
        values.put("major", major);
        values.put("gpa", gpa);
        return values;
    }

    // jalanin semua pengujian SM-15 di satu direktori data, balikin daftar pengujian yang gagal
    List<String> run(Path dir) {
        RecordingFrm frm = new RecordingFrm();
        StorageManager storage = open(dir, 32, frm);
        // komponen lain cuma kenal SM lewat interface ini
        IDataAccess data = storage;
        ISchemaCatalog catalog = storage;
        IStatisticsProvider stats = storage;
        IDurableStorage durable = storage;

        section("SM-14 database contoh");
        new SampleDataLoader(data, catalog).loadIfEmpty();
        check("database contoh punya tabel students, takes, courses",
                () -> catalog.listTable().containsAll(List.of("students", "takes", "courses")));
        check("tiap tabel contoh punya minimal 50 baris", () -> catalog.listTable().stream()
                .allMatch(table -> select(data, table).size() >= 50));
        check("database contoh mencakup INTEGER, FLOAT, CHAR, VARCHAR", () -> {
            Set<DataType> types = EnumSet.noneOf(DataType.class);
            for (String table : catalog.listTable()) {
                catalog.getTableSchema(table).columns().forEach(column -> types.add(column.type()));
            }
            return types.containsAll(EnumSet.allOf(DataType.class));
        });
        check("tabel contoh saling berkaitan lewat foreign key takes ke students dan courses", () ->
                catalog.getTableSchema("takes").foreignKeys().stream()
                        .map(fk -> fk.referencedTable()).collect(Collectors.toSet())
                        .containsAll(Set.of("students", "courses")));

        section("SM-08 struktur tabel buat QO dan QP");
        TableSchema students = catalog.getTableSchema("students");
        check("schema students kebaca lengkap dengan primary key nim", () ->
                students.columns().stream().map(ColumnDefinition::name).toList()
                        .equals(List.of("nim", "name", "gender", "major", "gpa"))
                        && students.primaryKey().equals(List.of("nim")));
        check("tabel yang tidak ada dikembalikan null", () -> catalog.getTableSchema("ghost") == null);
        check("membuat tabel dengan nama yang sudah ada ditolak dengan TABLE_ALREADY_EXISTS", () -> {
            try {
                catalog.updateTableSchema(new TableSchema("students", List.of(
                        new ColumnDefinition("id", DataType.INTEGER, 0, false)), List.of("id"), List.of()));
                return false;
            } catch (StorageException e) {
                return e.getCode() == StorageErrorCode.TABLE_ALREADY_EXISTS
                        && catalog.getTableSchema("students").equals(students);
            }
        });

        section("SM-04 mengambil data");
        Condition goodGpa = new Condition("gpa", ComparisonOperator.GTE, 3.5f);
        check("readBlock dengan kondisi gpa >= 3.5 sama dengan filter manual", () -> {
            long expected = select(data, "students").stream()
                    .filter(row -> (Float) value(students, row, "gpa") >= 3.5f).count();
            return expected > 0 && select(data, "students", goodGpa).size() == expected;
        });
        check("readBlock dengan projeksi cuma ngembaliin kolom yang diminta", () ->
                data.readBlock(new DataRetrieval("students", List.of("nim", "gpa"), List.of(goodGpa))).rows()
                        .stream().allMatch(row -> row.values().size() == 2));
        check("fetchByRowId balikin baris yang sama dengan hasil scan", () -> {
            Row first = select(data, "courses").get(0);
            return data.fetchByRowId(first.rowId()).equals(first);
        });

        section("SM-05 sampai SM-07 menambah, mengubah, menghapus data");
        check("writeBlock tanpa kondisi menambah satu mahasiswa", () -> {
            data.writeBlock(new DataWrite("students", student(200001, "Driver", "M", "Informatika", 3.9f), List.of()));
            return select(data, "students", new Condition("nim", ComparisonOperator.EQ, 200001)).size() == 1;
        });
        RowId reserved = data.reserveRowId("takes", List.of(1001, 200001, "IF0001", 90, 20262));
        check("alur QP reserveRowId lalu insertAt menaruh baris di RowId yang dipesan", () -> {
            data.insertAt(reserved, List.of(1001, 200001, "IF0001", 90, 20262));
            return data.fetchByRowId(reserved).values().get(0).equals(1001);
        });
        check("writeBlock dengan kondisi mengubah semua baris yang cocok", () -> {
            Condition semester = new Condition("semester", ComparisonOperator.EQ, 20261);
            int target = select(data, "takes", semester).size();
            Map<String, Object> grade = new LinkedHashMap<>();
            grade.put("grade", 100);
            return data.writeBlock(new DataWrite("takes", grade, List.of(semester))).size() == target
                    && select(data, "takes", semester).stream().allMatch(row -> row.values().get(3).equals(100));
        });
        check("updateByRowId mengganti isi baris", () ->
                data.updateByRowId(reserved, List.of(1001, 200001, "IF0001", 75, 20262)).values().get(3).equals(75));
        check("deleteBlock dengan kondisi menghapus semua baris yang cocok", () -> {
            Condition lowCredit = new Condition("credits", ComparisonOperator.EQ, 2);
            int target = select(data, "courses", lowCredit).size();
            return target > 0 && data.deleteBlock(new DataDeletion("courses", List.of(lowCredit))) == target
                    && select(data, "courses", lowCredit).isEmpty();
        });
        check("deleteByRowId menghapus satu baris dan fetch setelahnya gagal", () -> {
            data.deleteByRowId(reserved);
            try {
                data.fetchByRowId(reserved);
                return false;
            } catch (StorageException e) {
                return true;
            }
        });
        check("data yang melanggar schema ditolak dengan SCHEMA_VIOLATION", () -> {
            try {
                Map<String, Object> wrongType = student(1, "x", "M", "x", 1f);
                wrongType.put("nim", "bukan angka");
                data.writeBlock(new DataWrite("students", wrongType, List.of()));
                return false;
            } catch (StorageException e) {
                return e.getCode() == StorageErrorCode.SCHEMA_VIOLATION;
            }
        });

        section("SM-10 dan SM-11 index B+Tree");
        check("createIndex dari baris yang sudah ada tercatat di katalog", () -> {
            catalog.createIndex(INDEX_NIM, "takes", "nim");
            catalog.createIndex(INDEX_GPA, "students", "gpa");
            return catalog.getIndexes("takes").stream().anyMatch(info -> info.indexName().equals(INDEX_NIM));
        });
        check("searchIndex sama dengan hasil scan linear", () -> {
            Condition nim = new Condition("nim", ComparisonOperator.EQ, 100010);
            return sorted(data.searchIndex(INDEX_NIM, 100010)).equals(rowIds(data, "takes", nim));
        });
        check("searchIndexRange sama dengan hasil scan linear", () -> sorted(
                data.searchIndexRange(INDEX_GPA, 3.0f, 3.5f, true, false)).equals(rowIds(data, "students",
                new Condition("gpa", ComparisonOperator.GTE, 3.0f), new Condition("gpa", ComparisonOperator.LT, 3.5f))));
        check("index ikut berubah saat data ditambah", () -> {
            RowId added = data.writeBlock(new DataWrite("students", student(200002, "Index", "F", "Informatika", 1.25f), List.of())).get(0);
            return data.searchIndex(INDEX_GPA, 1.25f).equals(List.of(added));
        });

        section("SM-09 statistik buat cost model QO");
        check("statistik students sesuai isi tabel", () -> {
            Statistic stat = stats.getStatistics("students");
            int rows = select(data, "students").size();
            return stat.nr() == rows && stat.br() >= 1 && stat.lr() > 0 && stat.fr() > 0
                    && stat.distinctValues().get("gender") == 2 && stat.distinctValues().get("nim") == rows;
        });
        check("statistik ikut diperbarui setelah perubahan data", () -> {
            int before = stats.getStatistics("students").nr();
            data.deleteBlock(new DataDeletion("students", List.of(new Condition("nim", ComparisonOperator.EQ, 200002))));
            return stats.getStatistics("students").nr() == before - 1;
        });

        section("SM-12 buffer");
        check("baca ulang data memakai buffer tanpa membaca disk lagi", () -> {
            select(data, "students");
            BufferStats before = durable.getBufferStats();
            select(data, "students");
            BufferStats after = durable.getBufferStats();
            return after.misses() == before.misses() && after.hits() > before.hits();
        });
        check("buffer kecil tetap bisa scan semua tabel karena ada penggantian halaman", () -> {
            durable.flushBuffer();
            int expected = 0;
            for (String table : catalog.listTable()) expected += select(data, table).size();
            StorageManager small = open(dir, 2, frm);
            int total = 0;
            for (String table : small.listTable()) total += select(small, table).size();
            BufferStats bs = small.getBufferStats();
            return total == expected && bs.usedBlocks() <= 2 && bs.misses() > 2;
        });

        section("SM-13 dan komunikasi WAL dengan FRM");
        check("flushBuffer manggil FRM flushLogUpTo sebelum halaman ditulis", () -> {
            data.writeBlock(new DataWrite("students", student(200003, "Flush", "M", "Informatika", 3f), List.of()));
            int before = frm.flushCalls;
            durable.flushBuffer();
            return frm.flushCalls > before && durable.getBufferStats().dirtyBlocks() == 0;
        });
        check("kalau FRM nolak flush log, SM gagal dengan WAL_FLUSH_FAILED dan halaman tetap dirty", () -> {
            data.writeBlock(new DataWrite("students", student(200004, "Wal", "F", "Informatika", 3f), List.of()));
            frm.accept = false;
            try {
                durable.flushBuffer();
                return false;
            } catch (StorageException e) {
                return e.getCode() == StorageErrorCode.WAL_FLUSH_FAILED && durable.getBufferStats().dirtyBlocks() > 0;
            } finally {
                frm.accept = true;
            }
        });
        check("setelah FRM menerima lagi, flush ulang berhasil", () -> {
            durable.flushBuffer();
            return durable.getBufferStats().dirtyBlocks() == 0;
        });

        section("SM-01 data permanen setelah restart");
        int studentsBefore = select(data, "students").size();
        StorageManager restarted = open(dir, 32, frm);
        check("data, katalog, dan index kebaca lagi setelah restart", () ->
                select(restarted, "students").size() == studentsBefore
                        && restarted.listTable().equals(catalog.listTable())
                        && sorted(restarted.searchIndex(INDEX_NIM, 100010)).equals(rowIds(restarted, "takes",
                        new Condition("nim", ComparisonOperator.EQ, 100010))));

        section("Komunikasi recovery dengan FRM lewat IDataAccess dan IDurableStorage");
        List<List<Object>> lost = new ArrayList<>();
        List<RowId> lostIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<Object> values = List.of(2000 + i, 100001, "IF0003", 80, 20262);
            RowId rowId = restarted.reserveRowId("takes", values);
            restarted.insertAt(rowId, values);
            lost.add(values);
            lostIds.add(rowId);
        }
        StorageManager afterCrash = open(dir, 32, frm);
        int takesAfterCrash = select(afterCrash, "takes").size();
        check("redo insertAt FRM dua kali menghasilkan data yang sama dengan sekali", () -> {
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < lost.size(); i++) afterCrash.insertAt(lostIds.get(i), lost.get(i));
            }
            return select(afterCrash, "takes").size() == takesAfterCrash + lost.size();
        });
        check("rebuildIndexes bikin index sama lagi dengan heap setelah redo", () -> {
            afterCrash.rebuildIndexes();
            return sorted(afterCrash.searchIndex(INDEX_NIM, 100001)).equals(rowIds(afterCrash, "takes",
                    new Condition("nim", ComparisonOperator.EQ, 100001)));
        });
        check("redo DELETE FRM dua kali aman, panggilan kedua balikin null tanpa error", () -> {
            int before = select(afterCrash, "takes").size();
            Row first = afterCrash.deleteByRowId(lostIds.get(2));
            Row second = afterCrash.deleteByRowId(lostIds.get(2));
            return first != null && second == null && select(afterCrash, "takes").size() == before - 1
                    && !afterCrash.searchIndex(INDEX_NIM, 100001).contains(lostIds.get(2));
        });
        check("undo delete FRM lewat insertAt mengembalikan baris di RowId yang sama", () -> {
            Row victim = afterCrash.fetchByRowId(lostIds.get(0));
            afterCrash.deleteByRowId(victim.rowId());
            afterCrash.insertAt(victim.rowId(), victim.values());
            return afterCrash.fetchByRowId(victim.rowId()).equals(victim)
                    && afterCrash.searchIndex(INDEX_NIM, 100001).contains(victim.rowId());
        });
        check("reloadFromDisk setelah restore snapshot balik ke isi snapshot", () -> {
            afterCrash.flushBuffer();
            Path snapshot = dir.resolveSibling(dir.getFileName() + "-snapshot");
            copyFiles(dir, snapshot);
            int snapshotRows = select(afterCrash, "students").size();
            afterCrash.writeBlock(new DataWrite("students", student(200005, "After", "M", "Informatika", 2f), List.of()));
            afterCrash.flushBuffer();
            copyFiles(snapshot, dir);
            afterCrash.reloadFromDisk();
            return select(afterCrash, "students").size() == snapshotRows
                    && afterCrash.searchIndex(INDEX_GPA, 2f).stream()
                            .noneMatch(id -> afterCrash.fetchByRowId(id).values().get(0).equals(200005));
        });

        System.out.println();
        System.out.println("== Ringkasan: " + passed + " berhasil, " + failures.size() + " gagal");
        failures.forEach(name -> System.out.println("   gagal: " + name));
        return failures;
    }

    // pintu masuk JUnit, gagal kalau ada satu pengujian driver yang gagal
    @Test
    void runsStorageManagerDriver() {
        assertEquals(List.of(), run(directory.resolve("data")));
    }

    // pintu masuk manual dari IDE atau terminal, exit code 1 kalau ada pengujian yang gagal
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("shayladb-sm-driver");
        List<String> failed = new StorageTestDriver().run(dir.resolve("data"));
        System.exit(failed.isEmpty() ? 0 : 1);
    }
}
