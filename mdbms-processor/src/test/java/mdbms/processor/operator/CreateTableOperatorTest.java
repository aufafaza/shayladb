package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;

class CreateTableOperatorTest {

    @Test
    void storesTableSchemaInCatalog() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        TableSchema tableSchema = new TableSchema(
                "users",
                List.of(new ColumnDefinition("id", DataType.INTEGER, 0, false)),
                List.of("id"),
                List.of());

        new CreateTableOperator(catalog).execute(tableSchema);

        assertEquals(tableSchema, catalog.getTableSchema("users"));
        assertEquals(List.of("users"), catalog.listTable());
    }
}
