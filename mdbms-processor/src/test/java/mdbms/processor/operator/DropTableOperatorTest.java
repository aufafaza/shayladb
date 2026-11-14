package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;

class DropTableOperatorTest {

    @Test
    void removesOnlyRequestedTableFromCatalog() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        TableSchema users = new TableSchema("users", List.of(), List.of(), List.of());
        TableSchema orders = new TableSchema("orders", List.of(), List.of(), List.of());
        catalog.updateTableSchema(users);
        catalog.updateTableSchema(orders);

        new DropTableOperator(catalog, "users").execute();

        assertNull(catalog.getTableSchema("users"));
        assertEquals(orders, catalog.getTableSchema("orders"));
        assertEquals(List.of("orders"), catalog.listTable());
    }
}
