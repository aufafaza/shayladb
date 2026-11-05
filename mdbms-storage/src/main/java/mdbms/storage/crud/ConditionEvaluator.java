package mdbms.storage.crud;

import java.util.List;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

public class ConditionEvaluator{
    public boolean evaluate(Condition condition, Row row, TableSchema schema){
        List<ColumnDefinition> cd = schema.columns();

        for (int i=0; i<cd.size(); i++){
            if (cd.get(i).name().equals(condition.column())){
                return compare(row.values().get(i), condition.value(), condition.operator(), cd.get(i).type());
            }
        } 

        throw new StorageException(
            StorageErrorCode.COLUMN_NOT_FOUND, 
            "Column not found: " + condition.column()
        );
    }

    private boolean compare(Object left, Object right, ComparisonOperator operator, DataType type){
        if (left == null || right == null) return false;

        int cmp;

        try {
            switch(type){
                case INTEGER:
                    cmp = Integer.compare((Integer) left, (Integer) right);
                    break;

                case FLOAT:
                    cmp = Float.compare((Float) left, (Float) right);
                    break;

                case CHAR, VARCHAR:
                    cmp = ((String) left).compareTo((String) right);
                    break;

                default:
                    throw new StorageException(
                        StorageErrorCode.SCHEMA_VIOLATION,
                        "Unsupported data type: " + type
                    );
            }
        } catch (ClassCastException e){
            throw new StorageException(
                StorageErrorCode.SCHEMA_VIOLATION,
                "Value does not match column data type: " + type,
                e
            );
        }
        

        return switch(operator){
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
            case LT -> cmp < 0;
            case GT -> cmp > 0;
            case LTE -> cmp <= 0;
            case GTE -> cmp >= 0;
        };
    }
}  