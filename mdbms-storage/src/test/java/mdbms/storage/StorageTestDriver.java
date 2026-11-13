package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// driver SM-15 yang jalanin alur Storage Manager dari ujung ke ujung lewat interface yang dipakai komponen lain
// tiap langkah dicetak ke stdout dan dicek, jalanin pakai mvn -pl mdbms-storage -am test -Dtest=StorageTestDriver
class StorageTestDriver {

    private static final String TABLE = "employees";
    private static final String INDEX = "idx_employees_dept";

    @TempDir Path directory;

    // FRM palsu yang selalu bilang log udah aman, driver ini cuma nguji sisi SM
    private static IFailureRecoveryManager acceptingFrm() {
        return (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
    }

    // buka StorageManager di direktori driver dengan FRM palsu terpasang
    private StorageManager open() {
        StorageManager storage = new StorageManager(directory.toString(), 32, 0);
        storage.attachRecoveryManager(acceptingFrm());
        return storage;
    }

    // susun map kolom ke nilai sesuai urutan kolom employees
    private static Map<String, Object> employee(int id, String name, String dept, float salary) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("name", name);
        values.put("dept", dept);
        values.put("salary", salary);
        return values;
    }

    // ambil baris employees yang cocok sama semua kondisi
    private static List<Row> select(StorageManager storage, Condition... conditions) {
        return storage.readBlock(new DataRetrieval(TABLE, List.of(), List.of(conditions))).rows();
    }

    // cetak satu langkah driver biar alurnya kebaca pas dijalanin
    private static void step(String message) {
        System.out.println("[SM driver] " + message);
    }

    // skenario lengkap, DDL, CRUD, index, statistik, restart, sampai simulasi crash dan redo
    @Test
    void runsFullStorageScenario() {
        StorageManager storage = open();

        step("bikin tabel " + TABLE);
        storage.updateTableSchema(new TableSchema(TABLE, List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 30, false),
                new ColumnDefinition("dept", DataType.CHAR, 4, false),
                new ColumnDefinition("salary", DataType.FLOAT, 0, true)),
                List.of("id"), List.of()));
        assertEquals(List.of(TABLE), storage.listTable());

        step("insert 200 baris lewat writeBlock");
        String[] depts = {"ENG", "OPS", "HR", "FIN"};
        for (int id = 1; id <= 200; id++) {
            storage.writeBlock(new DataWrite(TABLE, employee(id, "emp" + id, depts[id % 4], 1000f + id), List.of()));
        }
        assertEquals(200, select(storage).size());

        step("bikin index B+Tree di kolom dept dari baris yang udah ada");
        storage.createIndex(INDEX, TABLE, "dept");
        assertEquals(50, storage.searchIndex(INDEX, "ENG").size());

        step("update gaji semua HR lewat writeBlock dengan kondisi");
        Condition isHr = new Condition("dept", ComparisonOperator.EQ, "HR");
        Map<String, Object> raise = new LinkedHashMap<>();
        raise.put("salary", 9999f);
        assertEquals(50, storage.writeBlock(new DataWrite(TABLE, raise, List.of(isHr))).size());
        assertTrue(select(storage, isHr).stream().allMatch(row -> row.values().get(3).equals(9999f)));

        step("delete semua OPS lewat deleteBlock, index ikut kosong");
        assertEquals(50, storage.deleteBlock(new DataDeletion(TABLE,
                List.of(new Condition("dept", ComparisonOperator.EQ, "OPS")))));
        assertTrue(storage.searchIndex(INDEX, "OPS").isEmpty());
        assertEquals(150, select(storage).size());

        step("alur QP, reserveRowId lalu insertAt, updateByRowId, deleteByRowId");
        RowId reserved = storage.reserveRowId(TABLE, List.of(500, "new", "ENG", 1f));
        storage.insertAt(reserved, List.of(500, "new", "ENG", 1f));
        Row moved = storage.updateByRowId(reserved, List.of(500, "new", "FIN", 2f));
        assertTrue(storage.searchIndex(INDEX, "FIN").contains(moved.rowId()));
        assertEquals(moved.values(), storage.deleteByRowId(moved.rowId()).values());

        step("statistik buat QO");
        Statistic stat = storage.getStatistics(TABLE);
        step("  nr=" + stat.nr() + " br=" + stat.br() + " lr=" + stat.lr() + " fr=" + stat.fr()
                + " V(dept)=" + stat.distinctValues().get("dept"));
        assertEquals(150, stat.nr());
        assertEquals(3, stat.distinctValues().get("dept"));

        step("flush lalu restart, data dan index kebaca lagi dari disk");
        storage.flushBuffer();
        StorageManager restarted = open();
        assertEquals(150, select(restarted).size());
        assertEquals(50, restarted.searchIndex(INDEX, "HR").size());
        assertEquals(stat, restarted.getStatistics(TABLE));

        step("simulasi crash, 3 insert yang ga sempat di flush hilang dari disk");
        List<List<Object>> lost = new ArrayList<>();
        List<RowId> lostIds = new ArrayList<>();
        for (int id = 601; id <= 603; id++) {
            List<Object> values = List.of(id, "late" + id, "ENG", 5f);
            RowId rowId = restarted.reserveRowId(TABLE, values);
            restarted.insertAt(rowId, values);
            lost.add(values);
            lostIds.add(rowId);
        }
        StorageManager afterCrash = open();
        assertEquals(150, select(afterCrash).size());

        step("redo dua kali lewat insertAt kayak FRM, panggilan kedua ga ngubah apa apa");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < lost.size(); i++) {
                afterCrash.insertAt(lostIds.get(i), lost.get(i));
            }
        }
        afterCrash.rebuildIndexes();
        assertEquals(153, select(afterCrash).size());
        for (RowId rowId : lostIds) {
            assertTrue(afterCrash.searchIndex(INDEX, "ENG").contains(rowId));
        }

        step("drop tabel, katalog dan index ikut hilang");
        afterCrash.dropTable(TABLE);
        assertTrue(afterCrash.listTable().isEmpty());
        step("selesai, buffer " + afterCrash.getBufferStats());
    }
}
