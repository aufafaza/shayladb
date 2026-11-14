package mdbms.storage.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest{
    private ConditionEvaluator evaluator;
    private TableSchema schema;
    private Row row;

    @BeforeEach
    void setUp(){
        evaluator = new ConditionEvaluator();

        schema = new TableSchema(
            "users",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("score", DataType.FLOAT, 0, false),
                new ColumnDefinition("code", DataType.CHAR, 10, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, true)
            ),
            List.of("id"),
            List.of()
        );

        row = new Row(
            new RowId("users", 0, 0),
            List.of(10, 3.5f, "A1", "Budi")
        );
    }

    // Tests comparisons using INTEGER values
    @Test
    void evaluatesAllIntegerOperators(){
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.EQ, 10), row, schema));
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.NEQ, 9), row, schema));
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.LT, 11), row, schema));
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.GT, 9), row, schema));
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.LTE, 10), row, schema));
        assertTrue(evaluator.evaluate(condition("id", ComparisonOperator.GTE, 10), row, schema));
    }

    // Tests comparisons using FLOAT values
    @Test
    void evaluatesFloatCondition(){
        assertTrue(evaluator.evaluate(condition("score", ComparisonOperator.GT, 3.0f), row, schema));
        assertFalse(evaluator.evaluate(condition("score", ComparisonOperator.LT, 3.0f), row, schema));
    }

    // Tests comparisons using CHAR and VARCHAR values
    @Test
    void evaluatesStringConditions(){
        assertTrue(evaluator.evaluate(condition("code", ComparisonOperator.EQ, "A1"), row, schema));
        assertTrue(evaluator.evaluate(condition("name", ComparisonOperator.GT, "Andi"), row, schema));
    }

    // Tests that null values do not satisfy comparison conditions
    @Test
    void nullValueDoesNotMatch(){
        Row nullRow = new Row(
            new RowId("users", 0, 1),
            Arrays.asList(11, 2.0f, "A2", null)
        );

        assertFalse(evaluator.evaluate(condition("name", ComparisonOperator.EQ, "Budi"), nullRow, schema));
        assertFalse(evaluator.evaluate(condition("name", ComparisonOperator.EQ, null), row, schema));
    }

    // Tests that an error is thrown when the condition references a non-existent column
    @Test
    void rejectsUnknownColumn(){
        StorageException exception = assertThrows(
            StorageException.class,
            () -> evaluator.evaluate(condition("age", ComparisonOperator.EQ, 20), row, schema)
        );

        assertEquals(StorageErrorCode.COLUMN_NOT_FOUND, exception.getCode());
    }

    // Tests that an error is thrown when the condition value does not match the column's data type
    @Test
    void rejectsMismatchedType(){
        StorageException exception = assertThrows(
            StorageException.class,
            () -> evaluator.evaluate(condition("id", ComparisonOperator.EQ, "10"), row, schema)
        );

        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, exception.getCode());
    }

    private Condition condition(String column, ComparisonOperator operator, Object value){
        return new Condition(column, operator, value);
    }
}