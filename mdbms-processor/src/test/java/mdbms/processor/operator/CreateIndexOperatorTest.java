package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import org.junit.jupiter.api.Test;

class CreateIndexOperatorTest {

    @Test
    void createsIndexOnceAndExposesMessage() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        IndexInfo indexInfo = new IndexInfo("users_id_idx", "users", "id", IndexType.BPLUS_TREE);
        CreateIndexOperator operator = new CreateIndexOperator(catalog, indexInfo);

        operator.open();
        assertNull(operator.next());

        assertEquals(List.of(indexInfo), catalog.getIndexes("users"));
        assertEquals("Index users_id_idx created on users(id)", operator.getMessage());
        assertNull(operator.next());
    }
}
