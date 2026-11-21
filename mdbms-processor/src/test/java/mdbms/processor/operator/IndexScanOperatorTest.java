package mdbms.processor.operator;

import java.lang.reflect.Proxy;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import mdbms.processor.PlanTranslator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndexScanOperatorTest {
    @Test void aliasQualifiesResultsWhileIndexAndLocksUsePhysicalTable() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        catalog.updateTableSchema(new TableSchema("users", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false)), List.of("id"), List.of()));
        RowId id = new RowId("users", 0, 0);
        IDataAccess access = (IDataAccess) Proxy.newProxyInstance(IDataAccess.class.getClassLoader(),
            new Class<?>[] {IDataAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                case "searchIndex" -> {
                    assertEquals("idx_users_id", args[0]);
                    assertEquals(2, args[1]);
                    yield List.of(id);
                }
                case "fetchByRowId" -> new Row(id, List.of(2));
                default -> throw new AssertionError(method.getName());
            });
        IConcurrencyControl concurrency = (IConcurrencyControl) Proxy.newProxyInstance(
            IConcurrencyControl.class.getClassLoader(), new Class<?>[] {IConcurrencyControl.class},
            (proxy, method, args) -> {
                assertEquals("requestRead", method.getName());
                assertEquals("users", ((DataItem) args[1]).tableName());
                return AccessStatus.GRANTED;
            });
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.INDEX_SCAN,
            "users", List.of(), List.of(), 0);
        scan.setTableAlias("u");
        scan.setIndexScan(new IndexScanSpec(new IndexInfo("idx_users_id", "users", "id", IndexType.BPLUS_TREE),
            new Condition("id", ComparisonOperator.EQ, 2)));
        IQueryOperator operator = new PlanTranslator(access, catalog, concurrency).translate(
            new ParsedQuery("SELECT u.id FROM users AS u", scan, List.of("users")), 1);
        operator.open();
        var row = operator.next();
        assertEquals(2, row.get("u", "id"));
        assertNull(row.get("users", "id"));
        assertEquals(id, row.getRowId());
        assertNull(operator.next());
        operator.close();
    }
}
