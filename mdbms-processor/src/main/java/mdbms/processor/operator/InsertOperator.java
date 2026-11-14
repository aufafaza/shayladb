package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;

public class InsertOperator implements IQueryOperator, IWriteOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final List<ExpressionTree> values;
    private List<Object> rowValues;
    private int affectedRows;

    public InsertOperator(IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            List<ExpressionTree> values) {
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.values = values;
    }

    public void open() {
        List<ColumnDefinition> columns = schemaCatalog.getTableSchema(tableName).columns();
        rowValues = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object value = ExpressionEvaluator.evaluate(values.get(i));
            rowValues.add(ColumnTypes.toColumnType(value, columns.get(i).type()));
        }
    }

    public IResultRow next() {
        RowId rowId = dataAccess.reserveRowId(tableName, rowValues);
        try {
            AccessPermission.write(concurrencyControlManager, transactionId, new DataItem(tableName, rowId), null, rowValues);
        } catch (RuntimeException e) {
            dataAccess.releaseRowId(rowId);
            throw e;
        }
        dataAccess.insertAt(rowId, rowValues);
        affectedRows = 1;
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
