package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// test SampleDataLoader SM4, database contoh students takes courses kebentuk sekali dan tetap ada setelah restart
class SampleDataLoaderTest {

    @TempDir Path directory;

    // FRM palsu yang selalu bilang log udah aman biar flush bisa jalan
    private static IFailureRecoveryManager acceptingFrm() {
        return (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
    }

    // StorageManager baru di direktori test dengan FRM palsu terpasang
    private StorageManager open() {
        StorageManager storage = new StorageManager(directory.toString(), 32, 0);
        storage.attachRecoveryManager(acceptingFrm());
        return storage;
    }

    // jumlah baris satu tabel tanpa kondisi
    private static int count(StorageManager storage, String table) {
        return storage.readBlock(new DataRetrieval(table, List.of(), List.of())).rows().size();
    }

    // database kosong diisi tiga tabel masing masing 50 baris
    @Test
    void loadsSampleDatabaseWhenEmpty() {
        StorageManager storage = open();

        new SampleDataLoader(storage, storage).loadIfEmpty();

        assertEquals(List.of("students", "takes", "courses"), storage.listTable());
        assertEquals(50, count(storage, "students"));
        assertEquals(50, count(storage, "takes"));
        assertEquals(50, count(storage, "courses"));

        List<Row> first = storage.readBlock(new DataRetrieval("students", List.of(),
                List.of(new Condition("nim", ComparisonOperator.EQ, 100001)))).rows();
        assertEquals(1, first.size());
        assertEquals(List.of(100001, "Student 001", "F", "Information Systems", 2.1f), trimmed(first.get(0)));
    }

    // nilai CHAR dari storage dibuang spasi ujungnya biar gampang dibandingin
    private static List<Object> trimmed(Row row) {
        return row.values().stream().map(v -> v instanceof String s ? (Object) s.trim() : v).toList();
    }

    // database yang udah punya tabel ga disentuh sama sekali
    @Test
    void skipsWhenDatabaseNotEmpty() {
        StorageManager storage = open();
        storage.updateTableSchema(new TableSchema("other", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false)), List.of("id"), List.of()));

        new SampleDataLoader(storage, storage).loadIfEmpty();
        new SampleDataLoader(storage, storage).loadIfEmpty();

        assertEquals(List.of("other"), storage.listTable());
    }

    // setelah flush dan restart data contoh, statistik, dan loader kedua ga bikin baris dobel
    @Test
    void sampleDatabaseAndStatisticsSurviveRestart() {
        StorageManager storage = open();
        new SampleDataLoader(storage, storage).loadIfEmpty();
        Statistic before = storage.getStatistics("students");
        storage.flushBuffer();

        StorageManager restarted = open();
        new SampleDataLoader(restarted, restarted).loadIfEmpty();

        assertEquals(50, count(restarted, "students"));
        assertEquals(50, count(restarted, "takes"));
        assertEquals(50, count(restarted, "courses"));
        Statistic after = restarted.getStatistics("students");
        assertEquals(before, after);
        assertEquals(50, after.nr());
        assertEquals(Map.of("nim", 50, "name", 50, "gender", 2, "major", 2, "gpa", 21), after.distinctValues());
        assertEquals(50, restarted.getStatistics("takes").distinctValues().get("code"));
    }

    // loader butuh storage dan katalog
    @Test
    void rejectsNullDependencies() {
        StorageManager storage = open();
        assertThrows(NullPointerException.class, () -> new SampleDataLoader(null, storage));
        assertThrows(NullPointerException.class, () -> new SampleDataLoader(storage, null));
    }
}
