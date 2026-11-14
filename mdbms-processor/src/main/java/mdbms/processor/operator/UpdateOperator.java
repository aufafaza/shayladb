package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;

public class UpdateOperator extends QueryUnaryOperator implements IWriteOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String columnName;
    private final ExpressionTree newValue;
    private List<String> columnNames;
    private int columnIndex;
    private DataType columnType;
    private List<IResultRow> targetRows;
    private int affectedRows;

    public UpdateOperator(IQueryOperator childOperator, IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            String columnName, ExpressionTree newValue) {
        super(childOperator);
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.newValue = newValue;
    }

    public void open() {
        List<ColumnDefinition> columns = schemaCatalog.getTableSchema(tableName).columns();
        columnNames = columns.stream()
                .map(ColumnDefinition::name)
                .toList();
        columnIndex = columnNames.indexOf(columnName);
        columnType = columns.get(columnIndex).type();

        targetRows = new ArrayList<>();
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) {
            targetRows.add(row);
        }
        childOperator.close();
    }

    public IResultRow next() {
        for (IResultRow row : targetRows) {
            List<Object> oldValues = new ArrayList<>();
            for (String name : columnNames) {
                oldValues.add(row.get(name));
            }
            List<Object> newValues = new ArrayList<>(oldValues);
            newValues.set(columnIndex, ColumnTypes.toColumnType(ExpressionEvaluator.evaluate(newValue, row), columnType));

            DataItem item = new DataItem(tableName, row.getRowId());
            AccessPermission.write(concurrencyControlManager, transactionId, item, oldValues, newValues);
            Row updated = dataAccess.updateByRowId(row.getRowId(), newValues);
            if (!updated.rowId().equals(row.getRowId())) {
                AccessPermission.write(concurrencyControlManager, transactionId, item, newValues, null);
                AccessPermission.write(concurrencyControlManager, transactionId,
                        new DataItem(tableName, updated.rowId()), null, newValues);
            }
        }
        affectedRows = targetRows.size();
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
